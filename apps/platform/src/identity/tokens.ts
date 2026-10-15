import { SignJWT, jwtVerify, type JWTPayload } from 'jose'
import type { IdentityKeys } from './keys'

/**
 * 令牌受众的划分（ADR 0005）：
 * - eab-platform       用户令牌，调用平台接口
 * - eab-agent-runtime  Agent 令牌，由平台运行时持有，只用作令牌交换的 actor_token
 * - demo-erp-bo        委托令牌（用户 + Agent），调用 BO API
 * - demo-erp-open      集成令牌（客户端凭证），只能调用 Open API
 */
export const ISSUER = 'eab-identity'
export const AUDIENCE = {
  platform: 'eab-platform',
  agentRuntime: 'eab-agent-runtime',
  bo: 'demo-erp-bo',
  open: 'demo-erp-open',
} as const

/** 有效期（秒） */
const TTL = { user: 8 * 3600, agent: 15 * 60, client: 3600, delegated: 300 } as const
export const DELEGATED_TTL_SECONDS = TTL.delegated

export interface UserClaims {
  kind: 'user'
  sub: string
  tid: string
  roles: string[]
}

export interface AgentClaims {
  kind: 'agent'
  /** agent:<namespace>/<name> */
  sub: string
  tid: string
  /** Agent 声明的 BO 操作，空格分隔 */
  scope: string
  agent_version: string
}

export interface ClientClaims {
  kind: 'client'
  /** client:<id> */
  sub: string
  tid: string
}

/** 委托令牌（RFC 8693）：sub 是用户（受托运行时是委托人），act.sub 是 Agent */
export interface DelegatedClaims {
  kind: 'delegated'
  sub: string
  act: { sub: string }
  tid: string
  scope: string
  mode: 'on-behalf' | 'delegated'
  delegation_id?: string
  agent_version: string
}

export class TokenError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'TokenError'
  }
}

export class TokenService {
  constructor(
    private readonly keys: IdentityKeys,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async #sign(
    claims: Record<string, unknown>,
    audience: string,
    ttlSeconds: number,
  ): Promise<string> {
    // 签发时间和过期时间都用注入的时钟计算；jose 的相对写法（如 '8h'）走系统时钟，不能用
    const issuedAt = Math.floor(this.now().getTime() / 1000)
    return new SignJWT(claims)
      .setProtectedHeader({ alg: 'EdDSA', kid: this.keys.kid, typ: 'JWT' })
      .setIssuer(ISSUER)
      .setAudience(audience)
      .setIssuedAt(issuedAt)
      .setExpirationTime(issuedAt + ttlSeconds)
      .setJti(crypto.randomUUID())
      .sign(this.keys.privateKey)
  }

  /** 公开的校验公钥，DemoERP 等下游据此校验令牌 */
  jwks(): IdentityKeys['jwks'] {
    return this.keys.jwks
  }

  issueUser(claims: Omit<UserClaims, 'kind'>): Promise<string> {
    return this.#sign({ ...claims, kind: 'user' }, AUDIENCE.platform, TTL.user)
  }

  issueAgent(claims: Omit<AgentClaims, 'kind'>): Promise<string> {
    return this.#sign({ ...claims, kind: 'agent' }, AUDIENCE.agentRuntime, TTL.agent)
  }

  issueClient(claims: Omit<ClientClaims, 'kind'>): Promise<string> {
    return this.#sign({ ...claims, kind: 'client' }, AUDIENCE.open, TTL.client)
  }

  issueDelegated(claims: Omit<DelegatedClaims, 'kind'>): Promise<string> {
    return this.#sign({ ...claims, kind: 'delegated' }, AUDIENCE.bo, TTL.delegated)
  }

  /** 校验签名、签发者、受众、有效期和令牌种类，任何一项不符都抛 TokenError */
  async verify<T extends { kind: string }>(
    token: string,
    audience: string,
    kind: T['kind'],
  ): Promise<T & JWTPayload> {
    let payload: JWTPayload
    try {
      ;({ payload } = await jwtVerify(token, this.keys.publicKey, {
        issuer: ISSUER,
        audience,
        algorithms: ['EdDSA'],
        currentDate: this.now(),
      }))
    } catch (error: unknown) {
      throw new TokenError(
        'INVALID_TOKEN',
        `令牌无效：${error instanceof Error ? error.message : String(error)}`,
      )
    }
    if (payload.kind !== kind) {
      throw new TokenError('WRONG_TOKEN_KIND', `需要 ${kind} 令牌，收到 ${String(payload.kind)}`)
    }
    return payload as T & JWTPayload
  }

  verifyUser(token: string): Promise<UserClaims & JWTPayload> {
    return this.verify<UserClaims>(token, AUDIENCE.platform, 'user')
  }

  verifyAgent(token: string): Promise<AgentClaims & JWTPayload> {
    return this.verify<AgentClaims>(token, AUDIENCE.agentRuntime, 'agent')
  }
}
