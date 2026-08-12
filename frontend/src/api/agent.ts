import { http } from './http'
import type {
  AgentResult,
  ToolListResponse,
} from './types'

export async function runAgent(
  query: string,
  sessionId: string,
): Promise<AgentResult> {
  const { data } = await http.post<AgentResult>('/agent/chat', {
    query,
    sessionId,
  })
  return data
}

export async function listTools(): Promise<ToolListResponse> {
  const { data } =
    await http.get<ToolListResponse>('/agent/tools')
  return data
}
