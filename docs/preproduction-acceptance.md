# 本机预生产验收手册

## 前置条件

Docker Desktop 必须运行且 `docker info` 成功。不要把真实密钥写入仓库；在未提交的 `.env` 或进程环境变量中提供 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_MODEL`、`MCP_CONFIG_ENCRYPTION_KEY`、数据库密码和 JWT 密钥。

## 启动与健康检查

```powershell
docker compose --profile mcp-example up -d --build
docker compose ps
Invoke-WebRequest http://localhost:8080/healthz
Invoke-WebRequest http://localhost:9091/-/ready
Invoke-WebRequest http://localhost:3000/api/health
docker compose logs app --tail 200
```

核心服务 mysql、redis、app、frontend、nginx、prometheus、grafana、mcp-http-example 必须为 healthy 或 running（仅无 healthcheck 的一次性组件除外）。应用健康接口为 `http://localhost:8080/api/actuator/health`。

## 三个验收场景

1. 以 ANALYST 登录，确认 `docker/docs/enterprise-expense-policy.txt` 已入库后提问“费用报销的住宿标准和审核时限是什么”，验证引用指向制度文档。
2. 以 ANALYST 调用 `business_metrics`，使用已发布的周报 Skill 对 2026-01 固定脱敏指标生成总结；确认会话历史、Skill 版本和工具轨迹。
3. 注册 `mcp-http-example` 的 `http://mcp-http-example:3001/mcp`，执行三节点 Workflow（知识查询、`demo00-http-example.add`、总结），确认步骤、Audit 和 MCP 工具事件。

真实 LLM 的 SSE `done` 事件记录 `firstTokenMs`、`totalElapsedMs` 与 Token 字段。`tokenAccounting=estimated` 表示供应商流未返回 usage，不能作为账单统计。

## 停止与证据

```powershell
docker compose logs --no-color app | Out-File -Encoding utf8 .\artifacts\app.log
docker compose down
```

不要导出 `.env`、Authorization、MCP 配置密文或任何供应商响应中的密钥。
