# RAG Intelligence Web Console

独立的 React 18 + TypeScript + Vite 前端，用于展示 demo00 的 RAG、
SSE、知识库入库和 Agent Function Calling 能力。

## 启动

先确认后端运行在 `http://localhost:9090`，然后执行：

```bash
npm install
npm run dev
```

访问 `http://localhost:5173`。开发环境使用 Vite `/api` 反向代理，因此
默认不需要修改配置。

如需直接访问其他后端，复制环境变量模板：

```bash
cp .env.example .env.local
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env.local
```

然后设置：

```text
VITE_API_BASE_URL=http://localhost:9090
```

后端同时需要通过 `CORS_ALLOWED_ORIGINS` 允许该前端 Origin。

## 命令

```bash
npm run dev      # 本地开发
npm run lint     # TypeScript 严格检查
npm run build    # 生产构建
npm run preview  # 预览 dist
```

## 目录

```text
src/
├── api/          # Axios API 与前后端 DTO
├── components/   # Chat、Message、Citation、RAG Trace、Shell
├── hooks/        # SSE 连接及事件生命周期
├── pages/        # Chat、Knowledge、Agent、Login
├── router/       # 路由与认证保护
├── store/        # JWT 用户状态
├── styles/       # 企业工作台视觉与响应式布局
└── utils/        # 统一错误提取
```

## SSE 协议

`useSSE` 连接 `GET /chat/stream?query=...&sessionId=...`，监听：

- `session`：后端确认的会话 ID
- `context`：检索或生成阶段
- `citations`：引用文档与匹配分
- `trace`：RAG Pipeline 步骤和耗时
- `token`：实时增量内容
- `done`：Token 数、最终耗时
- `error`：统一错误

`EventSourcePolyfill` 用于携带 JWT Header。收到每个 `token` 后只追加该
增量内容，不等待完整答案，也不在前端进行字符串切割。

## 权限

- `/chat`、`/agent`：登录用户可访问。
- `/knowledge`：页面可进入，但上传、列表和删除需要后端 `ADMIN` 权限。
- Axios 请求拦截器自动添加 `Authorization: Bearer <token>`。
- 401 会清理已失效 Token 并返回登录页。
