import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildApp } from './app'
import { ClientRegistry } from './identity/clients'
import { identityKeysFromDirectory } from './identity/keys'
import { seedAssets } from './registry/seed'
import { Ed25519Signer } from './registry/signing'
import { createPlatformServices, ensureDemoDefaults } from './services'

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

const port = Number(process.env.EAB_PLATFORM_PORT ?? 4000)
const host = process.env.EAB_HOST ?? '127.0.0.1'
const dataDir = resolve(process.env.EAB_DATA_DIR ?? join(REPO_ROOT, 'data'))
const assetsDir = resolve(process.env.EAB_ASSETS_DIR ?? join(REPO_ROOT, 'assets'))

const keysDir = join(dataDir, 'keys')
const internalSecret = process.env.EAB_INTERNAL_SECRET
const services = createPlatformServices({
  dbPath: join(dataDir, 'platform.db'),
  signer: Ed25519Signer.fromDirectory(keysDir),
  identityKeys: await identityKeysFromDirectory(keysDir),
  clients: ClientRegistry.fromDirectory(keysDir),
  ...(internalSecret === undefined ? {} : { internalSecret }),
})
const demoMode = process.env.EAB_DEMO_MODE === '1'
// 演示模式开放选人登录和令牌交换端点，只允许监听本机回环地址
if (demoMode && !['127.0.0.1', 'localhost', '::1'].includes(host)) {
  console.error(`演示模式只能监听本机回环地址，当前 EAB_HOST=${host}；请改用 SSH 隧道访问`)
  process.exit(1)
}
const app = buildApp({
  services,
  demoMode,
  fastify: { logger: { level: process.env.LOG_LEVEL ?? 'info' } },
})
if (internalSecret === undefined) {
  app.log.warn(
    '没有设置 EAB_INTERNAL_SECRET，已随机生成；DemoERP 无法推送事件和核实委托，受托调用会被拒绝',
  )
}

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
  ensureDemoDefaults(services)
  app.log.info({ count: seed.published.length }, '平台与标品资产已签名入库')
  for (const failure of seed.failed) app.log.error(failure, '资产入库失败')
  await app.listen({ port, host })
} catch (error: unknown) {
  app.log.error(error, '平台服务启动失败')
  process.exit(1)
}
