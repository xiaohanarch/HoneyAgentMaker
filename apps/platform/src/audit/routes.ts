import type { FastifyInstance } from 'fastify'
import type { ZodTypeProvider } from 'fastify-type-provider-zod'
import { z } from 'zod'
import { AuthHeader, type RequirePrincipal } from '../http/auth'
import { sendError, success } from '../http/envelope'
import type { AuditStore } from './audit-store'

/**
 * 审计查询（主提示词 7.5 节）：按用户、Agent、工具、时间、结果查询，
 * 用 target 可以反查“PO-2026-0117 的交期是谁通过哪个 Agent 改的”。
 * 租户身份只能看本租户的记录；平台身份能看全部；厂商身份看不到任何租户的审计。
 */
export function registerAuditRoutes(
  app: FastifyInstance,
  audit: AuditStore,
  requirePrincipal: RequirePrincipal,
): void {
  const r = app.withTypeProvider<ZodTypeProvider>()
  r.get(
    '/audit/events',
    {
      schema: {
        tags: ['audit'],
        summary: '查询审计事件',
        headers: AuthHeader,
        querystring: z.object({
          action: z.string().optional(),
          decision: z.enum(['allow', 'deny', 'confirm', 'approve']).optional(),
          userId: z.string().optional(),
          agent: z.string().optional(),
          tool: z.string().optional(),
          checkpoint: z.string().optional(),
          target: z.string().optional(),
          from: z.string().optional(),
          to: z.string().optional(),
          limit: z.coerce.number().int().min(1).max(500).optional(),
        }),
      },
    },
    async (request, reply) => {
      const principal = await requirePrincipal(request, reply)
      if (principal === undefined) return reply
      const { viewer, user } = principal
      if (viewer.type === 'vendor') {
        return sendError(reply, 403, { code: 'FORBIDDEN', message: '厂商身份不能查看租户审计' })
      }
      const isAdmin = user.roles.includes('tenant-admin') || viewer.type === 'platform'
      // 普通租户用户只能查自己的记录；租户管理员查本租户；平台查全部
      const scope =
        viewer.type === 'platform'
          ? {}
          : isAdmin
            ? { tenantId: viewer.tenantId }
            : { tenantId: viewer.tenantId, userId: user.id }
      return success(audit.list({ ...request.query, ...scope }))
    },
  )
}
