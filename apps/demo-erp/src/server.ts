import { buildApp } from './app'

const port = Number(process.env.EAB_DEMO_ERP_PORT ?? 4100)
const host = process.env.EAB_HOST ?? '127.0.0.1'

const app = buildApp({ logger: { level: process.env.LOG_LEVEL ?? 'info' } })

async function shutdown(signal: string): Promise<void> {
  app.log.info({ signal }, '正在关闭 DemoERP')
  await app.close()
  process.exit(0)
}

process.once('SIGINT', () => void shutdown('SIGINT'))
process.once('SIGTERM', () => void shutdown('SIGTERM'))

try {
  await app.listen({ port, host })
} catch (error: unknown) {
  app.log.error(error, 'DemoERP 启动失败')
  process.exit(1)
}
