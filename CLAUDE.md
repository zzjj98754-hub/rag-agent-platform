# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Environment

- **Java 17** (Corretto) at `D:\dev\jdks`. Set `JAVA_HOME=/d/dev/jdks` before running Maven.
- **MySQL 8.0** — required for full functionality (users, documents, chat history, outbox). Schema managed by **Flyway** (`src/main/resources/db/migration/`). `MysqlConnectionVerifier` logs connection status on boot. If MySQL is down the app still starts, but persistence degrades (Redis → in-memory fallback).
- **Redis** (7.x in Docker / 8.4 local at `D:\Redis\Redis-8.4.0-Windows-x64-cygwin-with-Service`) — hot window for chat history and answer cache. It is a **projection, not the source of truth** (see Outbox pattern). All Redis-dependent features have fallbacks.
- **Documents** for RAG knowledge base live in `D:\docs` (15 files: `java.txt`, `java-collections.txt`, `java-concurrency.txt`, `java-jvm.txt`, `spring.txt`, `spring-ioc-aop.txt`, `spring-boot-autoconfig.txt`, `spring-mvc-rest.txt`, `redis.txt`, `redis-in-depth.txt`, `mysql-index-transaction.txt`, `design-patterns.txt`, `rag-principles.txt`, `vector-search.txt`, `http-https.txt`). Ingested → 181 chunks.
- **Frontend**: React 18 + Vite 5 + TypeScript in `frontend/`, dev server on port 5173. Backend CORS allows `http://localhost:5173` by default.

## Dependencies (pom.xml)

- **spring-boot-starter-web** — Spring MVC + embedded Tomcat
- **spring-boot-starter-validation** — Bean Validation for DTOs
- **spring-boot-starter-webflux** — WebClient for streaming LLM calls (Reactor Netty)
- **pdfbox 3.0.2** — PDF text extraction for document uploads
- **spring-boot-starter-data-redis** + **commons-pool2** — Redis via Lettuce with pooling
- **spring-boot-starter-jdbc** — HikariCP
- **mybatis-spring-boot-starter 3.0.4** — MyBatis; XML mappers in `src/main/resources/mapper/`
- **mysql-connector-j** (runtime), **flyway-core** + **flyway-mysql** — migrations
- **spring-boot-starter-security** + **spring-security-oauth2-jose** — Spring Security + JWT (HS256 via Nimbus)
- **spring-boot-starter-actuator** + **micrometer-registry-prometheus** — metrics
- **lombok** (optional), **spring-boot-starter-test** (JUnit 5 + Mockito)

## Build & Run

```bash
export JAVA_HOME=/d/dev/jdks
export PATH=$JAVA_HOME/bin:$PATH
./mvnw spring-boot:run       # starts on port 9090
./mvnw test                   # run all tests
./mvnw test -Dtest=ClassName  # run single test class
./mvnw test -Dtest=ClassName#methodName  # run single test method
cd frontend && npm install && npm run dev   # frontend dev server (5173)
docker compose up -d --build  # dev full stack: mysql, redis, app, frontend, nginx, prometheus, grafana (zero-config)
# prod (single Linux host, Compose >= 2.24): base + overlay, secrets fail-fast via ${VAR:?}
#   cp .env.prod.example .env.prod && bash scripts/gen-grafana-htpasswd.sh  # .env.prod 已 gitignore，密钥不落库
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod --profile prod up -d --build
#   overlay adds: prod profile, port withdrawal (prometheus/grafana internal-only), redis requirepass,
#   resource limits, log rotation, cap_drop/read_only, mysql-backup sidecar (daily mysqldump, 7-day retention), node-exporter
python scripts/validate-compose-yaml.py  # compose YAML 结构校验（本机无 Docker CLI，替代 docker compose config）
```

Key test classes (in `src/test/java/com/example/demo/`):
- `persistence/BusinessPersistenceIntegrationTest` — MySQL business tables end-to-end
- `persistence/ChatHistoryPersistenceServiceTest`, `OutboxRelayTest`, `UserPersistenceServiceTest`
- `security/SecurityIntegrationTest`, `SseSecurityIntegrationTest`, `JwtServiceTest`
- `evaluation/EvalRunnerTest#runRetrievalEvaluation` — retrieval metrics (see Evaluation)
- `service/StreamingChatServiceTest`, `OpenAiStreamingLlmClientTest`, `SseReplayBufferTest`

## Project Structure

```
├── src/main/java/com/example/demo/   (125 files)
│   ├── Demo00Application.java        @SpringBootApplication + @EnableScheduling
│   ├── controller/   薄接入层：Auth/Chat/Stream/Agent/Admin/MockExternalLlm
│   ├── dto/          对外契约（record + @Valid）
│   ├── service/      业务编排层：ChatService、RagPromptService、会话、文档、SSE
│   ├── rag/          检索算法层（无状态）：BM25、RRF、Reranker、Chunker、门控
│   ├── agent/        AgentExecutor 推理循环 + AgentLlmClient 双实现
│   │   └── tool/     ToolDefinition 策略 + Registry + Scheduler + Executor
│   ├── security/     JWT、认证、会话归属校验（AuthenticatedSessionService）
│   ├── persistence/  MyBatis 数据访问层
│   │   ├── entity/   User/Document/ChatSession/ChatMessage/OutboxEvent
│   │   ├── mapper/   接口 → XML（resources/mapper/*.xml）
│   │   └── service/  @Transactional 边界 + Outbox 写入/投递
│   ├── observability/ RagObservability、Micrometer 指标
│   ├── config/       线程池、RestTemplate/WebClient、安全、CORS、MDC
│   └── web/error/    GlobalExceptionHandler 统一异常 → JSON
├── src/main/resources/
│   ├── application.yml + application-{dev,loadtest,prod}.yml
│   ├── db/migration/ V1__create_business_tables.sql, V2__create_outbox_event.sql
│   └── mapper/       5 个 MyBatis XML
├── src/test/java/    41 个测试类（含 evaluation/ 评测）
├── frontend/         React 18 + Vite + TS；api/ 层、hooks/useSSE.ts、RagTrace 组件
├── docker/           nginx 反代（含 grafana htpasswd 门）、prometheus、grafana（RAG Agent 看板）、mysql-backup/ 备份脚本
├── scripts/          gen-grafana-htpasswd.sh、validate-compose-yaml.py；loadtest/ Python 压测脚本 + mock AI upstream
└── docs/PRD.md       下一阶段蓝图（Spring AI 迁移 + MCP/Skills/Workflow，见 Roadmap 节）
```

## Architecture Overview

Spring Boot 3.3.0 AI Agent Platform — 125 main source files across 7 capability areas:

| Capability | Key Components |
|---|---|
| **AI 对话工程化** | `ChatService`（同步 RAG 编排）、`StreamingChatService`（SSE 流式）、`ChatSessionService`（三级存储门面）、`ConversationCompletionService`（引用校验+落库） |
| **RAG 检索增强** | `HybridRetriever`（双路并行→RRF→精排）、`Bm25Index`、`RrfFusion`、`BgeReranker`、`LlmQueryRewriter`（LLM+规则混合改写）、`RelevanceGate`（双阈值门控）、`HierarchicalChunker`、`CitationFormatter`、`CitationValidator` |
| **Embedding 弹性化** | `ResilientEmbeddingService`（@Primary，缓存+熔断+降级）、`SiliconFlowEmbeddingService`（主）、`SimpleEmbeddingService`（备，TF-IDF） |
| **VectorStore 可插拔** | `VectorStore` 接口、`InMemoryVectorStore`（@Primary）、`VectorStoreConfig`（backend 开关） |
| **文档入库管线** | `DocumentIngestionService`（Load→Chunk→Embed→Index，增量+异步）、`DocumentRegistry`（SHA-256 变更检测）、`DocumentPersistenceService`（MySQL 状态）、`DocumentContentExtractor` 策略（txt/md + pdfbox） |
| **Agent / Tool Calling** | `AgentExecutor`（决策→工具→观察→再决策循环）、`ToolRegistry`、`ToolScheduler`、`ToolExecutor`、`SearchTool`、`CalculatorTool`、`PromptAgentLlmClient` / `OpenAiFunctionCallingClient`（可切换） |
| **认证与安全** | `SecurityConfig`（无状态 JWT）、`JwtAuthenticationFilter`（Token 校验+DB 回查角色）、`AuthenticatedSessionService`（会话归属边界） |
| **持久化** | MyBatis + Flyway：`user` / `document` / `chat_session` / `chat_message` / `outbox_event` 五张表；HikariCP |
| **缓存一致性** | Transactional Outbox：`OutboxEventService`（同事务写事件）→ `OutboxRelay`（轮询投递）→ `ChatCacheProjector`（幂等投影到 Redis） |
| **高并发高可用** | 熔断器（CAS 防惊群）、超时降级链、SSE 心跳+重放缓冲、专用线程池 |
| **可观测性** | `RequestLoggingFilter`（traceId+MDC+计时）、`MdcTaskDecorator`（跨线程传递）、`RagObservability`（分阶段耗时）、Micrometer→Prometheus→Grafana、结构化日志 `logback-spring.xml` |
| **部署** | Docker Compose base + prod overlay（9 服务：+mysql-backup/node-exporter）、三网络隔离（backend/frontend/monitoring）、密钥 `${VAR:?}` fail-fast、非 root + read_only 加固、资源限额与日志轮转、多 profile 配置 |

## Roadmap（docs/PRD.md —— 下一阶段蓝图）

`docs/PRD.md`（v0.1，2026-08-25，开发前评审中）定义基于 demo00 的增量演进，4 项决策已拍板：**D1** 基于 demo00 增量演进（非重写）；**D2** 全面迁移 Spring AI 1.x（ChatClient / EmbeddingModel / VectorStore / ToolCallingManager / ChatMemory，Hybrid+Rerank+门控等差异化能力以包装层/Advisor 保留）；**D3** 完整 PRD 含验收基线；**D4** 部署目标自建 Linux 单机 Compose（沿用 base + prod overlay）。

P0 新增能力：**MCP Client**（stdio/HTTP-SSE）、**Skills**（Prompt+Tool 组合包，版本化+RBAC）、**Agent Workflow**（DAG 编排）、**Redis Stack HNSW** 向量后端（`app.vector-store.backend` 扩展）、**ANALYST 角色**；并修复 BM25 / SimpleEmbeddingService 竞态（对应 Known Limitations 表）。改动 RAG/Agent/持久化前先读该文档 —— Spring AI 迁移映射、REST/SSE 协议扩展、Flyway 增量、里程碑规划均在其中。

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/login` | 登录，返回 JWT。Body: `{"username":"...", "password":"..."}`（permitAll） |
| `POST` | `/chat` | 同步 RAG 聊天。Body: `{"query":"...", "sessionId":"..."}`（authenticated） |
| `GET` | `/chat/stream` | SSE 流式聊天。Params: `query`, `sessionId`, Header `Last-Event-ID`（断线重放） |
| `POST` | `/agent/chat` | 完整 Agent Loop。Body: `{"query":"...", "sessionId":"..."}` |
| `POST` | `/agent/tool-call` | 单次工具调用（调试/兼容）。Body: `{"toolName":"...", "params":{...}}` |
| `GET` | `/agent/tools` | 工具清单 + JSON Schema（OpenAI function 格式） |
| `POST` | `/admin/documents/ingest` | 同步增量入库（ADMIN） |
| `POST` | `/admin/documents/ingest/async` | 异步入库，返回 taskId（ADMIN） |
| `GET` | `/admin/documents/status/{taskId}` | 查询入库进度（ADMIN） |
| `GET` | `/admin/documents` | 文档清单（含 MySQL 状态 + 向量统计）（ADMIN） |
| `POST` | `/admin/documents/upload` | multipart 上传文档（txt/md/pdf）（ADMIN） |
| `DELETE` | `/admin/documents/{documentId}` | 删除文档及索引（ADMIN） |
| `POST` | `/mock-llm` | Mock LLM（仅 dev/loadtest profile） |
| `POST` | `/mock-llm/stream` | Mock OpenAI-compatible SSE 上游（仅 dev/loadtest） |
| `GET` | `/actuator/health`, `/actuator/prometheus` | 健康检查（permitAll）/ 指标（ADMIN） |

安全规则（`SecurityConfig.java:39-47`）：CSRF 关闭、无状态 Session、`/auth/login` `/mock-llm/**` `/error` `/actuator/health` 放行，`/admin/**` `/actuator/**` 需 ADMIN，其余全部需认证。

## Request Lifecycle: POST /chat（Controller → Service → DAO → 落库）

```
① Filter 链（WebConfig 注册 RequestLoggingFilter，最外层）
   CharacterEncodingFilter → RequestLoggingFilter（traceId 入 MDC、响应头回传、计时）
   → JwtAuthenticationFilter（验签 → 回查 MySQL 用户 → 比对 token.uid/role 与 DB → 放入 SecurityContext）
② ChatController.chat()                              controller/ChatController.java:19
   @Valid @RequestBody ChatRequest → chatService.ask(query, sessionId)
③ ChatService.ask() → observeRequest() → doAsk()     service/ChatService.java:81
   ├─ resolveSessionId() → AuthenticatedSessionService.resolveOrCreate()
   │    会话不存在 → 绑定当前用户创建；存在 → canAccess() 校验归属（防越权）
   ├─ RagPromptService.prepare()                     service/RagPromptService.java:40
   │    history(formatHistory) → LlmQueryRewriter.rewrite()
   │    → HybridRetriever.retrieve()（双路并行→RRF→Rerank→Parent 去重）
   │    → RelevanceGate.evaluate()（低于阈值 → Prompt 告知 LLM"未找到"）
   │    → CitationFormatter 拼引用编号 [1][2][3]
   ├─ 答案缓存：key = "chat:cache:" + SHA-256(query|session|docsHash) 前 16 位
   │   读 Redis（失败→本地 ConcurrentHashMap），命中直接返回
   ├─ llmClient.callLlm(prompt, model)               ExternalLlmClient（RestTemplate，3s/30s 超时）
   │   超时/异常 → fallbackResponse() 关键词降级回答，永不断链
   ├─ putToCache()：Redis SET，TTL = base × (1 ± 10% jitter)，防缓存雪崩
   └─ ConversationCompletionService.complete()
        citationValidator.validate()（检测编造的引用编号）
        → sessionService.appendMessage(sid, "user", query)
        → sessionService.appendMessage(sid, "assistant", response)
④ ChatSessionService.appendMessage()                 service/ChatSessionService.java:116
   【主路】historyPersistenceService.appendMessage() —— 成功即 return，不再碰 Redis
   【降级】MySQL 挂 → 直接 LPUSH+LTRIM 写 Redis；Redis 也挂 → 本地 Map
⑤ ChatHistoryPersistenceService.appendMessage()      persistence/service/ChatHistoryPersistenceService.java:48
   @Transactional，一个事务内：
     ensureSession（查/建 chat_session）
     → chatMessageMapper.insert(message)             → INSERT INTO chat_message
     → chatSessionMapper.touch(id)                   → 更新 update_time
     → outboxEventService.messageAppended(...)       → INSERT INTO outbox_event ★
⑥ ChatMessageMapper（接口，无实现类）──MyBatis 动态代理──▶
   resources/mapper/ChatMessageMapper.xml → HikariCP → MySQL InnoDB
⑦ 异步尾巴：OutboxRelay @Scheduled(500ms) 轮询 PENDING 事件
   → ChatCacheProjector.project()（Lua 脚本 SETNX 幂等 + LPUSH/LTRIM）→ 写 Redis 热窗口
   → markProcessed()；失败重试 8 次（5s 间隔）→ 标 DEAD
```

同步请求线程到第 ⑤ 步事务提交即结束；Redis 热窗口由 outbox 在 ≤500ms 内最终一致地投影（详见下节）。

## Key Design Patterns

### Transactional Outbox：MySQL 真相源，Redis 投影

**双写不一致问题的答案**：不直接写 Redis，而是把"通知 Redis"变成同事务内的一条数据库记录。

- **写入侧**（`ChatHistoryPersistenceService.appendMessage/createSession`）：业务数据 + `outbox_event` 行在同一个 `@Transactional` 里，原子提交。
- **投递侧**（`OutboxRelay`）：`@Scheduled(fixedDelayString="${app.outbox.poll-interval-ms}")`，默认 500ms 轮询 PENDING 事件（batch 50），逐条投影到 Redis 后标 PROCESSED；失败按 `app.outbox.max-retries`（8）× `retry-delay-seconds`（5s）重试，超限标 DEAD（`OutboxEventMapper.xml:43-50`）。
- **幂等投影**（`ChatCacheProjector.java:29-40`）：每次投影执行一个 Lua 脚本，先 `SETNX outbox:processed:<eventId>` 抢幂等标记再写业务 key —— 投影成功后 relay 崩溃，重放不会重复写入。Lua 保证"查重+写入"在 Redis 内原子。
- **已知取舍**：MySQL 写入成功后 `ChatSessionService.appendMessage` 直接 return（`ChatSessionService.java:130-132`），所以刚写入的消息最长 ~500ms 后才出现在 Redis。接受这个延迟，换取永不双写。
- **读路径**：`getHistory()` 先读 Redis 热窗口（40 条）→ 未命中读 MySQL 最近记录并 `warmRedis()` 回填 → 双挂读本地 Map。Redis 整个删除可自愈。
- **答案缓存不走 outbox**（`ChatService.putToCache()`）：它是可重算的派生物，丢了再问一次 LLM 即可；会话历史是持久状态，必须走 outbox。按数据性质选择一致性策略。

### Embedding：Primary + Fallback + Circuit Breaker（`ResilientEmbeddingService`）

1. **查询缓存**：SHA-256(text) → 向量，FIFO 淘汰（max 2000）
2. **熔断器**：CLOSED →（连续 3 次失败）→ OPEN（60s 冷却）→ HALF_OPEN（探测）→ CLOSED
3. **自动降级**：主服务不可用 → 透明切到 `SimpleEmbeddingService`（TF-IDF，**可工作的实现**）
4. **HALF_OPEN 防惊群**：`halfOpenProbeInProgress.compareAndSet(false, true)`（`ResilientEmbeddingService.java:239-258`）保证冷却结束后只有一个请求获得探测权，其余继续走降级。

⚠️ `SimpleEmbeddingService.buildVocabulary()` 整体替换 `vocabulary`/`termIndex`/`idf` 字段，与 `embed()` 并发存在竞态（入库重建词表 vs 在线查询）。

### RAG Pipeline（同步路径）

```
RagPromptService.prepare(query, sessionId)
  → ChatSessionService.formatHistory()                  // 热窗口最近 40 轮
  → LlmQueryRewriter.rewrite(query, history)            // 先规则判断跳过 → LLM 改写 → 失败降级规则改写
  → HybridRetriever.retrieve(query, topK=3)             // 两阶段：
      Stage1: BM25 + Embedding 并行（CompletableFuture, ragRetrievalExecutor）
              → RRF 融合 → topK×6 候选
      Stage2: BgeReranker Cross-Encoder 精排 → topK
              （Reranker 失败 → 降级用粗排结果，不阻塞链路）
      Small-to-Big：检索全程用 Child(500字)，进 Prompt 前展开 Parent(2000字) 并按 Parent 去重
  → RelevanceGate.evaluate(docs)                        // 双阈值：BGE 0.35 / RRF 0.01（按 scoreType 区分）
  → CitationFormatter.formatReferenceSection(docs)
```

### Tool Calling：Strategy + Registry + 安全调度

- `ToolDefinition` 是策略接口（name/description/parametersSchema/execute/requiredPermissions）。`ToolRegistry` 用 `List<ToolDefinition>` 构造注入自动收集所有实现并检测重名 —— 加工具只需 `implements ToolDefinition` + `@Component`。
- `ToolScheduler.dispatch()` 顺序执行 4 道检查：①步数上限（`maxSteps`）②死循环检测（连续 N 次相同工具+相同参数）③Registry 存在性 ④RBAC 权限（`ToolPermissionEvaluator`）。全过才交给 `ToolExecutor`。
- `ToolExecutor.execute()`：专用线程池 `agentToolExecutor` 提交 + `Future.get(stepTimeoutMs, MILLISECONDS)` 超时取消；线程池饱和（`RejectedExecutionException`）返回"资源繁忙"而不是抛出。
- `AgentExecutor.execute()`：真正的 Agent Loop —— `AgentLlmClient.decide()` → tool_call → 执行 → observation 回填 → 再决策，直到 final_answer 或 maxSteps（默认 5）。工具轨迹逐条写入会话（role="tool"）。
- `AgentLlmClient` 双实现（属性开关 `app.agent.function-calling.enabled`，默认 false）：
  - `PromptAgentLlmClient`：结构化 JSON 协议（`AGENT_DECISION_PROTOCOL_V1`），解析不出来的 JSON 降级为直接回答；
  - `OpenAiFunctionCallingClient`：原生 OpenAI Function Calling。

### Streaming（SSE）

`StreamingChatService`（`service/StreamingChatService.java`）：
- 事件序列：`session` → `context(retrieving)` → `citations` → `trace` → `context(generating)` → `token`×N → `done`（含 timings），另有 `heartbeat`（15s 周期，防代理超时断连）和 `error`。
- **断线重放**：每个非心跳事件经 `SseReplayBuffer` 分配自增 id；客户端带 `Last-Event-ID` 重连，服务端重放后续事件。上游已完成的流不续传（reconnect 事件告知 `resumable:false`）。
- **上游**：`OpenAiStreamingLlmClient` 用 WebClient + `Flux<String>` 解析 OpenAI-compatible SSE（`data:` → `choices[0].delta.content` / `[DONE]`）。`onBackpressureBuffer(256, ERROR)` 防止慢客户端压垮内存。
- **线程模型**：RAG 准备在 `ragAsyncExecutor`，token 发送在 `sseSendExecutor`（`publishOn`），所有跨线程处手动搬运 MDC（`withTrace`）。
- 发送串行化：每个 emitter 一个 `SendContext.monitor`，`synchronized` 保证事件顺序。

### Document Ingestion：增量 + 异步 + MySQL 状态

`DocumentIngestionService`：`@PostConstruct` 时 `CompletableFuture.runAsync()` 后台入库，不阻塞启动。

- **管线**：Load（SHA-256 变更检测，未变跳过）→ Chunk（HierarchicalChunker）→ 注册到 DocumentRegistry → 批次结束统一 `rebuildBm25()` + `buildLocalVocabulary()` + `reindexAllVectors()`（全部 embedding 成功后才替换索引，避免半成品可见）→ `verifyIndexState()` 完整性检查。
- **更新安全**：旧 chunk 先删后写；BM25 全量重建。
- **MySQL 状态**：`document` 表记录 PROCESSING / INDEXED / FAILED + creator_id；`/admin/documents` 聚合 Registry 快照展示 chunkCount/vectorCount/embeddingSource（siliconflow | local）。
- **上传**：`DocumentManagementService` 用 `List<DocumentContentExtractor>` 策略链按扩展名匹配（`PlainTextDocumentExtractor` + `PdfDocumentExtractor`），白名单 `txt,md,pdf`。
- **异步 API**：taskId 轮询进度（`ConcurrentHashMap` 内存任务表）。

### Security：无状态 JWT + 边界收敛

- `JwtService`：HS256 签发（claims: sub/uid/role），`JwtConfig` 要求 secret ≥ 32 字节，启动即校验。
- `JwtAuthenticationFilter`：验签后**回查数据库**比对 uid/role —— token 中的身份与库不一致即拒绝，封禁/降权立即生效。
- `AuthenticatedSessionService.resolveOrCreate()`：会话创建绑定 userId，访问校验归属（admin 例外）。**所有**会话入口（Chat/Agent/Stream）都走这一个方法，会话越权检查只有一处。
- 认证失败/拒绝由 `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` 返回 JSON（非 HTML 重定向）。
- 初始数据：`BootstrapAdminInitializer`（属性开关，默认关，存在同名用户不重置凭据）、`LoadTestUserInitializer`（loadtest profile）。

### Observability

- `RequestLoggingFilter`：traceId（支持上游透传 `X-Trace-Id`，否则生成 12 位）入 MDC、请求/响应计时日志、响应头回传、finally 清理 MDC。
- `MdcTaskDecorator`：线程池提交时捕获调用方 MDC 并在任务中恢复 —— 所有自建线程池都装了它。
- `RagObservability`：ThreadLocal 请求上下文，`observeRequest(Supplier)` / `measure(stage, Supplier)` 无侵入埋点；RAG_METRICS 结构化日志含 bm25/embedding/retrieval/rerank/llm/total 各阶段耗时。
- 日志（`logback-spring.xml`）：CONSOLE 彩色 + traceId；FILE 结构化 key=value，按大小(50MB)/时间滚动，保留 7 天/1GB。

## Configuration Reference (application.yml，值均可被环境变量覆盖)

| Key | Default | Purpose |
|---|---|---|
| `app.llm.url` / `app.llm.model` | `${LLM_URL:}` / `${LLM_MODEL:default}` | 同步 LLM 端点（dev profile 指向 /mock-llm） |
| `app.llm.connect-timeout` / `read-timeout` | 3 / 30 (s) | RestTemplate + WebClient 超时 |
| `app.llm.streaming.url/api-key/model` | `${STREAMING_LLM_URL:}` 等 | 流式 LLM（OpenAI-compatible） |
| `app.llm.streaming.emitter-timeout-ms` / `heartbeat-interval-ms` / `retry-ms` / `buffer-capacity` | 300000 / 15000 / 3000 / 256 | SSE 生命周期 |
| `app.llm.streaming.replay.max-events` / `ttl-ms` | 512 / 300000 | 断线重放缓冲 |
| `app.session.max-history` / `ttl` | 40 / 604800s | Redis 热窗口大小 / 过期 |
| `app.outbox.poll-interval-ms` / `batch-size` / `max-retries` / `retry-delay-seconds` | 500 / 50 / 8 / 5 | Outbox 轮询投递 |
| `app.cache.ttl` / `ttl-jitter` | 3600s / 0.1 | 答案缓存 TTL 及 ±10% 抖动 |
| `app.agent.max-steps` / `step-timeout-ms` / `dead-loop-threshold` | 5 / 30000 / 3 | Agent 循环护栏 |
| `app.agent.tool-threads` / `tool-queue-capacity` | 4 / 100 | 工具线程池 |
| `app.agent.function-calling.enabled` | false | false=Prompt 协议；true=OpenAI FC（需 url/api-key/model） |
| `app.rag.top-k` | 3 | 返回 LLM 的最终 chunk 数 |
| `app.rag.relevance-thresholds.bge` / `rrf` | 0.35 / 0.01 | 门控阈值（按 scoreType 分体系） |
| `app.rag.executor.*` / `app.rag.async.*` | 4-16/200 / 2-8/100 | 检索/异步线程池 |
| `app.embedding.api-key/url/model` | `${EMBEDDING_API_KEY:}` / SiliconFlow / bge-large-zh-v1.5 | Embedding API（密钥走环境变量） |
| `app.embedding.batch-size` / `retry-max` / `retry-backoff-ms` | 32 / 2 / 500 | 批量 + 指数退避重试 |
| `app.embedding.cache-max-size` / `circuit-threshold` / `circuit-cooldown-seconds` | 2000 / 3 / 60 | 缓存 + 熔断 |
| `app.reranker.*` | 同 SiliconFlow，模型 bge-reranker-v2-m3 | 精排 API |
| `app.vector-store.backend` | in-memory | 后端选择（redis-stack 为未来扩展点） |
| `app.ingestion.startup-enabled` / `docs-path` | true / `${RAG_DOCS_PATH:D:/docs}` | 启动入库开关与路径 |
| `app.document.allowed-extensions` | txt,md,pdf | 上传白名单 |
| `app.security.jwt.secret/issuer/expiration-seconds` | dev 默认值 / demo00 / 7200 | JWT（⚠️ dev secret 仅开发用） |
| `app.security.bootstrap-admin.*` | enabled=false | 首启建管理员 |
| `spring.datasource.*` | `${MYSQL_URL:...}` 等，Hikari max 10 | MySQL 连接 |
| `spring.data.redis.*` | localhost:6379，Lettuce 池 max-active 8 | Redis |
| `spring.flyway.*` | enabled，classpath:db/migration | 迁移（clean-disabled） |
| `management.endpoints.web.exposure.include` | health,info,metrics,prometheus | Actuator |

Profiles：`dev`（默认，mock LLM）、`loadtest`（mock AI upstream on :18080、线程/超时调大）、`prod`（LLM 地址无默认值，必须注入环境变量）。

## Database Schema（Flyway）

`V1__create_business_tables.sql`：`user`（BCrypt 密码，唯一用户名）、`document`（状态机 PROCESSING/INDEXED/FAILED，FK creator）、`chat_session`（session_id 唯一，FK user，`(user_id, update_time)` 索引）、`chat_message`（FK session ON DELETE CASCADE，`(session_id, id)` 索引，content LONGTEXT）。
`V2__create_outbox_event.sql`：`outbox_event`（aggregate_type/id、event_type、JSON payload、status PENDING/PROCESSED/DEAD、retry_count、next_retry_time、last_error、processed_time），索引 `(status, next_retry_time, id)` 支撑轮询查询。

## Evaluation

Evaluation code lives in `src/test/java/.../evaluation/`. `eval-dataset.json`（30 annotated queries：11 factoid / 15 conceptual / 3 multi-hop / 1 pronoun-resolution）is in `src/test/resources/`.

Dataset entry format:
```json
{
  "id": "eval-001",
  "query": "面向对象有哪三大特性",
  "type": "factoid",
  "groundTruthDocPrefixes": ["java.txt"],
  "referenceAnswer": "面向对象三大特性是封装、继承和多态。"
}
```
`groundTruthDocPrefixes` are matched against chunk IDs by prefix (e.g., chunk `java.txt:0:2` matches `["java.txt"]`).

**Test methods in `EvalRunnerTest`:**

- **`runRetrievalEvaluation()`**: Recall@K, Precision@K, MRR, NDCG@K — pure math, free, runs every `mvnw test`. Also breaks down results by query type.
- **`runGenerationEvaluation()`**: RAGAS-style Faithfulness / Answer Relevance / Context Relevance — keyword-overlap approximation (mock LLM). Scores are relative rankings, not absolute quality.
- **`compareBm25VsHybrid()`**: BM25-only vs full hybrid head-to-head (same 30 queries, Recall/Precision/MRR/NDCG + latency). **MUST call `waitForIngestion()` first** — see caveat below.
- **`testCircuitBreakerFallback()`**: Verifies the embedding circuit breaker via reflection (forced failures → OPEN → TF-IDF fallback → CLOSED).

**⚠️ Testing caveat — async ingestion race**: `DocumentIngestionService` runs on `@PostConstruct` via `CompletableFuture.runAsync()`. `VectorStore.size() > 0` only means the FIRST document was ingested; `rebuildBm25()` runs at the END of the batch. Use `waitForIngestion()` which polls both `vectorStore.size()` and `bm25Index.search("Java", 1)`.

**Baseline (2026-06-08, 15 docs / 181 chunks / 30 queries, topK=3)**: BM25-only Recall@3=0.96, MRR=0.91; Hybrid Recall@3=0.99, MRR=0.95. Latency: BM25 12ms vs hybrid ~14s per 30 queries. NDCG can exceed 1.0 when `groundTruthDocPrefixes.size()` < relevant chunks in top-K — compare directionally. ⚠️ Baseline predates the auth/persistence refactor; retrieval pipeline itself unchanged, but re-run to confirm.

## Known Limitations (Demo vs Production)

| Limitation | Detail | Production Fix |
|---|---|---|
| **Mock LLM (dev/loadtest)** | `MockExternalLlmController` returns keyword-matched pre-written responses; real clients (`ExternalLlmClient` / `OpenAiStreamingLlmClient`) are already wired via env vars | Set `LLM_URL` / `STREAMING_LLM_URL` etc. in prod profile |
| **O(N) vector search** | `InMemoryVectorStore.search()` brute-force cosine over all entries | Implement `VectorStore` with Redis Stack HNSW or pgvector |
| **BM25 rebuild race** | `rebuildBm25()` replaces index internals while queries may be reading | ReadWriteLock or double-buffer swap |
| **SimpleEmbeddingService not thread-safe** | `buildVocabulary()` vs `embed()` race during re-ingestion | `synchronized` or volatile field swap |
| **Local fallbackStore unbounded** | `ChatSessionService.fallbackStore` has no TTL (only hit when MySQL AND Redis both down) | Scheduled cleanup or Guava Cache |
| **SSE replay is partial** | `SseReplayBuffer` replays buffered events but cannot resume mid-stream generation (`resumable:false`) | Keep stream subscription alive on client disconnect |
| **No rate limiting** | No protection against abuse or runaway LLM calls | bucket4j or gateway rate limiter |
| **Dev JWT secret in repo** | `app.security.jwt.secret` has a dev default | `JWT_SECRET` env var in prod |
| **Outbox relay ≤500ms lag** | Redis hot window is eventually consistent by design | Accept, or trigger projection on read |

## Critical Design Decisions (面试重点)

Focus on **trade-offs**, not just features:

- **Why Outbox + Redis projection instead of dual-write?** → MySQL/Redis 双写无共同事务，任何时序都会不一致。把"通知 Redis"变成同事务的一条 outbox 记录，投递侧幂等重试，最终一致且可重放。代价是 ~500ms 投影延迟。
- **Why MySQL is source of truth and Redis a projection?** → 会话历史是持久状态，丢失不可恢复；Redis 是热窗口加速层，可随时删掉从 MySQL 重建（`getHistory()` 自带回填）。答案缓存则相反 —— 可重算，所以直接写 Redis。
- **Why self-implemented BM25 instead of Elasticsearch?** → Learning project; understanding the algorithm beats using a black box. k1=1.2, b=0.75 are TREC-verified defaults.
- **Why RRF (k=60) instead of weighted sum for fusion?** → BM25 scores are unbounded, cosine is [-1,1]; weighted sum needs per-query normalization. RRF uses rank only — naturally cross-source comparable.
- **Why CompletableFuture for parallel retrieval?** → Independent I/O; latency = max(BM25, Embedding) instead of sum. Dedicated `ragRetrievalExecutor` avoids blocking Tomcat threads.
- **Why `AtomicReference`/`AtomicBoolean` CAS for the circuit breaker?** → State transitions need compare-and-swap, not just visibility: HALF_OPEN 惊群 is solved by allowing exactly one probe (`compareAndSet(false, true)`), not by `synchronized`.
- **Why TF-IDF fallback is a working implementation, not a TODO?** → "降级方案是可工作的实现而非占位符" — semantically weaker but keeps the RAG pipeline operational when the primary API is down. Evaluated in `testCircuitBreakerFallback`.
- **Why re-verify JWT identity against DB on every request?** → Roles/ban take effect immediately without waiting for token expiry; token claims can't drift from DB state.
- **Why one `AuthenticatedSessionService.resolveOrCreate()` for all session entry points?** → 会话归属校验只有一个入口，不会出现某个 Controller 忘记校验导致越权。
- **Why Small-to-Big chunking (Parent 2000 / Child 500 + 128 overlap)?** → Retrieval precision needs small units; LLM context needs big units. Child drives the whole retrieval path; Parent expansion happens only before prompt assembly.

## Learning Notes

- `D:\AI-Notes\RAG\` — 11 demand packages covering the full RAG pipeline (01-basics through 11-evaluation).
- `D:\AI-Notes\Agent\` — 12 documents on rate limiting, timeout, circuit breaker, async, observability, multi-tenant, canary release, Prompt/Tool versioning.
- `D:\AI-Notes\Agent\three-works-agent\` — 15 project internalization documents: 01 project map, 02-09 module deep-dives (ChatService, HybridRetriever, ToolScheduler, RelevanceGate, ResilientEmbeddingService, SSE, Redis sessions, Evaluation), 10-15 Java fundamentals × project integration (Concurrency, Collections, Spring Boot, Redis, MySQL, JVM).
- `D:\AI-Notes\Java Basics\AI实习面试-项目映射Java基础\Java Basics-Expand\` — 8 source-code reading guides (01-CompletableFuture through 08-ToolScheduler), each analyzing a specific class or pattern in the project.
