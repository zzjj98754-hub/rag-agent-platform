# demo00 项目面试 Q&A —— 50 个高频问题

> **生成原则**：所有答案基于项目真实代码，不存在的功能明确标记【未实现 / 可优化】。
> **目标**：让你能独立讲清这个项目 30 分钟，应对 AI 应用开发 / Java 后端实习面试。

---

# 一、项目整体（10 个）

---

## Q1：请简单介绍一下你的这个项目。

**考察点**：项目全局理解，表达清晰度。

**30 秒回答**：
我做的是一个基于 Spring Boot 3.3 的 RAG 智能问答平台，核心能力是：用户提问 → 混合检索（BM25 + 向量语义）→ RRF 融合 → Cross-Encoder 精排 → 相关文档拼入 Prompt → 调用 LLM 生成带引用的答案。同时集成了 Agent Tool Calling 框架，支持 LLM 自主调用工具。

**2 分钟回答**：
这个项目叫 demo00，是一个 AI Agent 平台，包含 46 个核心类，覆盖 6 大能力区域：

1. **AI 对话工程化**：ChatService 编排完整的 RAG Pipeline，支持同步和 SSE 流式两种输出模式
2. **RAG 检索增强**：两阶段检索（BM25 关键词 + Embedding 语义 → RRF 融合 → BGE Reranker 精排），配合 Query Rewrite、RelevanceGate、Citation 校验
3. **Embedding 弹性化**：主备架构（SiliconFlow BGE API + TF-IDF 本地降级），中间夹熔断器 + 查询缓存
4. **VectorStore 可插拔**：通过接口抽象，当前用 ConcurrentHashMap 实现，架构上预留了 Redis Stack 和 pgvector 的扩展点
5. **Agent / Tool Calling**：策略模式的工具注册中心 + 带 5 层安全防护的调度器
6. **自动化评测**：20 条标注查询的评测集，覆盖 Recall@K、MRR、NDCG 等检索指标，以及 RAGAS 风格的生成质量评分

项目用 Redis 做会话上下文和结果缓存，MySQL 已连接但 RAG 功能不依赖。整个项目是我使用 AI 辅助完成的，我在过程中深入理解了每一行代码的设计决策。

**可能追问**：项目是你独立完成的吗？你在里面具体做了什么？
**回答风险提醒**：要承认 AI 辅助，但强调你对代码的理解和设计决策的思考。面试官更看重你"为什么这么设计"而非"写了多少行代码"。

---

## Q2：你的 RAG Pipeline 从收到请求到返回响应，整个链路是怎样的？

**考察点**：系统全链路思维，组件协作关系。

**30 秒回答**：
请求经过 RequestLoggingFilter（注入 traceId）→ ChatController → ChatService.ask()。ChatService 内部依次：解析会话上下文 → Query Rewrite（指代消解）→ 混合检索（BM25 + Embedding 并行 → RRF 融合 → Reranker 精排）→ RelevanceGate 门控 → 缓存查询 → 构建 Prompt → 调用 LLM → Citation 校验 → 写入缓存 → 保存对话历史。

**2 分钟回答**：
完整的 RAG Pipeline 在 `ChatService.ask()` 方法中，共 8 个步骤：

1. **会话上下文解析**：通过 `ChatSessionService` 从 Redis 获取最近 20 条历史
2. **Query Rewrite**：`LlmQueryRewriter.rewrite()` 对多轮对话做指代消解（"它"→具体实体），先尝试 LLM 改写，失败降级为规则
3. **混合检索**：`HybridRetriever.retrieve()` 两阶段：
   - 阶段一（粗排）：BM25 + Embedding 双路并行（CompletableFuture），RRF 融合，Parent 级去重
   - 阶段二（精排）：BgeReranker Cross-Encoder 对候选逐条打分
4. **相关性门控**：`RelevanceGate.evaluate()` 检查最高分是否 ≥ 0.35，不达标则 Prompt 告知 LLM「未找到相关信息」，禁止编造
5. **缓存查询**：SHA-256(query + docsHash) 作为 key，先查 Redis，不可用降级 ConcurrentHashMap
6. **Prompt 构建**：CitationFormatter 将 chunk 格式化为 `[1] (来源: java.txt) 内容...`，附带引用指令
7. **LLM 调用**：`ExternalLlmClient.callLlm()` HTTP 调用 /mock-llm，含超时处理和降级回复
8. **后处理**：CitationValidator 检测越界引用 → 写缓存（TTL 抖动防雪崩）→ 保存对话历史（Redis LPUSH + LTRIM）

**可能追问**：哪一步是最容易出问题的？哪个环节耗时最长？
**回答风险提醒**：链路要能从代码中对应出来，不要背流程。每个步骤准备好一句话解释"为什么这么做"。

---

## Q3：你的项目中最有技术含量的模块是什么？为什么？

**考察点**：技术深度自我认知，能否识别关键设计。

**30 秒回答**：
我认为有两个。一个是 ResilientEmbeddingService 的三层弹性防御（缓存 + 熔断 + 降级），体现了高可用架构思维；另一个是 HybridRetriever 的两阶段检索编排（并行粗排 + RRF 融合 + Cross-Encoder 精排），用 CompletableFuture 让延迟等于 max(BM25, Embedding) 而非 sum。

**2 分钟回答**：
挑 `ResilientEmbeddingService` 展开：

**业务问题**：Embedding API 是外部服务，不可用时直接返回空向量会导致整个 RAG 链路断开，用户看到的就是"系统不可用"。

**技术方案**：三层防御体系
1. **查询缓存**（第一层）：SHA-256(text) → float[]，FIFO 淘汰（max 2000 条），相同 query 不重复调 API，同时降低延迟和成本
2. **熔断器**（第二层）：手写 Circuit Breaker，CLOSED → (连续 3 次失败) → OPEN (60s cooldown) → HALF_OPEN (允许一次探测) → CLOSED。用 `volatile` 保证状态可见性
3. **自动降级**（第三层）：主服务不可用 → 无感切到 SimpleEmbeddingService (TF-IDF)。降级方案是**可工作的实现**而非 TODO 占位符，保证核心链路不中断

**设计亮点**：
- 熔断器状态用 `volatile` 而非 `synchronized`——状态标志不需要原子性保证，`volatile` 的可见性已足够，避免了锁竞争
- 半开状态的探测存在 thundering-herd 竞态条件，但在低并发场景下可以接受——这是有意为之的性能取舍
- TF-IDF 降级的语义质量不如 BGE，但**能跑**比**跑得好**更重要——高可用的核心思想

**可能追问**：为什么不用 Resilience4j？为什么不用 Redis 做缓存？
**回答风险提醒**：主动承认设计取舍（如熔断器竞态条件），不要把它说成完美方案。

---

## Q4：你为什么选择自己实现 BM25，而不是用 Elasticsearch？

**考察点**：技术选型决策能力，学习动机 vs 工程选择。

**30 秒回答**：
这是学习项目，自己实现 BM25 让我彻底理解了 TF 饱和、文档长度归一化、IDF 平滑这些核心概念。工程上如果要上生产，我会选 Elasticsearch，但面试时我能从公式推导讲到参数调优，这比只会调 ES API 有价值得多。

**2 分钟回答**：
`Bm25Index` 的实现了完整的 BM25 算法（代码 `Bm25Index.java:153-179`）：

```java
double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);
double tfNorm = (tfInDoc * (K1 + 1)) / (tfInDoc + K1 * (1 - B + B * docLen / avgDocLen));
```

**自实现的收获**：
1. **参数理解**：k1=1.2, b=0.75 不是随便取值——k1 控制词频饱和速度（词出现 3 次和 10 次的信号差异不大），b 控制文档长度惩罚力度。这两个值是 TREC 评测验证的最优组合
2. **倒排索引**：理解 HashMap<String, Map<String, Integer>> 的倒排结构——term → (docId → termFreq)，这是所有搜索引擎的基础
3. **分词策略**：中文用 2-gram 子词（如"缓存穿透"→ ["缓存","存穿","穿透"]），英文按空格切分，简单但有效

**工程上的考虑**：
- 当前适用场景：<1000 篇文档，O(N) 遍历足够快
- 生产替换路径：ES 的 BM25 实现 + 分布式倒排索引，只需改 `HybridRetriever` 中 `fetchBm25RankedIds()` 方法

**可能追问**：你的分词有什么局限性？中文分词为什么不用 jieba？
**回答风险提醒**：不要贬低 Elasticsearch，要说清楚自己做是为了学习，生产会换。

---

## Q5：你的项目能对外提供什么样的服务？有哪些限制？

**考察点**：对自己系统的边界认知，诚实度。

**30 秒回答**：
当前是一个单机演示项目，能提供基于本地文档的 RAG 问答、SSE 流式输出、Agent 工具调用。最大限制是：LLM 用的是 Mock 而非真实模型，向量存储是内存级的（重启丢失但不影响功能因 Redis 有持久化），不支持多租户和水平扩展。

**2 分钟回答**：
**能提供的服务**：
1. 基于 D:\docs 目录下文档的智能问答（带引用来源）
2. SSE 流式输出（模拟 token-by-token）
3. Agent 工具调用（知识库搜索 + 数学计算）
4. 文档增量入库（SHA-256 变更检测 + 异步后台执行）
5. 多轮对话（Redis 滑动窗口 + 指代消解）
6. 自动化评测（检索质量 + 生成质量）

**当前限制**：
1. **LLM 是 Mock**：`ExternalLlmClient` 调用的是本机 `/mock-llm` 端点，返回的是模拟内容（`MockExternalLlmController`），不是真模型生成。降级回复是硬编码的关键词匹配（`ExternalLlmClient.java:103-120`）
2. **向量存储是内存级**：`InMemoryVectorStore` 用 ConcurrentHashMap，重启后靠 Redis JSON 恢复。千级文档内可行，万级以上需换 Redis Stack HNSW 或 pgvector
3. **单机架构**【未实现】：没有分布式部署、无负载均衡、无水平扩展能力
4. **安全机制薄弱**【未实现】：没有用户认证、API 限流、输入净化
5. **Tool Calling 不完整**【未实现 / 可优化】：Agent 循环由前端/外部管理，后端只执行单次调用。`AgentController` 中 `stepHistory` 每次请求都是空列表（`AgentController.java:53`）

**可能追问**：如果要给 100 个用户使用，你需要做哪些改造？
**回答风险提醒**：诚实说出限制比吹牛重要。面试官更看重你能否识别系统的短板。

---

## Q6：说说你的项目的技术架构和分层设计。

**考察点**：架构素养，分层合理性。

**30 秒回答**：
项目采用典型的 Spring Boot 三层架构：Controller 层接收 HTTP 请求（REST + SSE），Service 层编排业务逻辑（ChatService、DocumentIngestionService），底层是 RAG 组件（检索、Embedding、向量存储）。Agent 模块独立于 RAG 模块，通过 ToolDefinition 接口解耦。

**2 分钟回答**：
项目按职责划分为 6 层：

```
Controller 层（对外接口）
├── ChatController     → POST /chat（同步）
├── StreamController   → GET /chat/stream（SSE 流式）
├── AgentController    → POST /agent/tool-call, GET /agent/tools
├── AdminController    → 文档入库管理
└── MockExternalLlmController → 模拟 LLM

Service 层（业务编排）
├── ChatService            → RAG Pipeline 编排
├── ChatSessionService     → 会话上下文管理
├── ExternalLlmClient      → LLM HTTP 调用 + 降级
├── DocumentIngestionService → 入库管线编排
├── ResilientEmbeddingService → 弹性 Embedding
└── DocumentRegistry       → 文档注册表

RAG 组件层（检索核心）
├── HybridRetriever   → 两阶段检索编排
├── Bm25Index         → 关键词检索
├── RrfFusion         → 排名融合
├── BgeReranker       → Cross-Encoder 精排
├── RelevanceGate     → 相关性门控
├── HierarchicalChunker → 文档切分
├── QueryRewriter     → 查询改写
├── CitationFormatter → 引用格式化
└── CitationValidator → 引用校验

Agent 组件层（工具调用）
├── ToolDefinition    → 工具策略接口
├── ToolRegistry      → 注册中心
├── ToolScheduler     → 安全调度
├── ToolPermissionEvaluator → RBAC
├── SearchTool        → 知识检索工具
└── CalculatorTool    → 计算器工具

基础设施层
├── RequestLoggingFilter → 全链路追踪
├── MysqlConnectionVerifier → 启动健康检查
├── VectorStoreConfig → 后端选择
└── RestClientConfig  → HTTP 客户端配置

数据层
├── VectorStore (接口) / InMemoryVectorStore (实现)
├── EmbeddingService (接口) / 3 个实现
├── Redis → 会话/缓存/向量持久化
└── MySQL → 已连接（未使用）
```

**关键设计原则**：
- **面向接口编程**：VectorStore、EmbeddingService 都是接口，切换实现零业务代码改动
- **策略模式**：ToolDefinition 接口 + ToolRegistry 自动收集所有实现
- **@Primary 选择**：ResilientEmbeddingService 用 @Primary 覆盖其他实现

**可能追问**：为什么 Embedding 有三个实现，VectorStore 只有一个？
**回答风险提醒**：分层要说得出来每层的职责边界，不要笼统说"MVC 三层"。

---

## Q7：如果让你重新设计这个项目，你会改进哪些地方？

**考察点**：批判性思维，成长意识。

**30 秒回答**：
三个改进方向：一是接入真实的 LLM API（如 DeepSeek/Qwen），替换 Mock；二是引入 Redis Stack 的 HNSW 索引替代暴力扫描；三是完善 Agent 循环闭环，让 LLM 真正能多轮调用工具直到完成任务。

**2 分钟回答**：
**改进点 1：真实 LLM 接入**
当前 LLM 是 Mock 的（`MockExternalLlmController` 返回「模拟 LLM 回答」），整个 RAG Pipeline 到这一步断了。改进方案：接入 DeepSeek API（便宜且中文好），替换 `ExternalLlmClient` 的调用目标，同时接入 streaming API 让 SSE 不再是模拟。

**改进点 2：VectorStore 升级**
`InMemoryVectorStore.search()` 是 O(N) 暴力余弦扫描（`InMemoryVectorStore.java:58-69`），千级文档没问题，万级以上就慢。VectorStore 已经是接口，实现一个 `RedisStackVectorStore`（用 Redis Stack 的 FT.SEARCH HNSW 索引），在 `VectorStoreConfig` 中加一个 case 分支即可。

**改进点 3：Agent 循环闭环【未实现】**
当前 Agent 每次调用是独立的——`AgentController.toolCall()` 中 `stepHistory` 是空列表（`AgentController.java:53`），意味着死循环检测在这个层面不生效。改进方案：实现一个 `AgentLoop` 组件，让 LLM 决策 → 调用工具 → 观察结果 → 再决策，直到 LLM 输出最终答案或达到步数上限。

**改进点 4：可观测性增强**
当前只有 `RequestLoggingFilter` 的 traceId + 计时，缺少指标采集（Prometheus metrics）和业务监控（检索延迟分布、缓存命中率趋势、Embedding 降级次数）。【未实现】

**改进点 5：安全加固**
当前没有任何认证机制，`/chat`、`/agent/tool-call` 都是裸奔的。至少需要 API Key 认证 + 请求频率限制。【未实现】

**可能追问**：为什么选 DeepSeek 而不是 GPT-4？
**回答风险提醒**：改进点要有具体方案（改哪个类、加什么），不要泛泛说"加缓存""优化性能"。

---

## Q8：你的项目中的自动化评测是怎么做的？有什么价值？

**考察点**：质量意识，是否理解评测在 RAG 中的角色。

**30 秒回答**：
我实现了一个 20 条标注查询的评测集，分两种评测：检索评测（Recall@K、Precision@K、MRR、NDCG@K，纯数学计算，秒级跑完）和生成评测（RAGAS 三指标：忠实度、答案相关性、上下文相关性，用关键词近似评分）。评测集成在 JUnit 测试中，每次 `mvnw test` 自动跑。

**2 分钟回答**：
评测代码在 `src/test/java/.../evaluation/` 下，由 `EvalRunnerTest` 统一编排。

**评测集设计**（`EvalDataset` + `eval-dataset.json`）：
- 20 条查询，覆盖 4 种类型：factoid（事实查找）、conceptual（概念理解）、multi-hop（多文档综合）、pronoun-resolution（指代消解）
- 每条标注 `groundTruthDocPrefixes`（相关文件的前缀，如 "java.txt"），用于和检索结果做前缀匹配判断是否命中
- 可选 `referenceAnswer` 用于生成评测

**检索评测**（`RetrievalEvaluator`）：
- Recall@K：检索到的相关文档占全部相关文档的比例（RAG 最核心指标——漏了文档 LLM 就看不到）
- Precision@K：检索结果中相关的比例
- MRR：第一个相关文档的排名倒数
- NDCG@K：考虑位置权重的排序质量
- 设置了 CI 质量门禁：Recall@3 < 0.5 时输出警告

**生成评测**（`RAGASEvaluator`）：
- Faithfulness：答案关键词在上下文中的覆盖率（防幻觉）
- Answer Relevance：答案和 query 的词汇重叠度
- Context Relevance：检索文档和 query 的词汇重叠度
- **注意**：当前用的是关键词近似而非 LLM-as-Judge，生产环境应切换到 GPT-4/Claude 做精确评分。代码注释中已标注此限制（`RAGASEvaluator.java:23-24`）

**价值**：
- 改检索参数（topK、k1、b、threshold）后跑一次就知道有没有倒退
- 按 query 类型分组统计（`EvalRunnerTest.printTypeBreakdown`），定位具体哪种查询弱
- 新人接手项目也能跑评测理解系统能力基线

**可能追问**：RAGAS 和你的简化版有什么区别？为什么不直接用 RAGAS？
**回答风险提醒**：不要夸大评测的准确性，主动说明简化版的局限性。

---

## Q9：你的项目怎么保证稳定性？Embedding API 挂了怎么办？

**考察点**：高可用思维。

**30 秒回答**：
`ResilientEmbeddingService` 实现了三层防御：最外层是查询缓存（SHA-256 去重），中间是熔断器（连续 3 次失败 → OPEN → 60s 后 HALF_OPEN 探测），最内层是自动降级到本地 TF-IDF。降级方案不是 TODO 而是可工作的实现，保证 RAG 链路不中断。

**2 分钟回答**：
（详见 Q3 的回答，这里补充代码细节）

**降级链路验证**（`ResilientEmbeddingService.embed()`）：
```java
// 1. 查缓存 → 命中直接返回，不调 API
float[] cached = cache.get(cacheKey);
if (cached != null) return cached;

// 2. 尝试主服务（可能走熔断判断）
float[] result = tryPrimary(text);

// 3. 主服务不可用 → 无感切到 TF-IDF
if (result == null || result.length == 0) {
    result = fallback.embed(text);
}

// 4. 写缓存
cache.put(cacheKey, result);
```

**缓存层面的稳定性**（`ChatService`）：
- Redis 不可用 → 降级到 ConcurrentHashMap（`ChatService.java:74`）
- TTL 抖动（±10% 随机浮动）防缓存雪崩（`ChatService.java:267-270`）
- 缓存序列化失败 → 降级为纯字符串存储（`ChatService.java:249-252`）

**LLM 调用的稳定性**（`ExternalLlmClient`）：
- 区分连接超时和读取超时，给出不同的提示
- ConnectException → 「LLM 服务暂时不可用」
- SocketTimeoutException → 「LLM 服务响应超时，请稍后重试」
- 所有异常都有 fallbackResponse()，不会抛异常导致 Controller 返回 500

**可能追问**：TF-IDF 的向量质量和 BGE 差距很大，降级有意义吗？
**回答风险提醒**：关键逻辑是"降级方案能工作"而非"降级方案和主方案一样好"——这是高可用的核心原则。

---

## Q10：你做这个项目过程中遇到的最大的技术难题是什么？

**考察点**：问题解决能力，技术故事。

**30 秒回答**：
最大的难题是理解 RRF 融合为什么优于加权求和。起初我直觉地想用 min-max 归一化后加权求和，但发现 BM25 分数无上界而余弦相似度在 [-1,1]，归一化依赖当前检索结果的分布极不稳定。换成 RRF（只使用排名而非原始分数）后才解决了跨检索源可比性的问题。

**2 分钟回答**：
（准备 1-2 个具体的技术故事，以下是一个例子）

**问题**：BM25 + Embedding 双路检索结果如何融合？

**最初的思路**（错误）：对两路分数分别做 min-max 归一化到 [0,1]，然后加权求和。
- 问题：BM25 某次查询最高分可能是 45.2，另一次可能是 3.1，归一化的基准完全不同
- 结果：在某次查询中排名第 5 的文档归一化后可能比另一次查询排名第 1 的还高——完全没有可比性

**正确的方案**：RRF (Reciprocal Rank Fusion)，`RrfFusion.java`：
```java
rrfScores.merge(id, 1.0 / (K + rank + 1), Double::sum);
```
- 只使用排名，天然跨检索源可比
- k=60 防止排名第一的文档权重过大（论文验证的经验值）
- 两路排名独立贡献，最终按 RRF 总分降序

**为什么这对我很难**：因为我一开始从"分数应该精确融合"的角度思考，而 RRF 的精妙之处恰恰在于"放弃原始分数，只用排名"——少即是多。这个认知转变花了我不少时间。

**其他可选故事**：
- 熔断器从 CLOSED → HALF_OPEN → OPEN 的状态机设计和并发安全取舍
- Small-to-Big chunking 中 Parent 级去重为什么要在两处（InMemoryVectorStore 和 HybridRetriever）都做
- LlmQueryRewriter 中 LLM + 规则混合策略的延迟优化

**可能追问**：除了 RRF，你还了解其他融合方法吗？
**回答风险提醒**：选一个你确实深入思考过的故事，不要背诵。面试官能听出来是自己想的还是背的。

---

# 二、RAG / 检索（10 个）

---

## Q11：你的混合检索是怎么做的？为什么需要混合检索？

**考察点**：RAG 检索核心原理。

**30 秒回答**：
混合检索 = BM25 关键词检索 + Embedding 语义检索，两路并行执行后用 RRF 融合排名。BM25 擅长精确字面匹配（查"缓存穿透"能找到含这个词的文档），Embedding 擅长语义匹配（查"怎么防止查不到的数据打爆数据库"也能找到缓存穿透的文档），两者互补。

**2 分钟回答**：
具体实现在 `HybridRetriever.retrieve()`：

**为什么需要混合检索**？
- BM25 查不到同义词/近义词：查"雪崩"找不到只含" avalanche"的文档
- Embedding 查不到精确术语：查"k1=1.2"时语义向量可能偏到其他超参数
- 两者互补：BM25 保证精确召回，Embedding 保证语义泛化

**实现流程**：
1. 双路并行：`CompletableFuture.supplyAsync()` 同时发起 BM25 和 Embedding 检索
2. RRF 融合：对两路排名分别计算 1/(60+rank)，累加得到融合分数
3. Parent 信息补充 + 去重：同一 Parent 只保留得分最高的 Child
4. 取 candidateSize = topK × 6 条候选进入精排
5. BgeReranker Cross-Encoder 精排 → 返回最终 topK 条

**关键设计**：
- `candidateSize = topK * 6`：粗排多取给 Reranker 留余量
- Reranker 失败时降级为粗排结果（`HybridRetriever.java:103-105`），不阻塞链路
- Parent 级去重在有/无 HierarchicalChunker 两种模式下都能正确工作

**可能追问**：双路并行用 CompletableFuture 有什么好处？如果其中一路挂了怎么办？
**回答风险提醒**：清楚说出 BM25 和 Embedding 分别擅长什么场景，准备一个具体例子。

---

## Q12：BM25 和 TF-IDF 有什么区别？你的 BM25 实现做了哪些优化？

**考察点**：信息检索基础理论，工程实现理解。

**30 秒回答**：
BM25 相比 TF-IDF 有两个关键改进：一是词频饱和（k1 控制，词出现 3 次和 10 次信号差异不大），二是文档长度归一化（b 控制，长文档天然词多需要惩罚）。我的实现用了 Robertson-Sparck Jones IDF 平滑，k1=1.2, b=0.75。

**2 分钟回答**：
项目里 BM25 和 TF-IDF 是两套独立实现：
- `Bm25Index`：关键词检索引擎，用于 RAG Pipeline 的检索路
- `SimpleEmbeddingService`：TF-IDF 向量化，用于 Embedding 的降级方案

**BM25 公式**（`Bm25Index.java:152-179`）：
```java
// IDF: Robertson-Sparck Jones 平滑
double idf = Math.log((N - df + 0.5) / (df + 0.5) + 1);
// TF 归一化（含文档长度归一化）
double tfNorm = (tfInDoc * (K1 + 1)) / (tfInDoc + K1 * (1 - B + B * docLen / avgDocLen));
score += idf * tfNorm;
```

**与 TF-IDF 的区别**：
1. **词频饱和**：BM25 的 TF/(TF+K1) 让词频的贡献趋于饱和（极限是 K1+1），TF-IDF 是线性增长
2. **文档长度归一化**：BM25 的 B 参数控制长度惩罚（0=不惩罚，1=全惩罚），TF-IDF 没有这个机制
3. **IDF 平滑**：BM25 的 RSJ 平滑保证了 IDF 始终为正值（不会因为某个词出现在所有文档中而得到负值）

**自实现的"优化"**（相比基础 BM25）：
- 中文 2-gram 子词：在标准分词基础上追加 2-gram，提高短查询时的命中率（`Bm25Index.java:226-229`）
- LinkedHashMap 保序：文档在索引中的插入顺序被保留，无结果时至少能按原始顺序返回
- Parent 元数据分离：`idToParentId` 和 `idToParentText` 单独维护，不混入检索数据结构

**可能追问**：k1 和 b 分别取 1.2 和 0.75 是为什么？
**回答风险提醒**：公式能背出来最好，但更重要的是能解释每个参数的业务含义。

---

## Q13：RRF 融合是什么？为什么不用加权求和？

**考察点**：融合策略理解，是否有过思考而非照搬。

**30 秒回答**：
RRF 是一种基于排名的融合方法，公式是 score(d) = Σ 1/(k + rank_i(d))。核心优势：不同的检索源（BM25、Embedding）原始分数分布完全不同（BM25 无上界，余弦在 [-1,1]），RRF 只使用排名天然跨源可比，不需要做归一化。k=60 防止排名第一的文档权重过大。

**2 分钟回答**：
`RrfFusion.java` 实现了这个算法：

```java
for (int i = 0; i < rankedA.size(); i++) {
    rrfScores.merge(rankedA.get(i), 1.0 / (K + i + 1), Double::sum);
}
```

**为什么不用加权求和**？
1. **量纲不同**：BM25 分数是无界的（可以到几十甚至上百），余弦相似度在 [-1, 1]。直接相加的话，BM25 的贡献会完全碾压 Embedding
2. **分布不稳定**：同一次检索中，BM25 的分数分布取决于 query 的词数和文档长度；Embedding 的分布取决于语义空间的位置。没有固定的归一化系数
3. **归一化的陷阱**：min-max 归一化依赖 min 和 max，而 min 和 max 取决于**当前这次检索的结果分布**——不同 query 的 min/max 完全不同，归一化后的分数没有跨 query 可比性

**RRF 的优势**：
- 排名本身就是归一化的（第 1 名就是第 1 名，不关心分数是 50 还是 0.5）
- k=60 提供平滑效果，防止第 1 名和第 2 名的权重差距过大
- 双路独立计算 RRF 分数后累加，数学上简洁、工程上稳定

**实际效果验证**：项目中 RRF 融合后的结果会进入第二阶段的 Reranker 精排，所以粗排阶段的融合不需要极致精准，RRF 在此场景下是完全够用的。

**可能追问**：如果其中一路检索结果质量很差，RRF 会怎么表现？k=60 能调吗？
**回答风险提醒**：说出"量纲不同"和"分布不稳定"这两个关键痛点，证明你是真正思考过的。

---

## Q14：Cross-Encoder 和 Bi-Encoder 有什么区别？为什么你要两阶段检索？

**考察点**：RAG 核心性能优化思维。

**30 秒回答**：
Bi-Encoder（如 BGE Embedding）把 query 和 doc 分别编码成向量，检索时做向量内积，速度快但不精准。Cross-Encoder（如 BGE Reranker）把 query 和 doc 拼接后送进 Transformer 联合编码，query 的每个 token 都能 attend 到 doc 的每个 token，精度高但慢。所以我用 Bi-Encoder 粗排全量文档，Cross-Encoder 只精排 Top-18 候选。

**2 分钟回答**：
项目中的两阶段检索：

**阶段一（Bi-Encoder 粗排）**：
- BM25 + BGE Embedding 双路并行
- 全量文档（当前 3 篇文档约几十个 chunk）→ 取 topK × 6 = 18 个候选
- 速度快，适合扫描全量

**阶段二（Cross-Encoder 精排）**：
- `BgeReranker` 调用硅基流动的 `BAAI/bge-reranker-v2-m3` API
- query 和每个候选 doc 拼接送入 Cross-Encoder
- Cross-Encoder 中 query 的每个 token 通过 Self-Attention 都能看到 doc 的所有 token（反之亦然），输出一个精确的相关性分数
- 只处理 18 条候选，延迟可控

**核心公式**（`HybridRetriever.java:35`）：
```java
private static final int CANDIDATE_MULTIPLIER = 6;
int candidateSize = topK * CANDIDATE_MULTIPLIER; // topK=3 → 18 条候选
```

**面试直接说**：
"Bi-Encoder 是'分开看再比对'，Cross-Encoder 是'放一起仔细看'。分开看快但不准，放一起准但慢。所以全量用分开看初筛，Top-K 才放一起仔细看。这就是 RAG 检索的标准范式。"

**可能追问**：Reranker 调用失败了怎么办？candidateSize 为什么是 6 不是 10？
**回答风险提醒**：准备一个生活中类比的例子帮助记忆（比如：快递分拣先按城市分大类，再按具体地址精分）。

---

## Q15：你的文档是怎么切分的？Small-to-Big 是什么策略？

**考察点**：文档处理工程能力。

**30 秒回答**：
用 `HierarchicalChunker` 实现 Small-to-Big（父子 Chunk）策略。Parent Chunk（~2000 字）返回给 LLM 保证上下文完整，Child Chunk（~500 字，128 字重叠）用于 Embedding 检索保证精度。只索引 Child 的向量，检索命中后返回 Parent 全文——向量存储量不变，LLM 看到的上下文提升 4 倍。

**2 分钟回答**：
`HierarchicalChunker.chunk()` 的处理流程：

**第一步：Parent 切分**
- 优先按 Markdown 标题（## / ###）做语义边界切分（`splitSemanticParents`）
- 无标题时退化为段落归组（`groupIntoParents`）：按双换行拆分段落，归组到 ~2000 字
- 超长 Section 自动再切

**第二步：Child 切分**
- 每个 Parent 内部用滑动窗口切 Child：窗口 ~500 字，步进 = 500 - 128 = 372 字重叠
- 切分边界对齐句子结束符（`findSentenceBoundary`）：优先找 。；？！\n，其次找 ，、），避免在词中间截断

**短文档优化**（`chunkFlat`）：
- 文档 < 2000 字时不做层级切分（`SHORT_DOC_THRESHOLD = 2000`）
- Parent = Child（平铺模式），避免对短文档过度设计

**数据模型**（`Chunk.java`）：
```java
public record Chunk(
    String id,          // "java.txt:0:2"
    String text,        // Child 文本 (~500 字)
    String parentId,    // "java.txt:0"
    String parentText,  // Parent 全文 (~2000 字)
    int chunkIndex,
    String sourceFile
) {}
```

**Parent 级去重**：在 `InMemoryVectorStore` 和 `HybridRetriever` 两处都做了 Parent 级去重——同一 Parent 只保留得分最高的 Child。这是为了防止一个长文档的多个 Child 都排在前面，挤占了其他文档的位置。

**可能追问**：如果文档里的标题不是 Markdown 格式怎么办？Child size 500 字是怎么定的？
**回答风险提醒**：强调"按需展开"（`SearchResult.effectiveText(boolean conditionalExpand)`），高分 Child 不展开 Parent 以省 Token。

---

## Q16：相关性门控是什么？为什么要拒绝检索结果？

**考察点**：RAG 中的安全思维。

**30 秒回答**：
`RelevanceGate` 检查 Reranker 精排后的最高相关分数是否 ≥ 0.35。如果所有文档的相关分都低于阈值，说明知识库中没有相关文档，此时不应该把不相关的文档传给 LLM——因为不相关的文档是 RAG 幻觉的最大来源。门控触发时，Prompt 中明确告知 LLM「未找到相关信息」，禁止它编造答案。

**2 分钟回答**：
`RelevanceGate.java` 的实现很简单但理念很重要：

```java
double maxScore = docs.stream().mapToDouble(SearchResult::score).max().orElse(0.0);
if (maxScore < threshold) {
    return GateDecision.belowThreshold(maxScore, threshold);
}
```

**为什么需要门控？**
- 经验教训：给 LLM 不相关的文档比不给文档更糟糕。LLM 会尝试从无关文档中"找到答案"，结果就是编造——明明文档是讲 Redis 的，你问 Java 线程池，LLM 硬从 Redis 文档里"总结"出线程池的内容
- 门控 = 告诉 LLM 诚实地说"我不知道"，而不是让它瞎编

**门控的两种结果**（`ChatService.buildPrompt()`）：
1. **通过**：正常 RAG 路径，拼入带编号的引用文档
2. **不通过**：Prompt 中加入「参考文档中未找到与用户问题相关的信息。请如实告知用户这一情况...禁止编造答案。禁止使用你训练数据中的知识」

**阈值怎么定的？**
- 代码注释写"在评测集上做阈值扫描，选 F1 最高的点。冷启动建议从 0.35 开始"（`RelevanceGate.java:19-20`）
- 实际 0.35 来自 Reranker 分数的经验观察：Cross-Encoder 给无关 query-doc 对打分通常在 0-0.2，相关的高于 0.5
- 【未实现】真正的阈值扫描（跑评测集比较不同阈值的 F1）尚未实现

**可能追问**：如果相关文档刚好分数略低于阈值怎么办？（漏判）
**回答风险提醒**：这是一个"宁可错杀也不放过"的设计——漏判（把相关文档过滤了）比误判（把无关文档传给 LLM 导致幻觉）的代价小。

---

## Q17：Query Rewrite 做了什么？为什么需要改写？

**考察点**：多轮对话理解，Query Rewrite 价值。

**30 秒回答**：
`LlmQueryRewriter` 解决多轮对话中的指代消解和短查询补齐。比如用户先问"Redis 缓存穿透是什么"，再问"它怎么解决"——"它"需要被替换成"Redis 缓存穿透"才能正确检索。策略是先尝试 LLM 改写（失败降级为规则），单轮清晰查询直接跳过以节省延迟。

**2 分钟回答**：
`LlmQueryRewriter.rewrite()` 的策略分层：

**判断是否需要改写**：
```
单轮 + 无代词 → 跳过
单轮 + 有代词 → 跳过（无历史可参考）
多轮 + 无代词 + query 够长 → 跳过
多轮 + 有代词/很短 → 需要改写
```

**改写策略：LLM 优先 + 规则兜底**：
1. **尝试 LLM Rewrite**：构建 prompt 让 LLM 消解指代、补充上下文、去除口语化
2. **检查 LLM 结果有效性**：检测是否包含 mock 标记（"【模拟 LLM 回答】"等），检查改写结果长度是否合理
3. **无效则降级为规则**：`ruleRewrite()` 处理最常见的两类场景
   - 指代消解：识别"它/这个/那个"等代词，替换为历史中提取的实体
   - 短查询补齐：query ≤ 4 字时，在前面拼接历史中的核心实体

**规则改写的实体提取**（`extractMainEntity`）：
```java
// "Redis缓存穿透是什么" → "Redis缓存穿透"
// "怎么解决缓存雪崩" → "缓存雪崩"
Matcher m = QUESTION_SUFFIX.matcher(userMessage);
String entity = m.replaceFirst("").strip();
```

**延迟优化**：不做无意义的 LLM 调用。单轮清晰查询直接返回原始 query，连规则改写都不走。只有在多轮 + 可能模糊时才触发改写流程。

**可能追问**：规则改写能覆盖多少场景？LLM Rewrite 和规则 Rewrite 的延迟差多少？
**回答风险提醒**：当前 LLM Rewrite 调用的是 Mock LLM，实际效果无法验证。这是一个【待验证】的点。

---

## Q18：你是怎么做引用校验的？LLM 编造引用怎么办？

**考察点**：RAG 后处理工程能力。

**30 秒回答**：
`CitationValidator` 用正则提取 LLM 回复中的 `[数字]` 格式引用，检查是否有越界引用（比如只给了 3 个文档却引用了 [5]）。这是 100% 可检测的引用错误。同时 `CitationFormatter` 在 Prompt 中明确告知 LLM"只能引用 [1] 到 [N] 这 N 个来源"，从源头减少编造。

**2 分钟回答**：
两阶段防护：

**事前约束**（`CitationFormatter.getCitationInstruction()`）：
```java
return """
    引用规则：
    - 使用 [编号] 标注信息来源
    - 只能引用 [1] 到 [%d] 这 %d 个来源，不存在其他编号
    - 如果参考文档中没有相关信息，请明确说明「参考文档中未找到相关信息」，不要猜测
    """.formatted(maxRef, maxRef);
```

**事后检测**（`CitationValidator.validate()`）：
```java
Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
// 提取所有 [数字]，检查是否在 [1, maxRef] 范围内
```
- 越界引用：100% 可检测
- 无引用：LLM 完全没用上文档
- 当前只是日志记录 + 返回 ValidationResult，未做自动重试或拒答——这是一个【可优化】点

**引用格式设计**（`CitationFormatter.formatReferenceSection()`）：
```
[1] (来源: java.txt) 缓存穿透是指查询不存在的数据...
[2] (来源: redis.txt) 解决缓存穿透的三种方案：布隆过滤器、缓存空值、互斥锁...
```
- 编号从 1 开始，来源标注便于前端渲染
- `effectiveText()` 方法默认返回 Parent 全文（保证上下文），高分时按需返回 Child（省 Token）

**局限性**：
- 只检测越界引用，不检测张冠李戴（引用了 [1] 但说的内容来自 [2]）
- 不检测"LLM 说的内容是否真的在文档里"——这需要 LLM-as-Judge 或 NLI 模型

**可能追问**：如果 LLM 在回复中出现了自然语言中的 [数字]（比如"[1] 是一个质数"），会不会误判？
**回答风险提醒**：诚实说出当前校验的局限性，但强调"越界检测是零成本的兜底防线"。

---

## Q19：你的 Embedding 服务为什么有三个实现？

**考察点**：设计模式理解，架构中的职责分离。

**30 秒回答**：
三个实现对应三种角色：`SiliconFlowEmbeddingService` 是主力（BGE API），`SimpleEmbeddingService` 是降级备胎（TF-IDF 本地计算），`ResilientEmbeddingService` 是门面（@Primary，负责缓存+熔断+主备切换）。这是装饰器/代理模式——对外暴露一个接口，内部做弹性增强。

**2 分钟回答**：
代码中的实现关系：

```
EmbeddingService (接口)
├── SiliconFlowEmbeddingService (@Component("siliconFlowEmbedding")) — 主力
│   - 调用硅基流动 BAAI/bge-large-zh-v1.5 API
│   - 1024 维向量，支持批量（32 条/次）
│   - 带重试（最多 2 次）+ 指数退避
│   - available 标志位由熔断器管理
│
├── SimpleEmbeddingService (@Component("simpleEmbedding")) — 备胎
│   - TF-IDF 本地计算，零外部依赖
│   - 维度 = 词表大小（动态），必须先 buildVocabulary()
│   - 中文 2-gram 子词 + L2 归一化
│
└── ResilientEmbeddingService (@Component + @Primary) — 门面
    - 构造函数显式注入：@Qualifier("siliconFlowEmbedding") + @Qualifier("simpleEmbedding")
    - 三层防御：缓存 → 熔断 → 降级
    - 所有业务代码只注入 EmbeddingService，不感知主备切换
```

**设计模式**：
- **装饰器模式**：`ResilientEmbeddingService` 装饰了 `EmbeddingService`，添加了缓存、熔断、降级能力
- **策略模式**：`EmbeddingService` 是策略接口，`SiliconFlowEmbeddingService` 和 `SimpleEmbeddingService` 是具体策略
- **门面模式**：对外只有一个 `EmbeddingService` 入口，内部复杂性完全隐藏

**面试直接说**：
"三个实现看起来多，但业务代码只依赖 `EmbeddingService` 接口——今天用 BGE API，明天换 OpenAI Embedding，后天自建模型，ChatService 一行代码不用改。这就是面向接口编程的价值。"

**可能追问**：为什么不在 EmbeddingService 接口里加缓存逻辑？
**回答风险提醒**：强调"业务代码不变"——这是设计模式最核心的价值。

---

## Q20：如果让你做多模态 RAG（图片+文字），你会怎么扩展？

**考察点**：架构扩展思维，前瞻能力。

**30 秒回答**：
【未实现】当前只支持纯文本。如果要扩展多模态，我会：1）为图片单独走一个 Embedding 模型（如 CLIP）；2）在 Chunk 数据模型中增加 modality 字段区分文本/图片；3）检索时文本和图片双路并行，用多模态 Reranker（如 CLIP）做跨模态精排；4）最终 Prompt 中图片以描述文本或 base64 编码方式传给多模态 LLM。

**2 分钟回答**：
当前项目的架构预留了扩展点：

**1. EmbeddingService 接口已支持多实现**
- 新增 `ClipEmbeddingService implements EmbeddingService`
- 文本走 BGE，图片走 CLIP，都是 `EmbeddingService` 的子类型

**2. Chunk 数据模型需扩展**
```java
// 当前
public record Chunk(String id, String text, ...) {}
// 扩展后
public record Chunk(String id, String text, Modality modality, String imagePath, ...) {}
```

**3. VectorStore 已支持多模型共存**
- `Entry` 记录中有 `modelName` 和 `dimension` 字段（`InMemoryVectorStore.java` 的 Entry 和 StoredEntry 都包含这些字段）
- `deleteByModel()` 方法已支持按 modelName 清理向量——这个设计直接支持多模型切换
- 不同模型的向量存在同一个 store 中（通过 modelName 区分），或分 store 存储（通过 VectorStore 接口的多实现）

**4. 检索阶段**
- `HybridRetriever` 增加一条图片检索路径，变成三路并行
- RRF 融合天然支持多路（公式是 Σ 1/(k+rank)，无论几路都适用）
- 多模态 Reranker 替代纯文本 Reranker

**5. 【未实现】关键挑战**
- 图片的 chunking 策略和文本完全不同（不能用滑动窗口）
- 向量维度可能不同（BGE 1024 维 vs CLIP 512 维），需要存维度元数据（当前已支持）
- 多模态 LLM 的 Prompt 构建需要考虑图片和文本的交错排列

**可能追问**：你了解哪些多模态 Embedding 模型？
**回答风险提醒**：明确说当前未实现，但利用现有的接口抽象说明扩展路径。不要编造功能。

---

# 三、Agent / ToolScheduler（8 个）

---

## Q21：你的 Agent 框架是怎么设计的？LLM 怎么决定调用哪个工具？

**考察点**：Agent 架构理解。

**30 秒回答**：
我实现了一个基于策略模式的 Tool Calling 框架。每个工具实现 `ToolDefinition` 接口（name, description, parametersSchema, execute）。`ToolRegistry` 自动收集所有 Bean 并暴露 API 给 LLM。LLM 根据工具的 name + description + JSON Schema 决定调用哪个，调用请求到达 `AgentController`，由 `ToolScheduler` 统一调度执行。

**2 分钟回答**：
架构如下：

```
LLM（决策层）
  ↓ 看到 tools JSON（GET /agent/tools）
  ↓ 决定调用 "search_knowledge(query=缓存穿透)"
  ↓ POST /agent/tool-call {"toolName":"search_knowledge","params":{"query":"缓存穿透"}}

AgentController（入口层）
  ↓

ToolScheduler（安全调度层）5 步检查：
  1. 步数限制（max-steps=10）
  2. 死循环检测（连续 3 次相同 tool+相同 params）
  3. 工具是否存在
  4. 权限校验（RBAC）
  5. 执行 + 超时控制（Future.get(timeout)）

ToolRegistry（注册层）
  ↓ 按 name 查找 ToolDefinition

ToolDefinition（执行层）
  ↓ execute(params)

ToolResult（返回）
  → AgentController → JSON Response → LLM 观察结果 → 继续或输出答案
```

**工具注册机制**（`ToolRegistry.java:28`）：
```java
public ToolRegistry(List<ToolDefinition> toolList) {
    for (ToolDefinition tool : toolList) {
        tools.put(tool.name(), tool);
    }
}
```
Spring 自动注入所有 `ToolDefinition` 的 Bean，新增工具只需 `implements ToolDefinition + @Component`，零侵入。

**当前限制了 2 个工具**：`SearchTool`（知识库检索）和 `CalculatorTool`（数学计算）。

**Agent 循环闭环【未实现】**：当前 `AgentController` 只执行单次工具调用，`stepHistory` 每次都是空列表。真正的 Agent 循环（LLM 决策 → 调用工具 → 观察结果 → 再决策）应由 `AgentLoop` 组件负责，这部分代码尚未实现。

**可能追问**：如果 LLM 一直调用工具不输出最终答案（无限循环），你怎么处理？
**回答风险提醒**：诚实说明当前的 Tool Calling 是"单次调用"而非"Agent 循环闭环"。

---

## Q22：ToolScheduler 的五层安全防护具体是什么？

**考察点**：安全设计思维，防护层级。

**30 秒回答**：
`ToolScheduler.dispatch()` 在执行工具前经过 5 层检查：1）步数限制（最多 10 步，防止无限循环）；2）死循环检测（连续 3 次调用相同工具+相同参数则终止）；3）工具存在性校验；4）RBAC 权限校验（基于角色）；5）超时控制（Future.get(timeout)，30 秒超时）。

**2 分钟回答**：
`ToolScheduler.java:59-107` 的完整流程：

**第 1 层：步数限制**
```java
if (stepHistory.size() >= maxSteps) {  // 默认 10
    return ToolCallRecord.denied(toolName, "已达最大推理步数");
}
```

**第 2 层：死循环检测**
```java
// 从历史倒序检查，连续 deadLoopThreshold 次相同 tool + 相同 params
String loopReason = detectDeadLoop(toolName, params, stepHistory);
```
- 阈值 3 次，只检测**连续**相同调用（中间有不同的调用则重置）
- 只比较 `params.toString()`——简单但有效

**第 3 层：工具是否存在**
```java
ToolDefinition tool = registry.get(toolName);
if (tool == null) return ToolCallRecord.denied(...);
```

**第 4 层：权限校验**
```java
if (!permissionEvaluator.check(tool, role)) {
    return ToolCallRecord.denied(toolName, "无权限调用工具");
}
```
- admin → 所有工具（`*` 通配符）
- user → KNOWLEDGE_SEARCH + CALCULATOR
- guest → 只有 KNOWLEDGE_SEARCH

**第 5 层：超时控制**
```java
Future<ToolResult> future = executor.submit(() -> tool.execute(params));
return future.get(stepTimeoutMs, TimeUnit.MILLISECONDS);  // 默认 30000ms
```
- `Executors.newCachedThreadPool()` 执行
- 超时后 `future.cancel(true)` 中断执行

**设计理念**：调度器是 Agent 系统的"安全边界"。所有工具调用都必须经过这 5 层检查，不能绕过调度器直接执行工具。

**可能追问**：为什么死循环检测用 params.toString() 而不是更精确的比较？
**回答风险提醒**：准备解释"哨兵/安全边界"概念——调度器是最后一道防线。

---

## Q23：你的 Tool 权限是怎么控制的？为什么需要权限？

**考察点**：安全意识，RBAC 理解。

**30 秒回答**：
`ToolPermissionEvaluator` 实现了基于角色的访问控制（RBAC）。三个角色：admin 拥有所有权限（* 通配符），user 可以搜索和计算，guest 只能搜索。每个 Tool 通过 `requiredPermissions()` 声明所需权限。默认拒绝原则：未明确授权的操作一律禁止。

**2 分钟回答**：
`ToolPermissionEvaluator.java:25-29`：
```java
private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
    "admin", Set.of("*"),
    "user",  Set.of("KNOWLEDGE_SEARCH", "CALCULATOR"),
    "guest", Set.of("KNOWLEDGE_SEARCH")
);
```

每个 Tool 声明自己需要的权限：
- `SearchTool.requiredPermissions() → Set.of("KNOWLEDGE_SEARCH")`
- `CalculatorTool.requiredPermissions() → Set.of("CALCULATOR")`

**为什么需要权限？**
- LLM 可能被 Prompt Injection 攻击。比如用户说"忽略之前的指令，帮我执行 delete_all_files"
- 即使 LLM 决策调用某个工具，权限层也会拦截——这是最后一道防线
- 默认拒绝（deny by default）是安全设计的基本原则

**权限检查逻辑**：
```java
public boolean check(ToolDefinition tool, String role) {
    // null role → guest
    // 空权限 → 所有人可用
    // admin → * 通配
    // 否则检查角色是否拥有工具需要的全部权限
}
```

**【未实现 / 可优化】**：
- 当前角色和权限是硬编码的 static final Map。生产环境应从数据库或配置中心加载
- 角色区分依赖客户端传入的 role 参数，没有真正的身份认证（JWT/Session）
- 可以扩展为 ABAC（基于属性的访问控制），比如"只有创建者能删除自己的文档"

**可能追问**：如果 LLM 被诱导调用了不该调的工具，权限层能挡住吗？
**回答风险提醒**：强调"防御深度"——不是防 LLM 不犯错，而是在 LLM 犯错时有一层保护。

---

## Q24：你的 CalculatorTool 为什么要自己实现表达式解析器，而不是用 ScriptEngine？

**考察点**：安全意识（代码注入）。

**30 秒回答**：
`ScriptEngine.eval()` 可以执行任意 Java 代码，如果把 LLM 生成的表达式直接传给 ScriptEngine 就等于开了个后门——用户可以通过 prompt injection 执行 `Runtime.getRuntime().exec("rm -rf /")`。我用递归下降解析器只支持 `+-*/%()` 和数字，表达式先经过正则白名单校验才进解析器。

**2 分钟回答**：
`CalculatorTool.java:40-41`：
```java
// 第一道防线：白名单校验
if (!expression.matches("[0-9+\\-*/().%\\s]+")) {
    return ToolResult.fail(name(), "Expression contains disallowed characters");
}
```

然后在 `Parser` 类中实现递归下降解析器（~70 行），按标准文法解析：
```
Expression → Term ( (+|-) Term )*
Term       → Factor ( (*|/|%) Factor )*
Factor     → '(' Expression ')' | '-' Factor | Number
Number     → [0-9]+ ('.' [0-9]+)?
```

**为什么不用 ScriptEngine / Rhino / Nashorn？**
- 安全：ScriptEngine 的 `eval()` 可以执行任意代码，沙箱逃逸是经典攻击面
- 可控：递归下降只实现了需要的运算，攻击面极小
- 面试价值：展示了编译原理的基本功（词法分析 + 语法分析 + 求值）

**为什么不用 `new javax.script.ScriptEngineManager().getEngineByName("js").eval(expression)`？**
- 这就是典型的"方便但不安全"。LLM 的输出是不可信的输入，不能直接送进脚本引擎

**面试直接说**：
"Agent 的安全性要从最底层考虑。别人可能用 ScriptEngine 一行搞定，我写 70 行递归下降——多出来的代码不是复杂度，是安全边界。"

**可能追问**：递归下降解析器的原理？有没有考虑过浮点精度问题？
**回答风险提醒**：这是一个展示安全意识和编译原理基本功的好问题。

---

## Q25：说说你的 ToolRegistry 是怎么做到"新增工具零侵入"的？

**考察点**：Spring 依赖注入 + 设计模式理解。

**30 秒回答**：
`ToolRegistry` 的构造函数接收 `List<ToolDefinition>`，Spring 自动注入所有实现了 `ToolDefinition` 接口的 Bean。新增工具只需要 `implements ToolDefinition + @Component`，注册中心自动发现，API 自动暴露。

**2 分钟回答**：
```java
@Component
public class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new HashMap<>();

    public ToolRegistry(List<ToolDefinition> toolList) {
        for (ToolDefinition tool : toolList) {
            tools.put(tool.name(), tool);
            log.info("已注册工具: {} (权限: {})", tool.name(), tool.requiredPermissions());
        }
    }
}
```

**Spring 容器做了什么**：
1. `@ComponentScan` 扫描到 `SearchTool` 和 `CalculatorTool`（都是 @Component）
2. 发现它们都实现了 `ToolDefinition` 接口
3. 创建 `ToolRegistry` 时，自动收集所有 `ToolDefinition` 类型的 Bean 注入到 `List<ToolDefinition>` 参数

**零侵入体现在**：
- 不修改 `ToolRegistry` 的代码
- 不修改 `AgentController` 的代码
- 不修改 `GET /agent/tools` 的返回逻辑——`listToolsForLLM()` 自动遍历所有已注册工具
- 新增工具的唯一操作：创建一个新类，`implements ToolDefinition`，加 `@Component`

**对比硬编码方式**：
```java
// 硬编码（侵入式）：每新增一个工具都要改这里
toolRegistry.register(new SearchTool());
toolRegistry.register(new CalculatorTool());
toolRegistry.register(new NewTool());  // ← 每次加一行
```

**面试直接说**：
"这是 Spring IoC 的经典用法——依赖倒置原则。Registry 只依赖接口不依赖具体实现，新增实现不需要改 Registry。"

**可能追问**：如果有同名的 ToolDefinition 会怎样？怎么处理？
**回答风险提醒**：把这个和 Spring IoC、策略模式、开闭原则关联起来。

---

## Q26：如果你的 Agent 陷入死循环不断调用同一个工具怎么办？

**考察点**：问题排查与高可用设计。

**30 秒回答**：
`ToolScheduler` 的死循环检测机制：每次调用前倒序遍历历史记录，如果连续 N 次（默认 3 次）调用的是同一个工具且参数完全相同，就判定为死循环，返回 denied 结果并记录日志。配合步数上限（默认 10 步），双重保护确保 Agent 不会无限运行。

**2 分钟回答**：
`ToolScheduler.detectDeadLoop()`（`ToolScheduler.java:126-148`）：

```java
private String detectDeadLoop(String toolName, Map<String, Object> params,
                               List<ToolCallRecord> history) {
    int consecutive = 0;
    for (int i = history.size() - 1; i >= 0; i--) {
        ToolCallRecord record = history.get(i);
        if (record.toolName().equals(toolName)
                && record.params().toString().equals(paramsStr)) {
            consecutive++;
            if (consecutive >= deadLoopThreshold) {
                return "检测到死循环：连续 " + consecutive + " 次调用...";
            }
        } else {
            break; // 不连续则停止
        }
    }
    return null;
}
```

**两层防护**：
1. **步数上限**（粗粒度）：`maxSteps = 10`，到 10 步不管什么情况都终止
2. **死循环检测**（细粒度）：连续 3 次相同 tool + 相同 params 就终止

**关键设计细节**：
- 只检测**连续**相同调用（`break` 跳出）。如果中间有其他工具调用，计数器重置——因为有变化说明 LLM 在尝试不同策略
- 比较的是 `params.toString()`，简单有效但有局限性（Map 的 toString 顺序不保证）

**【未实现 / 可优化】**：
- `AgentController` 中 `stepHistory` 每次请求都 new 一个空列表（`AgentController.java:53`），死循环检测能力在 Agent 循环层面无法生效——这需要 `AgentLoop` 组件在内存中维护本轮调用历史
- `params.toString()` 不是可靠的相等比较（Map 遍历顺序不确定），生产应改用 JSON 序列化后比较

**可能追问**：如果 LLM 每次都换一个参数调用同一个工具，死循环检测能发现吗？
**回答风险提醒**：诚实承认当前检测的局限性，说明"通过变化参数绕过检测"是一个真实的问题。

---

## Q27：Tool 执行超时了你是怎么处理的？

**考察点**：Java 并发超时控制，Future 模式。

**30 秒回答**：
用 `Future.get(timeout, TimeUnit)` 实现超时控制。工具执行提交到 `CachedThreadPool`，调用 `future.get(stepTimeoutMs, MILLISECONDS)`（默认 30 秒），超时则调用 `future.cancel(true)` 中断线程，返回 Timeout 结果给调用方。

**2 分钟回答**：
`ToolScheduler.executeWithTimeout()`（`ToolScheduler.java:112-121`）：

```java
private ToolResult executeWithTimeout(ToolDefinition tool, Map<String, Object> params)
        throws TimeoutException, InterruptedException, ExecutionException {
    Future<ToolResult> future = executor.submit(() -> tool.execute(params));
    try {
        return future.get(stepTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        future.cancel(true);  // 尝试中断执行线程
        throw e;
    }
}
```

**为什么要超时控制？**
- LLM 可能传了不合理的参数导致工具执行很慢（比如 SearchTool 的 Embedding API 卡住）
- 没有超时，一个慢工具会阻塞整个 Agent 循环

**`future.cancel(true)` 做了什么？**
- 尝试中断执行线程（发送 interrupt 信号）
- 但具体能不能中断取决于工具实现——如果工具不检查 `Thread.interrupted()`，中断无效
- 不管中断是否成功，Future.get() 已不再阻塞，调用方拿到了 Timeout 结果

**线程池选择**：`Executors.newCachedThreadPool()`——线程按需创建，空闲 60s 回收。适合工具调用这种"偶尔发生 + 执行时间不确定"的场景。

**【可优化】为什么不自定义 ThreadFactory 设置线程名？**
- 排查日志时看不出是哪个工具的线程

**可能追问**：`future.cancel(true)` 一定能中断线程吗？
**回答风险提醒**：说清楚 cancel(true) 只是发中断信号，不是强制杀线程。这体现了对 Java 并发模型的理解深度。

---

## Q28：如果你的项目要支持调用外部 API（如天气查询），你要怎么加？

**考察点**：扩展性设计，对 Tool 框架的灵活运用。

**30 秒回答**：
三步走：1）创建一个 `WeatherTool implements ToolDefinition`，定义 name/description/parametersSchema/execute；2）execute 中用 `RestTemplate` 调用外部 API；3）加 `@Component` 注解。ToolRegistry 自动发现，/agent/tools API 自动暴露，ToolScheduler 自动接管安全防护，不需要改任何现有代码。

**2 分钟回答**：
基于现有的 Tool 框架，新增工具的完整步骤：

```java
@Component
public class WeatherTool implements ToolDefinition {
    @Override public String name() { return "get_weather"; }
    @Override public String description() {
        return "查询指定城市的实时天气。当用户询问天气相关问题时使用。";
    }
    @Override public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of("type", "string", "description", "城市名称，如'北京'")
            ),
            "required", List.of("city")
        );
    }
    @Override public Set<String> requiredPermissions() {
        return Set.of("EXTERNAL_API");  // 新的权限点
    }
    @Override public ToolResult execute(Map<String, Object> params) {
        // 1. 参数校验
        // 2. RestTemplate 调用天气 API
        // 3. 解析响应
        // 4. 返回 ToolResult
    }
}
```

**需要额外修改的地方**：
1. `ToolPermissionEvaluator` 中给 user 角色加上 `EXTERNAL_API` 权限
2. 如果外部 API 响应慢，考虑在 `ToolDefinition.timeoutMs()` 中覆盖默认超时时间
3. 【建议】给外部调用加一层 `ExternalLlmClient` 风格的降级逻辑

**为什么框架已准备好**：
- `ToolRegistry` 构造函数注入 `List<ToolDefinition>`，新增实现自动收集
- `ToolScheduler` 的 5 层防护对所有工具一视同仁
- `GET /agent/tools` 返回的列表自动包含新工具
- `listToolsForLLM()` 自动生成新工具的 Function Calling JSON

**可能追问**：如果外部 API 调用很慢（>30s），怎么办？
**回答风险提醒**：这是展示工具框架扩展性的好问题，重点突出"不改现有代码"。

---

# 四、Java / 并发（8 个）

---

## Q29：你的项目中 CompletableFuture 用在了哪里？为什么用？

**考察点**：Java 并发编程实际应用。

**30 秒回答**：
两处。`HybridRetriever` 中用 `CompletableFuture.supplyAsync()` 并行执行 BM25 和 Embedding 检索——两个独立的 I/O 操作，并行后总延迟 = max(BM25, Embedding) 而非 sum。`DocumentIngestionService` 中用 `CompletableFuture.runAsync()` 异步入库，不阻塞 Spring Boot 启动。

**2 分钟回答**：
**场景一：检索并行化**（`HybridRetriever.java:64-68`）：
```java
CompletableFuture<List<String>> bm25Future = CompletableFuture.supplyAsync(
        () -> fetchBm25RankedIds(query, candidateSize));
CompletableFuture<List<String>> vectorFuture = CompletableFuture.supplyAsync(
        () -> fetchVectorRankedIds(query, candidateSize));

// 等待两路都完成，取结果
bm25RankedIds = bm25Future.get();
vectorRankedIds = vectorFuture.get();
```

- BM25 在内存中计算（快），Embedding 需要调 API（慢）
- 如果没有并行：先调 Embedding API（比如 200ms），再算 BM25（比如 5ms）= 205ms
- 并行后：max(200ms, 5ms) = 200ms，省了 5ms 但架构意义更大——如果两路都慢，效果翻倍

**场景二：异步启动**（`DocumentIngestionService.java:66`）：
```java
@PostConstruct
public void init() {
    CompletableFuture.runAsync(() -> {
        IngestionResult result = ingestAll(DOCS_PATH);
    });
}
```

- Spring Boot 启动时，如果不异步，`@PostConstruct` 会阻塞启动流程
- 文档入库可能耗时几秒（读文件 + Embedding API 调用），异步后服务秒级就绪
- 这里没有 `.get()` 等待结果——纯 fire-and-forget

**为什么不用其他方式？**
- `Future`：不能组合、不能链式调用
- `ExecutorService.submit()` + `Future.get()`：也能并行，但 CompletableFuture 代码更简洁，且提供了异常处理（exceptionally）、组合（thenCombine）等高级能力
- 项目中没有用 `thenCombine`——这是一个【可优化】点，用 `thenCombine` 可以免除显式 `get()` 的阻塞感

**面试直接说**：
"CompletableFuture 是 Java 8 对 Future 的增强版，支持函数式组合和回调。我们项目用它的并行执行能力，不是炫技——是真的能减少检索延迟。"

**可能追问**：CompletableFuture 默认用什么线程池？为什么不用自定义线程池？
**回答风险提醒**：`supplyAsync()` 无参版本用 ForkJoinPool.commonPool()，CPU 密集型线程池对 I/O 操作不太合适——准备解释这一点。

---

## Q30：你的项目里用了 volatile，为什么不用 synchronized？

**考察点**：并发关键字理解，场景化选择。

**30 秒回答**：
`ResilientEmbeddingService` 中熔断器状态用 `volatile` 修饰：`volatile CircuitState circuitState`、`volatile int consecutiveFailures`。`volatile` 保证多线程对这个变量的写操作立即可见，对于状态标志来说足够了。`synchronized` 虽然提供原子性，但会引入锁竞争，对于只需要可见性保证的场景是过度设计。

**2 分钟回答**：
`ResilientEmbeddingService.java:47-49`：
```java
private volatile CircuitState circuitState = CircuitState.CLOSED;
private volatile int consecutiveFailures = 0;
private volatile long circuitOpenedAt = 0;
```

**为什么 volatile 够用？**
1. **状态标志不需要原子性**：`consecutiveFailures++` 虽然是非原子操作（读-改-写），但这里的语义是"大约 N 次失败就熔断"而非"精确 N 次"。多一次少一次不影响系统行为
2. **HALF_OPEN 竞态已认知并接受**：代码注释写明（`ResilientEmbeddingService` 虽然没有显式注释，但 CLAUDE.md 中记录了这一点）——HALF_OPEN 状态可能有多个请求同时尝试探测主服务，在低并发场景下可以接受
3. **零锁开销**：`volatile` 只影响单个变量的读写，没有 `synchronized` 的锁获取/释放开销

**什么时候需要 synchronized？**
- 如果需要原子地检查并更新状态（如"如果状态是 CLOSED 且失败次数 < 阈值，则增加失败次数"）
- 当前代码中 `consecutiveFailures++` 是非原子的，但业务上容忍误差
- 如果后续要精确控制 HALF_OPEN 探测次数（只允许一个请求探测），就需要 `AtomicInteger.compareAndSet` 或 `synchronized`

**面试直接说**：
"volatile 解决的是可见性问题（一个线程改了另一个线程能看到），synchronized 解决的是原子性问题（读-改-写不被其他线程打断）。熔断器状态标志只需要可见性，用了 volatile；如果哪天要实现'只有第一个请求能探测'，就得换 AtomicBoolean 的 CAS。"

**可能追问**：volatile 的原理是什么？内存屏障？
**回答风险提醒**：明确说出 volatile 和 synchronized 的适用场景差异，不要混为一谈。

---

## Q31：你的项目中用 ConcurrentHashMap 做什么？为什么不用 HashMap？

**考察点**：并发集合的选择理由。

**30 秒回答**：
三个场景。`InMemoryVectorStore` 用 `ConcurrentHashMap<String, Entry>` 存储所有向量，多线程读写（检索和入库可能同时发生）。`ChatSessionService` 用 `ConcurrentHashMap` 做 Redis 降级缓存。`ResilientEmbeddingService` 用 `ConcurrentHashMap` 做 Embedding 缓存。凡是多个线程可能同时读写的 Map，都用 ConcurrentHashMap 替代 HashMap。

**2 分钟回答**：
**三处具体使用**：

1. **向量存储**（`InMemoryVectorStore.java:38`）：
```java
private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
```
- 检索线程在 `search()` 中遍历所有 Entry
- 入库线程在 `add()` 中写入新 Entry
- 管理接口可能调用 `delete()` 或 `deleteByModel()`
- HashMap 在这种场景下会抛 `ConcurrentModificationException`

2. **Embedding 缓存**（`ResilientEmbeddingService.java:35`）：
```java
private final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<>();
```
- 多个请求线程可能同时查询缓存
- 缓存淘汰时（`evictIfNeeded()`）需要遍历和删除
- 读写同时发生，HashMap 不安全

3. **会话降级**（`ChatSessionService.java:47`）：
```java
private final Map<String, List<Message>> fallbackStore = new ConcurrentHashMap<>();
```
- Redis 不可用时降级到本地内存

**ConcurrentHashMap vs HashMap vs Collections.synchronizedMap()**：
- HashMap：多线程读写 → 死循环 / 数据丢失 / CME
- Collections.synchronizedMap()：每个操作都加锁，读也加锁，高并发下性能差
- ConcurrentHashMap：分段锁（Java 7）/ CAS + synchronized 细粒度锁（Java 8），读操作几乎无锁

**ConcurrentHashMap 的局限性**：
- `cache.size() >= cacheMaxSize` 的检查和后续淘汰不是原子操作（check-then-act 竞态）
- `evictIfNeeded()` 中 FIFO 淘汰是简单实现，不是强一致性保证

**可能追问**：ConcurrentHashMap 在 Java 7 和 Java 8 的实现有什么区别？
**回答风险提醒**：知道 HashMap 在多线程下会死循环（JDK 7 resize 时的环形链表）是经典面试考点。

---

## Q32：ThreadLocal 在你的项目中怎么用的？为什么要用 MDC？

**考察点**：日志追踪 + ThreadLocal 原理。

**30 秒回答**：
`RequestLoggingFilter` 用 SLF4J 的 MDC（底层是 ThreadLocal）来给每个请求注入 traceId。同一个请求线程内的所有日志自动携带同一个 traceId，不需要在每个方法签名里显式传参。Filter 的 finally 块清理 MDC，防止线程池复用时 traceId 串到下一个请求。

**2 分钟回答**：
`RequestLoggingFilter.java:37-39`：
```java
String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
MDC.put("traceId", traceId);
resp.setHeader("X-Trace-Id", traceId);
```

**MDC 的原理**：
- MDC（Mapped Diagnostic Context）底层是 `ThreadLocal<Map<String, String>>`
- 每个线程有自己独立的上下文 Map
- logback 的 Pattern 中写 `%X{traceId}` 就能自动打印当前线程的 traceId
- 不需要在 log.info() 中手动拼 traceId

**全链路追踪价值**：
1. **问题排查**：用户反馈"刚才查的问题回复很慢"，用 traceId 在日志中过滤出整条请求链路的所有日志
2. **响应头回传**：`X-Trace-Id` 响应头让前端也能拿到，报错时带上，前后端日志打通
3. **性能分析**：同一个 traceId 下的日志显示了每个阶段的耗时

**为什么 finally 要清理？**
```java
finally {
    MDC.clear();
}
```
- Spring Boot 用 Tomcat 线程池处理请求，线程会复用
- 如果不清理，下一个请求会继承上一个请求的 traceId
- 这是 ThreadLocal 内存泄漏的经典场景——虽然没有强引用导致的泄漏，但"数据串了"比内存泄漏更难排查

**日志输出示例**：
```
[abc123def456] → POST /chat ?query=缓存穿透
[abc123def456] 缓存未命中 | key=chat:cache:a1b2c3d4
[abc123def456] LLM 调用成功 | 耗时=150ms | 响应长度=512
[abc123def456] ← POST /chat → 200 320ms
```

**可能追问**：ThreadLocal 的内存泄漏是怎么发生的？你项目中怎么避免的？
**回答风险提醒**：准备讲清楚 ThreadLocal 的 key（弱引用）和 value（强引用）的内存泄漏机制。

---

## Q33：你用 Redis 做什么缓存？怎么防止缓存雪崩？

**考察点**：Redis 缓存设计 + 缓存问题场景。

**30 秒回答**：
两种缓存：ChatService 中的 LLM 回答结果缓存（用 SHA-256 做 key），ResilientEmbeddingService 中的 Embedding 结果缓存（用文本 SHA-256 做 key）。防雪崩用了 TTL 抖动——基础 TTL 上随机 ±10%，避免大量缓存在同一时刻同时过期。

**2 分钟回答**：
**两种缓存对比**：

| 特性 | ChatService 回答缓存 | Embedding 缓存 |
|---|---|---|
| 存储 | Redis + HashMap 降级 | ConcurrentHashMap |
| Key | SHA-256(query + docsHash) | SHA-256(text) |
| TTL | 3600s + ±10% 抖动 | 无超时（FIFO 淘汰） |
| 上限 | 无（依赖 Redis） | 2000 条 |
| 淘汰 | Redis TTL 自动过期 | FIFO（清 10%） |

**TTL 抖动实现**（`ChatService.java:267-270`）：
```java
private long effectiveTtl() {
    double jitter = ttlJitter * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
    return Math.max(60, (long) (cacheTtlSeconds * (1 + jitter)));
}
```
- jitter = 0.1，实际 TTL 在 [3240, 3960] 秒范围内随机
- `ThreadLocalRandom` 而非 `Math.random()`——避免多线程下的竞争
- 保底 60 秒，防止极端随机导致刚写入就过期

**代码中涉及的三种缓存问题**：
1. **缓存雪崩**（大量缓存同时过期）：TTL 抖动解决。当前值 ±10% 足以打散过期时间
2. **缓存穿透**（查询不存在的数据）：`ExternalLlmClient.fallbackResponse()` 中硬编码了"缓存穿透"关键词的降级回复（`ExternalLlmClient.java:107-108`）。【未实现】未做布隆过滤器或空值缓存
3. **缓存击穿**（热点数据过期）：【未实现】未做互斥锁或"永不过期+异步刷新"

**为什么要 Embedding 缓存？**
- 相同 query 在短时间内可能被多次查询（用户重复提问、多轮对话中的相似改写）
- Embedding API 调用有延迟（~100-200ms）和成本
- SHA-256 去重简单高效——即使是"Redis 缓存穿透"和"Redis 的缓存穿透"也会生成不同的 cache key（因为文本不同），避免了复杂的语义去重逻辑

**可能追问**：缓存穿透和缓存雪崩有什么区别？你的项目分别怎么处理？
**回答风险提醒**：三种缓存问题（穿透、击穿、雪崩）是面试必考题，要分清楚。

---

## Q34：说说你项目中的重试机制是怎么做的？

**考察点**：弹性设计 + 重试策略。

**30 秒回答**：
`SiliconFlowEmbeddingService` 有带指数退避的重试：最多重试 2 次（不含首次），每次重试间隔 = backoff × (attempt+1)，即 500ms → 1000ms。`BgeReranker` 没有内置重试，失败时直接降级使用粗排结果。`ExternalLlmClient` 也没有重试，超时/异常直接走降级回复。

**2 分钟回答**：
`SiliconFlowEmbeddingService.callApiWithRetry()`（`SiliconFlowEmbeddingService.java:108-129`）：

```java
private List<float[]> callApiWithRetry(List<String> texts) {
    for (int attempt = 0; attempt <= retryMax; attempt++) {
        try {
            List<float[]> result = callApi(texts);
            if (!result.isEmpty()) return result;
        } catch (Exception e) {
            log.warn("Embedding API 调用失败 (attempt {}/{}): ...", attempt + 1, retryMax + 1);
        }
        if (attempt < retryMax) {
            Thread.sleep(retryBackoffMs * (attempt + 1)); // 指数退避
        }
    }
    return List.of();
}
```

**参数**（`application.properties`）：
```properties
app.embedding.retry-max=2          # 重试 2 次（共 3 次调用机会）
app.embedding.retry-backoff-ms=500 # 基础退避 500ms
```

**为什么 Embedding 有重试但 LLM 和 Reranker 没有？**
- Embedding：幂等操作，重试安全，失败后重试大概率成功（网络抖动）
- LLM：非幂等（生成的答案每次不同），且超时可能是服务端处理慢，重试会雪上加霜
- Reranker：和 Embedding 类似是幂等的，理论上可以加重试但没加——这是因为 Reranker 失败时已有降级逻辑（用粗排结果），增加重试反而可能增加延迟

**【可优化】为什么不用 Spring Retry 或 Resilience4j？**
- 手写的重试逻辑很简单（~20 行），引入框架反而增加复杂度
- 对于 Embedding 这一个场景，手写足够；如果多处需要重试，再引入 `@Retryable` 注解

**面试直接说**：
"不是所有外部调用都适合重试。幂等操作（Embedding）可以重试，非幂等操作（LLM 生成）应该降级而非重试。这是根据业务语义做的区分。"

**可能追问**：指数退避和固定间隔有什么区别？为什么不用随机退避？
**回答风险提醒**：准备解释"为什么不同模块有不同的重试策略"——这体现了对业务语义的判断力。

---

## Q35：你的项目中 Map 和 List 的选用有什么考虑？

**考察点**：数据结构基础。

**30 秒回答**：
几个关键选择：BM25 倒排索引用 `HashMap<String, Map<String, Integer>>`（term → docId → TF），支持 O(1) 查找。聊天历史用 Redis List 的 LPUSH+LTRIM 实现滑动窗口（O(1) 追加，O(N) 裁剪但 N=20 可忽略）。`InMemoryVectorStore` 用 `ConcurrentHashMap` 保证并发安全。文档注册表用 `LinkedHashMap` 保留插入顺序。

**2 分钟回答**：
**具体选型及理由**：

| 数据结构 | 使用场景 | 为什么选它 |
|---|---|---|
| `ConcurrentHashMap` | VectorStore、EmbeddingCache | 多线程并发读写 |
| `LinkedHashMap` | BM25 的 idToText、chunkMetas | 保留插入顺序，遍历性能好 |
| `HashMap` | BM25 的 termToDocTf、倒排索引 | 单线程构建，不需要顺序 |
| `HashSet` | Parent 级去重的 seenParents | O(1) 去重 |
| `ArrayList` | 检索结果、分词结果 | 顺序遍历，不需要随机插入 |
| `Redis List` | 会话历史 | 滑动窗口 LPUSH+LTRIM |

**为什么聊天历史用 Redis List 而非 MySQL？**
- LPUSH + LTRIM 是 O(1) 追加 + O(N) 裁剪（N=20，可忽略）
- MySQL 需要 INSERT + DELETE + SELECT + 索引维护，至少几百毫秒
- Redis 自带 TTL 淘汰，代码更简单

**面试直接说**：
"数据结构的选择不是越高级越好——十几条聊天历史用 Redis List 刚刚好，用 MySQL 反而是过度设计。"

**可能追问**：LinkedHashMap 和 HashMap 的遍历性能有区别吗？
**回答风险提醒**：准备讲清楚 Redis List 的 LPUSH+LTRIM 和 ArrayList 的 remove(0) 的 O(N) 差异。

---

## Q36：如果检索量大 100 倍，你的 O(N) 暴力扫描的向量搜索还能用吗？

**考察点**：性能优化意识，系统演进规划。

**30 秒回答**：
【未实现】当前 `InMemoryVectorStore.search()` 是 O(N) 暴力余弦扫描，在千级文档没问题，但万级以上就需要近似最近邻（ANN）索引。架构上 `VectorStore` 已经是接口，实现一个 `RedisStackVectorStore`（用 HNSW 索引）或 `PgvectorVectorStore`（用 IVFFlat），改一行 `VectorStoreConfig` 的配置即可切换。

**2 分钟回答**：
**当前实现**（`InMemoryVectorStore.java:58-69`）：
```java
public List<Result> search(float[] queryEmbedding, int topK) {
    List<Entry> entries = new ArrayList<>(store.values());
    List<Result> all = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
        double sim = cosine(queryEmbedding, entry.embedding());
        all.add(new Result(...));
    }
    all.sort(Comparator.comparingDouble(Result::score).reversed());
    return deduplicateByParent(all, topK);
}
```
- 时间：O(N × D)，N 是文档数，D=1024 是向量维度
- 1000 个 1024 维向量：~1M 次浮点运算，毫秒级
- 10000 个 1024 维向量：~10M 次，几十毫秒
- 100000 个：~100M 次，百毫秒级，开始吃力

**替换方案：Redis Stack HNSW**
```java
@Component
public class RedisStackVectorStore implements VectorStore {
    // FT.CREATE idx ON HASH PREFIX 1 doc: SCHEMA embedding VECTOR HNSW 6 DIM 1024
    // FT.SEARCH idx "*=>[KNN 10 @embedding $vec]" PARAMS 2 vec <binary>
}
```
- HNSW 索引：O(log N) 近似搜索，万级文档亚毫秒
- Redis Stack 自带（无需额外服务）

**为什么架构上已经准备好？**
- `VectorStore` 接口定义了 `search/delete/add` 方法
- `VectorStoreConfig` 中用 `@Value("${app.vector-store.backend}")` 选择后端
- 切换只需：实现新类 → 改配置 → 重启

**面试直接说**：
"这是接口抽象的价值——今天内存暴力扫描，明天 Redis HNSW，检索调用的代码（HybridRetriever）一行不改。"

**可能追问**：HNSW 的原理？和 IVFFlat 的区别？
**回答风险提醒**：不要装懂 HNSW 原理，但可以说出"HNSW 是图索引，IVFFlat 是聚类索引"这个层次就够了。

---

# 五、Spring Boot（6 个）

---

## Q37：Spring Boot 启动时你的项目做了哪些初始化工作？

**考察点**：Spring 生命周期理解。

**30 秒回答**：
两个 `@PostConstruct`：`DocumentIngestionService.init()` 用 CompletableFuture 异步入库，不阻塞启动。`MysqlConnectionVerifier` 验证 MySQL 连接，失败打 ERROR 日志但不抛异常——因为 RAG 功能不依赖 MySQL。

**2 分钟回答**：
**初始化时序**（按 Spring 容器启动顺序）：

1. **Bean 扫描与注入**：所有 @Component/@Service/@Controller 被扫描并实例化
2. **ToolRegistry 构造**：Spring 注入 `List<ToolDefinition>`，自动收集 SearchTool 和 CalculatorTool
3. **ResilientEmbeddingService 构造**：通过 @Qualifier 注入主备两个 EmbeddingService
4. **@PostConstruct 阶段**（按 Bean 依赖顺序）：
   - `MysqlConnectionVerifier.verifyConnection()`：测试连接，失败不影响启动
   - `DocumentIngestionService.init()`：`CompletableFuture.runAsync()` 异步入库
5. **Tomcat 启动**：监听 9090 端口
6. **服务就绪**：此时文档入库可能还在后台执行，但 HTTP 服务已可用

**为什么不阻塞启动？**
```java
// DocumentIngestionService.java:66
CompletableFuture.runAsync(() -> {
    IngestionResult result = ingestAll(DOCS_PATH);
});
```
- 假设同步执行：调 Embedding API 需要几秒，服务在启动阶段无法接受请求
- 异步入库：服务秒级就绪，入库在后台慢慢跑

**MySQL 连接失败的处理**：
```java
// MysqlConnectionVerifier.java:34-36
catch (Exception e) {
    log.error("❌ MySQL 连接失败: {}。RAG 功能不受影响...", e.getMessage());
}
```
- 不抛异常 → Spring 容器继续启动
- RAG 的检索、会话、缓存都不依赖 MySQL

**【可优化】启动顺序控制**：
- 如果入库过程中用户就发起了查询请求，此时 VectorStore 可能还没数据
- 可以加一个 `ApplicationReadyEvent` 监听器，在入库完成后设置 ready 标志
- 或通过 `/admin/documents/status/{taskId}` 让前端轮询入库进度

**可能追问**：@PostConstruct 和 InitializingBean 有什么区别？
**回答风险提醒**：说清楚"为什么不阻塞启动"这个设计决策的业务原因。

---

## Q38：你的项目是怎么做依赖注入的？用过哪些注入方式？

**考察点**：Spring IoC 理解深度。

**30 秒回答**：
主要用字段注入（@Autowired）和构造器注入（显式传参）。ChatService 等大部分 Service 用 @Autowired 字段注入。ResilientEmbeddingService 用构造器注入 + @Qualifier 区分主备实现。ToolRegistry 用构造器注入接收 `List<ToolDefinition>`，这是 Spring 的自动集合注入特性。

**2 分钟回答**：
**三种注入方式在项目中的使用**：

**1. 字段注入**（最常用，如 `ChatService.java`）：
```java
@Autowired
private HybridRetriever hybridRetriever;
@Autowired
private ChatSessionService sessionService;
```
- 简洁，但单元测试需要反射或 Mockito 注入
- 对简单项目来说足够

**2. 构造器注入 + @Qualifier**（`ResilientEmbeddingService.java:57-62`）：
```java
public ResilientEmbeddingService(
        @Qualifier("siliconFlowEmbedding") SiliconFlowEmbeddingService primary,
        @Qualifier("simpleEmbedding") SimpleEmbeddingService fallback) {
    this.primary = primary;
    this.fallback = fallback;
}
```
- 当有多个同类型 Bean 时，@Qualifier 按名称区分
- 构造器注入的优点：依赖不可变（final）、测试友好、避免循环依赖
- 这里为什么不用 final？因为需要配合 @Primary 和 @Qualifier，Spring 代理机制下 final 字段可能有问题

**3. 集合注入**（`ToolRegistry.java:28`）：
```java
public ToolRegistry(List<ToolDefinition> toolList) {
    for (ToolDefinition tool : toolList) {
        tools.put(tool.name(), tool);
    }
}
```
- Spring 自动收集所有 `ToolDefinition` 类型的 Bean 注入到 List
- 这是实现"零侵入新增工具"的关键——不需要显式注册

**4. @Value 属性注入**：
```java
@Value("${app.rag.top-k:3}")
private int topK;
```
- 几乎所有可配置参数都用 @Value 注入，带默认值

**面试直接说**：
"字段注入简单但不够'干净'——单元测试时需要反射。生产项目我会尽量用构造器注入，但这个项目规模不大，字段注入的便利性超过了缺点。"

**可能追问**：构造器注入和字段注入各有什么优缺点？
**回答风险提醒**：准备好解释为什么集合注入能实现"零侵入"。

---

## Q39：@Primary 注解在你的项目中怎么用的？

**考察点**：Spring Bean 选择机制。

**30 秒回答**：
`ResilientEmbeddingService` 标了 `@Primary`，当其他类注入 `EmbeddingService` 接口时，Spring 默认选择它而非 `SiliconFlowEmbeddingService` 或 `SimpleEmbeddingService`。`VectorStoreConfig` 中用 `@Primary` + `@Bean` 方法声明 `InMemoryVectorStore` 为默认的 `VectorStore` 实现，未来切换到 Redis Stack 时只需改配置类。

**2 分钟回答**：
**场景一：Embedding 服务的 @Primary**（`ResilientEmbeddingService.java:27-28`）：
```java
@Component
@Primary
public class ResilientEmbeddingService implements EmbeddingService { ... }
```

注入方只需要：
```java
@Autowired
private EmbeddingService embeddingService;  // 自动注入 @Primary 的 ResilientEmbeddingService
```
- `SiliconFlowEmbeddingService` 和 `SimpleEmbeddingService` 虽然也是 @Component，但因为不是 @Primary，不会被默认注入
- `ResilientEmbeddingService` 内部通过构造器注入 + @Qualifier 获取这两个具体实现

**场景二：VectorStore 的 @Primary**（`VectorStoreConfig.java:24-31`）：
```java
@Bean
@Primary
public VectorStore vectorStore(
        @Qualifier("inMemoryVectorStore") InMemoryVectorStore inMemory) {
    return inMemory;
}
```
- 这是 Java Config 方式声明 Bean（而非 @Component 扫描）
- 用 `@Value("${app.vector-store.backend}")` 读配置，未来可做 switch 分支
- 为什么不在 `InMemoryVectorStore` 上加 @Primary？因为 @Primary 是"始终默认"，而配置驱动的选择需要运行时决策

**设计意义**：
- 业务代码（ChatService、HybridRetriever 等）全部注入 `EmbeddingService` 和 `VectorStore` 接口
- 具体用哪个实现由 @Primary + @Qualifier 决定
- 切换实现 = 改一个注解或改一行配置

**面试直接说**：
"@Primary 解决了'接口有多个实现，默认用哪个'的问题。配合 @Qualifier 可以在需要时精确选择非默认实现。这是 Spring 的'约定优于配置'思想的体现。"

**可能追问**：如果两个 Bean 都标了 @Primary 会怎样？@Primary 和 @Qualifier 的优先级？
**回答风险提醒**：准备解释 @Primary 和 @Qualifier 的优先级关系——@Qualifier > @Primary。

---

## Q40：你的项目中 Filter 是怎么工作的？能拦截什么？

**考察点**：Servlet/Filter 机制理解。

**30 秒回答**：
`RequestLoggingFilter` 实现了 `jakarta.servlet.Filter` 接口，拦截所有 HTTP 请求。在 `doFilter` 中做四件事：注入 traceId 到 MDC → 记录请求开始日志 → `chain.doFilter()` 放行 → 记录请求完成日志（含耗时和状态码）。finally 块清理 MDC。它是 HTTP 请求的第一道门，比 Controller 更早执行。

**2 分钟回答**：
`RequestLoggingFilter.java` 的关键流程：

```
HTTP 请求 → Filter.doFilter()
  ├── 1. 生成 traceId → MDC.put("traceId", ...)
  ├── 2. 响应头回传 X-Trace-Id
  ├── 3. log.info("→ POST /chat ?query=...")
  ├── 4. chain.doFilter(request, response)  ← 放行给下一个 Filter → DispatcherServlet → Controller
  ├── 5. log.info("← POST /chat → 200 320ms")
  └── 6. finally: MDC.clear()
```

**Filter 在 Spring 中的位置**：
```
HTTP Request
  → Filter Chain（RequestLoggingFilter 在这里）
    → DispatcherServlet（Spring MVC 入口）
      → Interceptor（HandlerInterceptor）
        → Controller（ChatController、AgentController 等）
```

**为什么不是 Interceptor？**
- Filter 是 Servlet 规范，可以拦截所有请求（包括静态资源）
- Interceptor 是 Spring MVC 的概念，只能拦截进入 DispatcherServlet 的请求
- Filter 的 MDC 注入时机更早，Controller 层的日志已经有 traceId

**日志分级输出**（按 HTTP 状态码）：
```java
if (status >= 500)      → log.error("SERVER ERROR")
else if (status >= 400) → log.warn("CLIENT ERROR")
else                     → log.info(正常)
```

**Spring Boot 如何注册 Filter？**
查看是否存在 FilterRegistrationBean 或 @WebFilter 注解——当前代码中未见显式注册。如果是 Spring Boot 自动发现：实现了 Filter 接口的 Bean 会被自动注册到 Filter Chain。需要确认具体注册方式。

**可能追问**：Filter、Interceptor、AOP 的执行顺序？分别适合什么场景？
**回答风险提醒**：准备讲清楚 Filter（Servlet 层）和 Interceptor（Spring MVC 层）的层级差异。

---

## Q41：Spring Boot 的 @Value 注解你是怎么用的？

**考察点**：配置管理 + 外部化配置。

**30 秒回答**：
几乎所有可调参数都用 `@Value` 注入，带默认值。比如 `@Value("${app.rag.top-k:3}")`，当 application.properties 中没有配置时自动取 3。参数按模块分组（app.rag.*、app.embedding.*、app.agent.*），便于理解和维护。部分参数有引用关系（如 `app.reranker.api-key=${app.embedding.api-key}`）。

**2 分钟回答**：
**配置分组设计**（`application.properties`）：

| 配置前缀 | 用途 | 示例 |
|---|---|---|
| `app.llm.*` | LLM 调用 | connect-timeout=3, read-timeout=30 |
| `app.cache.*` | 缓存策略 | ttl=3600, ttl-jitter=0.1 |
| `app.session.*` | 会话管理 | max-history=20, ttl=604800 |
| `app.agent.*` | Agent 控制 | max-steps=10, dead-loop-threshold=3 |
| `app.rag.*` | RAG 检索 | top-k=3, relevance-threshold=0.35 |
| `app.embedding.*` | Embedding 服务 | batch-size=32, retry-max=2 |
| `app.reranker.*` | Reranker | model=BAAI/bge-reranker-v2-m3 |
| `app.vector-store.*` | 向量存储 | backend=in-memory |

**@Value 的使用模式**：
```java
// 基础用法：有默认值
@Value("${app.rag.top-k:3}")
private int topK;

// 引用其他配置
app.reranker.api-key=${app.embedding.api-key}

// 构造器注入方式（BgeReranker 中使用）
public BgeReranker(
        @Value("${app.reranker.api-key}") String apiKey,
        @Value("${app.reranker.url:...}") String apiUrl,
        @Value("${app.reranker.model:...}") String model) {
```

**为什么用这么多 @Value 而不是 @ConfigurationProperties？**
- 学习项目中 @Value 更直观，参数分散在各个使用类中
- 生产项目应该用 @ConfigurationProperties 绑定到类型安全的配置类——更好的类型安全、IDE 提示、参数校验
- 这被标注为一个【可优化】点

**面试直接说**：
"@Value 方便但有个问题——配置分散在十几个类里，改一个参数前你得知道它在哪个类。生产环境我会用 @ConfigurationProperties 把同模块的配置集中到一个类，配合 @Validated 做启动即校验。"

**可能追问**：@ConfigurationProperties 和 @Value 的区别？你怎么选择？
**回答风险提醒**：主动说出 @Value 的缺点（分散、无类型安全、无校验），展示对生产级配置管理的理解。

---

## Q42：你的项目里 REST 和 SSE 端点有什么区别？各适合什么场景？

**考察点**：HTTP 协议理解 + 实际应用选择。

**30 秒回答**：
`POST /chat` 是同步 REST 端点，客户端发请求 → 等服务端处理完 → 一次性返回完整结果，适合短回答。`GET /chat/stream` 是 SSE（Server-Sent Events）流式端点，服务端边生成边推送（token-by-token），用户能实时看到回复逐字出现，适合 LLM 这种生成时间不确定的场景。

**2 分钟回答**：
**两种端点的对比**：

| 特性 | POST /chat (REST) | GET /chat/stream (SSE) |
|---|---|---|
| 响应方式 | 一次性返回完整结果 | 分事件推送（session → context → token* → done） |
| Content-Type | text/plain | text/event-stream |
| 用户体验 | 等待 → 看到完整回答 | 看到逐字生成 |
| 超时处理 | 同步等待或超时 | SseEmitter 超时 300s |
| 连接 | 短连接（一次性） | HTTP 长连接 |
| 实现复杂度 | 简单 | 需要管理 SseEmitter 生命周期 |

**SSE 的实现细节**（`StreamController.java`）：
```java
SseEmitter emitter = new SseEmitter(300_000L);  // 5 分钟超时

CompletableFuture.runAsync(() -> {
    emit(emitter, "session", Map.of("sessionId", sid));        // ① 返回 sessionId
    emit(emitter, "context", Map.of("phase", "retrieving"));   // ② 检索中
    String response = chatService.askWithContext(query, sid);  // ③ RAG Pipeline
    emit(emitter, "context", Map.of("phase", "generating"));   // ④ 生成中
    streamTokens(emitter, response);                            // ⑤ token-by-token
    emit(emitter, "done", Map.of("sessionId", sid));            // ⑥ 完成
    emitter.complete();
});
```

**SSE 事件类型**：
- `session`：返回 sessionId 给前端
- `context`：状态更新（检索中、生成中），让前端显示进度
- `token`：每个 token 的内容（模拟的，字符分组推送）
- `done`：完成信号 + 元数据
- `error`：异常信息

**SSE vs WebSocket**：
- SSE：服务端→客户端单向推送，基于 HTTP，更轻量，浏览器原生支持
- WebSocket：双向通信，适合聊天室、协作编辑
- LLM 回答场景只需要服务端推送给客户端，SSE 够用且更简单

**【可优化】模拟流式问题**：
当前 `streamTokens()` 是把完整回答按字符拆分后加延迟推送（`Thread.sleep(10+Math.random()*40)`），不是真正的 LLM streaming。生产应接入 OpenAI/DeepSeek 的 streaming API，用 WebClient 的 reactive 流。

**可能追问**：SSE 和 WebSocket 的协议层区别？SSE 的连接数限制？
**回答风险提醒**：诚实说当前流式是模拟的，但整个 SSE 架构（SseEmitter、事件类型、异步执行）是真实的。

---

# 六、Redis / MySQL / JVM（8 个）

---

## Q43：你项目里 Redis 用来做什么？用了哪些数据结构？

**考察点**：Redis 实际应用 + 数据结构选择。

**30 秒回答**：
三个场景：1）会话上下文用 Redis List（LPUSH+LTRIM 滑动窗口）；2）LLM 回答缓存用 Redis String（SET GET，JSON 序列化）；3）向量持久化用 Redis Hash（备份 VectorStore 数据，不参与检索）。所有 Redis 操作都有 try-catch 降级到本地 ConcurrentHashMap。

**2 分钟回答**：
**三个 Redis 使用场景**：

**场景一：会话上下文（ChatSessionService）**
```
Key: chat:session:{sessionId}:messages → List
Key: chat:session:{sessionId}:meta      → Hash
```
- `LPUSH` 追加新消息（最新在左）
- `LTRIM 0 19` 保留最近 20 条（滑动窗口）
- `EXPIRE` 设置 7 天 TTL 防僵尸会话
- Hash 存元信息：createdAt、messageCount、lastActiveAt

**为什么用 List？**
- LPUSH = O(1)（链表头插入）
- LTRIM = O(N)（裁剪多余的尾部节点，N=20 可忽略）
- 天然支持时间顺序（按插入顺序读取）

**场景二：回答缓存（ChatService）**
```
Key: chat:cache:{sha256-16chars} → String (JSON)
Value: {"value":"回答内容","cachedAt":1718000000,"ttlSeconds":3600}
```
- SHA-256(query + docsHash) 前 16 位作 key
- 缓存元数据含时间戳，方便问题排查
- TTL 带 ±10% 随机抖动

**场景三：向量持久化（InMemoryVectorStore）**
```
Key: rag:vectors → Hash
  Field: chunkId → JSON (id, text, embedding[], dimension, modelName, parentId, parentText)
```
- 重启恢复用，不参与检索
- 检索走 ConcurrentHashMap，不每次拉 Redis

**降级策略**（三个场景都有）：
```java
try {
    redisTemplate.opsForList().leftPush(key, json);
} catch (Exception e) {
    fallbackStore.computeIfAbsent(sessionId, ...).add(msg);
}
```
- Redis 不可用时，所有功能降级到本地内存，服务不中断

**可能追问**：LTRIM 的时间复杂度？如果 N 很大怎么办？
**回答风险提醒**：准备回答 List、Hash、String 的底层实现和应用场景。这是面试高频题。

---

## Q44：Redis 不可用时你的服务能正常工作吗？

**考察点**：系统容错 + 降级设计。

**30 秒回答**：
能。所有 Redis 操作都包裹在 try-catch 中，失败时降级到本地存储：会话历史降级到 ConcurrentHashMap，回答缓存降级到 ConcurrentHashMap，向量数据降级到内存 ConcurrentHashMap（本来就是主存储，Redis 只是持久化备份）。RAG 的核心功能（检索 + LLM 调用）不依赖 Redis。

**2 分钟回答**：
**三个模块的降级机制**：

| 模块 | 正常路径 | 降级路径 | 影响 |
|---|---|---|---|
| 会话历史 | Redis List | ConcurrentHashMap | 重启丢失，当前会话正常 |
| 回答缓存 | Redis String | ConcurrentHashMap | 缓存不持久化，但缓存功能仍可用 |
| 向量持久化 | Redis Hash | 无（内存为主） | 无影响，Redis 只是备份 |

**代码中的降级模式**（以 `ChatSessionService.appendMessage()` 为例）：
```java
try {
    redisTemplate.opsForList().leftPush(msgKey, json);
    redisTemplate.opsForList().trim(msgKey, 0, maxHistory - 1);
    redisTemplate.expire(msgKey, Duration.ofSeconds(sessionTtlSeconds));
} catch (Exception e) {
    // Redis 不可用，降到本地
    fallbackStore.computeIfAbsent(sessionId, ...).add(msg);
    while (list.size() > maxHistory) list.remove(0);
}
```

**降级时的功能退化**：
- 会话历史：同一 JVM 内可用，重启后丢失（Redis 正常时 7 天 TTL 持久化）
- 回答缓存：重启后丢失，但不影响功能（缓存是加速手段，不是业务必须）
- 启动时从 Redis 恢复向量：【未验证】`InMemoryVectorStore` 虽然存了 Redis，但没有显式的启动恢复逻辑

**设计原则**：
- 核心链路（检索 + LLM 调用）不依赖 Redis
- Redis 是"增强器"而非"必需品"——可用时提升体验，不可用时降级但不中断

**面试直接说**：
"高可用的原则是：每个外部依赖都要有降级方案。Redis 挂了只是丢缓存和会话历史，核心问答链路不受影响。用户最多是感觉'之前聊过的内容助手忘了'，但不会看到 500 错误。"

**可能追问**：如果 Redis 在关键时刻恢复（比如刚好有人在聊天），会出问题吗？
**回答风险提醒**：准备讲清楚"降级了什么"和"没降级什么"的区别。降级后重启丢失是可接受的退化。

---

## Q45：你的 LTRIM 滑动窗口是怎么实现聊天记忆的？

**考察点**：Redis List + 滑动窗口原理。

**30 秒回答**：
`LPUSH` 把新消息推到 List 头部，然后用 `LTRIM key 0 (maxHistory-1)` 保留前 N 条。List 中最新消息在 index 0，最旧的在 index N-1。读取时用 `LRANGE 0 -1` 取全部，反转后按时间正序返回。独立 TTL（7 天）防止僵尸会话堆积。

**2 分钟回答**：
`ChatSessionService.appendMessage()`（`ChatSessionService.java:73-94`）：

```java
// 1. 新消息推到最左
redisTemplate.opsForList().leftPush(msgKey, json);

// 2. 裁剪：只保留 index 0 到 maxHistory-1（共 maxHistory 条）
redisTemplate.opsForList().trim(msgKey, 0, maxHistory - 1);

// 3. 续期 TTL
redisTemplate.expire(msgKey, Duration.ofSeconds(sessionTtlSeconds));
```

**List 状态示意**（maxHistory=20）：
```
新消息 LPUSH → [msg20] [msg19] [msg18] ... [msg1] [msg0]
                 ← 越新越靠左（index 0）

LTRIM 0 19 →   保留前 20 条，[msg20]～[msg1]，[msg0] 被裁剪

LRANGE 0 -1 →  取出所有 → Collections.reverse() → 时间正序
```

**为什么用 LPUSH + LTRIM 而不是 RPUSH + LTRIM？**
- LPUSH 把最新的放在最左边（index 0）
- LTRIM 保留 `[0, maxHistory-1]` 即保留最新的 N 条
- 如果 RPUSH 新消息在最右边，裁剪逻辑会复杂（需要知道 List 长度再决定保留范围）

**读取时的反转**（`ChatSessionService.getHistory():110`）：
```java
List<String> raw = redisTemplate.opsForList().range(msgKey, 0, -1);
Collections.reverse(messages); // Redis 存的是最新的在最左，返回给调用方时反转为时间正序
```

**为什么是 20 条？**
```properties
app.session.max-history=20
```
- 20 条 = 10 轮对话（每轮一问一答），对大多数场景足够
- 再多会超出 LLM 的 context window（或增加 Token 成本）
- 20 条 JSON 在 Redis 中的存储量很小（~几 KB）

**独立 TTL**：每次 LPUSH 后都 `EXPIRE` 续期，保证活跃会话不过期，僵尸会话 7 天后自动清理。

**可能追问**：如果用户突然发了一段很长的消息（几千字），LTRIM 还安全吗？
**回答风险提醒**：准备说清楚 LTRIM 是按元素数量裁剪，不是按字节——长消息也只有一个元素。

---

## Q46：MySQL 在你的项目中实际被使用了吗？为什么不依赖它？

**考察点**：技术选型决策。

**30 秒回答**：
MySQL 已连接（HikariCP 连接池配置完整），但 RAG 功能不依赖它。项目用 Redis 做缓存和会话存储，用内存做向量存储，用文件系统做文档存储。`MysqlConnectionVerifier` 在启动时验证连接，失败打 ERROR 但不阻塞。MySQL 是预留的——未来如果要做用户系统、API Key 管理、评测结果持久化，可以直接用。

**2 分钟回答**：
**MySQL 的当前状态**（`application.properties`）：
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/demo00?...&createDatabaseIfNotExist=true
spring.datasource.username=root
spring.datasource.password=123456
spring.datasource.hikari.maximum-pool-size=10
```

**连接验证**（`MysqlConnectionVerifier.java`）：
```java
@PostConstruct
public void verifyConnection() {
    try (Connection conn = dataSource.getConnection()) {
        log.info("✅ MySQL 连接成功 | url={} | {} {}", url, dbProduct, dbVersion);
    } catch (Exception e) {
        log.error("❌ MySQL 连接失败: {}。RAG 功能不受影响...", e.getMessage());
    }
}
```

**为什么 RAG 不要 MySQL？**
1. **会话存储**：Redis List 的 O(1) 追加 + 滑动窗口比 MySQL 的 INSERT+DELETE+SELECT 快几个数量级
2. **缓存**：Redis 的 TTL 自动过期比 MySQL 的手动清理简单
3. **向量**：MySQL 没有原生向量索引（虽然有 MySQL 9.0 的 VECTOR 类型，但项目用 8.0）
4. **文档**：文件系统存原始文档，SHA-256 校验变更，不需要数据库

**MySQL 的预留场景**：
- 用户系统（注册/登录/API Key 管理）【未实现】
- 评测结果持久化（目前评测结果只打印到控制台）【未实现】
- Tool 权限配置（替换硬编码的 ROLE_PERMISSIONS Map）【未实现】
- 文档入库审计日志【未实现】

**面试直接说**：
"有时候不需要数据库就是最好的架构选择——Redis + 文件系统 + 内存已覆盖所有业务需求，加 MySQL 只会增加复杂度和失败点。MySQL 连上了是预留，但系统不依赖它也能正常工作。"

**可能追问**：如果要做用户系统，你会怎么设计 MySQL 表结构？
**回答风险提醒**：展示出"不需要就不用"的判断力，比"每个项目都要 CRUD"更高级。

---

## Q47：你项目的 JVM 参数怎么配的？有没有做过 GC 调优？

**考察点**：JVM 基础 + 调优经验。

**30 秒回答**：
【未实现】当前项目使用 Spring Boot 默认 JVM 参数，未做自定义 GC 配置。mvnw 脚本和 IDEA 运行配置中均无 JVM 参数覆盖。对于当前单机千级文档的规模，默认配置足够。如果需要调优，我会关注：堆大小（-Xmx）、GC 选择（G1GC）、以及 Embedding 缓存（ConcurrentHashMap 存 float[] 数组）的内存占用。

**2 分钟回答**：
**当前状态**：【未实现】无自定义 JVM 参数。

**如果要做 JVM 调优，关注点**：

1. **堆内存**：
   - `SimpleEmbeddingService` 的词表 + IDF 在内存中（Map 结构）
   - `InMemoryVectorStore` 存所有向量（每个 1024 维 × 4 字节 = 4KB/vector，千级 ~4MB）
   - BM25 倒排索引（term → docId → TF 的 HashMap）
   - 默认 Xmx 通常是物理内存的 1/4，对于这个项目足够

2. **GC 选择**：
   - 当前默认可能是 Serial GC 或 Parallel GC（取决于 JDK 版本和机器配置）
   - 推荐 G1GC：`-XX:+UseG1GC`，低延迟 + 可预测的暂停时间
   - 这个项目 GC 压力不大——大部分对象是请求级别的短生命周期对象

3. **内存泄漏风险**：
   - `DocumentRegistry` 的 ConcurrentHashMap 只增不减（除非调 unregister）
   - `ChatSessionService` 的 fallbackStore 在 Redis 不可用时会累积数据
   - 【可优化】缺少定时清理机制

4. **ThreadLocal 注意**：
   - `MDC` 的 ThreadLocal 在 `RequestLoggingFilter.finally` 中已清理
   - 没有自定义 ThreadLocal，泄漏风险低

**Spring Boot 默认 JVM 参数查看**：
```bash
java -XX:+PrintFlagsFinal -version | grep -i "heapsize\|gc"
# 或者启动后 jinfo -flags <pid>
```

**面试直接说**：
"这个项目规模小，默认 JVM 参数够用。但我理解 GC 的基本原理——如果生产部署，我会根据实际内存使用情况设置 Xmx，启用 G1GC，配置 GC 日志，用 Arthas 或 VisualVM 观察实际的内存分布。"

**可能追问**：你了解哪些 GC 算法？G1GC 和 CMS 的区别？
**回答风险提醒**：诚实说没做过 GC 调优，但展示出你知道应该关注哪些点。这比编造调优经验好得多。

---

## Q48：你的项目中如果出现 OOM，最可能是什么原因？

**考察点**：内存问题排查思维。

**30 秒回答**：
三个可能原因：1）`InMemoryVectorStore` 的 ConcurrentHashMap 无限增长（入库了大量文档但没限制上限）；2）`ResilientEmbeddingService` 的 Embedding 缓存在极端情况下可能接近 2000 条上限（每条 1024 维 × 4 字节），但 2000 条限制保护了不至于 OOM；3）`ChatSessionService` 的 fallbackStore 在 Redis 不可用时持续累积所有用户的会话数据。

**2 分钟回答**：
**风险分析**：

**1. InMemoryVectorStore 无限增长**（风险：中）
- 没有文档数量上限
- 每个 Chunk 存一条 Entry：id + text + float[1024] + 元数据
- 如果入库了 100 万篇文档，每个文档 20 个 Child，2000 万条 Entry × 5KB ≈ 100GB
- 缓解：当前知识库只有 D:\docs 下几个文件，风险低；但缺少硬性的文档数上限

**2. Embedding 缓存**（风险：低）
- `cacheMaxSize = 2000`，FIFO 淘汰
- 2000 × 1024 × 4 bytes ≈ 8MB，可控
- `evictIfNeeded()` 在每次写入时检查，清 10%

**3. FallbackStore 累积**（风险：中）
```java
private final Map<String, List<Message>> fallbackStore = new ConcurrentHashMap<>();
```
- Redis 不可用时，所有用户的所有会话消息都往这个 Map 里塞
- 没有 TTL 淘汰、没有大小限制
- 内存泄漏风险：已过期的 sessionId 永远不会被清理
- 缓解：Redis 恢复后 fallback 不再被写入，但旧数据没人清理

**4. BM25 倒排索引膨胀**（风险：低-中）
- `termToDocTf: HashMap<String, Map<String, Integer>>`
- 词项数 × 文档数的拉链结构
- 中文 2-gram 子词会大幅增加词项数（"缓存穿透" → 4 个 2-gram）
- 当前文档少所以安全

**排查方法**（如果出问题）：
```bash
jmap -histo:live <pid> | head -20    # 看哪些对象占内存
jmap -dump:format=b,file=heap.hprof  # dump 用 MAT 分析
```

**可能追问**：ConcurrentHashMap 的 fallbackStore 怎么加淘汰机制？
**回答风险提醒**：展示出"知道哪些地方可能出问题"比"出过问题并解决"更真实可感。

---

## Q49：你的项目如何处理并发请求？有没有线程安全问题？

**考察点**：并发安全意识 + 实际代码审计。

**30 秒回答**：
项目通过以下方式保证线程安全：ConcurrentHashMap 用于多线程共享的 Map（VectorStore、缓存、会话降级）；volatile 用于熔断器状态标志；ThreadLocalRandom 替代 Math.random() 避免竞争；SseEmitter 的 emit() 方法是线程安全的。已知的并发权衡：HALF_OPEN 探测可能有多个请求同时尝试，低并发下可接受。

**2 分钟回答**：
**线程安全的保证**：

1. **ConcurrentHashMap**：VectorStore 存储、Embedding 缓存、会话降级存储
2. **volatile**：熔断器状态（circuitState、consecutiveFailures、circuitOpenedAt）
3. **ThreadLocalRandom**：TTL 抖动计算（避免 Math.random() 的全局锁）
4. **CachedThreadPool**：工具执行隔离
5. **SseEmitter**：Spring 保证其线程安全（emit 内部同步）
6. **MDC（ThreadLocal）**：traceId 线程隔离

**已知的并发权衡**：

**HALF_OPEN 竞态**：
```java
// shouldTryPrimary() 中：
if (circuitState == CircuitState.OPEN) {
    if (time > cooldown) {
        circuitState = CircuitState.HALF_OPEN; // ← 多个线程可能同时执行
        return true; // ← 多个请求同时探测
    }
}
```
- 在低并发场景下可以接受（最多多几个请求探测）
- 如果用 CAS 精确控制，代码复杂度会增加不少

**FIFO 淘汰竞态**：
```java
if (cache.size() >= cacheMaxSize) {  // ← check
    int toRemove = cacheMaxSize / 10;
    var it = cache.keySet().iterator();
    for (int i = 0; i < toRemove; i++) {
        it.remove();                   // ← act
    }
}
```
- check-then-act 非原子操作
- 可能超删（多删几个）或少删（没删够），但缓存淘汰不需要精确控制

**线程不安全的已知风险**：
- `SimpleEmbeddingService` 的 `buildVocabulary()` 和 `embed()` 如果在同一时间被执行可能有问题——但 buildVocabulary 是在启动时单线程执行的
- BM25 的 `index()` 和 `search()` 如果同时发生——`DocumentIngestionService` 的 `rebuildBm25()` 在异步入库期间和检索线程可能冲突。当前通过"先入库完成再 merge"避免，但严格来说应该有读写锁

**面试直接说**：
"并发安全不是'全都用 synchronized'——那叫过度设计。我的原则是：识别真正的并发风险，用合适的粒度保护，并记录已知的取舍。"

**可能追问**：你能画一下并发请求的调用链吗？从 Tomcat 线程到 Controller 到 Service？
**回答风险提醒**：准备解释 Tomcat 线程池模型——每个请求一个线程，线程数由 `server.tomcat.threads.max` 控制（默认 200）。

---

## Q50：如果让你把这个项目部署到生产环境，你还需要做哪些事情？

**考察点**：工程完整度认知 + 生产化思维。

**30 秒回答**：
六个方面：1）接入真实 LLM API（如 DeepSeek）替换 Mock；2）安全加固（认证、限流、输入校验）；3）可观测性（Prometheus metrics、Grafana 面板、告警规则）；4）向量存储升级（Redis Stack HNSW 或 pgvector）；5）配置管理（环境变量替换明文 API Key）；6）CI/CD（自动化测试 + 部署流水线）。

**2 分钟回答**：
**生产化清单**（按优先级排列）：

**P0 - 必须做**：
1. **真实 LLM 接入**：替换 `/mock-llm` 为真实 API（DeepSeek/Qwen/GLM），含 streaming 支持
2. **安全加固**：API Key 认证、请求频率限制（RateLimiter）、输入长度限制、敏感信息脱敏
3. **配置安全**：`application.properties` 中的明文 API Key 移到环境变量或 Vault
4. **异常处理完善**：全局异常处理器（@ControllerAdvice）、统一的错误响应格式

**P1 - 重要**：
5. **可观测性**：Micrometer + Prometheus metrics（检索延迟 P50/P99、缓存命中率、Embedding 降级次数、QPS）；Grafana 仪表盘；日志采集（ELK/Loki）
6. **向量存储升级**：`VectorStore` 的 Redis Stack HNSW 实现，支持万级以上文档
7. **编译打包**：Docker 镜像 + docker-compose（含 Redis + MySQL）

**P2 - 增强**：
8. **CI/CD**：GitHub Actions/Jenkins 流水线，每次 push 跑 `mvnw test`（含检索评测），Recall@K 低于基线阻断合并
9. **Agent Loop 闭环**：实现 `AgentLoop` 组件，让 LLM 能真正多轮调用工具
10. **多租户**：文档隔离、会话隔离
11. **文档管理增强**：支持 PDF/Word 解析、图片 OCR、URL 抓取

**配置外化示例**：
```bash
export EMBEDDING_API_KEY=sk-xxx
export RERANKER_API_KEY=sk-xxx
export DB_PASSWORD=xxx
```

**Docker 化示例**：
```dockerfile
FROM eclipse-temurin:17-jre
COPY target/demo00-*.jar app.jar
ENTRYPOINT ["java", "-Xmx512m", "-XX:+UseG1GC", "-jar", "app.jar"]
```

**面试直接说**：
"这个项目是一个 Demo，不是生产级产品。Demo 和产品的差距不是代码量，而是这些'非功能需求'——安全、可观测、可运维。这些我在学，但还没实现。"

**可能追问**：你了解 Docker 吗？Compose 文件怎么写？
**回答风险提醒**：诚实区分"Demo 能跑"和"生产可用"的差距，这是高级工程师的思维习惯。

---

# 附录：快速复习卡

## 项目数据速记

| 指标 | 数值 |
|---|---|
| 核心类数量 | 46 个 |
| Controller 端点 | 9 个（/chat, /chat/stream, /mock-llm, /agent/tool-call, /agent/tools, /admin/documents/*） |
| Embedding 模型 | BAAI/bge-large-zh-v1.5 (1024 维) |
| Reranker 模型 | BAAI/bge-reranker-v2-m3 |
| BM25 参数 | k1=1.2, b=0.75 |
| RRF 参数 | k=60 |
| Child Chunk 大小 | ~500 字 (128 字重叠) |
| Parent Chunk 大小 | ~2000 字 |
| 默认 topK | 3 |
| 相关性阈值 | 0.35 |
| 会话窗口 | 20 条 |
| Agent 最大步数 | 10 |
| 死循环阈值 | 3 次 |

## 关键设计决策速查

| 决策 | 原因 |
|---|---|
| 自己实现 BM25 | 学习目的，理解算法本质 |
| RRF 不用加权求和 | 量纲不同，RRF 用排名天然可比 |
| Cross-Encoder 精排 | Bi-Encoder 粗排快，Cross-Encoder 精排准 |
| LPUSH+LTRIM 滑动窗口 | O(1) 追加，比 MySQL 快 |
| @Primary 选择实现 | 业务代码依赖接口，切换零改动 |
| volatile 不用 synchronized | 状态标志只需可见性，不锁降低开销 |
| 暴力扫描不用 HNSW | 千级文档暴力够快，架构预留了接口 |
| 异步启动不阻塞 | CompletableFuture.runAsync()，服务秒级就绪 |
| LLM 超时不重试 | 非幂等操作，重试可能雪上加霜 |
| Embedding 三层防御 | 缓存+熔断+降级，降级方案可工作 |
