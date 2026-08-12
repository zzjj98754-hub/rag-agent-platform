# 06 · Agent Tool Calling · 掌握卡（入口版）

定位：S 级；AI 应用核心，追问集中在循环、安全边界、RBAC、超时降级。
关联：01 项目介绍、08 系统可靠性、JWT 安全体系。

## 1. 知识树

```
Agent Tool Calling
├── Agent 循环
│   ├── AgentExecutor：决策 → 工具 → Observation → 再决策 → 终答
│   ├── AgentContext：跨步骤消息 / 工具轨迹 / 步数
│   └── 终止：FINAL_ANSWER / MAX_STEPS / LOOP_DETECTED
├── 工具体系
│   ├── ToolDefinition 策略接口 + @Component 自动注册
│   ├── ToolRegistry：名称 → 工具 + 给 LLM 的 JSON Schema
│   └── 工具：SearchTool / CalculatorTool
├── 安全边界（ToolScheduler 顺序检查）
│   ├── 1 步数上限（max-steps=5）
│   ├── 2 死循环检测（同工具 + 同参数连续 3 次）
│   ├── 3 工具存在性
│   ├── 4 RBAC 权限（UserRole ↔ requiredPermissions）
│   └── 5 超时隔离（ToolExecutor 独立线程池 + Future.get 30s）
├── RBAC / 身份
│   ├── JWT（JwtAuthenticationFilter / JwtService / SecurityConfig）
│   └── 角色来自认证主体（CurrentUserProvider）
└── 失败处理
    ├── ToolResult.success / fail → Observation 回灌
    ├── 超时 / 拒绝 → 结构化错误，不崩链路
    └── LLM 客户端：OpenAI Function Calling（可关）+ Prompt Agent 双实现
```

## 2. 核心流程（骨架）

场景 A：一次工具调用

```
LLM 决策 tool_call → ToolScheduler.dispatch：
  max steps？→ 终态；dead loop？→ 终态；工具存在？→ 拒绝；权限？→ 拒绝；执行
  ├─ 成功 → ToolResult → Observation 回灌 → 下一轮决策
  ├─ 超时 → cancel(true) + 失败 Observation
  └─ 拒绝 / 不存在 → 原因回灌 → 继续或终止
```

场景 B：Agent 会话

```
提问 → 循环（≤5 步）→ FINAL_ANSWER → 返回最终回答 + 工具轨迹审计
```

## 3. 两分钟口述（骨架，第 3 步再展开）

- 结论：Agent = 决策 → 执行 → 观察的循环，让 LLM 从「会说」到「能做事」。
- 原理：LLM 输出结构化动作，系统执行后把结果回灌。
- 安全 5 连：步数、死循环、存在性、RBAC、超时。
- 失败降级：结构化 ToolResult → observation，不中断会话。
- 钩子：权限靠 JWT 身份绑定，而不是请求体自报角色。

## 4. 三个为什么（入口问题）

1. 为什么需要 Agent 循环？ → 方向：多步工具任务、可观察、可审计。
2. 为什么把安全检查集中在 Scheduler？ → 方向：LLM 输出不可信，执行前统一边界。
3. 为什么用 Future.get + 独立线程池？ → 方向：不可信工具隔离、限时、防拖垮主链路。

判定：能按顺序说出 5 道检查并解释顺序逻辑 → 掌握。

## 5. 项目应用（证据映射）

| 场景 | 方案 | 收益 | 代价 |
|---|---|---|---|
| 工具扩展 | ToolDefinition + 自动注册 | 加工具只写实现类 | Schema 转换需同步维护 |
| 安全 | Scheduler 5 检查 | 防滥用 / 死循环 | 步骤上限限制复杂任务 |
| 身份权限 | JWT + UserRole + PermissionEvaluator | 角色隔离 | 目前偏角色级，可细化到资源级 |
| 超时 | ToolExecutor Future.get 30s | 工具卡死不阻塞 | 线程池参数需调优 |

## 缺口清单

- [ ] 说全 5 道安全检查及顺序
- [ ] 解释死循环检测的判定条件（同工具 + 同参数）
- [ ] 说清 Function Calling 开关与两个 LLM 客户端实现
- [ ] 说清 RBAC 目前是角色级还是资源级
