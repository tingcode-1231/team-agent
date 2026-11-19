package com.teamagent.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class VectorDataLoader implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;

    public VectorDataLoader(JdbcTemplate jdbc, VectorStore vectorStore) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        try{
            // 1. 从 MySQL 读出所有人
            List<Map<String, Object>> candidates = jdbc.queryForList("SELECT * FROM candidate");

            // 2. 拼成自然语言文本
            List<Document> documents = candidates.stream().map(c -> {
                String text = String.format("%s %s %s 技能 %s 竞赛 %s",
                        c.get("name"), c.get("major"), c.get("grade"),
                        c.get("skills"), c.get("competitions") != null ? c.get("competitions") : "无");

                // 固定 ID，保证重启重复执行时是覆盖，而不是无限新增
                String id = "candidate-" + c.get("id");
                return new Document(id, text, Map.of("candidateId", c.get("id")));
            }).toList();

            // 3. 存入 Redis 向量库（会自动调用智谱转成向量）
            vectorStore.add(documents);
            log.info("成功将 " + documents.size() + " 条候选人数据写入向量库！");
        } catch (Exception e){
            // 不抛出！只打印错误日志，让应用继续启动
            log.error("向量数据入库失败，不影响主服务启动，原因: {}", e.getMessage());
        }

    }
}