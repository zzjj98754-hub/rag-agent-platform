# 部署说明

## 本机预生产

启动 Docker Desktop 后，在未提交的 `.env` 中填写密钥和强密码，再执行：

```powershell
docker compose --profile mcp-example up -d --build
docker compose ps
```

请按 [本机预生产验收手册](preproduction-acceptance.md) 采集健康检查、真实 LLM、MCP 和三个业务场景的证据。停止环境使用 `docker compose down`；数据卷仅在明确需要清空验收数据时才手动删除。

## Linux 单机生产

使用 `.env.prod.example` 创建未提交的 `.env.prod`，设置强密码、`MCP_CONFIG_ENCRYPTION_KEY`、LLM/Embedding 凭据、绝对的 `RAG_DOCS_PATH` 与域名。生成 Grafana basic auth 后使用生产叠加配置启动：

```bash
bash scripts/gen-grafana-htpasswd.sh
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
  --env-file .env.prod --profile prod up -d --build
```

TLS 首次签发需要有效域名和公网 80 端口；证书和防火墙验证不属于本机预生产验收。上线前执行 MySQL 备份恢复演练、Redis/MySQL/LLM/MCP 故障演练和 SSE 并发压测。回滚使用 `scripts/rollback.sh <previous-image-reference>`，并在回滚前确认新 Flyway 迁移均为前向兼容。
