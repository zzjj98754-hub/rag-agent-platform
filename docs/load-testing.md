# RAG 压测与故障演练

本方案使用 Python 标准库，不依赖 JMeter、Gatling 或第三方 Python
包。默认执行 `100` 个聊天请求、并发度 `100`，输出 QPS、平均响应时间、
P95、P99、错误率，并自动验证 Embedding 熔断和 LLM 超时降级。

压测只能在本地或隔离测试环境执行，禁止对生产环境直接使用默认参数。

## 1. 测试组成

| 文件 | 作用 |
|---|---|
| `scripts/loadtest/mock_ai_upstream.py` | 可控的 Embedding、Reranker、LLM 上游 |
| `scripts/loadtest/chat_load_test.py` | 并发请求、统计延迟、读取 Actuator 指标、输出 JSON 报告 |
| `application-loadtest.yml` | 独立压测 Profile，不影响默认环境 |

Mock 上游的用途是让故障具有确定性。它不是性能优化工具，也不能代替真实
模型服务的容量测试。

## 2. 环境准备

需要：

- Java 17
- Python 3.9+
- MySQL 8.0
- `D:\docs` 中的知识库文档
- Redis 可选；Redis 不可用时项目会走内存降级，但健康状态可能为 `DOWN`

打开三个 PowerShell 终端。

### 终端一：启动可控 AI 上游

```powershell
python scripts/loadtest/mock_ai_upstream.py
```

检查：

```powershell
Invoke-RestMethod http://localhost:18080/health
```

默认状态应为：

```json
{
  "status": "UP",
  "embedding_mode": "success",
  "llm_delay_ms": 50
}
```

### 终端二：启动应用

`loadtest` Profile 会创建或刷新一个 ADMIN 压测账号，以便脚本读取受 RBAC
保护的 Actuator 指标。密码必须通过环境变量提供，不存在默认密码。

```powershell
$env:JAVA_HOME = 'D:\dev\jdks'
$env:LOAD_TEST_PASSWORD = 'replace-with-a-local-test-password'
.\mvnw.cmd "-Dspring-boot.run.profiles=loadtest" spring-boot:run
```

等待日志出现文档入库完成，再开始测试。文档入库期间执行压测会混入启动
开销，结果无比较价值。

### 终端三：设置脚本密码

```powershell
$env:LOAD_TEST_PASSWORD = 'replace-with-a-local-test-password'
```

## 3. 基线：100 并发聊天请求

每个请求使用唯一 query 和 sessionId，避免答案缓存、Embedding 查询缓存和
会话复用掩盖真实负载。

```powershell
python scripts/loadtest/chat_load_test.py `
  --scenario baseline `
  --requests 100 `
  --concurrency 100
```

控制台示例：

```text
=== demo00 RAG Load Test ===
Scenario=baseline requests=100 concurrency=100
QPS=... avg=...ms P99=...ms P95=...ms
HTTP success=100 errors=0 error_rate=0.00%
```

完整结果写入：

```text
load-test-results/baseline-YYYYMMDD-HHMMSS.json
```

关键字段：

- `qps`：完成请求数 / 压测阶段总耗时。
- `latency_ms.average`：平均端到端响应时间。
- `latency_ms.p99`：按 nearest-rank 计算的 P99。
- `http.error_rate`：非 2xx 或客户端异常占比。
- `metrics_delta`：本轮压测引起的熔断、降级指标增量。

## 4. Embedding 失败与熔断验证

建议每个故障场景前重启应用，清除熔断状态和进程内缓存。Mock 上游必须先以
成功模式完成文档入库；压测脚本会在发压前自动把 Embedding 切换为 `503`
失败模式。

```powershell
python scripts/loadtest/chat_load_test.py `
  --scenario embedding-failure `
  --requests 100 `
  --concurrency 100
```

脚本自动验证：

1. 连续 3 次主 Embedding 失败后熔断器进入 `OPEN`。
2. 后续调用不再访问主服务，转入本地 TF-IDF Embedding。
3. 聊天请求仍返回 2xx。

相关指标：

| 指标 | 含义 |
|---|---|
| `rag.embedding.circuit.state` | `0=CLOSED`、`1=HALF_OPEN`、`2=OPEN` |
| `rag.embedding.circuit.opens` | 熔断器打开次数 |
| `rag.embedding.primary.failures` | 主 Embedding 调用失败次数 |
| `rag.embedding.fallback.calls` | 本地 Embedding 降级调用次数 |

报告中的 `fault_validation` 必须满足：

```json
{
  "passed": true,
  "circuit_open": true,
  "fallback_used": true,
  "circuit_state": 2.0
}
```

并发情况下熔断器使用原子状态迁移，只有一个线程能完成
`OPEN -> HALF_OPEN` 探测，避免恢复瞬间的 thundering herd。

## 5. LLM 超时与降级验证

`loadtest` Profile 的 LLM read timeout 为 1 秒。脚本把 Mock LLM 延迟设置为
1500ms，使请求稳定触发读取超时。

```powershell
python scripts/loadtest/chat_load_test.py `
  --scenario llm-timeout `
  --llm-delay-ms 1500 `
  --requests 100 `
  --concurrency 100
```

期望结果：

- 应用日志包含 `outcome=fallback`。
- `/chat` 仍返回本地降级答案，而不是向客户端抛出 5xx。
- `rag.llm.calls{outcome=fallback}` 的增量大于 0。
- 报告中 `fault_validation.passed=true`。

该场景的平均响应时间通常接近 read timeout，证明降级发生在超时边界之后，
而不是 Mock 上游正常返回。

## 6. 查看原始 Actuator / Prometheus 指标

压测账号是 ADMIN，可以读取：

```text
GET /actuator/metrics
GET /actuator/metrics/rag.embedding.circuit.state
GET /actuator/metrics/rag.llm.calls?tag=outcome:fallback
GET /actuator/prometheus
```

`/actuator/health` 可匿名访问，其他 Actuator 端点必须携带 ADMIN JWT。
压测脚本会自动登录和添加 JWT。

## 7. 常用参数

```text
--requests 100              总请求数
--concurrency 100           并发线程数
--warmup 3                  正式计时前的预热请求数
--timeout 20                单请求客户端超时，单位秒
--max-error-rate 0.0        允许的最大错误率
--output result.json        指定报告文件
--token <JWT>               使用已有 ADMIN JWT，跳过登录
--skip-upstream-control     不控制 Mock 上游，用于真实服务测试
```

例如执行 1000 请求、100 并发：

```powershell
python scripts/loadtest/chat_load_test.py `
  --scenario baseline `
  --requests 1000 `
  --concurrency 100 `
  --max-error-rate 0.01
```

## 8. 结果解读原则

- 本机压测结果只能用于版本间相对比较，不能直接宣称为生产容量。
- 比较前固定 JVM 参数、文档数量、数据库状态、Redis 状态和 Mock 延迟。
- 至少执行 3 轮，取中位数；首轮包含 JIT、连接池预热开销。
- QPS 提升但 P99 恶化通常意味着队列等待或下游饱和。
- 故障场景重点看错误率、熔断状态和 fallback 指标，不只看 QPS。
- 真实模型容量测试可使用 `--skip-upstream-control`，并通过启动参数覆盖
  Embedding、Reranker、LLM URL；务必先确认供应商限流和费用。
