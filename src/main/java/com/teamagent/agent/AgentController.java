package com.teamagent.agent;

import com.teamagent.tool.TeamTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class AgentController {

    private final ChatClient chatClient;

    public AgentController(ChatClient.Builder builder, TeamTools tools) {
        this.chatClient = builder
                .defaultSystem("""
                        你是竞赛组队助手。用户会用自然语言描述组队需求。
                        你必须从用户需求中提取年级、每周最少天数、必会技能、需要人数，传递给 twoStageRecommend 工具。
                        
                        【极其重要的参数提取规则】：
                        1. requiredSkills 只能填真正的技术栈硬技能（例如：Java、Python、MySQL、Vue）。
                        2. 如果用户提到的是“项目经历、研究方向、业务方向”（例如：做过推荐系统、前端经验、算法竞赛），请把原话放进 query 里做语义检索，**绝对不要**放进 requiredSkills。
                        3. 用户没说清的约束传空值或默认值，不要自己猜。
                        
                        组队推荐一律调用 twoStageRecommend 一次。禁止凭空编造候选人。推荐时说明每个人的匹配理由。
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
}