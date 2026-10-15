/**
 * DemoERP 测试共用：本地签发与平台同格式的令牌、本地 JWKS 的平台连接、已灌好种子数据的应用。
 */
import { generateKeyPairSync } from 'node:crypto'
import { SignJWT, exportJWK } from 'jose'
import type { FastifyInstance } from 'fastify'
import { buildApp } from './app'
import { createLocalPlatformLink } from './auth/platform-link'
import { openErpDb } from './db/client'
import { seedErp } from './seed'

export const TODAY = new Date('2026-10-15T08:00:00Z')
export const INTERNAL_SECRET = 'erp-test-secret'

const { privateKey, publicKey } = generateKeyPairSync('ed25519')
const JWKS = { keys: [{ ...(await exportJWK(publicKey)), kid: 'test', alg: 'EdDSA' }] }

async function sign(claims: Record<string, unknown>, audience: string): Promise<string> {
  const iat = Math.floor(TODAY.getTime() / 1000)
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'EdDSA', kid: 'test' })
    .setIssuer('eab-identity')
    .setAudience(audience)
    .setIssuedAt(iat)
    .setExpirationTime(Math.floor(Date.now() / 1000) + 300)
    .sign(privateKey)
}

/** 委托令牌：与平台令牌交换签出的格式相同 */
export function delegatedToken(options: {
  user: string
  tenant?: string
  scope: readonly string[]
  agent?: string
  mode?: 'on-behalf' | 'delegated'
  delegationId?: string
}): Promise<string> {
  return sign(
    {
      kind: 'delegated',
      sub: options.user,
      tid: options.tenant ?? 't001',
      act: { sub: `agent:${options.agent ?? 'vendor.pur/supplier-performance-analyst'}` },
      scope: options.scope.join(' '),
      mode: options.mode ?? 'on-behalf',
      agent_version: '1.0.0',
      ...(options.delegationId === undefined ? {} : { delegation_id: options.delegationId }),
    },
    'demo-erp-bo',
  )
}

export function clientToken(tenant = 't001'): Promise<string> {
  return sign({ kind: 'client', sub: `client:${tenant}-integration`, tid: tenant }, 'demo-erp-open')
}

/** 平台签发的只读页面令牌 */
export function pageToken(user: string, tenant = 't001'): Promise<string> {
  return sign({ kind: 'page', sub: user, tid: tenant }, 'demo-erp-pages')
}

export function userToken(user: string): Promise<string> {
  return sign({ kind: 'user', sub: user, tid: 't001', roles: [] }, 'eab-platform')
}

export interface TestErp {
  app: FastifyInstance
  link: ReturnType<typeof createLocalPlatformLink>
  activeDelegations: Set<string>
}

export function createTestErp(): TestErp {
  const db = openErpDb(':memory:')
  seedErp(db, TODAY)
  const activeDelegations = new Set<string>()
  const link = createLocalPlatformLink({
    jwks: JWKS,
    delegationActive: (id) => activeDelegations.has(id),
  })
  const app = buildApp({ db, link, internalSecret: INTERNAL_SECRET, now: () => TODAY })
  return { app, link, activeDelegations }
}

export const bearer = (token: string) => ({ authorization: `Bearer ${token}` })
