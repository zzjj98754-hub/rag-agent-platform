# RAG Agent 项目学习体系

这个文档不是项目介绍，而是你的学习总控台。目标是把项目拆成 5 条主线，每条主线都能做到：

- 知道它在项目完整链路里的位置；
- 能找到对应源码和测试；
- 能用一两句话讲清工程取舍；
- 学完后自己更新进度，而不是被固定计划推着走。

建议节奏：一次只学一个模块。每学完一个模块，把状态从 `TODO` 改成 `DONE`，并在“我的复盘”里写 3 句话：这个模块解决什么问题、项目里怎么做、它的边界是什么。

## 0. 总览链路

```text
用户请求
  -> Controller / JWT / 参数校验 / TraceId
  -> ChatService 或 AgentExecutor
  -> Query Rewrite / History
  -> Structured Chunk / Small-to-Big
  -> BM25 + Vector 并行召回
  -> RRF 融合
  -> BGE Reranker 精排
  -> RelevanceGate
  -> Prompt + Citation
  -> LLM / Streaming LLM
  -> Citation 校验
  -> MySQL 持久化 + Outbox
  -> Redis 热窗口 / 缓存投影
  -> JSON 或 SSE 返回
```

五条主线不是平行背诵，它们在同一条请求链路上互相咬合：

| 主线 | 解决的问题 | 最后要能讲成 |
|---|---|---|
| 1. 检索质量 | 用户问得不标准时，怎么尽量召回对的上下文 | 为什么不是单路检索，为什么 Small-to-Big |
| 2. 效果证明 | 怎么证明改动真的变好，而不是感觉变好 | 标注集、指标、坏例和局限 |
| 3. Agent 可控性 | 模型想调用工具时，后端怎么接住它 | Function Calling 到 Agent Loop |
| 4. 运行可靠性 | 工具和请求出错时，系统怎么不失控 | 校验、权限、超时、幂等、循环检测 |
| 5. 状态与扩展性 | 历史、事件、流式和容量怎么工程化 | Redis/MySQL/Outbox/SSE/限流/监控 |

## 1. 检索质量

目标：把“文档怎么进来、怎么切、怎么召回、怎么精排、怎么给 LLM”讲成一条自然链路。

| 模块 | 状态 | 重点源码 | 测试/验证 | 学完要能回答 |
|---|---|---|---|---|
| 1.1 结构化 Chunk | TODO | `HierarchicalChunker`, `Chunk`, `DocumentIngestionService` | `LocalHybridRagIntegrationTest` | 为什么 child 检索、parent 回答 |
| 1.2 BM25 召回 | TODO | `Bm25Index`, `DocumentRegistry` | `EvalRunnerTest#compareBm25VsHybrid` | 为什么技术文档里 BM25 很重要 |
| 1.3 Vector 召回 | TODO | `EmbeddingService`, `InMemoryVectorStore`, `ResilientEmbeddingService` | `InMemoryVectorStoreTest` | 向量检索补了 BM25 什么短板 |
| 1.4 RRF 融合 | TODO | `RrfFusion`, `HybridRetriever` | `RetrievalEvaluator` | 为什么不直接加权合并分数 |
| 1.5 BGE Reranker | TODO | `BgeReranker`, `Reranker`, `RerankResult` | `RelevanceGateTest` | RRF 和 Reranker 的职责边界 |
| 1.6 Small-to-Big | TODO | `HybridRetriever`, `SearchResult`, `CitationFormatter` | `LocalHybridRagIntegrationTest` | 小块命中和大块上下文怎么兼顾 |

学习顺序建议：

```text
DocumentIngestionService
  -> HierarchicalChunker
  -> Bm25Index / InMemoryVectorStore
  -> HybridRetriever
  -> RrfFusion
  -> BgeReranker
  -> RelevanceGate / CitationFormatter
```

面试表达模板：

> 这个项目里我没有直接把整篇文档塞给模型，而是先做结构化切块。Child chunk 用来提高检索精度，命中以后再展开 parent 文本给模型，这样既不会因为块太大导致召回不准，也不会因为块太小导致回答缺上下文。召回阶段我并行走 BM25 和向量检索，BM25 负责精确术语，向量负责语义表达不一致的情况。两路结果用 RRF 融合，因为 BM25 分数和 cosine 分数不是一个尺度。最后再用 BGE Reranker 精排，让 Cross-Encoder 做更细的 query-doc 判断。

我的复盘：

```text
完成日期：
我能讲清：
我还不稳的点：
一个坏例：
下一步：
```

## 2. 效果证明

目标：从“我做了混合检索”升级成“我知道怎么验证混合检索是否值得”。

| 模块 | 状态 | 重点源码/资源 | 测试/验证 | 学完要能回答 |
|---|---|---|---|---|
| 2.1 标注集设计 | TODO | `eval-dataset.json`, `EvalDataset` | 查看 30 条 query 类型 | 30 条数据怎么标，为什么用 doc prefix |
| 2.2 Recall / Precision | TODO | `RetrievalEvaluator` | `EvalRunnerTest#runRetrievalEvaluation` | Recall 高说明什么，不说明什么 |
| 2.3 MRR | TODO | `RetrievalEvaluator` | 同上 | 为什么第一个相关结果的位置重要 |
| 2.4 NDCG | TODO | `RetrievalEvaluator` | 同上 | 为什么当前 NDCG 只能方向性参考 |
| 2.5 BM25 vs Hybrid | TODO | `EvalRunnerTest#compareBm25VsHybrid` | 单测输出对比表 | 怎么证明不是凭感觉加了向量 |
| 2.6 坏例分析 | TODO | 评估输出 + 手工记录 | 自己维护 bad cases | 哪些 query 会失败，下一步怎么改 |

建议你维护一个小坏例表：

| 日期 | Query | 期望文档 | 实际命中文档 | 失败原因 | 下一步 |
|---|---|---|---|---|---|
|  |  |  |  | 标注问题 / 切块问题 / 召回问题 / 精排问题 / Gate 问题 |  |

参数理解：

| 参数 | 项目含义 | 调参方向 |
|---|---|---|
| `TopK` | 最终给 LLM 的文档数量 | 太小上下文不够，太大 prompt 噪声增加 |
| `RRF k` | 控制排名差距被放大还是抹平 | 小 k 更信头部排名，大 k 更平均 |
| `TopN` | 进入 Reranker 的候选池 | 太小丢召回，太大延迟高 |
| Gate threshold | 控制是否允许 RAG 回答 | 太高拒答多，太低容易带错上下文 |

面试表达模板：

> 评估这块我没有只看主观回答，而是做了一个小型标注集。每条 query 会标问题类型、参考答案和 ground truth 文档前缀。因为切块后一个知识点可能分布在相邻 chunk，所以我没有强行标死 chunk id，而是用文档前缀做命中判断。指标上主要看 Recall、MRR 和 NDCG。Recall 看有没有召回来，MRR 看相关结果是不是排得靠前，NDCG 看整体排序质量。但我也会主动说明局限：数据集只有 30 条，标注粒度偏文档级，生成评估还是 mock LLM，所以这些指标更适合比较方案变化，不适合当成绝对效果宣传。

我的复盘：

```text
完成日期：
我能讲清：
我还不稳的点：
一个坏例：
下一步：
```

## 3. Agent 可控性

目标：讲清楚模型怎么“提出工具调用”，后端怎么“审核、执行、把结果喂回模型”。

| 模块 | 状态 | 重点源码 | 测试/验证 | 学完要能回答 |
|---|---|---|---|---|
| 3.1 Function Calling 协议 | TODO | `OpenAiFunctionCallingClient`, `AgentAction` | `OpenAiFunctionCallingClientTest` | tool call 不是模型直接执行函数 |
| 3.2 AgentContext | TODO | `AgentContext`, `AgentResult` | `AgentExecutorTest` | step history 为什么必须贯穿一轮推理 |
| 3.3 Registry | TODO | `ToolRegistry`, `ToolDefinition` | `ToolSchedulerTest` | 工具发现和 schema 谁负责 |
| 3.4 Executor | TODO | `ToolExecutor`, `SearchTool`, `CalculatorTool` | `AgentExecutorTest` | 工具执行结果如何结构化 |
| 3.5 Scheduler | TODO | `ToolScheduler`, `ToolPermissionEvaluator` | `ToolPermissionEvaluatorTest` | 步数、权限、重复调用、超时谁控制 |
| 3.6 Tool Role 回传 | TODO | `AgentExecutor`, `PromptAgentLlmClient` | `AgentExecutorTest` | 工具结果为什么要回传给模型继续推理 |

标准 Agent Loop：

```text
User message
  -> LLM chooses final answer or tool call
  -> Backend validates tool name and arguments
  -> Registry finds definition
  -> Scheduler checks permission, step limit, loop risk, timeout
  -> Executor runs tool
  -> Tool result becomes observation/tool message
  -> LLM sees observation
  -> Final answer or next tool call
```

面试表达模板：

> 我对 Function Calling 的理解是，模型只是提出一个结构化调用意图，比如工具名和参数，真正能不能执行要由后端决定。项目里 Registry 负责维护工具定义和 schema，Executor 负责调用具体工具，Scheduler 负责权限、步数、超时和重复调用这类安全边界。工具执行完以后，结果不会直接等同于最终答案，而是作为 observation 或 tool message 放回上下文，让模型基于真实工具结果继续推理。这样模型负责决策，后端负责边界和执行。

我的复盘：

```text
完成日期：
我能讲清：
我还不稳的点：
一个工具调用例子：
下一步：
```

## 4. 运行可靠性

目标：把“能跑”讲到“出错时也不乱跑”。

| 模块 | 状态 | 重点源码 | 测试/验证 | 学完要能回答 |
|---|---|---|---|---|
| 4.1 参数校验 | TODO | DTO, `GlobalExceptionHandler`, `ApiErrorFactory` | `ConfigurationQualityTest` | 参数错时在哪里拦、怎么返回 |
| 4.2 RBAC | TODO | `SecurityConfig`, `JwtAuthenticationFilter`, `ToolPermissionEvaluator` | `SecurityIntegrationTest` | 为什么不能相信 request body 里的 role |
| 4.3 超时 | TODO | `ToolScheduler`, `ExternalLlmClient`, `RestClientConfig` | `ExternalLlmClientMetricsTest` | 工具和外部模型超时怎么隔离 |
| 4.4 循环检测 | TODO | `ToolScheduler`, `AgentExecutor` | `AgentExecutorTest` | 连续相同工具调用为什么危险 |
| 4.5 Embedding 熔断降级 | TODO | `ResilientEmbeddingService`, `SimpleEmbeddingService` | `EmbeddingResilienceMetricsTest` | 外部 embedding 挂了为什么系统还能检索 |
| 4.6 部分失败 | TODO | `ToolResult`, `OutboxRelay`, `DocumentManagementService` | 对应 service test | 批量任务为什么不能只有成功/失败二值 |
| 4.7 幂等 | TODO | `OutboxRelay`, mapper XML, Redis Lua 投影 | `OutboxRelayTest` | 重试为什么不能产生重复副作用 |

故障处理分层：

| 故障 | 负责层 | 项目里的处理思路 |
|---|---|---|
| 参数错误 | Controller/DTO/ExceptionHandler | 统一错误响应 |
| 工具不存在 | Registry/Scheduler | 执行前拒绝 |
| 越权 | Security/PermissionEvaluator/Scheduler | 使用认证身份，不信客户端自报 |
| 工具超时 | Scheduler | Future timeout，返回结构化失败 |
| LLM 超时 | LlmClient | timeout + fallback/错误指标 |
| Embedding 失败 | ResilientEmbeddingService | 重试、熔断、本地 TF-IDF |
| 重复调用 | Scheduler/AgentContext | step history 检测 |
| Redis 投影失败 | OutboxRelay | 重试、DEAD 状态、MySQL 回源 |

面试表达模板：

> 我做这块时没有把可靠性都塞在某一个类里，而是按边界拆开。参数错误在入口和统一异常处理里解决，工具是否存在由 Registry 和 Scheduler 在执行前判断，权限来自 SecurityContext，不信任请求体里的 role。真正执行工具时由 Executor 返回结构化结果，Scheduler 再控制超时、最大步数和重复调用。外部 embedding 失败时走熔断和本地 TF-IDF fallback。我的理解是，Agent 系统最怕的不是某个工具失败，而是失败以后模型继续盲目调用，所以每一步都要有可观察、可中断、可解释的结果。

我的复盘：

```text
完成日期：
我能讲清：
我还不稳的点：
一个故障场景：
下一步：
```

## 5. 状态与扩展性

目标：讲清 Redis、MySQL、Outbox、SSE、限流和监控之间的关系。

| 模块 | 状态 | 重点源码/配置 | 测试/验证 | 学完要能回答 |
|---|---|---|---|---|
| 5.1 Redis 热窗口 | TODO | `ChatSessionService`, `ChatCacheProjector` | `ChatSessionServiceTest` | 为什么会话最近窗口适合 Redis |
| 5.2 MySQL 持久化 | TODO | entity/mapper/service, Flyway V1 | `BusinessPersistenceIntegrationTest` | 为什么完整历史不能只靠 Redis |
| 5.3 Outbox | TODO | `OutboxEventService`, `OutboxRelay`, Flyway V2 | `OutboxRelayTest` | 怎么避免业务成功但事件丢失 |
| 5.4 SSE 恢复 | TODO | `StreamingChatService`, `SseReplayBuffer`, `useSSE.ts` | `SseReplayBufferTest`, `StreamingChatServiceTest` | Last-Event-ID 能恢复什么，不能恢复什么 |
| 5.5 限流排队隔离 | TODO | `scripts/loadtest`, `docs/load-testing.md`, async config | load test 脚本 | 10 QPS 模型遇到 200 请求怎么处理 |
| 5.6 监控 | TODO | `RagMetrics`, `RagObservability`, Grafana JSON | observability tests | 看哪些指标判断系统要降级 |

Redis、MySQL、Outbox、SSE 的关系：

```text
MySQL
  保存长期、完整、可审计状态
  同事务写业务数据和 outbox_event

OutboxRelay
  异步读取 PENDING 事件
  投影到 Redis
  失败重试，超过上限 DEAD

Redis
  保存最近会话窗口、缓存、热状态
  提供低延迟读取
  未命中时可以回源 MySQL

SSE
  负责把生成过程推给前端
  短断线靠 Last-Event-ID 和 replay buffer 补发
  当前不是跨实例持久消息流
```

容量治理思路：

| 场景 | 处理方式 |
|---|---|
| 模型 10 QPS，来了 200 请求 | 入口限流、模型层队列、并发隔离、超时上限 |
| 队列持续上涨 | 拒绝低优先级请求，缩短上下文，关闭 Reranker，走缓存 |
| Redis 不可用 | 本地 fallback 或 MySQL 回源，记录指标 |
| MySQL 慢 | 降低写入压力、核心链路隔离、告警 |
| SSE 慢客户端 | 独立发送线程池、有界队列、断开清理 |
| RAG 质量下降 | 看 retrieval/rerank/gate 指标和坏例 |

面试表达模板：

> 我在项目里把 Redis 和 MySQL 的职责分开了。Redis 主要放最近会话窗口和热缓存，因为它读写快、适合 TTL；MySQL 放完整历史、用户、文档元数据和 outbox，因为这些需要事务和审计。为了避免业务数据写成功但缓存事件丢失，我用 Transactional Outbox，在同一个事务里写业务表和 outbox_event，再由 Relay 异步投影到 Redis。SSE 这块支持 event id 和 Last-Event-ID，但当前只是单 JVM 短期 replay，不会夸大成 Kafka 那种跨实例可恢复消息系统。容量上，如果模型只有 10 QPS，就要入口限流、队列隔离、超时和降级，不能让所有请求直接打模型。

我的复盘：

```text
完成日期：
我能讲清：
我还不稳的点：
一个扩展性问题：
下一步：
```

## 6. 推荐学习路线

如果你想先把面试能讲顺，按这个顺序：

```text
第 1 轮：主线 1 -> 主线 2
目标：能讲清 RAG 为什么这样设计，以及怎么证明有效。

第 2 轮：主线 3 -> 主线 4
目标：能讲清 Agent 不是裸调用工具，而是有后端边界控制。

第 3 轮：主线 5
目标：能把项目从 demo 讲到生产化取舍。

第 4 轮：串讲
目标：90 秒项目介绍 + 8 个高频追问 + 2 个坏例复盘。
```

如果你想先读源码，按这个顺序：

```text
DocumentIngestionService
HybridRetriever
RrfFusion
BgeReranker
RelevanceGate
EvalRunnerTest
AgentExecutor
ToolRegistry / ToolExecutor / ToolScheduler
ChatSessionService
OutboxRelay
StreamingChatService
RagObservability / RagMetrics
```

## 7. 每次学习后的自查

每学完一个模块，用下面 5 个问题检查自己：

```text
1. 这个模块在完整请求链路中处于哪一步？
2. 它解决的真实问题是什么？
3. 项目代码里具体由哪些类负责？
4. 它有什么边界或生产化缺口？
5. 面试官追问时，我能不能用一个例子讲出来？
```

## 8. 总进度

| 主线 | 当前状态 | 下一模块 | 我自己的备注 |
|---|---|---|---|
| 1. 检索质量 | TODO | 1.1 结构化 Chunk |  |
| 2. 效果证明 | TODO | 2.1 标注集设计 |  |
| 3. Agent 可控性 | TODO | 3.1 Function Calling 协议 |  |
| 4. 运行可靠性 | TODO | 4.1 参数校验 |  |
| 5. 状态与扩展性 | TODO | 5.1 Redis 热窗口 |  |

