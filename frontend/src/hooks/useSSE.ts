import { useCallback, useEffect, useRef, useState } from 'react'
import { EventSourcePolyfill } from 'event-source-polyfill'
import { chatStreamUrl } from '../api/chat'
import { TOKEN_STORAGE_KEY } from '../api/http'
import type {
  Citation,
  RagTraceData,
  RagTimings,
} from '../api/types'

interface StreamHandlers {
  onSession: (sessionId: string) => void
  onContext: (phase: string, message: string) => void
  onToken: (content: string) => void
  onCitations: (items: Citation[]) => void
  onTrace: (trace: RagTraceData) => void
  onPlan?: (steps: string[]) => void
  onMcpTool?: (payload: { server: string; tool: string; status: string }) => void
  onSummary?: (applied: boolean) => void
  onDone: (payload: {
    sessionId: string
    length: number
    tokens?: number
    timings?: RagTimings
  }) => void
  onError: (message: string) => void
}

function parseEvent<T>(event: Event): T | null {
  if (!('data' in event)) return null
  try {
    return JSON.parse(String((event as MessageEvent).data)) as T
  } catch {
    return null
  }
}

export function useSSE() {
  const sourceRef = useRef<EventSource | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)

  const close = useCallback(() => {
    sourceRef.current?.close()
    sourceRef.current = null
    setIsStreaming(false)
  }, [])

  useEffect(() => close, [close])

  const stream = useCallback(
    (
      query: string,
      sessionId: string,
      handlers: StreamHandlers,
    ) => {
      close()
      const token = localStorage.getItem(TOKEN_STORAGE_KEY)
      const source = new EventSourcePolyfill(
        chatStreamUrl(query, sessionId),
        {
          headers: token
            ? { Authorization: `Bearer ${token}` }
            : undefined,
          heartbeatTimeout: 300_000,
        },
      )
      sourceRef.current = source
      setIsStreaming(true)

      source.addEventListener('session', (event) => {
        const data = parseEvent<{ sessionId: string }>(event)
        if (data) handlers.onSession(data.sessionId)
      })
      source.addEventListener('context', (event) => {
        const data = parseEvent<{
          phase: string
          message: string
        }>(event)
        if (data) handlers.onContext(data.phase, data.message)
      })
      source.addEventListener('citations', (event) => {
        const data = parseEvent<{ items: Citation[] }>(event)
        if (data) handlers.onCitations(data.items ?? [])
      })
      source.addEventListener('trace', (event) => {
        const data = parseEvent<RagTraceData>(event)
        if (data) handlers.onTrace(data)
      })
      source.addEventListener('plan', (event) => {
        const data = parseEvent<{ steps: string[] }>(event)
        if (data) handlers.onPlan?.(data.steps ?? [])
      })
      source.addEventListener('mcp_tool', (event) => {
        const data = parseEvent<{ server: string; tool: string; status: string }>(event)
        if (data) handlers.onMcpTool?.(data)
      })
      source.addEventListener('summary', (event) => {
        const data = parseEvent<{ applied: boolean }>(event)
        if (data) handlers.onSummary?.(data.applied)
      })
      source.addEventListener('token', (event) => {
        const data = parseEvent<{ content: string }>(event)
        if (data) handlers.onToken(data.content)
      })
      source.addEventListener('done', (event) => {
        const data = parseEvent<{
          sessionId: string
          length: number
          tokens?: number
          timings?: RagTimings
        }>(event)
        if (data) handlers.onDone(data)
        close()
      })
      source.addEventListener('reconnect', (event) => {
        const data = parseEvent<{ message?: string; resumable?: boolean }>(event)
        handlers.onError(
          data?.message ?? '服务端无法恢复上一次生成，请重新发送问题',
        )
        close()
      })
      source.addEventListener('error', (event) => {
        const data = parseEvent<{ message: string }>(event)
        if (data?.message) {
          handlers.onError(data.message)
          close()
        }
      })
      source.onerror = (event) => {
        if ('data' in event) return
        // EventSourcePolyfill will reconnect using the server-provided retry
        // interval and automatically attach Last-Event-ID.
        handlers.onContext('reconnecting', 'SSE 连接中断，正在尝试恢复...')
      }
    },
    [close],
  )

  return { stream, close, isStreaming }
}
