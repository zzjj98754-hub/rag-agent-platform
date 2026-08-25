# 智能 Agent 平台需求文档（PRD）

- 版本：v0.1（草案，待评审）
- 日期：2026-08-25
- 状态：开发前评审中
- 基线项目：demo00（Spring Boot 3.3.0，125 个主源文件，41 个测试类）

## 决策记录（2026-08-25 拍板）

| # | 决策项 | 结论 |
|---|---|---|
| D1 | 与 demo00 关系 | **基于 demo00 增量演进**——RAG/Agent/Memory/SSE/治理已有 80% 资产，文档以现有架构为基线写增量 |
| D2 | Spring AI | **全面迁移**——基础抽象全部切换到 Spring AI 1.x（ChatClient/EmbeddingModel/VectorStore/ToolCallingManager/McpClient/ChatMemory），差异化能力以 Advisor/包装层保留 |
| D3 | 文档粒度 | 完整 PRD（含已有能力验收基线 + 新增能力定义） |
| D4 | 部署目标 | **自建 Linux 服务器**（单机 Compose，prod overlay 形态） |

---

## 1. 项目背景与目标

### 1.1 背景

面向企业知识问答与业务自动化场景，构建一个可私有化部署的智能 Agent 平台。平台解决两个核心问题：

1. **知识问答**：企业知识库（文档、制度、技术规范）的检索增强问答，回答必须带可验证引用；
2. **业务自动化**：Agent 通过工具（内部 API、MCP 工具、Skills）自主完成"查订单 + 生成周报"类多步任务，并接受企业级治理（权限、限流、审计、可观测）。

### 1.2 八大能力目标

| # | 能力 | demo00 现状 | 本 PRD 增量 |
|---|---|---|---|
| 1 | 企业知识库增强检索 RAG | 已有（Hybrid+RRF+Rerank+门控+评测） | 向量库升级 Redis Stack、Word/Excel 解析、BM25 竞态修复 |
| 2 | Agent 多工具调用 | 已有（ReAct 循环 + 双协议） | 迁移 ToolCallingManager、新增 Planning、显式 State |
| 3 | 多轮对话 Memory | 已有（Redis 热窗 + MySQL 真相源 + Outbox） | Summary 压缩、适配 ChatMemory 接口 |
| 4 | SSE 流式交互 | 已有（心跳 + 断线重放） | 事件协议扩展（plan/mcp_tool 事件） |
| 5 | **MCP 工具接入** | **缺失** | MCP Client（stdio/HTTP-SSE 传输、tools/list、tools/call） |
| 6 | **Skills 能力抽象** | **缺失** | Skill = Prompt + Tool 组合包，版本化 + RBAC |
| 7 | **Agent Workflow 编排** | **缺失** | DAG 编排引擎，DB 持久化实例，重试/恢复 |
| 8 | 企业级可靠性治理 | 部分（Retry/Timeout/CB/监控已有） | 限流配额、API 幂等、审计日志 |

### 1.3 成功标准（北极星指标）

- 业务域检索 **Recall@3 ≥ 0.95**（业务数据集，继承 2026-06-08 基线 0.99 的通用集水平）
- 生成引用准确率 **≥ 95%**（CitationValidator，编造引用检出率 100%）
- 3 个端到端业务场景（知识问答 / 数据查询+总结 / 多工具协同）自动化成功率 **100%**
- 同步问答 P95 < 3s（mock 上游），SSE 首 token P95 < 2s（上游不阻塞时）

---

## 2. 用户角色与权限（RBAC）

现有 `user` 表 role 为二元（USER/ADMIN），扩展为三级：

| 角色 | 会话问答 | 查看文档 | 管理文档/入库 | Skill 使用/管理 | MCP 工具 | Workflow 执行/管理 | 监控看板 |
|---|---|---|---|---|---|---|---|
| **USER** | ✅（限本人会话） | ✅ | ❌ | ✅使用 / ❌管理 | 受权限矩阵约束 | ✅执行 / ❌管理 | ❌ |
| **ANALYST**（新增） | ✅ | ✅ | ✅ 上传+入库 | ✅使用 / ❌管理 | 受权限矩阵约束 | ✅执行 / ✅查看运行 | ✅ 只读 |
| **ADMIN** | ✅（可访问任意会话，审计留痕） | ✅ | ✅ | ✅全量 | ✅全量 | ✅全量 | ✅ |

- 会话归属：维持 `AuthenticatedSessionService.resolveOrCreate()` 单一入口，所有会话入口（Chat/Agent/Stream/Workflow 触发）强制走该校验。
- 工具权限：现有 `ToolPermissionEvaluator` 扩展为"角色 → 工具集"映射，MCP 工具按 `{server}.{tool}` 命名空间纳入同一矩阵。
- 文档可见性：MVP 全员可见；部门级可见性列为 P2（`document` 表加 owner_dept 字段预留）。

---

## 3. 功能需求

优先级定义：**P0** = 本版本必须交付；**P1** = 本版本应交付，可裁剪；**P2** = 后续版本。

### 3.1 RAG 检索增强（增量）

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-RAG-01 | 迁移 Embedding 抽象到 Spring AI `EmbeddingModel`；`ResilientEmbeddingService` 降级为 @Primary 包装层（缓存+熔断+TF-IDF 降级逻辑全部保留） | P0 | `testCircuitBreakerFallback` 全绿；行为等价 |
| FR-RAG-02 | 向量库升级：实现 `VectorStore` 接口的 **Redis Vector Search** 后端（Redis Stack HNSW），`app.vector-store.backend` 开关切换 in-memory / redis；索引可整体重建（与 MySQL 文档状态一致） | P0 | 181 chunks 重建 < 30s；检索结果与 InMemory 版 Top-K 重合率 ≥ 90% |
| FR-RAG-03 | Hybrid 检索保留自研：RRF 融合 + Small-to-Big + BgeReranker 精排 + RelevanceGate 双阈值门控，封装为 Spring AI 兼容层（自定义 VectorStore 聚合 + Advisor） | P0 | 评测基线不退化：Recall@3 ≥ 0.95 |
| FR-RAG-04 | 文档解析扩展 **Word（docx）/ Excel（xlsx）** 解析器（Apache POI），扩展 `app.document.allowed-extensions` | P1 | 上传 docx/xlsx 成功入库且内容可检索 |
| FR-RAG-05 | 修复 BM25 重建竞态（ReadWriteLock / 双缓冲交换） | P1 | 并发入库+查询压测无异常 |
| FR-RAG-06 | 修复 `SimpleEmbeddingService` 词表重建竞态（volatile 快照交换） | P1 | 同上 |
| FR-RAG-07 | 评测体系固化：业务域数据集接入 `EvalRunnerTest`，CI 每次全量跑检索评测 | P1 | 依赖业务数据集提供（见 §4） |

### 3.2 Agent 与工具调用

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-AGT-01 | 迁移到 Spring AI `ToolCallingManager` + `@Tool` 注解注册（现有 ToolDefinition 策略保留为自定义 ToolCallback 适配）；淘汰 Prompt 协议，OpenAI Function Calling 为唯一默认 | P0 | 现有 Agent 集成测试迁移后全绿 |
| FR-AGT-02 | 新增 **Planning**：复杂任务先经规划步骤分解为子任务列表（plan 事件下发到 SSE），再逐子任务 ReAct 执行 | P1 | "查 3 个部门数据并汇总" 类任务产生可读 plan |
| FR-AGT-03 | 新增显式 **Agent State**：会话级状态机（IDLE→PLANNING→ACTING→COMPLETED/FAILED），状态持久化到 chat_session 扩展字段，断线可恢复 | P1 | 中断后重连能报告当前状态 |
| FR-AGT-04 | 护栏保留：maxSteps、死循环检测、步超时、工具线程池饱和保护全部保留 | P0 | 回归测试全绿 |
| FR-AGT-05 | 工具结果注入防护：LLM 与业务系统之间对工具输出做 schema 校验与长度截断，防止提示注入 | P1 | 恶意工具输出无法改变系统指令行为（安全测试用例） |

### 3.3 Memory 多轮对话

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-MEM-01 | 会话存储适配 Spring AI `ChatMemory` 接口；**Outbox + Redis 投影架构不变**（MySQL 真相源），ChatMemory 作为 facade | P0 | Outbox 集成测试全绿 |
| FR-MEM-02 | 新增 **Summary 压缩**：会话超过滑窗（40 条）时，远期消息压缩为摘要存 `conversation_summary` 表 + 注入后续 Prompt | P0 | 超过 40 轮后，早期关键事实仍可被引用回答 |
| FR-MEM-03 | Summary 触发策略可配置：按消息数 / 按 token 估算双阈值 | P1 | 配置生效可观测 |
| FR-MEM-04 | 本地 fallbackStore 加 TTL 上限（双挂兜底防内存膨胀） | P1 | 压测后内存曲线平稳 |

### 3.4 SSE 流式交互

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-SSE-01 | 迁移上游到 Spring AI 流式（`ChatClient.stream()` / Flux），心跳、重放缓冲（SseReplayBuffer）、背压保护保留自研 | P0 | SSE 集成测试全绿 |
| FR-SSE-02 | 事件协议扩展：新增 `plan`（子任务列表）、`mcp_tool`（MCP 工具调用过程）、`summary`（触发压缩时）事件类型；`done` 事件携带 usage token 统计 | P1 | 前端 RagTrace 能渲染新事件 |
| FR-SSE-03 | `done` 事件补齐 token 用量，供配额扣减与成本统计 | P0 | 与配额系统联动（FR-GOV-01） |

### 3.5 MCP 工具接入（新增）

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-MCP-01 | MCP Client 接入（Spring AI McpClient 生态，Java MCP SDK 底座）：支持 **stdio**（本地子进程）与 **HTTP/SSE**（远程）两种传输 | P0 | 对接一个真实/示例 MCP Server 完成工具调用 |
| FR-MCP-02 | 多 Server 注册：`mcp_server` 表管理连接配置（命令/URL/认证），启动时动态建立连接，tools/list 自动发现 | P0 | 两个 Server 的工具同时出现在 /agent/tools |
| FR-MCP-03 | MCP 工具统一纳入现有 ToolRegistry + 权限矩阵（`{server}.{tool}` 命名空间），走同一调度/护栏 | P0 | MCP 工具与内置工具权限行为一致 |
| FR-MCP-04 | MCP Server 故障隔离：单 Server 断连/超时不拖垮主链路，工具清单降级显示不可用 | P1 | 杀掉 MCP 进程后主对话可用 |
| FR-MCP-05 | MCP 连接参数中的密钥加密存储（对称加密，密钥走环境变量） | P1 | DB 中无明文密钥 |
| FR-MCP-06 | 认证透传：HTTP/SSE 传输支持 Bearer Token 配置（可选自定义 header） | P1 | 对接带鉴权的远程 Server |

### 3.6 Skills 能力抽象（新增）

Skill = **Prompt 模板 + 工具集 + 约束** 的可版本化组合包（企业业务能力的封装单位）。

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-SKL-01 | Skill 模型：code、name、description、版本化 Prompt 模板（变量插值）、绑定工具引用列表、参数 schema | P0 | Skill 可注册/启用/停用 |
| FR-SKL-02 | Skill 运行时：对话中按描述匹配触发（LLM 判定或规则路由），注入系统指令 + 限定工具集执行 | P0 | "用【周报】skill 生成周报" 端到端可用 |
| FR-SKL-03 | Skill 版本管理：`skill_version` 表，发布/回滚，会话记录使用版本号（可追溯） | P1 | 回滚后新会话使用旧版 |
| FR-SKL-04 | Skill 权限：按角色控制可见/可用（纳入 RBAC 矩阵） | P1 | USER 不可见管理类 Skill |
| FR-SKL-05 | Skill 与 MCP 组合：Skill 可绑定 MCP 工具 | P0 | 业务场景 #3 通过（见 §9） |

### 3.7 Agent Workflow 编排（新增）

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-WF-01 | 编排模型：**DAG**，节点类型 = LLM 步骤 / Agent 子任务 / Skill 调用 / 工具调用 / 条件分支 / 并行扇出；定义存 `workflow_definition`（JSON DSL + 版本） | P0 | 设计一个 3 节点流程可运行 |
| FR-WF-02 | 执行引擎：轻量自研 DB-backed 引擎——实例表持久化每一步执行状态，支持重试（指数退避）、超时、失败挂起（WAITING_MANUAL）与手动恢复/取消 | P0 | 节点失败后可重试成功 |
| FR-WF-03 | 触发方式：HTTP 触发（`POST /workflows/{code}/run`，支持异步任务轮询 + SSE 进度流） | P0 | 异步执行可查询进度 |
| FR-WF-04 | 会话集成：Workflow 执行的 LLM 步骤复用 Memory 会话上下文 | P1 | 流程内可引用历史对话 |
| FR-WF-05 | 可视化：MVP 不做画布编辑器，提供 JSON 定义 + 校验 + 运行轨迹（每步输入输出）查看 | P2 | — |
| FR-WF-06 | 编排引擎技术选型：不引入 Flowable/Temporal（重引擎与本期规模不匹配），自研 DAG 引擎 ~千行级；Temporal 列为规模化演进方向 | P0 | 选型评审通过 |

### 3.8 企业级可靠性治理（增量）

| 编号 | 需求 | 优先级 | 验收要点 |
|---|---|---|---|
| FR-GOV-01 | **限流 + 配额**：HTTP 层按用户/角色限流（Redis token bucket，超限 429 + Retry-After）；LLM 调用按日/月 token 配额（FR-SSE-03 联动），超配额降级为拒绝+告警 | P0 | 压测验证限流生效；配额耗尽后调用被拒 |
| FR-GOV-02 | **API 幂等**：POST 非幂等端点（/chat、/agent/chat、workflow run）支持 `Idempotency-Key`，重复请求返回首次结果 | P1 | 同 Key 重复提交只执行一次 |
| FR-GOV-03 | **审计日志**：`audit_log` 表记录敏感操作（登录、文档管理、Skill/Workflow 管理、ADMIN 会话访问、MCP 配置变更），含 traceId 关联 | P0 | 每个敏感操作可追溯（谁/何时/什么/traceId） |
| FR-GOV-04 | 全链路 traceId 贯穿 MCP 调用与 Workflow 步骤（MDC 跨线程/跨服务透传） | P1 | MCP 工具执行日志可关联到原始请求 |
| FR-GOV-05 | 现有 Retry/Timeout/Circuit Breaker/优雅停机/SIGTERM 排干 全部保留 | P0 | 回归测试全绿 |

---

## 4. 非功能需求

| 维度 | 指标 |
|---|---|
| 性能 | 同步问答 P95 < 3s；SSE 首 token P95 < 2s；检索阶段 P95 < 500ms（Redis 向量后端） |
| 可用性 | 单机部署全年 99%；MySQL/Redis 任一故障服务降级可用（现有降级链）；优雅重启不丢在途 SSE |
| 安全 | JWT（HS256，prod 强制环境变量注入）；TLS 终结于 Nginx；密钥不进 Git/镜像；工具输出防注入（FR-AGT-05）；MCP 密钥加密存储（FR-MCP-05）；审计留痕 |
| 可观测 | 全链路 traceId（含 MCP/Workflow）；现有 Prometheus + Grafana 覆盖新增指标（配额余量、限流计数、Workflow 状态分布、MCP 时延） |
| 容量（设计假设） | 注册用户 ≤ 500；日活 ≤ 100；文档 ≤ 5,000 份 / 50 万 chunks；并发 SSE 会话 ≤ 100；LLM QPS ≤ 5 |
| 兼容性 | Java 17 LTS；Spring Boot 升级至 3.5.x（Spring AI 1.x 要求 ≥ 3.4）；MySQL 8.0；Redis 7/8 |

---

## 5. 技术架构与选型决策

### 5.1 总体架构（增量后）

```
浏览器 ── nginx(TLS) ── frontend(静态) + 反向代理
                            │
        ┌───────────────────┴────────────────────────────┐
        │                 Spring Boot App                │
        │  Controller → Service → [RAG 管线]             │
        │                     → [Agent Loop (Spring AI)] │
        │                     → [Workflow 引擎(自研)]     │
        │   Advisor 层：门控/引用/Skill 注入/Summary      │
        └──┬──────────┬──────────┬──────────┬────────────┘
           │          │          │          │
        MySQL 8   Redis 7/8   Redis Stack   MCP Servers
       (真相源)  (热窗/限流/缓存)  (向量 HNSW)   (stdio/HTTP 外部进程)
```

### 5.2 Spring AI 迁移映射（决策 D2 的执行细则）

**原则：基础抽象全面切 Spring AI；差异化能力（本项目面试价值所在）以 Advisor/包装层保留自研。**

| 现有自研组件 | Spring AI 对应 | 处置 |
|---|---|---|
| ExternalLlmClient（RestTemplate） | `ChatClient` / OpenAI-compatible `OpenAiApi` | 迁移 |
| OpenAiStreamingLlmClient | `ChatClient.stream()` + Flux | 迁移 |
| SiliconFlowEmbeddingService / SimpleEmbeddingService | `EmbeddingModel`（自定义实现） | 迁移 |
| ResilientEmbeddingService（缓存+熔断+降级） | @Primary 包装 `EmbeddingModel` | **保留自研** |
| InMemoryVectorStore | `VectorStore` 接口 + Redis Stack 实现（FR-RAG-02） | 迁移+升级 |
| HybridRetriever（双路并行+RRF） | 无现成 API → 自研封装多 VectorStore 聚合 | **保留自研** |
| BgeReranker | 第三方 rerank 集成 | **保留自研** |
| HierarchicalChunker（Parent/Child） | `DocumentSplitter`/TokenTextSplitter + 自研层级映射 | 半迁移 |
| LlmQueryRewriter | ChatClient + Advisor（RewriteQueryAdvisor 思路） | 迁移 |
| ToolRegistry/ToolScheduler/AgentExecutor | `ToolCallingManager` + `@Tool`；护栏（步数/死循环/超时）保留为自定义 `ToolCallingManager` 包装 | 半迁移 |
| AgentLlmClient Prompt 协议 | 原生 Function Calling | **淘汰** |
| ChatSessionService 三级存储 | `ChatMemory` 接口 + 自研 Outbox 实现 | 半迁移 |
| SseReplayBuffer / 心跳 / 背压 | 无对应 | **保留自研** |
| CitationFormatter/Validator、RelevanceGate | Advisor | **保留自研** |

迁移门禁：**行为等价**——现有 41 个测试类全部通过（含 SecurityIntegrationTest 容器环境重跑，见记忆中的未验证清单）；评测基线不退化。

### 5.3 关键选型

| 选型 | 结论 | 理由 |
|---|---|---|
| 向量库 | Redis Stack 向量集（HNSW） | 栈内已有 Redis，投影架构契合，百万级够用；Milvus 运维过重（etcd+MinIO） |
| Embedding | API（SiliconFlow bge-large-zh-v1.5）+ 自托管 BGE-M3 可切换（EmbeddingModel 接口隔离） | 现有 API 已验证；接口层保证可替换 |
| Reranker | bge-reranker-v2-m3（API），降级走粗排 | 现有链路不退化 |
| LLM | OpenAI 兼容（DeepSeek 已配置），对话/规划模型可分离 | 已验证可用 |
| MCP SDK | Spring AI McpClient（底层 Java MCP SDK） | 与 D2 一致，避免重复造协议轮子 |
| Workflow | 自研轻量 DAG 引擎（DB 持久化） | 可控性 + 学习价值；Temporal 列为规模化方向 |
| 限流 | bucket4j + Redis（分布式计数） | Java 生态标准，与现有栈契合 |
| Spring Boot | 3.3.0 → 3.5.x | Spring AI 1.x 硬性要求 |

---

## 6. 接口设计

### 6.1 REST（新增，其余继承现有端点表）

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| GET | `/mcp/servers` / POST / DELETE `/mcp/servers/{id}` | MCP Server 注册管理 | ADMIN |
| GET | `/mcp/servers/{id}/tools` | 单个 Server 工具清单 | 认证 |
| GET | `/skills` | 当前用户可用 Skill 清单 | 认证 |
| POST/PUT | `/admin/skills`、`/admin/skills/{id}` | Skill 定义管理 | ADMIN |
| POST | `/admin/skills/{id}/versions` | 发布新版本 / 回滚 | ADMIN |
| POST | `/admin/skills/{id}/enable` `/disable` | 启停 | ADMIN |
| POST | `/admin/workflows`、PUT `/admin/workflows/{id}` | Workflow 定义（JSON DSL 校验） | ADMIN |
| POST | `/workflows/{code}/run` | 触发执行（支持 Idempotency-Key） | 认证+权限 |
| GET | `/workflows/runs/{id}` | 实例状态 + 运行轨迹 | 认证 |
| POST | `/workflows/runs/{id}/cancel` `/retry` | 取消 / 失败重试 | 认证+权限 |
| GET | `/agent/tools` | 现有端点扩展：包含 MCP 工具（`{server}.{tool}` 命名） | 认证 |

### 6.2 SSE 事件协议（扩展）

现有序列 `session → context(retrieving) → citations → trace → context(generating) → token×N → done` 基础上新增：

| 事件 | 触发时机 | payload 要点 |
|---|---|---|
| `plan` | Agent 规划完成（FR-AGT-02） | 子任务列表（id/title/tool 预期） |
| `mcp_tool` | MCP 工具执行中 | server/tool/状态(开始|完成|失败) |
| `summary` | 触发摘要压缩（FR-MEM-02） | 压缩前后消息范围 |
| `done` 扩展 | 结束 | + usage{tokens} 供配额扣减 |

### 6.3 治理相关协议

- `Idempotency-Key` header：/chat、/agent/chat、/workflows/{code}/run 支持，Redis 存 24h
- 限流：429 + `Retry-After`；配额：403 + `X-Quota-Reset` 提示
- 审计：响应头统一回传 `X-Trace-Id`（现有能力），审计查询入口 `/admin/audit`（ADMIN）

---

## 7. 数据模型（Flyway 增量）

现有 5 表（user/document/chat_session/chat_message/outbox_event）**不动**。新增迁移（V3 起，按里程碑拆分）：

| 表 | 关键字段 | 用途 |
|---|---|---|
| `mcp_server` | name、type(stdio/http_sse)、command_args 或 url、auth_header_enc、enabled、created_by | FR-MCP-02/05 |
| `skill` | code(uk)、name、description、current_version、enabled、owner | FR-SKL-01 |
| `skill_version` | skill_id、version、prompt_template(LONGTEXT)、tool_refs(JSON)、change_log、created_by | FR-SKL-03 |
| `workflow_definition` | code、version、dsl(JSON DAG)、enabled | FR-WF-01 |
| `workflow_instance` | definition_id、version、status(RUNNING/WAITING_MANUAL/SUCCEEDED/FAILED/CANCELLED)、current_node、input/output(JSON)、triggered_by、session_id | FR-WF-02 |
| `workflow_step_execution` | instance_id、node_id、node_type、input/output(JSON)、status、retry_count、started/finished_time | FR-WF-02 轨迹 |
| `conversation_summary` | session_id(uk)、summary(LONGTEXT)、start_message_id、end_message_id | FR-MEM-02 |
| `audit_log` | user_id、action、resource_type/id、detail(JSON)、ip、trace_id、created_at(索引) | FR-GOV-03 |
| `user` 扩展 | role 增加 ANALYST（DDL 修改现有表 + 数据校验） | §2 |

Redis 新增 key 规划：限流桶（`rl:{user}:{api}`）、配额计数（`quota:{user}:{yyyyMM}`）、幂等键（`idem:{key}`）、向量索引（`vec:chunks`）。

---

## 8. 部署架构（自建 Linux 服务器，决策 D4）

### 8.1 目标形态：单机 Compose（沿用现有 base + prod overlay，扩展新服务）

- 服务器：Ubuntu 22.04/24.04，建议 4C8G + 100G SSD（向量索引 + MySQL + 日志）
- 形态：现有 `docker-compose.yml` + `docker-compose.prod.yml` 两文件叠加，`.env.prod` 注入密钥（`${VAR:?}` fail-fast 已有）
- 变更点：
  1. `redis:7-alpine` → **redis-stack-server**（启用向量检索后）；仍走 backend 内网，无端口暴露
  2. MCP stdio 型 Server 以**独立容器**部署（同 network），由 app 通过命令/URL 配置接入
  3. Workflow/Skills 无新增基础设施

### 8.2 生产加固清单（部署章节验收依据）

- [ ] TLS：Nginx 终止 + certbot 自动续期；HTTP 强制跳转
- [ ] 密钥：`JWT_SECRET`/`EMBEDDING_API_KEY`/`AGENT_LLM_API_KEY`/Grafana 密码全部 `.env.prod` 注入，`.env.prod` 权限 600，不入 Git
- [ ] 备份：mysql-backup sidecar（已有）验证恢复演练（RTO ≤ 2h）
- [ ] 日志：现有 50MB/7 天轮转 + `docker logs` 限制；磁盘水位告警（node-exporter → Prometheus → Grafana）
- [ ] 安全基线：SSH 密钥登录、fail2ban、UFW 仅开 22/80/443、非 root 容器（现有 read_only + cap_drop 保留）
- [ ] 单点风险接受声明：单机无高可用，依赖备份恢复；HA 列为 P2（多实例需处理 Outbox 并发与 SSE 粘性路由）

---

## 9. 评测与验收标准

### 9.1 自动化评测（CI 常驻）

| 评测 | 指标 | 基线/目标 |
|---|---|---|
| 检索评测（EvalRunnerTest） | Recall@3 / MRR / NDCG | 通用集基线 Recall@3=0.99；业务集 ≥ 0.95 |
| 生成评测 | Faithfulness / 引用准确率 | 引用编造检出 100% |
| 熔断降级 | testCircuitBreakerFallback | 保持全绿 |
| 压测（scripts/loadtest） | 并发问答 P95、SSE 稳定时长 | P95 < 3s；100 并发 SSE 2h 无泄漏 |

### 9.2 端到端业务场景验收（手动+自动化混合）

| # | 场景 | 涉及能力 | 通过标准 |
|---|---|---|---|
| S1 | 知识问答："XX 制度第 3 条是什么" | RAG + 引用 | 答案正确且引用指向真实文档段落 |
| S2 | 数据查询+总结："查近 7 天订单并生成周报" | Tool Calling + Skill + Planning | 工具调用正确、周报 Skill 输出合规 |
| S3 | 多工具协同：内部 API + 外部 MCP 工具串联 | MCP + Workflow + 权限 | 3 节点 DAG 全绿，审计日志可追溯 |

### 9.3 里程碑验收门禁

每个里程碑出口条件：全量测试绿 + 对应 FR 验收点通过 + 评测基线不退化。

---

## 10. 里程碑规划与风险

### 10.1 里程碑（单人开发参考排期，总 ~9-10 周）

| 里程碑 | 内容 | 出口 | 参考周期 |
|---|---|---|---|
| **M0** | 环境就绪（见启动检查报告 §六 Checklist 10 项） | Docker daemon 启动、LLM Key 验证通过、业务资料齐备 | 0.5 周 |
| **M1** | Spring Boot 3.5.x 升级 + Spring AI 迁移（§5.2 映射表全量执行） | 41 个测试类全绿 + 评测基线不退化 + 容器化验证 | 2 周 |
| **M2** | MCP 接入 + 限流/幂等/审计（FR-MCP-*、FR-GOV-01/02/03） | 对接示例 MCP Server 成功 + 压测限流生效 | 2 周 |
| **M3** | Redis Stack 向量后端 + Summary 压缩 + Skills（FR-RAG-02、FR-MEM-02、FR-SKL-*） | 业务场景 S2 通过 | 2 周 |
| **M4** | Workflow 编排（FR-WF-*）+ Planning/State | 业务场景 S3 通过 | 2.5 周 |
| **M5** | 自建 Linux 生产部署演练（§8.2 清单）+ 压测 + 文档收尾 | 服务器上全场景通过 + 备份恢复演练完成 | 1.5 周 |

### 10.2 风险与依赖

| 风险 | 等级 | 缓解 |
|---|---|---|
| Spring AI 迁移行为等价难保证（流式/降级路径细节多） | 高 | M1 以测试全绿 + 评测基线为硬门禁；分模块小步迁移 |
| MCP Server 无现成实现（需业务方或示例） | 中 | M2 自带示例 Server（stdio 参考实现）先行验证协议 |
| Redis Stack 许可证/镜像选型 | 低 | VectorStore 接口隔离，切换成本一天内 |
| 业务数据集/接口定义未提供（§四资料） | 中 | 用通用集先行，业务集到位后补充评测 |
| 单机部署单点故障 | 中 | 备份恢复演练 + 明确 RTO；HA 列 P2 |
| LLM 成本失控（配额缺失期间） | 高 | M2 配额功能优先于大规模开放使用 |

---

## 附录 A：术语表

| 术语 | 定义 |
|---|---|
| RRF | Reciprocal Rank Fusion，秩融合算法（k=60），本项目 Hybrid 检索融合手段 |
| Small-to-Big | Child(500字) 检索 + Parent(2000字) 进 Prompt 的分层 chunk 策略 |
| Outbox | 事务性发件箱：业务与 Redis 投影解耦的最终一致模式 |
| MCP | Model Context Protocol，模型上下文协议（Anthropic 发起，工具/资源接入标准） |
| Skill | 本平台定义：Prompt 模板 + 工具集 + 约束的版本化能力包 |
| Workflow | DAG 形式的多节点 Agent 任务编排定义 |
| ReAct | Reason + Act 推理循环范式 |
| RAGAS | 检索增强生成评测框架（本项目用关键词近似实现） |

## 附录 B：与启动检查报告的关系

本 PRD §3-8 的需求映射到启动检查报告 §五"优先补充"清单：

1. MCP Client → FR-MCP-01~06 ✅
2. 限流+配额 → FR-GOV-01 ✅
3. Skills 抽象 → FR-SKL-01~05 ✅
4. Summary 压缩 → FR-MEM-02/03 ✅
5. Workflow 编排 → FR-WF-01~06 ✅
6. 向量库升级 → FR-RAG-02 ✅
7. Word/Excel 解析 → FR-RAG-04 ✅
8. Agent Planning/State → FR-AGT-02/03 ✅
9. API 幂等/审计 → FR-GOV-02/03 ✅
10. Spring AI 决策（D2）→ §5.2 映射表 ✅
