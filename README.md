# 智能组队推荐 Agent（team-agent）

> Spring Boot 3.4.5 + Spring AI 1.0.0 + DeepSeek（OpenAI 兼容协议）｜Java 17

竞赛组队场景的对话式推荐服务：用户自然语言描述需求（年级/技能/时间），模型自主调用「召回 / 过滤 / 评分」三个工具，基于真实数据给出推荐与理由。

## 快速启动

```bash
# 1. 设置 DeepSeek API Key（环境变量，复用竞赛组队平台用过的那个 Key）
#    Windows PowerShell:  $env:DEEPSEEK_API_KEY="sk-xxxx"
#    IDEA 里：Run Configuration -> Environment variables 添加 DEEPSEEK_API_KEY

# 2. 启动
mvn spring-boot:run

# 3. 浏览器验证（工具调用是否发生，看控制台日志）
# http://localhost:8090/recommend?query=我是大二学生，会Java和MySQL，想找算法类竞赛的队友
```

## 当前结构（Day 1 完成态）

```
src/main/java/com/teamagent/
├── TeamAgentApplication.java      # 启动类
├── agent/AgentController.java     # GET /recommend：ChatClient + 系统提示 + 工具注册
└── tool/TeamTools.java            # 3 个 @Tool 工具（内存假数据版，Day4-5 换真实数据源）
```

## 路线图（对应简历条目，每行做完才写）

> **每日固定动作**：每天收工前 `git add . && git commit -m "D?: 当天做了什么" && git push`——连续 8 天的提交记录是这个项目"亲手所写"的最硬证据，也是简历上 GitHub 链接的内容。提交消息写实际内容（如 `D2: switch to real MySQL data source`），不要写 "update"。真实 API Key 永远不进仓库（走环境变量 `DEEPSEEK_API_KEY`）。

| 天 | 任务 | 完成标志 |
|---|---|---|
| D1 | 骨架 + 首次工具调用 | 模型先调工具再回答（本项目已就位，明天跑通即算） |
| D2 | 换真实数据：接 MySQL（复用组队平台 4 张表） | 工具查询真实 user 表 |
| D3 | JSON Schema 校验 + 规则降级 + 超时 | 坏输出不 500，降级出规则推荐 |
| D4 | Redis 向量召回（embedding + Top50） | 语义 query 能召回 |
| D5 | 结构化二次过滤 + Top5 组装 | 两段式链路可演示 |
| D6 | SSE 流式端点 | 浏览器流式输出 |
| D7 | 埋点：工具调用次数 / token / 降级率 | 指标可查 |
| D8 | 30 条评测集（人工标注）+ 命中率 | Top3 命中率有数字 |

## 开发原则

1. 工具签名（参数名 + description）稳定，实现可替换——模型只看签名决策。
2. 所有量化数字必须实测后写入简历，评测集标注必须人工做。
3. 模型只依赖工具结果说话，禁止编造（系统提示已约束）。
