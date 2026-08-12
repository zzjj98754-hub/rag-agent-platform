# 简历项目描述与面试问答

## 最终简历版本

**RAG Agent 智能知识库平台｜Spring Boot 3.3、Java 17、React、MySQL、Redis**

- 设计并实现 BM25 与向量并行召回、RRF 融合、BGE Reranker 精排的 Hybrid RAG
  链路；采用 Child 检索、Parent 上下文展开的 Small-to-Big 策略，并按 BGE/RRF
  评分体系实施独立相关性门控。
- 抽象统一 Embedding 接口，为 SiliconFlow 增加熔断、缓存和 Local TF-IDF
  fallback；入库按 Chunk、BM25、Embedding、Vector 顺序执行并校验索引完整性，
  外部服务不可用时仍可本地运行双路检索。
- 实现 OpenAI-compatible Function Calling Agent Loop，包含 Tool Registry、工具
  RBAC、Observation 回灌、最大步骤限制和重复调用检测；知识搜索工具复用完整
  HybridRetriever，前端提供 Agent 执行轨迹审计展示。
- 基于 WebClient/Flux 与 SseEmitter 实现 Token 级流式问答，增加 heartbeat、事件
  ID、retry、短期重放、断开清理及独立发送线程池；实现 Citation 编号校验与审计。
- 使用 JWT/RBAC 保护接口；MySQL 保存完整消息历史，Redis 保存热上下文，通过
  Transactional Outbox 异步投影缓存并失败重试；接入 MDC、Micrometer、Prometheus、
  Grafana，并提供 Nginx + 前后端 + MySQL + Redis 的 Docker Compose 部署。

## 高频追问与标准回答

### 1. 为什么 RRF 不做加权分数相加？

BM25、余弦相似度和 Cross-Encoder 分数的尺度不同，固定权重会受查询和模型影响。
RRF 只使用名次，先解决跨召回源融合；精排再交给 BGE。代价是丢失原始分数幅度，
因此它不是所有数据集的最优方案，但比未经校准的加权和更稳健。

### 2. 如何证明向量链路不是空接口？

入库结束校验 documents、chunks、BM25 size 和 VectorStore size；文档只有向量数与
Chunk 数一致才显示 READY。`LocalHybridRagIntegrationTest` 在无外部 Key 情况下
断言本地向量数量大于 0、BM25 和 Vector 两路都能召回，随后再走 RRF 与 Gate。

### 3. Local Embedding 能替代 BGE Embedding 吗？

不能等价替代。Local TF-IDF 是高可用与本地演示 fallback，保证向量接口和余弦链路
可运行，但语义泛化较弱。结果会记录 `embeddingSource=local`，避免 UI 将降级结果
伪装成真实语义模型。

### 4. Small-to-Big 在哪里发生？

Child 文本进入 BM25、向量检索、RRF 和 Reranker；精排完成后通过 parentId 展开
Parent 文本并去重，Prompt 只消费展开后的 Context。这样小块提高命中精度，大块
提供回答上下文。

### 5. BGE 失败为什么不会把上下文清空？

返回对象带有 `scoreType`。BGE 成功使用 0.35；失败保留约 0.03 的 RRF 分数并使用
0.01。Gate 拒绝混合 scoreType 的列表，防止不同评分体系误用同一阈值。

### 6. Agent 和普通 Tool API 有什么区别？

AgentExecutor 将工具 schema 交给 LLM，由 LLM 决策调用；执行结果作为 Observation
加入上下文，再调用 LLM，直到 final answer。Context 维护跨步骤历史，并限制 5 步、
连续相同调用 3 次中止。单次 Tool API 只执行用户指定工具，不构成 Agent Loop。

### 7. SearchTool 是否真的复用了完整 RAG？

它直接调用 HybridRetriever，因此具备 BM25、Vector、RRF、Rerank 和 Parent 展开。
它没有会话历史参数，所以不宣称执行 Chat 层的多轮 Query Rewrite；这是接口边界，
不是隐藏功能。

### 8. SSE 是真流式吗？

StreamingLlmClient 用 WebClient 发送 `stream=true`，逐条解析上游 SSE delta 为 Flux。
SseEmitter 直接发送每个 token。测试验证 token 顺序，代码没有 `Thread.sleep` 或
完整字符串切割。

### 9. 断线后能从任意位置继续生成吗？

只能重放单 JVM 短期缓冲中、Last-Event-ID 之后已经产生的事件。如果上游已因断线
取消且缓冲没有 done，服务端明确告知重新提问。真正跨实例可续传需 Redis Stream/
Kafka、生成任务与连接解耦，这个项目没有夸大为已实现。

### 10. Outbox 解决了什么，没解决什么？

它保证业务消息和待投影事件在同一个 MySQL 事务，避免“消息成功但没有更新缓存的
记录”。Relay 异步幂等更新 Redis，失败重试；Redis 未更新时从 MySQL 回填。当前
单 Relay 没有完整的多实例抢占、DEAD 告警和归档，生产化仍需补充。

### 11. 为什么 Redis 不直接替代 MySQL？

Redis 适合最近窗口、TTL 和高频读取；MySQL 适合完整历史、事务、关联用户和审计。
两者职责不同。缓存丢失可由 MySQL 回填，反过来只靠 Redis 无法提供同等持久性。

### 12. 项目最大生产差距是什么？

向量库仍是单机 O(N) 内存检索，SSE 重放是单 JVM，Local TF-IDF 质量有限，Outbox
Relay 也是单实例模型。下一步应优先用 pgvector/Redis Stack 做持久向量索引、用
持久消息流解耦生成任务，并对真实模型做离线评测和容量压测，而不是继续堆页面。
