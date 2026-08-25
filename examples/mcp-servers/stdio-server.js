import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'

const server = new McpServer({ name: 'demo00-stdio-example', version: '1.0.0' })
server.tool(
  'echo',
  'Echo text through an isolated MCP process',
  { text: z.string().max(2000) },
  async ({ text }) => ({ content: [{ type: 'text', text }] }),
)

await server.connect(new StdioServerTransport())
