package com.teamagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class AgentIntegrationEvalTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String ask(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return restTemplate.getForObject(
                "http://localhost:" + port + "/recommend?query=" + encoded, String.class);
    }

    // 先确认服务真的能查到数据，防止降级造成“假通过”
    private void 确认服务可用() {
        String resp = ask("找会Java的队友");
        assertThat(resp)
                .as("服务不可用或已降级，本次结果不作数：%s", resp)
                .containsAnyOf("林子豪", "陈雨桐", "赵一鸣", "何欣怡", "马俊杰", "郑浩然", "周子航");
    }

    @Test
    void 正例_做过推荐系统的研一应命中何欣怡() {
        确认服务可用();
        String resp = ask("找一个做过推荐系统的队友，研一，会Java");
        // 模型若把“推荐系统”错塞进 requiredSkills，她会被过滤掉 → 守住 D5 翻车
        assertThat(resp).as("应命中何欣怡，实际：%s", resp).contains("何欣怡");
    }

    @Test
    void 语义反例回归_大三找不到何欣怡() {
        确认服务可用();
        String resp = ask("找做过推荐系统的队友，但我只要大三的");
        assertThat(resp).as("大三不该出现研一的何欣怡；实际：%s", resp).doesNotContain("何欣怡");
        assertThat(resp).as("服务已降级，本测试无效：%s", resp).doesNotContain("暂时不可用");
    }

    @Test
    void 防幻觉回归_查不到的人() {
        确认服务可用();
        String resp = ask("找会Rust的队友");
        assertThat(resp).as("服务已降级，本测试无效：%s", resp).doesNotContain("暂时不可用");
        // 不逼模型说“没有”，只断言它没编造任何真实候选人
        List.of("陈雨桐", "林子豪", "王思远", "赵一鸣", "刘诗涵", "周子航",
                        "孙嘉宁", "吴梦琪", "郑浩然", "何欣怡", "马俊杰", "高梦瑶")
                .forEach(name -> assertThat(resp)
                        .as("没人会 Rust，不该出现 %s；实际：%s", name, resp)
                        .doesNotContain(name));
    }
}
