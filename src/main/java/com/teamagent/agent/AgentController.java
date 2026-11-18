package com.teamagent.agent;

import com.teamagent.tool.TeamTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 1 验收目标：浏览器访问
 * http://localhost:8090/recommend?query=我是大二学生，会Java和MySQL，想找算法类竞赛的队友
 *
 * <p>验收标准：控制台/响应里能看到模型先调用 searchCandidates / scoreMatch 等工具，
 * 再基于工具结果给出最终推荐——而不是模型自己凭空编名单。
 */
@Slf4j
@RestController
public class AgentController {

    private final ChatClient chatClient;

    public AgentController(ChatClient.Builder builder, TeamTools tools) {
        // 系统提示：角色 + 可用工具说明 + 输出约束
        this.chatClient =
                builder
                        .defaultSystem(
                                """
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
            return "抱歉，推荐服务暂时不可用，请稍后再试。";   // 降级文案
        }
    }
}
