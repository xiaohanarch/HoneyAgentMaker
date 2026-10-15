import { createHmac } from 'node:crypto'
import { createLocalJWKSet, createRemoteJWKSet, jwtVerify, type JWK, type JWTPayload } from 'jose'

/**
 * DemoERP 与平台之间的连接：用平台的 JWKS 校验令牌、向平台核实委托、把事件推送给平台。
 * 校验和核实失败都按拒绝处理；推送失败只记日志，不影响业务请求。
 */
export interface PlatformLink {
  verify(token: string, audience: string): Promise<JWTPayload>
  delegationActive(id: string): Promise<boolean>
  publish(event: {
    type: string
    tenantId: string
    payload: Record<string, unknown>
  }): Promise<void>
}

const ISSUER = 'eab-identity'
const DELEGATION_CACHE_MS = 5_000

function delegationCache(
  lookup: (id: string) => Promise<boolean>,
  cacheMs: number,
): (id: string) => Promise<boolean> {
  const cache = new Map<string, { active: boolean; at: number }>()
  return async (id) => {
    const hit = cache.get(id)
    if (hit !== undefined && Date.now() - hit.at < cacheMs) return hit.active
    let active: boolean
    try {
      active = await lookup(id)
    } catch {
      active = false // 核实不了就当作无效
    }
    cache.set(id, { active, at: Date.now() })
    return active
  }
}

export interface HttpLinkOptions {
  platformUrl: string
  internalSecret: string
  /** 委托状态的缓存时间，默认 5 秒；撤销在缓存过期后对已签发的令牌生效，新的令牌交换立即失败 */
  delegationCacheMs?: number
  onPublishError?: (error: unknown) => void
}

export function createHttpPlatformLink(options: HttpLinkOptions): PlatformLink {
  const jwks = createRemoteJWKSet(new URL('/identity/v1/jwks.json', options.platformUrl))
  return {
    async verify(token, audience) {
      const { payload } = await jwtVerify(token, jwks, {
        issuer: ISSUER,
        audience,
        algorithms: ['EdDSA'],
      })
      return payload
    },
    delegationActive: delegationCache(async (id) => {
      const response = await fetch(
        new URL(`/identity/v1/delegations/${encodeURIComponent(id)}/status`, options.platformUrl),
        {
          headers: { 'x-eab-internal-token': options.internalSecret },
          signal: AbortSignal.timeout(3_000),
        },
      )
      if (!response.ok) return false
      const body = (await response.json()) as { data?: { active?: boolean } }
      return body.data?.active === true
    }, options.delegationCacheMs ?? DELEGATION_CACHE_MS),
    async publish(event) {
      // 签名覆盖时间戳和请求体，平台拒绝 5 分钟以外的事件；事件编号用于去重，防止重放
      const body = JSON.stringify({ ...event, id: crypto.randomUUID() })
      const timestamp = String(Math.floor(Date.now() / 1000))
      const signature = createHmac('sha256', options.internalSecret)
        .update(`${timestamp}.${body}`, 'utf8')
        .digest('hex')
      try {
        await fetch(new URL('/events/v1/ingest', options.platformUrl), {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'x-eab-timestamp': timestamp,
            'x-eab-signature': `sha256=${signature}`,
          },
          body,
          signal: AbortSignal.timeout(3_000),
        })
      } catch (error: unknown) {
        options.onPublishError?.(error)
      }
    },
  }
}

/** 测试用：本地 JWKS、可控的委托状态、记录推送的事件 */
export function createLocalPlatformLink(options: {
  jwks: { keys: JWK[] }
  delegationActive?: (id: string) => boolean
}): PlatformLink & {
  published: { type: string; tenantId: string; payload: Record<string, unknown> }[]
} {
  const keySet = createLocalJWKSet(options.jwks)
  const published: { type: string; tenantId: string; payload: Record<string, unknown> }[] = []
  return {
    published,
    async verify(token, audience) {
      const { payload } = await jwtVerify(token, keySet, {
        issuer: ISSUER,
        audience,
        algorithms: ['EdDSA'],
      })
      return payload
    },
    delegationActive: async (id) => options.delegationActive?.(id) ?? false,
    async publish(event) {
      published.push(event)
    },
  }
}
