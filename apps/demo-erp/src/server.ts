import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildApp } from './app'
import { createHttpPlatformLink } from './auth/platform-link'
import { openErpDb } from './db/client'
import { isSeeded, seedErp } from './seed'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

const port = Number(process.env.EAB_DEMO_ERP_PORT ?? 4100)
const host = process.env.EAB_HOST ?? '127.0.0.1'
const dataDir = resolve(process.env.EAB_DATA_DIR ?? join(REPO_ROOT, 'data'))
const platformUrl = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const internalSecret = process.env.EAB_INTERNAL_SECRET ?? crypto.randomUUID()

const db = openErpDb(join(dataDir, 'erp.db'))
const app = buildApp({
  db,
  internalSecret,
  link: createHttpPlatformLink({
    platformUrl,
    internalSecret,
    onPublishError: (error) => app.log.warn({ err: error }, '事件推送到平台失败'),
  }),
  fastify: { logger: { level: process.env.LOG_LEVEL ?? 'info' } },
})
if (process.env.EAB_INTERNAL_SECRET === undefined) {
  app.log.warn('没有设置 EAB_INTERNAL_SECRET，事件推送和委托核实会失败，受托调用会被拒绝')
}

async function shutdown(signal: string): Promise<void> {
  app.log.info({ signal }, '正在关闭 DemoERP')
  await app.close()
  process.exit(0)
}

process.once('SIGINT', () => void shutdown('SIGINT'))
process.once('SIGTERM', () => void shutdown('SIGTERM'))

try {
  if (!isSeeded(db)) {
    seedErp(db)
    app.log.info('DemoERP 演示数据已生成')
  }
  await app.listen({ port, host })
} catch (error: unknown) {
  app.log.error(error, 'DemoERP 启动失败')
  process.exit(1)
}
