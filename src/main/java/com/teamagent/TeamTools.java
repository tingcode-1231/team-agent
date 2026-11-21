package com.teamagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class TeamTools {

    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;

    public TeamTools(JdbcTemplate jdbc, VectorStore vectorStore) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
        jdbc.setQueryTimeout(5);
    }

    // ================= 以下为降级后的内部方法（不再暴露给大模型） =================

    private List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("school", rs.getString("school"));
            m.put("major", rs.getString("major"));
            m.put("grade", rs.getString("grade"));
            m.put("skills", List.of(rs.getString("skills").split(",")));
            m.put("competitions", rs.getString("competitions"));
            m.put("freeDays", rs.getInt("free_days"));
            return m;
        }, args);
    }

    private List<Map<String, Object>> semanticSearchInternal(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        int k = Math.max(1, Math.min(topK, 50)); // 指南要求：上限放宽到 50

        try {
            SearchRequest request = SearchRequest.builder().query(query).topK(k).build();
            List<Document> docs = vectorStore.similaritySearch(request);

            return docs.stream().map(doc -> {
                String docId = doc.getId();
                String numericIdStr = docId.replace("candidate-", "");
                try {
                    Long realId = Long.parseLong(numericIdStr);
                    List<Map<String, Object>> dbResults = query(
                            "SELECT id,name,school,major,grade,skills,competitions,free_days FROM candidate WHERE id = ?",
                            realId
                    );
                    if (!dbResults.isEmpty()) {
                        Map<String, Object> m = new LinkedHashMap<>(dbResults.get(0));
                        m.put("score", doc.getScore()); // 记录语义得分
                        return m;
                    }
                } catch (Exception e) {
                    log.warn("解析向量库 ID 失败: {}", docId);
                }
                return null;
            }).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("向量检索失败: query={}, 原因: {}", query, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "语义查询服务暂时不可用，请勿编造结果");
            return List.of(err);
        }
    }

    // 把原来的 scoreMatch 改造成纯内存打分方法
    private List<Map<String, Object>> rank(List<Map<String, Object>> candidates, List<String> wantedSkills, int topN) {
        return candidates.stream()
                .map(c -> {
                    List<?> skills = (List<?>) c.get("skills");
                    long overlap = wantedSkills.stream().filter(skills::contains).count();
                    double skillScore = wantedSkills.isEmpty() ? 0 : overlap * 60.0 / wantedSkills.size();
                    double timeScore = Math.min((int) c.get("freeDays"), 7) * 40.0 / 7;
                    Map<String, Object> scored = new LinkedHashMap<>(c);
                    scored.put("score", Math.round((skillScore + timeScore) * 10) / 10.0);
                    return scored;
                })
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .limit(topN)
                .toList();
    }

    // ================= 唯一暴露给大模型的总入口 =================

    @Tool(description = "【组队推荐主入口】用户描述组队需求时调用本工具一次即可。内部完成：语义宽松召回 → 按年级/每周天数/必会技能做硬约束过滤 → 匹配打分 → 返回 TopN。无满足条件的候选人时返回空列表，不得编造候选人。工具出错时返回仅含 error 字段的单元素列表")
    public List<Map<String, Object>> twoStageRecommend(
            @ToolParam(description = "用户的自然语言需求原文") String query,
            @ToolParam(description = "年级硬约束，如 大三；不限传空字符串") String grade,
            @ToolParam(description = "每周最少可投入天数，0~7") int minFreeDays,
            @ToolParam(description = "必会技能列表，仅限硬技能如 [Java, MySQL]；若用户说的是经历/方向（如推荐系统），请留在 query 中，此参数传空列表") List<String> requiredSkills,
            @ToolParam(description = "最终返回人数，1~10") int topN) {

        // 1. 参数钳制 (D3 纪律)
        if (query == null || query.isBlank()) return List.of();
        int days = Math.max(0, Math.min(minFreeDays, 7));
        int n = Math.max(1, Math.min(topN, 10));
        List<String> wanted = (requiredSkills == null) ? List.of() : requiredSkills;

        try {
            long t0 = System.nanoTime();
            // 1. 宽松召回 Top 50
            List<Map<String, Object>> recalled = semanticSearchInternal(query, 50);
            long t1 = System.nanoTime();

            if (recalled.size() == 1 && recalled.get(0).containsKey("error")) {
                return recalled;
            }

            // 2. 内存硬约束过滤
            List<Map<String, Object>> filtered = recalled.stream()
                    .filter(c -> grade == null || grade.isBlank() || grade.equals(c.get("grade")))
                    .filter(c -> (int) c.get("freeDays") >= days)
                    .filter(c -> {
                        List<?> cSkills = c.get("skills") == null ? List.of() : (List<?>) c.get("skills");
                        String comps = (String) c.get("competitions");
                        String compsStr = comps == null ? "" : comps;
                        return wanted.stream().allMatch(skill -> cSkills.contains(skill) || compsStr.contains(skill));
                    })
                    .toList();
            long t2 = System.nanoTime();

            // 3. 打分重排 Top N
            List<Map<String, Object>> result = rank(filtered, wanted, n);
            long t3 = System.nanoTime();

            // 打点：记录每一段的耗时
            log.info("METRIC tool | recallMs={} filterMs={} rankMs={} totalMs={} recalled={} filtered={} output={} | grade={} minFreeDays={} skills={} query={}",
                    (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t3 - t0) / 1_000_000,
                    recalled.size(), filtered.size(), result.size(), // 加在这里
                    grade, days, wanted, query);

            return result;

        } catch (Exception e) {
            log.warn("两段式推荐失败: query={}, 原因: {}", query, e.getMessage());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "推荐服务暂时不可用，请勿编造结果，建议告知用户稍后再试");
            return List.of(err);
        }
    }
}