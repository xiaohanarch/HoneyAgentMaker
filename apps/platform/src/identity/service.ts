import { findUser, permittedOperations, type DemoUser } from '@eab/demo-directory'
import type { AuditStore } from '../audit/audit-store'
import type { ClientRegistry } from './clients'
import type { Delegation, DelegationStore } from './delegations'
import {
  AUDIENCE,
  DELEGATED_TTL_SECONDS,
  TokenError,
  type DelegatedClaims,
  type TokenService,
} from './tokens'

export const TOKEN_TYPE = {
  jwt: 'urn:ietf:params:oauth:token-type:jwt',
  accessToken: 'urn:ietf:params:oauth:token-type:access_token',
  /** 受托自主运行：subject_token 写 delegation:<委托编号> */
  delegation: 'urn:eab:params:oauth:token-type:delegation',
} as const

export interface ExchangeRequest {
  subjectToken: string
  subjectTokenType: string
  actorToken: string
  audience: string
  /** 可选：只申请部分操作 */
  scope?: readonly string[]
}

export interface ExchangeResult {
  accessToken: string
  scope: string[]
  expiresIn: number
  claims: Omit<DelegatedClaims, 'kind'>
}

export interface IdentityDeps {
  tokens: TokenService
  delegations: DelegationStore
  clients: ClientRegistry
  audit: AuditStore
}

const intersect = (a: readonly string[], b: readonly string[]): string[] => a.filter((x) => b.includes(x))
const splitScope = (scope: string): string[] => scope.split(' ').filter((s) => s !== '')

/**
 * 模拟身份服务（主提示词 7.1 节）：选人登录、客户端凭证、RFC 8693 令牌交换。
 * 令牌交换失败一律抛 TokenError，绝不退回到集成账号或其他技术身份（主提示词 8.4 节）。
 */
export class IdentityService {
  constructor(private readonly deps: IdentityDeps) {}

  async login(userId: string): Promise<{ token: string; user: DemoUser }> {
    const user = findUser(userId)
    if (user === undefined) throw new TokenError('UNKNOWN_USER', `用户 ${userId} 不存在`)
    const token = await this.deps.tokens.issueUser({ sub: user.id, tid: user.tenantId, roles: [...user.roles] })
    return { token, user }
  }

  async clientCredentials(clientId: string, secret: string): Promise<string> {
    const client = this.deps.clients.authenticate(clientId, secret)
    if (client === undefined) throw new TokenError('INVALID_CLIENT', '集成账号或密钥错误')
    return this.deps.tokens.issueClient({ sub: `client:${client.id}`, tid: client.tenantId })
  }

  /** 平台运行时在每次运行前为 Agent 签发身份，携带它声明的 BO 操作 */
  issueAgentToken(input: { agent: string; version: string; tenantId: string; operations: readonly string[] }): Promise<string> {
    return this.deps.tokens.issueAgent({
      sub: `agent:${input.agent}`,
      tid: input.tenantId,
      scope: [...input.operations].join(' '),
      agent_version: input.version,
    })
  }

  /**
   * 令牌交换：以“用户 + Agent”双重身份换取委托令牌。
   * 有效权限 = 用户的业务权限 ∩ Agent 声明的操作 ∩ 委托范围（受托运行时），再与申请范围取交集。
   */
  async exchange(request: ExchangeRequest): Promise<ExchangeResult> {
    try {
      const result = await this.#exchange(request)
      this.deps.audit.append({
        actor: result.claims.act.sub,
        action: 'identity.token-exchange',
        target: AUDIENCE.bo,
        decision: 'allow',
        reason: `签发委托令牌：${result.claims.mode === 'delegated' ? `受托运行（${result.claims.delegation_id ?? ''}）` : '代用户操作'}`,
        tenantId: result.claims.tid,
        userId: result.claims.sub,
        agent: `${result.claims.act.sub.replace(/^agent:/, '')}@${result.claims.agent_version}`,
        mode: result.claims.mode,
        resultSummary: `授权操作 ${result.scope.length} 个`,
      })
      return result
    } catch (error: unknown) {
      const tokenError =
        error instanceof TokenError ? error : new TokenError('EXCHANGE_FAILED', error instanceof Error ? error.message : String(error))
      this.deps.audit.append({
        actor: 'identity',
        action: 'identity.token-exchange',
        target: request.audience,
        decision: 'deny',
        reason: tokenError.message,
        ruleId: tokenError.code,
        resultSummary: '令牌交换失败，按失败即拒绝处理，未退回任何技术账号',
      })
      throw tokenError
    }
  }

  async #exchange(request: ExchangeRequest): Promise<ExchangeResult> {
    if (request.audience !== AUDIENCE.bo) {
      throw new TokenError('INVALID_TARGET', `不支持换取受众为 ${request.audience} 的令牌`)
    }
    const actor = await this.deps.tokens.verifyAgent(request.actorToken)
    const agentRef = actor.sub.replace(/^agent:/, '')

    let user: DemoUser | undefined
    let delegation: Delegation | undefined
    if (request.subjectTokenType === TOKEN_TYPE.delegation) {
      const id = request.subjectToken.replace(/^delegation:/, '')
      const status = this.deps.delegations.status(id)
      if (!status.active) throw new TokenError('DELEGATION_INACTIVE', `委托 ${id} 不可用：${status.reason}`)
      delegation = status.delegation
      if (delegation.agent !== agentRef) {
        throw new TokenError('DELEGATION_AGENT_MISMATCH', `委托 ${id} 授权的是 ${delegation.agent}，不是 ${agentRef}`)
      }
      if (delegation.tenantId !== actor.tid) throw new TokenError('TENANT_MISMATCH', '委托与 Agent 不属于同一租户')
      user = findUser(delegation.delegatorUserId)
    } else if (request.subjectTokenType === TOKEN_TYPE.jwt || request.subjectTokenType === TOKEN_TYPE.accessToken) {
      const subject = await this.deps.tokens.verifyUser(request.subjectToken)
      if (subject.tid !== actor.tid) throw new TokenError('TENANT_MISMATCH', '用户与 Agent 不属于同一租户')
      user = findUser(subject.sub)
    } else {
      throw new TokenError('UNSUPPORTED_SUBJECT', `不支持的 subject_token_type：${request.subjectTokenType}`)
    }
    if (user === undefined) throw new TokenError('UNKNOWN_USER', '委托令牌的主体用户不存在')

    let scope = intersect(permittedOperations(user), splitScope(actor.scope))
    if (delegation !== undefined) scope = intersect(scope, delegation.scope)
    if (request.scope !== undefined) scope = intersect(scope, request.scope)
    if (scope.length === 0) {
      throw new TokenError('EMPTY_SCOPE', `用户 ${user.id} 与 ${agentRef} 的权限交集为空，不签发令牌`)
    }

    const claims: Omit<DelegatedClaims, 'kind'> = {
      sub: user.id,
      act: { sub: actor.sub },
      tid: actor.tid,
      scope: scope.join(' '),
      mode: delegation === undefined ? 'on-behalf' : 'delegated',
      ...(delegation === undefined ? {} : { delegation_id: delegation.id }),
      agent_version: actor.agent_version,
    }
    const accessToken = await this.deps.tokens.issueDelegated(claims)
    return { accessToken, scope, expiresIn: DELEGATED_TTL_SECONDS, claims }
  }
}
