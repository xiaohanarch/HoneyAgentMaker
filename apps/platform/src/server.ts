import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildApp } from './app'
import { seedAssets } from './registry/seed'
import { Ed25519Signer } from './registry/signing'
import { createPlatformServices } from './services'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

const port = Number(process.env.EAB_PLATFORM_PORT ?? 4000)
const host = process.env.EAB_HOST ?? '127.0.0.1'
const dataDir = resolve(process.env.EAB_DATA_DIR ?? join(REPO_ROOT, 'data'))
const assetsDir = resolve(process.env.EAB_ASSETS_DIR ?? join(REPO_ROOT, 'assets'))

const services = createPlatformServices({
  dbPath: join(dataDir, 'platform.db'),
  signer: Ed25519Signer.fromDirectory(join(dataDir, 'keys')),
})
const app = buildApp({ services, fastify: { logger: { level: process.env.LOG_LEVEL ?? 'info' } } })

async function shutdown(signal: string): Promise<void> {
  app.log.info({ signal }, '正在关闭平台服务')
  await app.close()
  process.exit(0)
}

process.once('SIGINT', () => void shutdown('SIGINT'))
process.once('SIGTERM', () => void shutdown('SIGTERM'))

try {
  // 启动时把平台规则和标品资产签名入库；同版本同内容时跳过
  const seed = await seedAssets(services.registry, assetsDir, ['platform', 'vendor'])
  app.log.info({ count: seed.published.length }, '平台与标品资产已签名入库')
  for (const failure of seed.failed) app.log.error(failure, '资产入库失败')
  await app.listen({ port, host })
} catch (error: unknown) {
  app.log.error(error, '平台服务启动失败')
  process.exit(1)
}
