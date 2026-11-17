package com.teamagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Day 2 阶段：数据源从内存假数据替换为 MySQL。
 *
 * <p>工具签名（参数名 + 描述）保持稳定，模型只依赖签名决策，不依赖实现——这就是换实现不换协议的好处。
 */
@Component
public class TeamTools {

    private final JdbcTemplate jdbc;

    public TeamTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    @Tool(description = "按技能关键词召回候选队友，返回候选人列表（id、姓名、年级、技能、每周可投入天数）。无匹配时返回空列表，不得编造候选人")
    public List<Map<String, Object>> searchCandidates(
            @ToolParam(description = "技能关键词，如 Java、算法、前端") String skill) {
        String sql = "SELECT id,name,school,major,grade,skills,competitions,free_days FROM candidate WHERE skills LIKE ?";
        return query(sql, "%" + skill + "%");
    }

    @Tool(description = "按年级与每周最少可投入天数过滤候选人，返回满足条件的子集。无匹配时返回空列表，不得编造候选人")
    public List<Map<String, Object>> filterByConstraints(
            @ToolParam(description = "年级要求，如：大二") String grade,
            @ToolParam(description = "每周最少可投入天数") int minFreeDays) {
        String sql = "SELECT id,name,school,major,grade,skills,competitions,free_days FROM candidate WHERE grade = ? AND free_days >= ?";
        return query(sql, grade, minFreeDays);
    }

    @Tool(description = "为候选人计算匹配分（技能重合度 60% + 可投入时间 40%），返回按分数排序的 Top N。无匹配时返回空列表，不得编造候选人")
    public List<Map<String, Object>> scoreMatch(
            @ToolParam(description = "期望技能列表，如 [Java, MySQL]") List<String> wantedSkills,
            @ToolParam(description = "返回前几名") int topN) {
        List<Map<String, Object>> all = query("SELECT id,name,school,major,grade,skills,competitions,free_days FROM candidate");
        return all.stream()
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
}