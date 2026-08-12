import {
  ClearOutlined,
  SendOutlined,
  ThunderboltFilled,
} from '@ant-design/icons'
import { App, Button, Input, Tag, Tooltip } from 'antd'
import { useCallback, useMemo, useState } from 'react'
import type {
  ChatMessage,
  Citation,
  RagTraceData,
} from '../../api/types'
import ChatWindow from '../../components/ChatWindow'
import RagTrace, {
  initialTrace,
} from '../../components/RagTrace'
import { useSSE } from '../../hooks/useSSE'

function newSessionId() {
  return crypto.randomUUID().replaceAll('-', '').slice(0, 32)
}

export default function ChatPage() {
  const { message } = App.useApp()
  const [sessionId, setSessionId] = useState(newSessionId)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [phase, setPhase] = useState('idle')
  const [phaseMessage, setPhaseMessage] = useState('')
  const [trace, setTrace] = useState<RagTraceData>(initialTrace)
  const { stream, close, isStreaming } = useSSE()

  const shortSession = useMemo(
    () => sessionId.slice(0, 8),
    [sessionId],
  )

  const updateAssistant = useCallback(
    (
      assistantId: string,
      updater: (current: ChatMessage) => ChatMessage,
    ) => {
      setMessages((current) =>
        current.map((item) =>
          item.id === assistantId ? updater(item) : item,
        ),
      )
    },
    [],
  )

  const sendQuestion = useCallback(
    (question?: string) => {
      const query = (question ?? input).trim()
      if (!query || isStreaming) return

      const userMessage: ChatMessage = {
        id: crypto.randomUUID(),
        role: 'user',
        content: query,
      }
      const assistantId = crypto.randomUUID()
      const assistantMessage: ChatMessage = {
        id: assistantId,
        role: 'assistant',
        content: '',
        citations: [],
        streaming: true,
      }
      setMessages((current) => [
        ...current,
        userMessage,
        assistantMessage,
      ])
      setInput('')
      setPhase('retrieving')
      setPhaseMessage('正在准备查询改写...')
      setTrace({
        ...initialTrace,
        steps: initialTrace.steps.map((step, index) => ({
          ...step,
          status: index === 0 ? 'running' : 'pending',
        })),
      })

      stream(query, sessionId, {
        onSession: setSessionId,
        onContext: (nextPhase, nextMessage) => {
          setPhase(nextPhase)
          setPhaseMessage(nextMessage)
          if (nextPhase === 'generating') {
            setTrace((current) => ({
              ...current,
              steps: current.steps.map((step, index) => ({
                ...step,
                status:
                  index === current.steps.length - 1
                    ? 'running'
                    : 'completed',
              })),
            }))
          }
        },
        onToken: (content) => {
          updateAssistant(assistantId, (current) => ({
            ...current,
            content: current.content + content,
          }))
        },
        onCitations: (items: Citation[]) => {
          updateAssistant(assistantId, (current) => ({
            ...current,
            citations: items,
          }))
        },
        onTrace: (nextTrace) => {
          setTrace((current) => ({
            ...nextTrace,
            steps: nextTrace.steps.map((step) =>
              step.key === 'generate' &&
              isStreaming &&
              step.status === 'pending'
                ? { ...step, status: 'running' }
                : step,
            ),
            timings: {
              ...current.timings,
              ...nextTrace.timings,
            },
          }))
        },
        onDone: (payload) => {
          updateAssistant(assistantId, (current) => ({
            ...current,
            streaming: false,
          }))
          setPhase('completed')
          setPhaseMessage(
            `回答生成完成 · ${payload.tokens ?? 0} tokens`,
          )
          setTrace((current) => ({
            ...current,
            timings: payload.timings ?? current.timings,
            steps: current.steps.map((step) => ({
              ...step,
              status: 'completed',
            })),
          }))
        },
        onError: (errorMessage) => {
          updateAssistant(assistantId, (current) => ({
            ...current,
            content:
              current.content ||
              `生成失败：${errorMessage}`,
            streaming: false,
          }))
          setPhase('failed')
          setPhaseMessage(errorMessage)
          message.error(errorMessage)
        },
      })
    },
    [
      input,
      isStreaming,
      message,
      sessionId,
      stream,
      updateAssistant,
    ],
  )

  const resetSession = () => {
    close()
    setSessionId(newSessionId())
    setMessages([])
    setTrace(initialTrace)
    setPhase('idle')
    setPhaseMessage('')
  }

  return (
    <div className="page chat-page">
      <header className="page-header chat-header">
        <div>
          <div className="title-line">
            <h1>AI 知识库助手</h1>
            <Tag
              icon={<ThunderboltFilled />}
              color="success"
              className="online-tag"
            >
              RAG ONLINE
            </Tag>
          </div>
          <p>
            基于企业知识库回答，支持来源引用与完整检索链路追踪
          </p>
        </div>
        <div className="header-actions">
          <span className="session-chip">
            Session · {shortSession}
          </span>
          <Tooltip title="清空对话并创建新会话">
            <Button
              icon={<ClearOutlined />}
              onClick={resetSession}
            >
              新对话
            </Button>
          </Tooltip>
        </div>
      </header>

      <div className="chat-workspace">
        <section className="chat-main-panel">
          <ChatWindow
            messages={messages}
            onSuggestion={sendQuestion}
          />
          <div className="chat-composer">
            <Input.TextArea
              value={input}
              autoSize={{ minRows: 1, maxRows: 5 }}
              placeholder="向知识库提问，Shift + Enter 换行"
              maxLength={8000}
              onChange={(event) => setInput(event.target.value)}
              onPressEnter={(event) => {
                if (!event.shiftKey) {
                  event.preventDefault()
                  sendQuestion()
                }
              }}
              disabled={isStreaming}
            />
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={isStreaming}
              disabled={!input.trim()}
              onClick={() => sendQuestion()}
            >
              发送
            </Button>
            <div className="composer-meta">
              <span>回答将实时流式生成</span>
              <span>{input.length} / 8000</span>
            </div>
          </div>
        </section>
        <RagTrace
          trace={trace}
          phase={phase}
          message={phaseMessage}
          isStreaming={isStreaming}
        />
      </div>
    </div>
  )
}
