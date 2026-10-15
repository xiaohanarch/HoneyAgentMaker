import { existsSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { seedAssets, type SeedReport } from '../registry/seed'
import { ephemeralIdentityKeys } from '../identity/keys'
import { Ed25519Signer } from '../registry/signing'
import { createPlatformServices, type PlatformServices } from '../services'

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
export const ASSETS_ROOT = join(REPO_ROOT, 'assets')

/** 资产目录下要加载的子目录：平台、标品，以及 tenants/ 下的每个租户快照 */
export function assetSubdirectories(root: string = ASSETS_ROOT): string[] {
  const tenantsDir = join(root, 'tenants')
  const tenants = existsSync(tenantsDir)
    ? readdirSync(tenantsDir, { withFileTypes: true })
        .filter((d) => d.isDirectory())
        .map((d) => `tenants/${d.name}`)
    : []
  return ['platform', 'vendor', ...tenants]
}

/**
 * 用内存数据库和临时密钥建一个注册中心，把仓库里的全部资产按本层身份签名发布。
 * 命令行和集成测试共用，走的是和平台启动时相同的发布路径。
 */
export async function loadRepositoryRegistry(
  root: string = ASSETS_ROOT,
): Promise<{ services: PlatformServices; seed: SeedReport }> {
  const services = createPlatformServices({
    dbPath: ':memory:',
    signer: Ed25519Signer.ephemeral(),
    identityKeys: await ephemeralIdentityKeys(),
  })
  const seed = await seedAssets(services.registry, root, assetSubdirectories(root))
  return { services, seed }
}
