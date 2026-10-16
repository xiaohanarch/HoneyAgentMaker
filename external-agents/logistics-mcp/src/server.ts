import express, { type Express, type Request, type Response } from 'express'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js'
import { z } from 'zod'
import { track } from './shipments.js'

/** 每个请求一个无状态的 MCP Server，演示足够，也不会在调用方之间串数据 */
export function createMcpServer(): McpServer {
  const server = new McpServer({ name: 'logistics-mcp', version: '1.0.0' })
  server.registerTool(
    'track_shipment',
    {
      title: '物流跟踪',
      description: '按采购订单号或运单号查询在途运单的状态、预计到达时间和物流节点（演示数据）',
      inputSchema: {
        id: z.string().min(1).describe('采购订单号（PO-2026-0117）或运单号（SH-2026-0311）'),
      },
      annotations: { readOnlyHint: true },
    },
    async ({ id }) => {
      const shipment = track(id)
      if (shipment === undefined) {
        return { content: [{ type: 'text', text: `没有找到 ${id} 的在途运单` }], isError: true }
      }
      return {
        content: [{ type: 'text', text: JSON.stringify(shipment) }],
        structuredContent: { ...shipment },
      }
    },
  )
  return server
}

export function createApp(): Express {
  const app = express()
  app.use(express.json())
  app.get('/health', (_req: Request, res: Response) => {
    res.json({ status: 'ok' })
  })
  app.post('/mcp', async (req: Request, res: Response) => {
    const server = createMcpServer()
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
    res.on('close', () => {
      void transport.close()
      void server.close()
    })
    await server.connect(transport)
    await transport.handleRequest(req, res, req.body)
  })
  const notAllowed = (_req: Request, res: Response): void => {
    res
      .status(405)
      .json({ jsonrpc: '2.0', error: { code: -32000, message: '无状态模式只接受 POST' }, id: null })
  }
  app.get('/mcp', notAllowed)
  app.delete('/mcp', notAllowed)
  return app
}
