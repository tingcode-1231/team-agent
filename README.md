# 智能组队推荐 Agent（team-agent）

> Spring Boot 3.4.5 · Spring AI 1.0.0 · Java 17 · DeepSeek（OpenAI 兼容协议）+ 智谱 embedding-3 · Redis Stack（HNSW / COSINE）· MySQL 8

竞赛组队场景的对话式推荐服务：用户用自然语言描述需求（年级、技能、每周可投入天数），模型负责**理解需求并选择工具**，候选人召回后的**硬约束过滤与规则排序全部由 Java 代码确定性执行**，结果以 SSE 流式返回，并配有运行指标日志和回归评测集。

设计原则一句话：**模型只做参数提取和表达，不让模型决定最终推荐谁。**

---

## 目录

- [已实现功能](#已实现功能)
- [技术栈与版本](#技术栈与版本)
- [核心链路](#核心链路)
- [项目结构](#项目结构)
- [快速启动](#快速启动)
- [接口](#接口)
- [运行指标与评测](#运行指标与评测)
- [开发演进](#开发演进)
- [已知局限与后续方向](#已知局限与后续方向)

---

## 已实现功能

- **Agent 工具调用**：ChatClient + `@Tool` 驱动「模型选工具 → 框架执行 → 结果回填 → 模型组织回答」；对外只暴露一个 `twoStageRecommend` 总入口。
- **两段式推荐**：向量宽松召回 Top50 → 年级 / 每周天数 / 必会技能硬过滤 → 技能匹配 60% + 可投入时间 40% 人工规则打分 → Top5。
- **工具协议与防幻觉**：工具统一返回 `List<Map<String,Object>>`；出错返回含 `error` 字段的单元素列表；系统提示明确禁止编造候选人。
- **参数钳制**：每周天数 0~7、返回人数 1~10、必会技能为空列表等边界在 Java 侧兜底。
- **SSE 流式响应**：`/recommend/stream` 用 `Flux.defer` + `timeout(60s)` + `onErrorResume`；前端用 `EventSource` 逐字显示。
- **运行指标日志**：流式侧记录 TTFB / 总耗时 / chunk 数 / 最大 chunk 间隔 / `degraded` 标志；工具侧记录召回、过滤、排序各段耗时与结果条数。
- **回归评测集**：JUnit5 + Mockito 单测反射验证真实打分方法；TestRestTemplate 集成测试覆盖正例、反例、防幻觉场景。当前 5 个用例全部通过。

---

## 技术栈与版本

| 层次 | 选型 | 说明 |
|---|---|---|
| 基础框架 | Spring Boot 3.4.5、Java 17 | |
| Agent | Spring AI 1.0.0（`spring-ai-starter-model-openai`） | ChatClient + `@Tool` |
| 对话模型 | DeepSeek `deepseek-chat`（OpenAI 兼容协议） | `base-url: https://api.deepseek.com`，temperature 0.2 |
| Embedding | 智谱 `embedding-3`，2048 维 | `spring.ai.model.embedding=zhipuai`，与 chat 分开指定避免 Bean 冲突 |
| 向量库 | Redis Stack，索引 `candidate-index`，前缀 `candidate:` | HNSW + COSINE，`initialize-schema: true` |
| 业务库 | MySQL 8（Docker，宿主端口 3308），HikariCP 最大连接 10 | `utf8mb4` |
| 前端 | 原生 HTML + JavaScript `EventSource` | 静态页，无构建步骤 |
| 测试 | JUnit5、Mockito、AssertJ、TestRestTemplate | 随机端口 `@SpringBootTest` |

---

## 核心链路

```
用户自然语言
   │
   ▼
ChatClient + System Prompt ── 提取年级/天数/技能/人数，决定调用 tool
   │
   ▼
twoStageRecommend(query, grade, minFreeDays, requiredSkills, topN)
   │  参数钳制：days∈[0,7]，topN∈[1,10]
   │
   ├─ ① 语义召回：智谱 embedding-3 → Redis HNSW/COSINE，Top50
   │     每个 Document 的 id 形如 candidate-12，去前缀得到 MySQL 主键，逐条回表取业务字段
   │
   ├─ ② 硬过滤：grade 相等、freeDays ≥ 下限、必会技能在 skills 或 competitions 中命中
   │
   ├─ ③ 规则打分：技能重合度 60% + 可投入天数 40%（人工设定权重）
   │     score = 重合技能数×60/需求技能数 + min(freeDays,7)×40/7，保留 1 位小数
   │
   └─ ④ 取 TopN 返回 → 模型据此生成带匹配理由的自然语言回答
          │
          ▼
     GET /recommend/stream  →  SSE 流式输出
```

关键实现约定：

- **工具返回类型必须是 `List<Map<String,Object>>`**；返回 `Object` 会被 Spring AI 误判为 functional type，导致工具注册失败。
- **不依赖 Redis metadata 回传业务数据**：`doc.getId()` 去前缀 `candidate-` 得到 Long 型主键再查 MySQL。
- **未找到候选人返回空列表**，由模型如实告知用户；异常返回含 `error` 的单元素列表，不让模型编造。

---

## 项目结构

```
.
├── docker-compose.yml              # MySQL 8（3308）+ 自动执行 db/*.sql
├── db/
│   ├── 01-schema.sql               # candidate 候选人表（12 条种子数据）
│   └── 02-seed.sql
├── pom.xml
└── src/main/
    ├── java/com/teamagent/
    │   ├── TeamAgentApplication.java
    │   ├── TeamTools.java           # 唯一 @Tool 入口 + 召回/过滤/打分内部方法 + METRIC tool 日志
    │   ├── agent/AgentController.java    # /recommend、/recommend/stream + METRIC stream 日志
    │   └── runner/VectorDataLoader.java  # 启动时把 MySQL 候选人灌入 Redis 向量库
    └── resources/
        ├── application.yml
        └── static/index.html        # SSE 前端演示页
└── src/test/java/com/teamagent/
    ├── AgentIntegrationEvalTest.java     # 3 个集成用例
    └── tool/TeamToolsEvalTest.java       # 2 个单测用例
```

---

## 快速启动

### 前置条件

- JDK 17+、Maven 3.8+、Docker（含 Docker Compose）
- 可用的 DeepSeek API Key 和智谱 API Key
- **Redis Stack**（需带 RediSearch 模块，普通 Redis 不行），监听 `127.0.0.1:6380`

### 1. 启动基础设施

```bash
docker compose up -d          # MySQL 8，宿主端口 3308，首次启动自动导入 db/*.sql
```

确认 Redis Stack 已启动且**监听 6380 端口**（`application.yml` 中配置为 6380；若你的 Redis 在 6379，需相应修改）。可用 `redis-cli -p 6380 FT.INFO candidate-index` 检查向量索引是否建立。

### 2. 配置环境变量

```bash
# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-..."
$env:ZHIPU_API_KEY="..."

# macOS / Linux
export DEEPSEEK_API_KEY=sk-...
export ZHIPU_API_KEY=...
```

> 在 IDEA 中运行时，把这两个变量加进 Run Configuration → Environment variables。
> **不要把 Key 写进配置文件或提交到仓库。** 若曾把 401 误判为 Key 失效，先检查 JUnit / Run Configuration 的环境变量是否缺失。

### 3. 启动应用

```bash
mvn spring-boot:run
```

启动日志中应能看到 `成功将 12 条候选人数据写入向量库！`。若向量入库失败，日志会打印原因但**不影响主服务启动**（这是已知行为，见"已知局限"）。

### 4. 验证

```bash
# 同步接口
curl "http://localhost:8090/recommend?query=找一个做过推荐系统的研一，会Java的队友"

# 浏览器打开 SSE 演示页
# http://localhost:8090/index.html
```

成功时回答中应出现候选人姓名（例如种子数据中的"何欣怡"）及匹配理由。

### 5. 运行评测

```bash
mvn test
```

> 集成测试会真实调用 DeepSeek 与智谱 API，需先配置好上述两个环境变量，并保证 MySQL / Redis Stack 正常运行。5 个用例全部通过为正常。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/recommend?query=...` | 同步返回推荐结果文本；异常时返回兜底文案 |
| GET | `/recommend/stream?query=...` | `text/event-stream`；用 `EventSource` 或任意 SSE 客户端消费 |
| GET | `/index.html` | SSE 前端演示页（静态资源） |

---

## 运行指标与评测

**结构化日志**（`grep METRIC` 即可查看）：

- `METRIC tool | recallMs=… filterMs=… rankMs=… totalMs=… recalled=… filtered=… output=… | grade=… minFreeDays=… skills=… query=…`
- `METRIC stream | query=… ttfbMs=… totalMs=… chunks=… maxIntervalMs=… degraded=… signal=…`

这些是**应用内结构化日志**，不是 Prometheus/Grafana 监控平台。

**评测集（5 个用例）**：

- `TeamToolsEvalTest`（2 个）：反射调用真实私有 `rank()` 验证打分公式（技能 60 + 时间 22.9 = 82.9，不在测试里重抄公式自测）；显式 mock 向量库返回空列表，验证查不到时不返回假数据。
- `AgentIntegrationEvalTest`（3 个）：前置健康检查防止降级文案造成"假通过"；正例"做过推荐系统的研一 + Java"应命中何欣怡；反例"大三"不应出现研一的何欣怡；防幻觉用例不断言模型说"没有"，只断言没有编造任何真实候选人。

---

## 开发演进

| 提交 | 日期 | 内容 |
|---|---|---|
| `f4bec24` | 11-17 | init：Spring AI 骨架 + 3 个工具（内存数据） |
| `ca0e3e8` | 11-17 | D2：候选人数据源切换到真实 MySQL |
| `4577424` | 11-18 | D3：输入校验、查询超时与优雅降级 |
| `ef7d315` | 11-19 | D4：embedding 流水线 + Redis 向量召回 |
| `0637484` | 11-20 | D5：两段式召回 + 硬过滤 + Top5 重排 |
| `f891f6f` | 11-21 | D6：SSE 流式端点 + EventSource 前端 |
| `b596c1d` | 11-21 | D7：流式延迟、工具耗时与错误指标埋点 |
| `2885438` | 11-21 | D8：评测集与延迟基线报告；补齐两处 D7 埋点缺口 |

共 8 次提交，日期跨度 11-17 至 11-21。

---

## 已知局限与后续方向

以下是当前真实状态，**尚未实现**，不要当成已有功能：

1. **数据规模小**：候选人种子数据 12 条，能验证链路正确性，不能证明规模化召回效果。
2. **N+1 回表**：`semanticSearchInternal` 对每个召回 Document 单独查一次 MySQL；Top50 上限意味着最多约 50 次回表（当前数据只有 12 条）。后续可收集 ID 用 `WHERE id IN (...)` 批量查询并按召回顺序重排。
3. **无自动故障切换**：向量库不可用时返回错误结果，但**没有**自动降级到关键词/规则召回；向量数据入库失败只打日志不阻断启动。
4. **过滤与评分口径不完全一致**：硬过滤允许技能在 `skills` 或 `competitions` 中命中，而 `rank()` 的重合度只统计 `skills` 字段。
5. **分数字段名有歧义**：`semanticSearchInternal` 把语义相似度放进 `score`，`rank()` 又用规则分覆盖同名字段——最终返回的是规则分。后续应拆成 `semanticScore` 与 `rankScore`。
6. **指标只是日志**：没有持久化、聚合、告警或完整链路追踪。
7. **评测规模有限**：5 个回归用例通过 ≠ 推荐效果优秀；集成用例依赖真实模型，结果可能随模型版本、Prompt 与外部服务状态波动。
8. **权重是人工规则**：60/40 是业务设定，不是训练或实验得到的最优权重，也没有 NDCG / Recall@K 等排序指标评估。
