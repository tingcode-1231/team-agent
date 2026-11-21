package com.teamagent.tool;

import com.teamagent.TeamTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TeamToolsEvalTest {

    @Test
    void 测试打分公式确实走产品代码() throws Exception {
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("name", "林子豪");
        candidate.put("skills", List.of("Java", "MySQL"));
        candidate.put("freeDays", 4);

        // 反射调用 TeamTools 里真正的私有方法 rank()，不是自己重抄公式
        TeamTools tools = new TeamTools(mock(JdbcTemplate.class), mock(VectorStore.class));
        Method rank = TeamTools.class.getDeclaredMethod("rank", List.class, List.class, int.class);
        rank.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) rank.invoke(tools, List.of(candidate), List.of("Java"), 3);

        // 60（技能）+ 22.9（4/7 时间）= 82.9，由产品代码算出
        assertEquals(82.9, (double) result.get(0).get("score"), 0.001);
    }

    @Test
    void 测试空结果不会幻觉() {
        VectorStore vectorStore = mock(VectorStore.class);
        // 显式打桩返回空列表，不依赖 Mockito 默认行为
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        TeamTools tools = new TeamTools(mock(JdbcTemplate.class), vectorStore);
        List<Map<String, Object>> result = tools.twoStageRecommend("找一个火星人", "", 0, List.of(), 3);

        assertTrue(result.isEmpty(), "查不到人时必须返回空列表，不能返回假数据");
    }
}
