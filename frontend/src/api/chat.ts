import { API_BASE_URL } from './http'

export function chatStreamUrl(query: string, sessionId: string) {
  const params = new URLSearchParams({ query, sessionId })
  return `${API_BASE_URL}/chat/stream?${params.toString()}`
}
