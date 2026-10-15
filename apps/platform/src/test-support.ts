/**
 * 测试共用：临时密钥的平台服务、按身份签发用户令牌。只在测试里引用。
 */
import { ephemeralIdentityKeys } from './identity/keys'
import { Ed25519Signer } from './registry/signing'
import { createPlatformServices, type PlatformServices, type ServiceOptions } from './services'

export const TEST_IDENTITY_KEYS = await ephemeralIdentityKeys()
export const TEST_INTERNAL_SECRET = 'test-internal-secret'

export function createTestServices(options: Partial<ServiceOptions> = {}): PlatformServices {
  return createPlatformServices({
    dbPath: ':memory:',
    signer: Ed25519Signer.ephemeral(),
    identityKeys: TEST_IDENTITY_KEYS,
    internalSecret: TEST_INTERNAL_SECRET,
    ...options,
  })
}

/** P1 测试里的查看者写法对应的演示用户 */
export const VIEWER_USERS = {
  'tenant:t001': 'chengong',
  'tenant:t002': 'zhouting',
  vendor: 'vendordev',
  platform: 'platformops',
} as const
export type ViewerKey = keyof typeof VIEWER_USERS

/** 为各个身份登录一次，返回取 Authorization 头的函数 */
export async function loginAll(
  services: PlatformServices,
): Promise<(who: ViewerKey | string) => { authorization: string }> {
  const tokens = new Map<string, string>()
  for (const userId of [...Object.values(VIEWER_USERS), 'zhangmin', 'liqiang', 'wangfang', 'sunkg', 'zhaoadmin']) {
    tokens.set(userId, (await services.identity.login(userId)).token)
  }
  return (who) => {
    const userId = (VIEWER_USERS as Record<string, string>)[who] ?? who
    const token = tokens.get(userId)
    if (token === undefined) throw new Error(`测试里没有为 ${who} 登录`)
    return { authorization: `Bearer ${token}` }
  }
}
