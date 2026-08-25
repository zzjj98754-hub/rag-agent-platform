# 平台架构说明

请求经 JWT、RBAC、限流、配额、幂等与 TraceId 后进入 Chat、Agent、MCP、Skill 或 Workflow 入口。RAG 保留自研 BM25、向量召回、RRF、Rerank、Small-to-Big 与 Citation；Spring AI 负责真实 ChatClient、EmbeddingModel 和动态 ToolCallback 接入。

MySQL 是用户、会话、消息、Outbox、MCP、Skill、Workflow 和审计数据的真相源。Redis 用于热窗口、向量检索与治理计数。Outbox 将会话持久化与 Redis 投影解耦。SSE 保留心跳、短期重放和慢客户端隔离；Agent 流式入口额外输出 MCP 工具生命周期事件。

Workflow 是 DB-backed 的串行 DAG MVP：成功节点不重跑，带重试的失败节点可挂起为 `WAITING_MANUAL`，owner/ADMIN 可在同一实例上恢复。复杂就绪节点并行不在本版本范围内。
