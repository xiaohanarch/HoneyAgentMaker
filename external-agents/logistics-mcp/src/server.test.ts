import type { AddressInfo } from 'node:net'
import type { Server } from 'node:http'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import { createApp } from './server.js'

let server: Server
let url: URL

beforeAll(async () => {
  server = createApp().listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  url = new URL(`http://127.0.0.1:${(server.address() as AddressInfo).port}/mcp`)
})

afterAll(() => {
  server.close()
})

describe('物流跟踪 MCP Server', () => {
  it('用官方客户端列出工具并按订单号查询运单', async () => {
    const client = new Client({ name: 'test', version: '1.0.0' })
    await client.connect(new StreamableHTTPClientTransport(url))
    const tools = await client.listTools()
    expect(tools.tools.map((t) => t.name)).toEqual(['track_shipment'])
    const result = await client.callTool({ name: 'track_shipment', arguments: { id: 'PO-2026-0117' } })
    expect(result.structuredContent).toMatchObject({ shipmentId: 'SH-2026-0311', status: 'delayed' })
    const missing = await client.callTool({ name: 'track_shipment', arguments: { id: 'PO-0000' } })
    expect(missing.isError).toBe(true)
    await client.close()
  })
})
