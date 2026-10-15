import { timingSafeEqual } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import type { ZodTypeProvider } from 'fastify-type-provider-zod'
import { z } from 'zod'
import { USERS, findUser, isBoOperation, toPublicUser } from '@eab/demo-directory'
import { NameSchema, NamespaceSchema } from '@eab/spec'
import { AuthHeader, type RequirePrincipal } from '../http/auth'
import { sendError, success } from '../http/envelope'
import { assembleFromRegistry } from '../registry/effective'
import type { PlatformServices } from '../services'
import { declaredBoOperations } from './agent-scope'
import { TOKEN_TYPE } from './service'
import { TokenError } from './tokens'

export const INTERNAL_HEADER = 'x-eab-internal-token'

/** 平台与 DemoERP 之间的内部调用校验，常量时间比较 */
export function isInternalCall(header: unknown, secret: string): boolean {
  if (typeof header !== 'string') return false
  const a = Buffer.from(header)
  const b = Buffer.from(secret)
  return a.length === b.length && timingSafeEqual(a, b)
}

const TokenRequest = z.union([
  z.object({
    grant_type: z.literal('client_credentials'),
    client_id: z.string(),
    client_secret: z.string(),
  }),
  z.object({
    grant_type: z.literal('urn:ietf:params:oauth:grant-type:token-exchange'),
    subject_token: z.string(),
    subject_token_type: z.string(),
    actor_token: z.string(),
    actor_token_type: z.string().optional(),
    audience: z.string(),
    scope: z.string().optional(),
  }),
])

const DelegationBody = z.object({
  delegatorUserId: z.string(),
  agent: z.string().regex(/^[a-z0-9.-]+\/[a-z0-9-]+$/, 'Agent 写作 namespace/name'),
  scope: z.array(z.string()).min(1),
  validDays: z.number().int().min(1).max(365).default(30),
  note: z.string().optional(),
})

export function registerIdentityRoutes(
  app: FastifyInstance,
  services: PlatformServices,
  requirePrincipal: RequirePrincipal,
  options: { demoMode: boolean },
): void {
  const r = app.withTypeProvider<ZodTypeProvider>()
  const { identity, tokens, delegations } = services

  // RFC 6749/8693 的令牌端点按惯例接受表单编码
  app.addContentTypeParser('application/x-www-form-urlencoded', { parseAs: 'string' }, (_req, body, done) => {
    done(null, Object.fromEntries(new URLSearchParams(String(body))))
  })

  r.get('/identity/v1/users', { schema: { tags: ['identity'], summary: '演示用户列表（选人即登录）' } }, async () =>
    success(USERS.map(toPublicUser)),
  )

  r.get('/identity/v1/jwks.json', { schema: { tags: ['identity'], summary: '令牌校验公钥（JWKS）' } }, async () =>
    tokens.jwks(),
  )

  r.post(
    '/identity/v1/login',
    {
      schema: {
        tags: ['identity'],
        summary: '选人即登录，签发用户令牌（仅用于演示，没有密码）',
        body: z.object({ userId: z.string() }),
      },
    },
    async (request, reply) => {
      try {
        const { token, user } = await identity.login(request.body.userId)
        return success({ token, user: toPublicUser(user) })
      } catch (error: unknown) {
        return sendError(reply, 404, { code: 'UNKNOWN_USER', message: error instanceof Error ? error.message : '' })
      }
    },
  )

  r.post(
    '/identity/v1/token',
    {
      schema: {
        tags: ['identity'],
        summary: '令牌端点：客户端凭证，或 RFC 8693 令牌交换（用户 + Agent → 委托令牌）',
        body: TokenRequest,
      },
    },
    async (request, reply) => {
      const body = request.body
      try {
        if (body.grant_type === 'client_credentials') {
          const accessToken = await identity.clientCredentials(body.client_id, body.client_secret)
          return { access_token: accessToken, token_type: 'Bearer', expires_in: 3600 }
        }
        const result = await identity.exchange({
          subjectToken: body.subject_token,
          subjectTokenType: body.subject_token_type,
          actorToken: body.actor_token,
          audience: body.audience,
          ...(body.scope === undefined ? {} : { scope: body.scope.split(' ').filter((s) => s !== '') }),
        })
        return {
          access_token: result.accessToken,
          issued_token_type: TOKEN_TYPE.accessToken,
          token_type: 'Bearer',
          expires_in: result.expiresIn,
          scope: result.scope.join(' '),
        }
      } catch (error: unknown) {
        // RFC 6749 的错误格式；令牌交换失败绝不退回任何技术账号
        const code = error instanceof TokenError ? error.code : 'EXCHANGE_FAILED'
        return reply.status(400).send({
          error: code === 'INVALID_CLIENT' ? 'invalid_client' : 'invalid_grant',
          error_code: code,
          error_description: error instanceof Error ? error.message : String(error),
        })
      }
    },
  )

  // ── 委托管理（租户管理员） ───────────────────────────────────────────

  r.get(
    '/identity/v1/delegations',
    { schema: { tags: ['identity'], summary: '本租户的委托清单', headers: AuthHeader } },
    async (request, reply) => {
      const principal = await requirePrincipal(request, reply)
      if (principal === undefined) return reply
      if (principal.viewer.type !== 'tenant') return success([])
      return success(delegations.listByTenant(principal.viewer.tenantId))
    },
  )

  r.post(
    '/identity/v1/delegations',
    { schema: { tags: ['identity'], summary: '登记委托（租户管理员）', headers: AuthHeader, body: DelegationBody } },
    async (request, reply) => {
      const principal = await requirePrincipal(request, reply)
      if (principal === undefined) return reply
      const { user } = principal
      const delegator = findUser(request.body.delegatorUserId)
      if (!user.roles.includes('tenant-admin') || delegator?.tenantId !== user.tenantId) {
        return sendError(reply, 403, { code: 'FORBIDDEN', message: '只有本租户的租户管理员可以为本租户用户登记委托' })
      }
      const unknown = request.body.scope.filter((op) => !isBoOperation(op))
      if (unknown.length > 0) {
        return sendError(reply, 422, { code: 'UNKNOWN_OPERATION', message: `未知的 BO 操作：${unknown.join('、')}` })
      }
      const now = new Date()
      const delegation = delegations.create({
        tenantId: user.tenantId,
        delegatorUserId: delegator.id,
        agent: request.body.agent,
        scope: request.body.scope,
        validFrom: now.toISOString(),
        validUntil: new Date(now.getTime() + request.body.validDays * 86_400_000).toISOString(),
        ...(request.body.note === undefined ? {} : { note: request.body.note }),
        createdBy: user.id,
      })
      services.audit.append({
        actor: user.id,
        action: 'identity.delegation.create',
        target: delegation.id,
        decision: 'allow',
        reason: `${delegator.name} 委托 ${delegation.agent}`,
        tenantId: user.tenantId,
        userId: delegator.id,
        agent: delegation.agent,
      })
      return reply.status(201).send(success(delegation))
    },
  )

  r.delete(
    '/identity/v1/delegations/:id',
    {
      schema: {
        tags: ['identity'],
        summary: '撤销委托，即时生效（租户管理员）',
        headers: AuthHeader,
        params: z.object({ id: z.string() }),
      },
    },
    async (request, reply) => {
      const principal = await requirePrincipal(request, reply)
      if (principal === undefined) return reply
      const existing = delegations.get(request.params.id)
      if (existing === undefined || existing.tenantId !== principal.user.tenantId) {
        return sendError(reply, 404, { code: 'NOT_FOUND', message: `委托 ${request.params.id} 不存在` })
      }
      if (!principal.user.roles.includes('tenant-admin')) {
        return sendError(reply, 403, { code: 'FORBIDDEN', message: '只有租户管理员可以撤销委托' })
      }
      const revoked = delegations.revoke(existing.id, principal.user.id)
      services.audit.append({
        actor: principal.user.id,
        action: 'identity.delegation.revoke',
        target: existing.id,
        decision: 'allow',
        reason: `撤销 ${existing.agent} 的委托`,
        tenantId: existing.tenantId,
        userId: existing.delegatorUserId,
        agent: existing.agent,
      })
      return success(revoked)
    },
  )

  r.get(
    '/identity/v1/delegations/:id/status',
    {
      schema: {
        tags: ['identity'],
        summary: '核实委托是否有效（供 DemoERP 调用，需要内部调用密钥）',
        params: z.object({ id: z.string() }),
      },
    },
    async (request, reply) => {
      if (!isInternalCall(request.headers[INTERNAL_HEADER], services.internalSecret)) {
        return sendError(reply, 401, { code: 'INTERNAL_ONLY', message: '只接受内部调用' })
      }
      const status = delegations.status(request.params.id)
      return success(
        status.active
          ? { active: true, tenantId: status.delegation.tenantId, agent: status.delegation.agent }
          : { active: false, reason: status.reason },
      )
    },
  )

  // ── 演示模式：平台运维以运行时身份为 Agent 签发令牌，用于手工验证令牌交换 ──
  if (options.demoMode) {
    r.post(
      '/identity/v1/demo/agent-token',
      {
        schema: {
          tags: ['identity'],
          summary: '演示模式：为某租户组装 Agent 并签发 Agent 令牌（平台运维）',
          headers: AuthHeader,
          body: z.object({ namespace: NamespaceSchema, name: NameSchema, tenantId: z.string() }),
        },
      },
      async (request, reply) => {
        const principal = await requirePrincipal(request, reply)
        if (principal === undefined) return reply
        if (principal.viewer.type !== 'platform') {
          return sendError(reply, 403, { code: 'FORBIDDEN', message: '只有平台运维可以代运行时签发 Agent 令牌' })
        }
        const { namespace, name, tenantId } = request.body
        const scope = { type: 'tenant', tenantId } as const
        const outcome = assembleFromRegistry(services.registry, scope, { agent: `${namespace}/${name}`, tenantId })
        if (!outcome.ok) return sendError(reply, 422, { code: 'ASSEMBLY_FAILED', message: '组装失败', details: outcome.issues })
        const operations = declaredBoOperations(outcome.definition, services.registry.resolverFor(scope))
        const version = outcome.definition.provenance.agent.split('@')[1] ?? ''
        const token = await identity.issueAgentToken({ agent: `${namespace}/${name}`, version, tenantId, operations })
        return success({ token, operations })
      },
    )
  }
}
