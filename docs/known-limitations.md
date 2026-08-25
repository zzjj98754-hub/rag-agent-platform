# 已知限制

- Docker daemon、真实 LLM 密钥和公网 TLS 依赖部署环境；未就绪时不得宣称生产运行验收通过。
- Token usage 仅当上游流返回 usage 时才可精确；当前 SSE 会清楚标记估算值。
- Workflow 支持串行 DAG、重试与人工恢复，不支持复杂并行调度或外部人工审批系统。
- `business_metrics` 和制度文档是脱敏验收夹具，不是业务系统集成。
- SSE 重放位于单 JVM，不能实现跨实例上游流续传。
