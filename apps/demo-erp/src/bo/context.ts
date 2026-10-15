import { createHash } from 'node:crypto'
import { and, eq } from 'drizzle-orm'
import type { FastifyInstance, FastifyRequest } from 'fastify'
import { decodeJwt } from 'jose'
import type { z } from 'zod'
import {
  findTenant,
  findUser,
  permittedOperations,
  type BoOperation,
  type DemoUser,
} from '@eab/demo-directory'
import type { PlatformLink } from '../auth/platform-link'
import type { ErpDb } from '../db/client'
import { accessLog, idempotencyKeys } from '../db/schema'
import { DIMENSION_LABELS, OPERATION_SPECS, type ScopeDimension } from './operations'

export interface ErpDeps {
  db: ErpDb
  link: PlatformLink
  now: () => Date
}

/** 鉴权点 4 通过后的调用上下文 */
export interface BoContext {
  tenantId: string
  user: DemoUser
  /** agent:<namespace>/<name> */
  agent: string
  agentVersion: string
  mode: 'on-behalf' | 'delegated'
  delegationId?: string
  operation: BoOperation
  scope: {
    dimension: ScopeDimension
    /** 可见的组织编号 */
    values: string[]
    /** 可见的组织名称 */
    names: string[]
    label: string
    /** 是否因数据权限过滤掉了一部分数据 */
    filtered: boolean
  }
}

/** 结构化错误：错误码、原因、建议的下一步（主提示词 6.3 节） */
/** 令牌验签通过后已知的调用方身份；之后的检查被拒绝时，用它把拒绝记录推送到对应租户的审计 */
export interface CallerIdentity {
  tenantId: string
  userId: string
  agent: string
  agentVersion: string
  mode: string
  delegationId?: string
}

export class BoError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly reason: string,
    readonly suggestion: string,
    readonly caller?: CallerIdentity,
  ) {
    super(reason)
    this.name = 'BoError'
  }

  withCaller(caller: CallerIdentity): BoError {
    return new BoError(this.status, this.code, this.reason, this.suggestion, caller)
  }
}

function dataScopeOf(user: DemoUser, dimension: ScopeDimension): BoContext['scope'] {
  const tenant = findTenant(user.tenantId)
  if (dimension === 'none' || tenant === undefined) {
    return {
      dimension,
      values: [],
      names: [],
      label: `租户：${tenant?.name ?? user.tenantId}`,
      filtered: false,
    }
  }
  const all = dimension === 'purchasingOrg' ? tenant.purchasingOrgs : tenant.companies
  const values =
    dimension === 'purchasingOrg'
      ? [...user.dataScope.purchasingOrgs]
      : [...user.dataScope.companies]
  const names = all.filter((o) => values.includes(o.id)).map((o) => o.name)
  return {
    dimension,
    values,
    names,
    label: `${DIMENSION_LABELS[dimension]}：${names.join('、') || '无'}`,
    filtered: values.length < all.length,
  }
}

/**
 * 鉴权点 4（主提示词 7.3 节）。依次检查：委托令牌有效 → 带 Agent 身份 → 租户一致 →
 * 用户有业务权限 → Agent 声明过这个操作 → 受托运行的委托仍有效 → 计算数据权限。
 * 任何一步不过都抛 BoError，不存在降级路径。
 */
export async function authorizeBo(
  deps: ErpDeps,
  request: FastifyRequest,
  operation: BoOperation,
): Promise<BoContext> {
  const header = request.headers.authorization
  const token =
    typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : undefined
  if (token === undefined) {
    throw new BoError(
      401,
      'AUTH_REQUIRED',
      '缺少委托令牌',
      '由平台以“用户 + Agent”身份换取委托令牌后再调用',
    )
  }
  let claims: Awaited<ReturnType<PlatformLink['verify']>>
  try {
    claims = await deps.link.verify(token, 'demo-erp-bo')
  } catch {
    let unverified: Record<string, unknown> = {}
    try {
      unverified = decodeJwt(token)
    } catch {
      // 不是 JWT
    }
    if (unverified.kind === 'user') {
      throw new BoError(
        401,
        'AGENT_IDENTITY_REQUIRED',
        '用户令牌不能直接调用 BO API',
        '由平台以“用户 + Agent”身份做令牌交换，换取委托令牌',
      )
    }
    if (unverified.kind === 'client' || unverified.aud === 'demo-erp-open') {
      throw new BoError(
        401,
        'AGENT_IDENTITY_REQUIRED',
        '集成账号没有人的身份，不能调用 BO API',
        '系统集成请改用 Open API；Agent 必须以用户身份换取委托令牌',
      )
    }
    throw new BoError(401, 'INVALID_TOKEN', '委托令牌无效或已过期', '重新换取委托令牌')
  }
  const act = claims.act as { sub?: unknown } | undefined
  if (claims.kind !== 'delegated' || typeof act?.sub !== 'string') {
    throw new BoError(
      401,
      'AGENT_IDENTITY_REQUIRED',
      'BO API 只接受同时带用户和 Agent 身份的委托令牌',
      '由平台做令牌交换',
    )
  }
  const caller: CallerIdentity = {
    tenantId: String(claims.tid ?? ''),
    userId: String(claims.sub ?? ''),
    agent: act.sub,
    agentVersion: String(claims.agent_version ?? ''),
    mode: claims.mode === 'delegated' ? 'delegated' : 'on-behalf',
    ...(typeof claims.delegation_id === 'string' ? { delegationId: claims.delegation_id } : {}),
  }
  try {
    return await checkCaller(deps, caller, String(claims.scope ?? ''), operation)
  } catch (error: unknown) {
    // 令牌已验签：之后的拒绝都带上调用方身份，推送到该租户的审计
    throw error instanceof BoError ? error.withCaller(caller) : error
  }
}

/** 令牌验签之后的检查：租户、业务权限、Agent 声明、委托、数据权限 */
async function checkCaller(
  deps: ErpDeps,
  caller: CallerIdentity,
  scopeClaim: string,
  operation: BoOperation,
): Promise<BoContext> {
  const user = findUser(caller.userId)
  if (user === undefined || user.tenantId !== caller.tenantId) {
    throw new BoError(403, 'TENANT_MISMATCH', '令牌中的用户不属于该租户', '确认登录的租户')
  }
  if (!permittedOperations(user).includes(operation)) {
    throw new BoError(
      403,
      'BUSINESS_PERMISSION_DENIED',
      `用户 ${user.name} 在 ERP 里没有 ${operation} 的权限`,
      '用户在 ERP 里做不了的事，通过 Agent 也做不了；请联系管理员授权',
    )
  }
  if (!scopeClaim.split(' ').includes(operation)) {
    throw new BoError(
      403,
      'AGENT_SCOPE_DENIED',
      `${caller.agent} 没有声明 ${operation}`,
      '在 Agent 定义里声明该工具后重新发布',
    )
  }
  const mode = caller.mode === 'delegated' ? 'delegated' : 'on-behalf'
  // 受托自主运行没有在线的人确认，BO API 只允许只读操作（硬约束写在 API 里，不只靠平台策略）
  if (mode === 'delegated' && OPERATION_SPECS[operation].writeLevel !== 'L0') {
    throw new BoError(
      403,
      'DELEGATED_WRITE_FORBIDDEN',
      `受托自主运行不能执行写操作 ${operation}`,
      '改为生成建议，由用户在线确认后执行',
    )
  }
  const { delegationId } = caller
  if (
    mode === 'delegated' &&
    (delegationId === undefined || !(await deps.link.delegationActive(delegationId)))
  ) {
    throw new BoError(
      403,
      'DELEGATION_REVOKED',
      `委托 ${delegationId ?? ''} 已撤销或失效`,
      '由租户管理员重新登记委托',
    )
  }
  return {
    tenantId: user.tenantId,
    user,
    agent: caller.agent,
    agentVersion: caller.agentVersion,
    mode,
    ...(delegationId === undefined ? {} : { delegationId }),
    operation,
    scope: dataScopeOf(user, OPERATION_SPECS[operation].dimension),
  }
}

export interface Outcome {
  status?: number
  body: unknown
  /** 审计里的目标，例如单据编号 */
  target: string
  summary: string
}

interface RouteSpec<P extends z.ZodType, Q extends z.ZodType, B extends z.ZodType> {
  operation: BoOperation
  method: 'GET' | 'POST'
  url: string
  summary: string
  description: string
  params?: P
  querystring?: Q
  body?: B
  handler: (
    ctx: BoContext,
    input: { params: z.infer<P>; query: z.infer<Q>; body: z.infer<B> },
  ) => Promise<Outcome> | Outcome
}

function record(
  deps: ErpDeps,
  entry: {
    ctx?: BoContext
    operation: string
    principal: string
    agent?: string
    agentVersion?: string
    tenantId?: string
    mode: string
    outcome: 'allow' | 'deny'
    code: string
    reason: string
    target: string
    summary: string
  },
): void {
  const at = deps.now().toISOString()
  const dataScope = entry.ctx?.scope.label ?? ''
  deps.db
    .insert(accessLog)
    .values({
      at,
      tenantId: entry.tenantId ?? null,
      api: 'bo',
      operation: entry.operation,
      principal: entry.principal,
      agent: entry.agent ?? null,
      mode: entry.mode,
      outcome: entry.outcome,
      code: entry.code,
      reason: entry.reason,
      dataScope,
      target: entry.target,
    })
    .run()
  if (entry.tenantId === undefined) return
  void deps.link.publish({
    type: 'bo.access',
    tenantId: entry.tenantId,
    payload: {
      at,
      operation: entry.operation,
      userId: entry.principal,
      agent: `${(entry.agent ?? '').replace(/^agent:/, '')}${(entry.ctx?.agentVersion ?? entry.agentVersion) ? `@${entry.ctx?.agentVersion ?? entry.agentVersion}` : ''}`,
      mode: entry.mode,
      ...(entry.ctx?.delegationId === undefined ? {} : { delegationId: entry.ctx.delegationId }),
      outcome: entry.outcome,
      code: entry.code,
      reason: entry.reason,
      dataScope,
      target: entry.target,
      resultSummary: entry.summary,
    },
  })
}

/**
 * 声明一个 BO API 操作：operationId 与工具名一致；先过鉴权点 4，再执行业务；
 * 写操作支持 Idempotency-Key；每次裁决写访问日志并推送到平台审计。
 */
export function defineBoRoute<P extends z.ZodType, Q extends z.ZodType, B extends z.ZodType>(
  app: FastifyInstance,
  deps: ErpDeps,
  spec: RouteSpec<P, Q, B>,
): void {
  const isWrite = OPERATION_SPECS[spec.operation].writeLevel !== 'L0'
  const MAX_KEY_LENGTH = 128
  app.route({
    method: spec.method,
    url: spec.url,
    schema: {
      operationId: spec.operation,
      tags: ['bo'],
      summary: spec.summary,
      description: spec.description,
      ...(spec.params === undefined ? {} : { params: spec.params }),
      ...(spec.querystring === undefined ? {} : { querystring: spec.querystring }),
      ...(spec.body === undefined ? {} : { body: spec.body }),
    },
    handler: async (request, reply) => {
      let ctx: BoContext | undefined
      try {
        ctx = await authorizeBo(deps, request, spec.operation)
        const key = request.headers['idempotency-key']
        const fingerprint = createHash('sha256')
          .update(`${request.method} ${request.url} ${JSON.stringify(request.body ?? null)}`)
          .digest('hex')
        if (typeof key === 'string' && key.length > MAX_KEY_LENGTH) {
          throw new BoError(
            400,
            'IDEMPOTENCY_KEY_TOO_LONG',
            `幂等键不能超过 ${MAX_KEY_LENGTH} 个字符`,
            '使用较短的幂等键，例如 UUID',
          )
        }
        if (isWrite && typeof key === 'string' && key !== '') {
          const stored = deps.db
            .select()
            .from(idempotencyKeys)
            .where(
              and(
                eq(idempotencyKeys.tenantId, ctx.tenantId),
                eq(idempotencyKeys.operation, spec.operation),
                eq(idempotencyKeys.key, key),
              ),
            )
            .get()
          if (stored !== undefined) {
            // 只给同一个用户、同一份请求内容回放结果；否则拒绝，不泄露别人那次请求的响应
            if (stored.userId !== ctx.user.id || stored.fingerprint !== fingerprint) {
              throw new BoError(
                422,
                'IDEMPOTENCY_KEY_REUSED',
                '这个幂等键已被另一份请求使用',
                '每份请求使用新的幂等键',
              )
            }
            record(deps, {
              ctx,
              operation: spec.operation,
              principal: ctx.user.id,
              agent: ctx.agent,
              tenantId: ctx.tenantId,
              mode: ctx.mode,
              outcome: 'allow',
              code: 'IDEMPOTENT_REPLAY',
              reason: '幂等重放，返回首次结果，未重复执行',
              target: requestTarget(request.url),
              summary: '幂等重放',
            })
            return reply
              .status(stored.statusCode)
              .header('idempotent-replayed', 'true')
              .send(JSON.parse(stored.response))
          }
        }
        const outcome = await spec.handler(ctx, {
          params: request.params as z.infer<P>,
          query: request.query as z.infer<Q>,
          body: request.body as z.infer<B>,
        })
        const status = outcome.status ?? 200
        if (isWrite && typeof key === 'string' && key !== '') {
          deps.db
            .insert(idempotencyKeys)
            .values({
              tenantId: ctx.tenantId,
              operation: spec.operation,
              key,
              userId: ctx.user.id,
              fingerprint,
              statusCode: status,
              response: JSON.stringify(outcome.body),
              createdAt: deps.now().toISOString(),
            })
            .run()
        }
        record(deps, {
          ctx,
          operation: spec.operation,
          principal: ctx.user.id,
          agent: ctx.agent,
          tenantId: ctx.tenantId,
          mode: ctx.mode,
          outcome: 'allow',
          code: 'OK',
          reason: '允许',
          target: outcome.target,
          summary: outcome.summary,
        })
        return reply.status(status).send(outcome.body)
      } catch (error: unknown) {
        if (!(error instanceof BoError)) throw error
        // 鉴权通过后在业务处理中被拒绝（例如职责分离）时用调用上下文；鉴权阶段被拒绝时用令牌里的身份
        const caller =
          ctx === undefined
            ? error.caller
            : {
                tenantId: ctx.tenantId,
                userId: ctx.user.id,
                agent: ctx.agent,
                agentVersion: ctx.agentVersion,
                mode: ctx.mode,
              }
        record(deps, {
          ...(ctx === undefined ? {} : { ctx }),
          operation: spec.operation,
          principal: caller?.userId ?? 'unknown',
          ...(caller === undefined
            ? {}
            : {
                agent: caller.agent,
                tenantId: caller.tenantId,
                agentVersion: caller.agentVersion,
              }),
          mode: caller?.mode ?? 'unknown',
          outcome: 'deny',
          code: error.code,
          reason: error.reason,
          target: requestTarget(request.url),
          summary: '拒绝',
        })
        return reply
          .status(error.status)
          .send({ error: { code: error.code, reason: error.reason, suggestion: error.suggestion } })
      }
    },
  })
}

/** 审计里的请求目标只保留路径，不带查询字符串 */
function requestTarget(url: string): string {
  return url.split('?')[0] ?? url
}

/** 列表响应：附带数据权限过滤说明（主提示词 6.3 节） */
export function listBody<T>(
  ctx: BoContext,
  items: T[],
): { items: T[]; total: number; filtered: boolean; visibleScope: string[]; scopeLabel: string } {
  return {
    items,
    total: items.length,
    filtered: ctx.scope.filtered,
    visibleScope: ctx.scope.names,
    scopeLabel: ctx.scope.label,
  }
}

/** 是否在数据权限范围内；维度为 none 时只看租户 */
export function inScope(ctx: BoContext, value: string): boolean {
  return ctx.scope.dimension === 'none' || ctx.scope.values.includes(value)
}

export function notFound(what: string): BoError {
  return new BoError(
    404,
    'NOT_FOUND',
    `${what} 不存在，或不在你的数据权限范围内`,
    '确认单号，或联系有权限的同事',
  )
}
