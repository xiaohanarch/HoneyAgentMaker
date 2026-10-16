import { createApp } from './server.js'

const port = Number(process.env.PORT ?? 4400)

createApp().listen(port, process.env.HOST ?? '127.0.0.1', () => {
  console.info(`物流跟踪 MCP Server 已启动：http://127.0.0.1:${port}/mcp`)
})
