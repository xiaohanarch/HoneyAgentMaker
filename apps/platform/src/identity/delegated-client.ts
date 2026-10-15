import type { BoOperation } from '@eab/demo-directory'
import { TOKEN_TYPE, type IdentityService } from './service'
import { AUDIENCE, TokenError } from './tokens'

/**
 * 平台调用 DemoERP BO API 的唯一通道：先以“用户 + Agent”双重身份做令牌交换，再带委托令牌调用。
 * 身份透传失败即拒绝（主提示词 8.4 节）：令牌交换失败、委托缺失、配置不全时抛 DelegationError，
 * 这里没有任何使用集成账号或其他技术身份的代码路径。
 */
export class DelegationError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'DelegationError'
  }
}

export type DelegationSubject = { userToken: string } | { delegationId: string }

export interface BoCall {
  subject: DelegationSubject
  /** 平台运行时为本次运行签发的 Agent 令牌 */
  agentToken: string
  operation: BoOperation
  method: 'GET' | 'POST'
  path: string
  query?: Record<string, string>
  body?: unknown
  idempotencyKey?: string
}

export interface BoResponse {
  status: number
  body: unknown
}

export interface DelegatedClientDeps {
  identity: IdentityService
  /** DemoERP 的地址，P3 起由连接别名 DEMO_ERP 按环境绑定 */
  erpUrl: string | undefined
  fetch?: typeof fetch
}

export async function callBoApi(deps: DelegatedClientDeps, call: BoCall): Promise<BoResponse> {
  if (deps.erpUrl === undefined || deps.erpUrl === '') {
    throw new DelegationError('CONNECTION_MISSING', '没有配置 DemoERP 的连接，拒绝调用')
  }
  let accessToken: string
  try {
    const result = await deps.identity.exchange(
      'userToken' in call.subject
        ? {
            subjectToken: call.subject.userToken,
            subjectTokenType: TOKEN_TYPE.jwt,
            actorToken: call.agentToken,
            audience: AUDIENCE.bo,
            scope: [call.operation],
          }
        : {
            subjectToken: `delegation:${call.subject.delegationId}`,
            subjectTokenType: TOKEN_TYPE.delegation,
            actorToken: call.agentToken,
            audience: AUDIENCE.bo,
            scope: [call.operation],
          },
    )
    accessToken = result.accessToken
  } catch (error: unknown) {
    const code = error instanceof TokenError ? error.code : 'EXCHANGE_FAILED'
    throw new DelegationError(
      code,
      `身份透传失败，已拒绝调用 ${call.operation}：${error instanceof Error ? error.message : String(error)}`,
    )
  }

  // 只允许 BO API 路径，并且必须落在配置的 ERP 地址上，委托令牌不会被发往别处
  const base = new URL(deps.erpUrl)
  if (!call.path.startsWith('/bo/v1/') || call.path.includes('//') || call.path.includes('..')) {
    throw new DelegationError('INVALID_PATH', `不允许的 BO API 路径：${call.path}`)
  }
  const url = new URL(call.path, base)
  if (url.origin !== base.origin)
    throw new DelegationError('INVALID_PATH', 'BO API 地址与连接配置不一致')
  for (const [key, value] of Object.entries(call.query ?? {})) url.searchParams.set(key, value)
  const response = await (deps.fetch ?? fetch)(url, {
    method: call.method,
    headers: {
      authorization: `Bearer ${accessToken}`,
      ...(call.body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(call.idempotencyKey === undefined ? {} : { 'idempotency-key': call.idempotencyKey }),
    },
    ...(call.body === undefined ? {} : { body: JSON.stringify(call.body) }),
    signal: AbortSignal.timeout(15_000),
  })
  return { status: response.status, body: await response.json() }
}
