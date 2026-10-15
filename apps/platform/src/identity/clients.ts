import { randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { INTEGRATION_CLIENTS } from '@eab/demo-directory'

export interface IntegrationClient {
  id: string
  tenantId: string
  name: string
}

/**
 * 机机集成账号（客户端凭证）。密钥在首次启动时随机生成，存放在 data/keys/clients.json，不进仓库。
 * 集成账号没有人的身份，只能换到 Open API 的令牌（主提示词 7.2 节）。
 */
export class ClientRegistry {
  constructor(private readonly secrets: ReadonlyMap<string, string>) {}

  static ephemeral(): ClientRegistry {
    return new ClientRegistry(new Map(INTEGRATION_CLIENTS.map((c) => [c.id, randomBytes(24).toString('base64url')])))
  }

  static fromDirectory(dir: string): ClientRegistry {
    const path = join(dir, 'clients.json')
    if (!existsSync(path)) {
      mkdirSync(dir, { recursive: true })
      const generated = Object.fromEntries(INTEGRATION_CLIENTS.map((c) => [c.id, randomBytes(24).toString('base64url')]))
      writeFileSync(path, `${JSON.stringify(generated, null, 2)}\n`, { mode: 0o600 })
    }
    const stored = JSON.parse(readFileSync(path, 'utf8')) as Record<string, string>
    return new ClientRegistry(new Map(Object.entries(stored)))
  }

  /** 演示控制台和测试取密钥用；生产环境不应提供 */
  secretOf(clientId: string): string | undefined {
    return this.secrets.get(clientId)
  }

  authenticate(clientId: string, secret: string): IntegrationClient | undefined {
    const client = INTEGRATION_CLIENTS.find((c) => c.id === clientId)
    const expected = this.secrets.get(clientId)
    if (client === undefined || expected === undefined) return undefined
    const a = Buffer.from(expected)
    const b = Buffer.from(secret)
    return a.length === b.length && timingSafeEqual(a, b) ? client : undefined
  }
}
