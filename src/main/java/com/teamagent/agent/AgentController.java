package com.teamagent.agent;

import com.teamagent.tool.TeamTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
public class AgentController {

    private final ChatClient chatClient;
    private final TeamTools teamTools;              // 临时

    public AgentController(ChatClient.Builder builder, TeamTools tools) {
        this.teamTools = tools;                      // 注意是 tools
        this.chatClient = builder
                .defaultSystem("""
                        你是竞赛组队助手。用户会用自然语言描述组队需求（年级、技能、可投入时间等）。
                        你必须先调用工具获取真实候选人数据，再基于工具返回结果做推荐；
                        禁止凭空编造候选人。推荐时说明每个人的匹配理由。
                        最终输出格式：每个人一行，包含 姓名、年级、技能、匹配理由。
                        """)
                .defaultTools(tools)
                .build();
    }

    @GetMapping("/recommend")
    public String recommend(@RequestParam String query) {
        try {
            return chatClient.prompt().user(query).call().content();
        } catch (Exception e) {
            log.error("推荐接口异常: query={}", query, e);
            return "抱歉，推荐服务暂时不可用，请稍后再试。";
        }
    }

    // ↓↓↓ 临时端点，验完整段删除 ↓↓↓
    @GetMapping("/debug/compare")
    public Map<String, Object> compare(
            @RequestParam(defaultValue = "推荐") String kw,
            @RequestParam(defaultValue = "想找做过推荐系统的人") String q) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("keyword", teamTools.searchCandidates(kw));
        r.put("semantic", teamTools.semanticSearch(q, 5));
        return r;
    }
    // ↑↑↑ 验完整段删除 ↑↑↑
}
