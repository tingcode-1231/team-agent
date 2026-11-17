package com.teamagent.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Day 1 阶段：先用内存假数据演示「模型自主调用工具」。
 *
 * <p>后续 Day 4-5 会把这里的假数据换成竞赛组队平台的真实 MySQL 表和 Redis 向量召回。
 * 工具签名（参数名 + 描述）保持稳定，模型只依赖签名决策，不依赖实现——这就是换实现不换协议的好处。
 */
@Component
public class TeamTools {

  /** 模拟候选人库。字段与竞赛组队平台 user 表的技能标签对齐。 */
  private static final List<Map<String, Object>> CANDIDATES =
      List.of(
          Map.of("id", 1001, "name", "张三", "grade", "大二", "skills", List.of("Java", "MySQL"), "freeDays", 5),
          Map.of("id", 1002, "name", "李四", "grade", "大三", "skills", List.of("Java", "Spring", "Redis"), "freeDays", 3),
          Map.of("id", 1003, "name", "王五", "grade", "大二", "skills", List.of("Python", "算法"), "freeDays", 6),
          Map.of("id", 1004, "name", "赵六", "grade", "研一", "skills", List.of("Java", "算法", "Paddle"), "freeDays", 4),
          Map.of("id", 1005, "name", "钱七", "grade", "大三", "skills", List.of("前端", "Vue"), "freeDays", 2));

  @Tool(description = "按技能关键词召回候选队友，返回候选人列表（id、姓名、年级、技能、每周可投入天数）")
  public List<Map<String, Object>> searchCandidates(
      @ToolParam(description = "技能关键词，如 Java、算法、前端") String skill) {
    return CANDIDATES.stream()
        .filter(c -> ((List<?>) c.get("skills")).stream().anyMatch(s -> s.toString().contains(skill)))
        .collect(Collectors.toList());
  }

  @Tool(description = "按年级与每周最少可投入天数过滤候选人，返回满足条件的子集")
  public List<Map<String, Object>> filterByConstraints(
      @ToolParam(description = "年级要求，如：大二") String grade,
      @ToolParam(description = "每周最少可投入天数") int minFreeDays) {
    return CANDIDATES.stream()
        .filter(c -> grade.equals(c.get("grade")) && (int) c.get("freeDays") >= minFreeDays)
        .collect(Collectors.toList());
  }

  @Tool(description = "为候选人计算匹配分（技能重合度 60% + 可投入时间 40%），返回按分数排序的 Top N")
  public List<Map<String, Object>> scoreMatch(
      @ToolParam(description = "期望技能列表，如 [Java, MySQL]") List<String> wantedSkills,
      @ToolParam(description = "返回前几名") int topN) {
    return CANDIDATES.stream()
        .map(
            c -> {
              List<?> skills = (List<?>) c.get("skills");
              long overlap =
                  wantedSkills.stream().filter(skills::contains).count();
              double skillScore = wantedSkills.isEmpty() ? 0 : overlap * 60.0 / wantedSkills.size();
              double timeScore = Math.min((int) c.get("freeDays"), 7) * 40.0 / 7;
              Map<String, Object> withScore =
                  new java.util.HashMap<>(c);
              withScore.put("score", Math.round((skillScore + timeScore) * 10) / 10.0);
              return withScore;
            })
        .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
        .limit(topN)
        .collect(Collectors.toList());
  }
}
