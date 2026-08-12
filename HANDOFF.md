# 项目交接文档

> 更新时间：2026-07-20  
> 项目目录：`D:\dev\code\java\demo00`  
> 技术栈：Spring Boot 3.3 / Java 17 / MyBatis / MySQL / Redis / React 18 / TypeScript / Vite

## 1. 当前整体任务

该项目原本是学习型 RAG Demo，本轮目标是在不推翻既有分层架构的前提下，将其提升为可用于 Java 后端校招和 AI 应用开发岗位面试展示的 RAG Agent 平台。

重点不是继续增加页面，而是保证简历描述与代码真实一致，具体包含：

1. 修复 Hybrid RAG 向量链路，确保外部 Embedding 不可用时仍有可运行的本地向量检索。
2. 修复 BGE Reranker 降级时混用 RRF 分数和 BGE 阈值的问题。
3. 让 Child 检索、Parent 展开这一 Small-to-Big 链路真实成立。
4. 让 Agent 搜索工具复用完整 HybridRetriever。
5. 隔离开发 Mock LLM，完善 OpenAI-compatible Function Calling 配置校验。
6. 增强真实 Streaming SSE 的心跳、事件编号、重连、断开清理和慢客户端隔离。
7. 使用 Transactional Outbox 改善 MySQL 与 Redis 的最终一致性。
8. 补齐前端、Nginx、Prometheus、Grafana 和 Docker Compose。
9. 增加关键自动化测试，重写 README、简历描述和面试问答，删除夸大描述。

项目必须继续遵守：Controller 不写业务逻辑、配置集中在 YAML、接口隔离、单一职责、统一异常处理、不硬编码密钥。

## 2. 当前运行状态

- 当前没有后端进程监听 `9090`，下次需要重新启动。
- 本机 MySQL 8 在本轮测试期间可用，Flyway 已能执行 V1/V2 migration。
- Redis 不可用时项目有本地降级，但 Outbox 到 Redis 的真实投影仍建议在 Redis 可用时做一次端到端验证。
- 本机没有可用的 Docker CLI，因此 Compose、Nginx、Prometheus、Grafana 只完成静态配置与文件校验，尚未在本机实际拉起整套容器。
- 用户曾在系统环境变量中配置真实 Agent LLM Key。交接文档不会记录任何密钥；新会话必须自行检查环境变量是否仍存在，不得把密钥写入仓库。
- Git 工作区非常脏，包含本轮之前用户已有的大量修改和未跟踪文件。本轮没有执行 reset、checkout、clean、stage 或 commit。不要把所有变更误认为都由最后一轮产生，也不要擅自清理。

## 3. 已完成内容

### 3.1 Hybrid RAG 与文档入库

已将入库顺序调整为：

```text
Document
  -> Parent/Child Chunk
  -> BM25 Index
  -> Embedding
  -> VectorStore
  -> 完整性检查
```

关键文件：

- `src/main/java/com/example/demo/service/EmbeddingService.java`
- `src/main/java/com/example/demo/service/ResilientEmbeddingService.java`
- `src/main/java/com/example/demo/service/SimpleEmbeddingService.java`
- `src/main/java/com/example/demo/service/SiliconFlowEmbeddingService.java`
- `src/main/java/com/example/demo/service/DocumentIngestionService.java`
- `src/main/java/com/example/demo/service/DocumentRegistry.java`
- `src/main/java/com/example/demo/service/VectorStore.java`
- `src/main/java/com/example/demo/service/InMemoryVectorStore.java`

实现要点：

- `EmbeddingService` 统一返回 `EmbeddingVector`，包含向量、模型和 `EmbeddingSource`。
- Embedding 来源明确区分 `siliconflow`、`local`、`unknown`。
- SiliconFlow Key 为空或外部 API 失败时使用本地 TF-IDF Embedding。
- 本地词表重建会改变向量维度，因此小规模知识库会全量重新嵌入，避免新旧空间混用。
- 文档快照记录 `chunkCount`、`vectorCount`、`embeddingSource`。
- 只有 Chunk、BM25、Vector 都完整时文档才显示 `READY`。
- 若 MySQL 存在文档但当前 JVM VectorStore 为空，启动时输出 WARNING。
- 当前实测无外部 Embedding Key 时得到：`15 documents / 181 chunks / 181 vectors / BM25 181`，来源为 `local`。
- Redis 中的向量 JSON 被准确描述为可观测快照；当前代码没有从 Redis 恢复内存向量，README 不再宣称“Redis 向量恢复”。

### 3.2 Rerank 评分体系隔离

关键文件：

- `src/main/java/com/example/demo/rag/RerankResult.java`
- `src/main/java/com/example/demo/rag/SearchResult.java`
- `src/main/java/com/example/demo/rag/BgeReranker.java`
- `src/main/java/com/example/demo/rag/RelevanceGate.java`

实现要点：

- `RerankResult` 包含 `score` 和 `ScoreType(BGE/RRF)`。
- BGE 成功时结果类型为 BGE；失败时保留 RRF 评分，不再用 RRF 分数伪装 BGE。
- Gate 阈值分别为：BGE `0.35`，RRF `0.01`。
- 同一批结果如果混合 BGE/RRF 类型，Gate 直接抛出异常，防止静默误判。

### 3.3 Small-to-Big 检索

关键文件：`src/main/java/com/example/demo/rag/HybridRetriever.java`。

现在真实流程是：

```text
BM25(child) + Vector(child)
  -> RRF(child)
  -> BGE/RRF Rerank(child)
  -> Parent 信息展开
  -> Parent 去重
  -> Prompt Context
```

Parent 不再提前进入 RRF 或 Reranker。`InMemoryVectorStore` 也不再在向量召回阶段按 Parent 去重。

### 3.4 Agent 搜索与 Function Calling

关键文件：

- `src/main/java/com/example/demo/agent/tool/SearchTool.java`
- `src/main/java/com/example/demo/agent/AgentExecutor.java`
- `src/main/java/com/example/demo/agent/OpenAiFunctionCallingClient.java`
- `src/main/java/com/example/demo/controller/MockExternalLlmController.java`

实现要点：

- `SearchTool` 直接调用 `HybridRetriever`，实际走 BM25、Vector、RRF、Rerank、Parent 展开。
- SearchTool 没有会话历史参数，因此不要声称它执行 Chat 层的多轮 Query Rewrite。
- Agent Loop 已包含 LLM 决策、Tool Call、Observation 回灌、继续推理和 Final Answer。
- 最大步骤默认 5，连续相同工具与参数 3 次触发循环检测。
- Tool 权限来自 JWT SecurityContext 中的角色，不信任请求体 role。
- `OpenAiFunctionCallingClient` 在 `enabled=true` 且 URL、Key 或模型缺失时启动失败。
- Mock Controller 只在 `dev` / `loadtest` Profile 注册，`prod` 不注册。
- 配置位于 `application.yml`、`application-dev.yml`、`application-prod.yml`。

### 3.5 SSE 可靠性

关键文件：

- `src/main/java/com/example/demo/service/StreamingChatService.java`
- `src/main/java/com/example/demo/service/SseReplayBuffer.java`
- `src/main/java/com/example/demo/service/SseEmitterFactory.java`
- `src/main/java/com/example/demo/config/SseConfig.java`
- `src/main/java/com/example/demo/controller/StreamController.java`
- `frontend/src/hooks/useSSE.ts`

实现要点：

- 上游仍是 `StreamingLlmClient -> WebClient -> Flux<String>` 的真实增量 Token。
- 每个 SSE 事件包含 `id` 和 `retry`。
- 增加 heartbeat。
- `Last-Event-ID` 可重放单 JVM 内存缓冲中尚存的事件。
- 慢客户端发送通过有界 `sseSendExecutor` 隔离，Token Flux 在该 Scheduler 上消费。
- timeout、error、completion 都会取消上游订阅和 heartbeat，并释放连接上下文。
- 前端网络错误时不立即关闭 EventSource，而是允许 Polyfill 按 retry 自动携带 Last-Event-ID 重连。
- 当前重放不是 Kafka/Redis Stream。如果原上游已取消且缓冲没有终态，会发送 `reconnect` 事件提示用户重新提问，不伪装成跨实例可续传生成。

### 3.6 MySQL/Redis Outbox

关键文件：

- `src/main/resources/db/migration/V2__create_outbox_event.sql`
- `src/main/java/com/example/demo/persistence/entity/OutboxEventEntity.java`
- `src/main/java/com/example/demo/persistence/mapper/OutboxEventMapper.java`
- `src/main/resources/mapper/OutboxEventMapper.xml`
- `src/main/java/com/example/demo/persistence/service/OutboxEventService.java`
- `src/main/java/com/example/demo/persistence/service/OutboxRelay.java`
- `src/main/java/com/example/demo/service/ChatCacheProjector.java`
- `src/main/java/com/example/demo/persistence/service/ChatHistoryPersistenceService.java`
- `src/main/java/com/example/demo/service/ChatSessionService.java`

实现要点：

- 会话/消息业务数据与 Outbox Event 在同一个 MySQL 事务中写入。
- Relay 定时轮询 PENDING 事件并异步投影 Redis。
- Redis Lua 脚本使用 event ID 做幂等去重，并原子执行 LPUSH/LTRIM/EXPIRE/元数据更新。
- 投影失败会增加 retry count、延迟重试，达到上限转为 `DEAD`。
- Redis 未更新或未命中时仍可从 MySQL 读取并回填。
- 当前是单 Relay 模型；还没有实现多实例抢占、DEAD 告警、归档清理。

### 3.7 前端与工程化

已完成：

- 知识库页面展示真实 `vectorCount` 和 `embeddingSource`。
- Agent 页面文案改为“Agent 执行轨迹审计”，不再类比或声称 LangSmith。
- `frontend/Dockerfile` 使用 Node 构建、Nginx 运行的多阶段镜像。
- `docker-compose.yml` 包含 frontend、edge nginx、backend、MySQL、Redis、Prometheus、Grafana。
- Edge Nginx 将 `/api/*` 去前缀后代理到 backend，并关闭 SSE buffering。
- Prometheus 抓取 `/actuator/prometheus`。
- Grafana 自动加载 `docker/grafana/dashboards/rag-agent-overview.json`。
- Dashboard 覆盖 HTTP latency、RAG latency、LLM latency、Token、Error count、Retrieval/Rerank latency。

### 3.8 文档与简历

- 根目录 `README.md` 已重写，只描述真实实现，并明确已知边界。
- `docs/resume-and-interview.md` 包含可直接使用的简历项目描述和 12 个高频追问标准回答。
- 已删除或改写以下夸大描述：
  - LangSmith 实时 Agent Trace -> Agent 执行轨迹审计展示。
  - Redis 向量恢复 -> Redis 缓存与会话状态管理；向量由文档重建。
  - 防止引用幻觉 -> Citation 编号校验与审计。

## 4. 自动化验证结果

### 后端

最后一次完整执行：

```powershell
$env:JAVA_HOME='D:\dev\jdks'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
```

结果：

```text
Tests run: 73, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖内容包括：

- Local Embedding 后 VectorStore > 0。
- BM25 与 Vector 两路都能召回。
- RRF 降级不会被 BGE 阈值清空。
- Child 检索后 Parent 展开。
- Agent Tool Calling、最大步骤、循环检测、权限。
- Function Calling 缺 Key 时 fail-fast。
- Mock Profile 隔离。
- SSE Token 顺序、error、disconnect、Last-Event-ID replay。
- JWT/SSE 安全集成。
- Outbox 成功投影和失败重试。
- Flyway/MyBatis/MySQL 业务持久化。

### 前端

最后一次执行：

```powershell
cd frontend
npm run build
```

结果：TypeScript 与 Vite 生产构建成功。

### 静态配置

- Grafana Dashboard JSON 可解析。
- Outbox MyBatis XML 可解析，MyBatis mapper 测试通过。
- 配置质量测试确认 Java `@Value` 不含隐藏默认值，源码中没有硬编码 API Key。
- Docker CLI 不存在，所以尚未执行 `docker compose config` 和容器运行验证。

## 5. 现存卡点与已知边界

### P1：Docker 尚未真实启动验证

原因：当前主机没有 Docker CLI。

需要在有 Docker 的环境执行：

```bash
cp .env.example .env
docker compose config
docker compose up --build
docker compose ps
```

随后验证：

- `http://localhost:8080`
- `http://localhost:8080/api/actuator/health`
- `http://localhost:9091`
- `http://localhost:3000`
- Nginx 下 SSE 是否实时、不缓冲。

### P1：真实 DeepSeek/其他模型端到端验证

真实 Key 不在仓库。需要确认环境变量：

```text
AGENT_FUNCTION_CALLING_ENABLED=true
AGENT_LLM_URL=<OpenAI-compatible chat completions URL>
AGENT_LLM_API_KEY=<secret>
AGENT_LLM_MODEL=<支持 function calling 的模型>
AGENT_LLM_THINKING_MODE=disabled

STREAMING_LLM_URL=<OpenAI-compatible streaming URL>
STREAMING_LLM_API_KEY=<secret>
STREAMING_LLM_MODEL=<支持 stream=true 的模型>
```

先确认供应商当前模型名和 Function Calling/Thinking 参数格式，避免使用不存在或不兼容的模型名。不要在日志、README、命令回显或提交中暴露 Key。

### P1：Outbox 生产化边界

当前单 Relay 足以展示最终一致性思想，但生产多副本仍需要：

- 原子 claim 或 `FOR UPDATE SKIP LOCKED`。
- DEAD 事件告警和人工补偿接口。
- 已处理事件归档/清理。
- Relay 延迟、失败数、DEAD 数量监控。
- 明确读己之写策略。当前存在最长约一个轮询间隔的 Redis 最终一致窗口；缓存未命中会回源 MySQL，但缓存已有旧数据时短时间内可能读到旧窗口。

### P2：向量存储扩展

当前 `InMemoryVectorStore` 是 O(N) 余弦扫描，适合当前 181 chunks。生产化应基于现有 `VectorStore` 接口实现 Redis Stack HNSW、pgvector 或 Milvus，并增加持久索引恢复、模型版本迁移和灰度重建。

### P2：SSE 跨实例续传

当前 replay buffer 是单 JVM 内存。跨实例或进程重启续传需要生成任务与连接解耦，并使用 Redis Stream/Kafka 等持久事件日志。不要把现实现描述为完整断点续传。

### P2：项目截图

截图目录和命名约定已经存在，但没有在本轮伪造截图。需要实际启动前后端后补充：

- `docs/screenshots/chat.png`
- `docs/screenshots/knowledge.png`
- `docs/screenshots/agent.png`

### Git 状态

工作区包含大量 modified/untracked 文件，并包含运行日志。继续前务必先查看：

```powershell
git status --short
git diff --stat
```

不要执行 `git reset --hard`、`git checkout -- .` 或 `git clean -fd`。如果需要提交，应先区分用户原有变更、本轮功能变更和运行生成物，再分组审查。

## 6. 后续执行计划

建议按以下顺序继续：

1. 在有 Docker 的环境运行 `docker compose config` 和 `docker compose up --build`。
2. 通过 Nginx 完成登录、文档上传、聊天 SSE、Agent Function Calling 的浏览器端到端验证。
3. 验证知识库页面显示 `181 chunks / 181 vectors / local 或 siliconflow`，而不是只看 READY。
4. 启用真实模型，分别验证：普通问答、流式 Token、一次工具调用、多工具步骤、最大步骤停止。
5. 暂停 Redis，写入消息后恢复 Redis，确认 Outbox retry 后状态变为 PROCESSED、热窗口可读取。
6. 在 Prometheus 查询实际指标名，在 Grafana 确认六个 Panel 都有数据。
7. 执行 `scripts` 与 `docs/load-testing.md` 中的 100 并发、Embedding 失败和 LLM 超时场景。
8. 补齐真实项目截图。
9. 重新执行后端 73 个测试和前端 build。
10. 审核 Git diff，排除日志、密钥、`.env`、`node_modules`、`target` 后再按功能拆分提交。

## 7. 已踩过的坑与规避方案

### 7.1 PowerShell Maven 测试列表需要整体加引号

错误形式：

```powershell
.\mvnw.cmd test -Dtest=A,B,C
```

PowerShell 会把逗号解析为参数列表。正确形式：

```powershell
.\mvnw.cmd test '-Dtest=A,B,C'
```

### 7.2 多构造器 Spring Bean 无默认构造器

`SseReplayBuffer` 同时存在生产构造器和测试构造器时，直接标注 Component 导致 Spring 无法选择构造器。最终方案是移除 Component，在 `SseConfig` 中显式创建 Bean，同时保留测试构造器。

不要通过字段 `@Autowired` 规避：项目配置质量测试明确禁止字段注入，并要求构造器注入。

### 7.3 `@Value` 隐藏默认值会使配置质量测试失败

项目要求所有默认值进入 `application.yml`。不要写：

```java
@Value("${some.key:default}")
```

应在 YAML 声明默认值，Java 使用：

```java
@Value("${some.key}")
```

### 7.4 Local TF-IDF 词表重建会改变维度

增量加入文档后如果只嵌入新 Chunk，新旧向量可能维度或词表语义不一致。当前小规模方案是在词表重建后全量重新嵌入所有 Child。若未来切换稳定维度的外部模型，可按模型版本设计增量索引。

### 7.5 查询向量必须与存量向量来源一致

如果文档因 API 失败使用 local 向量，而查询后来使用 siliconflow 向量，两者维度和空间不兼容。`HybridRetriever` 会检查 VectorStore 的 embedding source，并要求查询使用同一来源。新增向量后端时必须保留来源、模型和维度校验。

### 7.6 BGE 和 RRF 分数绝不能共用阈值

BGE 常用阈值约 0.35，RRF 分数约 0.03。降级时只返回 double 会导致所有结果被 Gate 清空。必须保留 `ScoreType`，并禁止同一结果列表混合类型。

### 7.7 Parent 不能过早进入 Rerank

若 RRF 或 Reranker 使用 Parent，所谓 Small-to-Big 只是文案。Child 必须贯穿 BM25、Vector、RRF、Rerank，最后一步才展开 Parent。

### 7.8 EventSource 网络错误不应立即 close

浏览器 EventSource 自带 retry/Last-Event-ID 语义。原实现收到 native `onerror` 就 close，导致永远不能重连。现在 native error 只更新 UI 为 reconnecting；服务端业务 `error` 或 `reconnect` 事件才终止。

### 7.9 `Flux` 线程直接调用 `SseEmitter.send` 会被慢客户端阻塞

Token 流必须 `publishOn` 独立有界发送 Scheduler；heartbeat 也提交到发送池。发送操作按 emitter monitor 串行化，避免 Token 与 heartbeat 交叉写坏事件顺序。

### 7.10 Outbox 幂等标记必须和 Redis 更新在同一个 Lua 脚本

如果先 `SETNX processed` 再单独 LPUSH，进程在两步之间崩溃会永久丢失投影。当前 Lua 脚本将去重标记和缓存写入原子执行；MySQL markProcessed 失败时重试也不会重复 LPUSH。

### 7.11 不要把测试日志中的 ERROR 当成测试失败

SSE error、LLM timeout、Outbox retry 测试会故意打印 ERROR/WARN。判断结果以 Maven 最终的 `Failures/Errors` 和退出码为准。

### 7.12 Docker Compose 的 dev/prod Profile 含义

Compose 默认 `SPRING_PROFILES_ACTIVE=dev`，目的是无真实 Key 时仍能使用 Mock 演示。正式部署必须改为 `prod`，提供真实 LLM URL/模型/Key；prod 不注册 Mock Controller。

## 8. 新会话快速开始

建议新会话先执行：

```powershell
Set-Location D:\dev\code\java\demo00
Get-Content AGENTS.md
Get-Content HANDOFF.md
git status --short
$env:JAVA_HOME='D:\dev\jdks'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
```

启动本地后端：

```powershell
$env:APP_SECURITY_BOOTSTRAP_ADMIN_ENABLED='true'
$env:APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD='local-admin-change-me'
.\mvnw.cmd spring-boot:run
```

启动前端：

```powershell
Set-Location D:\dev\code\java\demo00\frontend
npm install
npm run dev
```

最后提醒：不要从聊天历史或日志复制真实 API Key；只从安全环境变量读取，并在输出命令结果前确认不会回显秘密。
