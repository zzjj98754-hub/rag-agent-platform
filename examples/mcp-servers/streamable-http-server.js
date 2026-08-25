import express from 'express'
import { randomUUID } from 'node:crypto'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { z } from 'zod'

const app = express()
app.use(express.json({ limit: '1mb' }))
app.get('/health', (_request, response) => response.json({ status: 'UP' }))

const sessions = new Map()
app.all('/mcp', async (request, response) => {
  const sessionId = request.headers['mcp-session-id']
  let transport = sessionId ? sessions.get(sessionId) : undefined
  if (!transport && request.method === 'POST') {
    const server = new McpServer({ name: 'demo00-http-example', version: '1.0.0' })
    server.tool(
      'add',
      'Add two finite numbers',
      { left: z.number().finite(), right: z.number().finite() },
      async ({ left, right }) => ({
        content: [{ type: 'text', text: String(left + right) }],
      }),
    )
    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: randomUUID,
      onsessioninitialized: (id) => sessions.set(id, transport),
    })
    transport.onclose = () => {
      if (transport.sessionId) sessions.delete(transport.sessionId)
    }
    await server.connect(transport)
  }
  if (!transport) {
    response.status(400).json({ error: 'Missing or invalid MCP session' })
    return
  }
  await transport.handleRequest(request, response, request.body)
})

app.listen(3001, '0.0.0.0')
