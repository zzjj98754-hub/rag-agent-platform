# 项目生产验收报告

## 已完成能力

Hybrid RAG、Spring AI ChatClient、Agent/Tool、MySQL+Outbox+Redis Memory、SSE、JWT/RBAC、MCP、Skill、DB-backed Workflow 与企业治理均已纳入代码与自动化门禁。新增本机预生产验收夹具包括费用制度文档、只读业务指标工具、Agent MCP SSE 事件和 Workflow 人工恢复状态。

## 本地验证结果

| 项目 | 结果 |
| --- | --- |
| 后端全量测试 | 84 tests，0 failures，0 errors |
| Maven 打包 | 通过 |
| 前端生产构建 | 通过 |
| RAG 评测 | Recall@3 = 0.9889 |
| Compose/示例脚本静态检查 | 通过 |
| Compose 静态解析 | 通过 |
| Docker daemon | 阻塞：当前主机 Docker Desktop Linux daemon 未启动 |
| 真实 LLM | 待凭据注入后执行 |
| 容器健康、MCP HTTP 联调 | 待 Docker daemon 启动后执行 |

## 剩余风险与建议

先启动 Docker Desktop 并按 `preproduction-acceptance.md` 完成容器、真实 LLM 与 MCP 证据采集；之后在 Linux 主机完成 TLS、备份恢复、100 SSE 并发及故障演练。任何未完成运行态项目均保持“待验收”状态。
