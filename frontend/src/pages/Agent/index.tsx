import {
  ApiOutlined,
  CalculatorOutlined,
  CheckCircleFilled,
  CodeOutlined,
  FunctionOutlined,
  PlayCircleFilled,
  SearchOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import {
  App,
  Button,
  Card,
  Collapse,
  Input,
  Space,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { listTools, runAgent } from '../../api/agent'
import type {
  AgentResult,
  ToolSchema,
} from '../../api/types'
import { errorMessage } from '../../utils/error'

const examples = [
  '查询知识库：Redis 为什么使用跳表？',
  '计算 (128 * 6 + 24) / 3',
  '解释 RRF 融合，并计算 k=60 时第一名的贡献值',
]

export default function AgentPage() {
  const { message } = App.useApp()
  const [query, setQuery] = useState(examples[0])
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<AgentResult | null>(null)
  const [tools, setTools] = useState<ToolSchema[]>([])
  const [sessionId, setSessionId] = useState(() =>
    crypto.randomUUID().replaceAll('-', '').slice(0, 32),
  )

  useEffect(() => {
    listTools()
      .then((response) => setTools(response.tools))
      .catch(() => setTools([]))
  }, [])

  const execute = async () => {
    if (!query.trim()) return
    setLoading(true)
    setResult(null)
    try {
      const response = await runAgent(query.trim(), sessionId)
      setResult(response)
      setSessionId(response.sessionId)
    } catch (error) {
      message.error(errorMessage(error, 'Agent 执行失败'))
    } finally {
      setLoading(false)
    }
  }

  const traceItems = result?.toolHistory.map((trace, index) => ({
    key: `${trace.toolCallId || trace.toolName}-${index}`,
    label: (
      <div className="agent-step-label">
        <span>STEP {index + 1}</span>
        <strong>{trace.toolName}</strong>
        <Tag color={trace.success ? 'success' : 'error'}>
          {trace.success ? 'SUCCESS' : 'FAILED'}
        </Tag>
      </div>
    ),
    children: (
      <div className="tool-trace-body">
        <section>
          <span className="trace-section-label">Tool Input</span>
          <pre>{JSON.stringify(trace.arguments, null, 2)}</pre>
        </section>
        <section>
          <span className="trace-section-label">Observation</span>
          <div className="observation-box">{trace.observation}</div>
        </section>
      </div>
    ),
  }))

  return (
    <div className="page agent-page">
      <header className="page-header">
        <div>
          <span className="eyebrow">Agent observability</span>
          <h1>Agent 调试台</h1>
          <p>查看模型决策、Function Calling 与 Observation 执行轨迹</p>
        </div>
        <Tag icon={<ApiOutlined />} color="geekblue">
          LOOP PROTECTED
        </Tag>
      </header>

      <div className="agent-layout">
        <section className="agent-main">
          <Card className="agent-query-card" bordered={false}>
            <div className="card-heading">
              <div>
                <Typography.Title level={4}>用户问题</Typography.Title>
                <span>Agent 将自主决定是否调用工具</span>
              </div>
              <span className="session-chip">
                Session · {sessionId.slice(0, 8)}
              </span>
            </div>
            <Input.TextArea
              value={query}
              rows={4}
              maxLength={8000}
              placeholder="输入需要 Agent 规划执行的问题"
              onChange={(event) => setQuery(event.target.value)}
            />
            <div className="agent-query-actions">
              <div className="example-pills">
                {examples.map((example, index) => (
                  <button
                    key={example}
                    type="button"
                    onClick={() => setQuery(example)}
                  >
                    示例 {index + 1}
                  </button>
                ))}
              </div>
              <Button
                type="primary"
                icon={<PlayCircleFilled />}
                loading={loading}
                onClick={() => void execute()}
              >
                运行 Agent
              </Button>
            </div>
          </Card>

          <Card className="agent-trace-card" bordered={false}>
            <div className="card-heading">
              <div>
                <Typography.Title level={4}>执行轨迹</Typography.Title>
                <span>可折叠的 Agent 执行轨迹审计视图</span>
              </div>
              {result && (
                <Space>
                  <Tag>{result.steps} steps</Tag>
                  <Tag
                    color={
                      result.status === 'COMPLETED'
                        ? 'success'
                        : 'warning'
                    }
                  >
                    {result.status}
                  </Tag>
                </Space>
              )}
            </div>
            {loading ? (
              <div className="agent-running">
                <span className="agent-pulse">
                  <FunctionOutlined />
                </span>
                <h3>Agent 正在规划下一步动作</h3>
                <p>LLM Decision → Tool Call → Observation</p>
              </div>
            ) : result ? (
              <>
                {traceItems?.length ? (
                  <Collapse
                    items={traceItems}
                    defaultActiveKey={traceItems.map((item) => item.key)}
                    className="agent-collapse"
                  />
                ) : (
                  <div className="direct-answer-note">
                    <CheckCircleFilled />
                    模型判断无需调用工具，直接生成最终答案
                  </div>
                )}
                <div className="agent-answer">
                  <span className="trace-section-label">Final Answer</span>
                  <div className="markdown-body">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                      {result.answer}
                    </ReactMarkdown>
                  </div>
                </div>
              </>
            ) : (
              <div className="agent-empty">
                <ToolOutlined />
                <h3>运行一次 Agent 查看完整轨迹</h3>
                <p>这里只展示工具决策和观察结果，不暴露模型内部思维链。</p>
              </div>
            )}
          </Card>
        </section>

        <aside className="tool-sidebar">
          <span className="eyebrow">Tool registry</span>
          <h3>可用工具</h3>
          <p>工具由 Spring Bean 自动注册并受 RBAC 权限控制。</p>
          <div className="tool-list">
            {tools.map((tool) => {
              const name = tool.name
              const icon = name.includes('calculator') ? (
                <CalculatorOutlined />
              ) : name.includes('search') ? (
                <SearchOutlined />
              ) : (
                <CodeOutlined />
              )
              return (
                <article key={name}>
                  <span className="tool-icon">{icon}</span>
                  <div>
                    <strong>{name}</strong>
                    <p>{tool.description}</p>
                  </div>
                </article>
              )
            })}
          </div>
          <div className="agent-loop-diagram">
            <div>LLM Decision</div>
            <i />
            <div>Tool Execution</div>
            <i />
            <div>Observation</div>
            <i />
            <div>Final Answer</div>
          </div>
        </aside>
      </div>
    </div>
  )
}
