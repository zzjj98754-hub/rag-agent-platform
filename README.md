# RAG Agent Platform

面向企业知识问答与业务自动化的 RAG Agent 平台。后端基于 Spring Boot 3.5 /
Java 17，前端基于 React 18 / TypeScript。项目保留自研检索算法，并把
流式输出、Agent Loop、认证、持久化、可观测性和容器部署接成可运行链路。

## 项目亮点（与实际代码一致）

### 1. Hybrid RAG 检索系统

- 自研 BM25 关键词检索与 Embedding 向量检索并行召回；
- 使用 RRF 按排名融合，避免 BM25 分数与余弦相似度直接混算；
- BGE Cross-Encoder 精排，失败时保留 RRF 排名；
- `RerankResult` 显式记录 `BGE` / `RRF` 评分体系，相关性门控分别使用
  `0.35` / `0.01`，不再混用阈值；
- Small-to-Big：Child 参与 BM25、Vector、RRF、Rerank，命中后才展开 Parent
  作为 LLM Context；
- SiliconFlow Embedding 不可用时自动使用本地 TF-IDF Embedding。文档状态只有在
  `chunkCount > 0`、BM25 非空且 `vectorCount == chunkCount` 时才为 `READY`，UI
  展示真实的 `embeddingSource`（`siliconflow` 或 `local`）。

真实入库顺序：

```text
Document -> Child/Parent Chunk -> BM25 Index -> Embedding -> VectorStore
```

启动和手工入库后执行完整性检查；存在文档但 JVM 向量库为空时输出 WARNING。
当前 `InMemoryVectorStore` 是小规模演示用 O(N) 余弦检索，Redis 中的 JSON 仅是
可观测快照，不宣称可以恢复向量索引。生产扩展点是 `VectorStore` 接口，可替换
Redis Stack、pgvector 或 Milvus。

### 2. Agent Tool Calling 框架

- LLM 决策 -> Function Call -> Tool Registry -> Tool Executor -> Observation ->
  再次调用 LLM -> Final Answer；
- `AgentContext` 维护消息历史、工具轨迹与步骤，最大步骤默认 5；
- 连续相同工具和参数达到 3 次即中断，防止死循环；
- JWT 中的角色经 RBAC 映射到工具权限，请求体不能自报角色；
- `SearchTool` 复用 `HybridRetriever`，实际执行 BM25 + Vector + RRF + Rerank，
  不再绕过完整检索链；
- 支持 OpenAI-compatible Function Calling；开启后缺少 URL、模型或 API Key 会
  在启动阶段失败，而不是运行时静默回退。

前端展示的是项目自身的“Agent 执行轨迹审计”，不是 LangSmith 集成。

### 3. Streaming AI Chat

- `WebClient + Flux<String>` 直接解析 OpenAI-compatible Streaming API 的增量
  token，不先生成整段答案再切割；
- Spring MVC 使用 `SseEmitter` 输出 `session/context/citations/trace/token/done`
  事件；独立有界线程池隔离慢客户端发送；
- 支持 heartbeat、event id、retry、断开清理和 `Last-Event-ID` 短期事件重放；
- 重放缓冲位于单 JVM 内存，只解决短暂连接抖动。若上游生成已经取消且没有终态，
  服务端明确返回不可继续提示，不伪装成跨节点、可续传的消息系统。

### 4. 企业级工程能力

- Spring Security + JWT + ADMIN/USER/GUEST RBAC；
- MySQL 保存完整历史，Redis 保存最近热窗口；
- Transactional Outbox：业务数据与 `outbox_event` 同事务写入，Relay 异步、幂等
  投影 Redis，失败退避重试，达到上限转 `DEAD`。缓存未及时更新时读取侧从 MySQL
  回填，实现最终一致性；
- MDC TraceId、结构化滚动日志、Micrometer/Prometheus 指标；
- Grafana Dashboard 展示 HTTP、RAG、LLM 延迟、Token 和错误数；
- 后端与前端多阶段镜像，Compose 包含 Nginx、Frontend、Backend、MySQL、Redis、
  Prometheus、Grafana；
- RAG、Agent、SSE、认证、持久化和配置约束均有自动化测试。

## 核心请求链路

```text
HTTP/JWT/MDC
  -> Controller（参数与协议适配）
  -> ChatService / StreamingChatService / AgentExecutor
  -> Query Rewrite
  -> HybridRetriever
       -> BM25(child) -----------+
       -> Embedding + Vector(child)+-> RRF -> BGE/RRF Rerank(child)
       -> Parent expansion -> RelevanceGate
  -> Prompt + Citation
  -> LLM / StreamingLlmClient
  -> Citation 校验与审计
  -> MySQL + Outbox -> Redis hot window
  -> JSON 或 SSE
```

## 运行方式

### 本地开发

要求 Java 17、MySQL 8；Redis 不可用时会降级。默认 profile 为 `dev`，只有
`dev` / `loadtest` 才注册 Mock LLM Controller。

```powershell
$env:JAVA_HOME = "D:\dev\jdks"
$env:APP_SECURITY_BOOTSTRAP_ADMIN_ENABLED = "true"
$env:APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD = "local-admin-change-me"
.\mvnw.cmd spring-boot:run
```

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。开发代理将 `/api` 转发到 `localhost:9090`，CORS
默认允许 `localhost:5173`。

### 配置真实 Agent / Streaming LLM

所有密钥只通过环境变量注入：

```text
AGENT_FUNCTION_CALLING_ENABLED=true
AGENT_LLM_URL=https://api.deepseek.com/chat/completions
AGENT_LLM_API_KEY=<key>
AGENT_LLM_MODEL=<支持 function calling 的模型>
AGENT_LLM_THINKING_MODE=disabled

STREAMING_LLM_URL=https://api.deepseek.com/chat/completions
STREAMING_LLM_API_KEY=<key>
STREAMING_LLM_MODEL=<支持 stream=true 的模型>
```

`dev` 可使用内置 Mock 做无 Key 协议演示；`prod` 不注册 Mock Controller，并要求
显式提供 LLM 配置。真实密钥不要写入配置文件或提交 Git。

### Docker Compose

**本机/开发模式(一条命令,零配置,内置 dev 默认值):**

```bash
docker compose up -d --build
```

服务入口:

| 服务 | 默认地址 |
|---|---|
| Nginx / Web UI / API | `http://localhost:8080` |
| Prometheus | `http://localhost:9091`(仅 dev 发布) |
| Grafana | `http://localhost:3000`(仅 dev 发布,凭据 `admin/admin`,改 `.env`) |

请求路径是 `Nginx -> frontend`，`/api/*` 则由 Nginx 去掉 `/api` 前缀后代理到
backend；SSE 代理关闭缓冲。MySQL、Redis 和 Backend 默认只暴露在 Compose 内网。

**生产模式(单机 Linux VPS,base + overlay):**

```bash
cp .env.prod.example .env.prod    # 填写全部 REPLACE 项
bash scripts/gen-grafana-htpasswd.sh   # 生成 Grafana 第一道门凭据
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    --env-file .env.prod --profile prod up -d --build
# 服务器防火墙只开放 80/tcp(+SSH);全部端口随 overlay 收敛
```

生产形态差异:`SPRING_PROFILES_ACTIVE=prod`、全部密钥 `${VAR:?}` fail-fast(缺失即拒绝启动,
绝不带默认值上线)、prometheus/grafana 不再发布主机端口、Grafana 经
`http://<host>/grafana` 子路径访问(basic_auth + Grafana 自身登录双门)、
Redis `requirepass`、全服务非 root + `read_only`/`cap_drop` 加固、资源限额、日志轮转、
每日 MySQL 备份 sidecar 与 node-exporter 主机指标(需 Docker Compose ≥ 2.24)。

镜像均为本地构建,不依赖任何镜像仓库。基础镜像固定 minor 版本;digest 级固定留作后续增强。

## 页面与接口

| 页面 | 能力 |
|---|---|
| `/chat` | 多轮问答、Markdown、真实 Token SSE、引用与 RAG 阶段耗时 |
| `/knowledge` | PDF/MD/TXT 上传、Chunk/Vector/Embedding 来源状态、删除 |
| `/agent` | Function Calling 输入、Observation、步骤与最终回答审计 |
| `/login` | JWT 登录与 Axios/SSE 自动携带 Authorization |

主要接口：`POST /auth/login`、`POST /chat`、`GET /chat/stream`、
`POST /agent/chat`、`GET /agent/chat/stream`、`GET /agent/tools`、
`GET/POST/DELETE /admin/documents`。

学习型异步索引入口为 `POST /admin/documents/upload/async`（multipart `file`），随后用
`GET /admin/documents/status/{taskId}` 轮询。它演示 `Document(PROCESSING) + Outbox`
同事务写入；`INDEX_KAFKA_ENABLED=true` 时 Relay 发布到 Kafka、消费者完成切分/向量化，
否则在本地消费者执行，便于零中间件启动。父/子 Chunk 关系同时保存在 `document_chunk`。
Graph 演示复用持久化 Workflow checkpoint：`GET /workflows/approvals` 查看高风险暂停项，
`POST /workflows/runs/{id}/approval` body `{"approved":true}` 审批并恢复，`false` 拒绝。

固定的 RAG 审批 Graph 位于 `POST /graph/rag/runs`（body: `{"query":"...","highRisk":true}`）；
它按“检索 → 方案 → 风险分支 → 审批/工具 → 验证”保存 `RagGraphState` checkpoint。使用
`GET /graph/rag/approvals` 查看待审批项，再调用 `POST /graph/rag/runs/{id}/approval`。
`POST /admin/evaluations/retrieval` 会执行内置 30 条检索回归问题并输出 BM25 与 Hybrid 的
Recall@3、MRR@3、NDCG@3。

`GET /agent/chat/stream` 为认证后的 Agent SSE 入口；当动态 MCP 工具执行时会输出
`mcp_tool` 事件（server、tool、STARTED/SUCCEEDED/FAILED、elapsedMs 与脱敏错误）。
普通 RAG SSE 的 `done` 事件包括 `firstTokenMs`、`totalElapsedMs` 与 Token 统计；
当上游未提供 usage 时会明确写入 `tokenAccounting=estimated`。

## 数据职责与一致性

| 存储 | 数据 | 原因 |
|---|---|---|
| MySQL | 用户、文档元数据、会话、完整消息、Outbox | 可审计、长期持久化、事务一致性 |
| Redis | 最近 20 轮（默认 40 条消息）上下文、会话元数据、回答缓存 | 高频低延迟访问与 TTL |
| JVM | BM25、Local Embedding 词表、演示向量索引、SSE 重放 | 小规模低成本演示；重启可由文档重建 |

Outbox 方案没有宣称解决所有分布式一致性问题：当前是单 Relay 轮询；生产多副本需
增加事件抢占/分区、告警、DEAD 事件人工补偿和长期清理策略。

## 测试

```powershell
.\mvnw.cmd test
cd frontend
npm run build
```

关键断言包括：本地降级后 VectorStore/BM25 非空、Hybrid 两路有结果、RRF 降级
不会被 BGE 阈值误清空、Agent 完整 Tool Calling/步数/权限、SSE token 顺序/error/
disconnect/Last-Event-ID，以及 MySQL 事务内写入 Outbox。

压测和故障演练见 [docs/load-testing.md](docs/load-testing.md)。

## 截图

截图约定见 [docs/screenshots/README.md](docs/screenshots/README.md)：
`chat.png`、`knowledge.png`、`agent.png`。仓库只声明已有截图文件，未提供的截图
不会在 README 中伪造展示。

## 已知边界

- In-memory 向量检索是 O(N)，适合当前 181 chunks，不适合百万级数据；
- Local TF-IDF 是可运行降级，不等同于语义模型的召回质量；
- BGE 不可用时采用 RRF 分数与独立阈值，属于可用性降级；
- SSE 重放不是 Kafka/Redis Stream，也不能跨实例续传上游生成；
- Citation 功能是编号校验与审计，不能承诺完全消除引用幻觉；
- Agent 页面是内部轨迹审计，不是 LangSmith 实时 Trace。

## 更多文档

- [压测与故障演练](docs/load-testing.md)
- [本机预生产验收手册](docs/preproduction-acceptance.md)
- [部署说明](docs/deployment.md)
- [平台架构说明](docs/architecture.md)
- [生产验收报告](docs/production-acceptance-report.md)
- [已知限制](docs/known-limitations.md)
- [前端工程说明](frontend/README.md)
