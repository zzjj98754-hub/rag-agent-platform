export interface UserInfo {
  id: number
  username: string
  role: 'ADMIN' | 'USER' | 'GUEST'
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  expiresAt: string
  user: UserInfo
}

export interface Citation {
  title: string
  chunkId: string
  score: number
  preview: string
}

export type TraceStatus = 'pending' | 'running' | 'completed' | 'failed'

export interface TraceStep {
  key: string
  label: string
  status: TraceStatus
  durationMs: number
}

export interface RagTimings {
  retrievalMs: number
  bm25Ms: number
  embeddingMs: number
  rerankMs: number
  llmMs: number
  totalMs: number
}

export interface RagTraceData {
  steps: TraceStep[]
  timings: RagTimings
}

export interface AgentStreamPlan {
  steps: string[]
}

export interface McpToolEvent {
  server: string
  tool: string
  status: 'started' | 'completed' | 'failed'
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: Citation[]
  streaming?: boolean
}

export interface DocumentItem {
  id: number
  title: string
  filePath: string
  status: 'PROCESSING' | 'INDEXED' | 'FAILED'
  creatorId?: number
  createTime: string
  chunkCount: number
  vectorCount: number
  embeddingSource: 'siliconflow' | 'local' | 'unknown'
  embeddingStatus: 'PROCESSING' | 'READY' | 'FAILED' | 'NOT_LOADED'
}

export interface DocumentListResponse {
  documents: DocumentItem[]
  total: number
}

export interface DocumentUploadResponse {
  success: boolean
  fileName: string
  indexed: string[]
  skipped: string[]
  failed: Array<{ docName: string; reason: string }>
  totalChunks: number
  durationMs: number
}

export interface ToolTrace {
  toolCallId?: string
  toolName: string
  arguments: Record<string, unknown>
  success: boolean
  denied: boolean
  observation: string
}

export interface AgentResult {
  sessionId: string
  answer: string
  status: 'COMPLETED' | 'MAX_STEPS' | 'LOOP_DETECTED' | 'FAILED'
  steps: number
  toolHistory: ToolTrace[]
}

export interface ToolSchema {
  name: string
  description: string
  parameters: Record<string, unknown>
}

export interface ToolListResponse {
  tools: ToolSchema[]
  count: number
}

export interface ApiError {
  code?: string
  message?: string
  traceId?: string
  details?: Record<string, string>
}
