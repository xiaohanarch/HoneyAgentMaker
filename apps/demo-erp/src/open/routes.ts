import { eq } from 'drizzle-orm'
import type { FastifyInstance, FastifyRequest } from 'fastify'
import { z } from 'zod'
import type { ErpDeps } from '../bo/context'
import { deliveryStatus, orgName, supplierNames } from '../bo/queries'
import { accessLog, purchaseOrders, suppliers } from '../db/schema'

/**
 * Open API（主提示词 6.3 节）：给系统集成用，客户端凭证认证，只做租户级隔离，不做数据权限过滤。
 * 演示里作为反面对照：同样的问题走 Open API 会返回所有采购组织的数据，所以 Agent 必须走 BO API。
 */
async function authorizeClient(
  deps: ErpDeps,
  request: FastifyRequest,
): Promise<{ tenantId: string; client: string } | undefined> {
  const header = request.headers.authorization
  const token =
    typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : undefined
  if (token === undefined) return undefined
  try {
    const claims = await deps.link.verify(token, 'demo-erp-open')
    if (claims.kind !== 'client' || typeof claims.tid !== 'string') return undefined
    return { tenantId: claims.tid, client: String(claims.sub) }
  } catch {
    return undefined
  }
}

function logAccess(
  deps: ErpDeps,
  tenantId: string,
  client: string,
  operation: string,
  count: number,
): void {
  const at = deps.now().toISOString()
  deps.db
    .insert(accessLog)
    .values({
      at,
      tenantId,
      api: 'open',
      operation,
      principal: client,
      agent: null,
      mode: 'integration',
      outcome: 'allow',
      code: 'OK',
      reason: '集成账号，仅租户隔离',
      dataScope: '租户：全部数据（未按采购组织过滤）',
      target: operation,
    })
    .run()
  void deps.link.publish({
    type: 'bo.access',
    tenantId,
    payload: {
      at,
      operation: `open:${operation}`,
      userId: client,
      agent: '',
      mode: 'integration',
      outcome: 'allow',
      code: 'OK',
      reason: '集成账号调用 Open API，只做租户隔离',
      dataScope: '租户：全部数据（未按采购组织过滤）',
      target: operation,
      resultSummary: `返回 ${count} 条`,
    },
  })
}

export function registerOpenRoutes(app: FastifyInstance, deps: ErpDeps): void {
  const unauthorized = {
    error: {
      code: 'CLIENT_AUTH_REQUIRED',
      reason: 'Open API 只接受集成账号的客户端凭证令牌',
      suggestion: '用 client_credentials 换取令牌',
    },
  }

  app.get(
    '/open/v1/suppliers',
    { schema: { tags: ['open'], summary: '集成接口：供应商（不做数据权限过滤）' } },
    async (request, reply) => {
      const auth = await authorizeClient(deps, request)
      if (auth === undefined) return reply.status(401).send(unauthorized)
      const items = deps.db
        .select()
        .from(suppliers)
        .where(eq(suppliers.tenantId, auth.tenantId))
        .all()
        .map((s) => ({ ...s, purchasingOrgName: orgName(auth.tenantId, s.purchasingOrg) }))
      logAccess(deps, auth.tenantId, auth.client, 'suppliers', items.length)
      return { items, total: items.length, filtered: false, visibleScope: ['全部'] }
    },
  )

  app.get(
    '/open/v1/purchase-orders',
    {
      schema: {
        tags: ['open'],
        summary: '集成接口：采购订单（不做数据权限过滤）',
        querystring: z.object({ overdueDaysGt: z.coerce.number().int().min(0).optional() }),
      },
    },
    async (request, reply) => {
      const auth = await authorizeClient(deps, request)
      if (auth === undefined) return reply.status(401).send(unauthorized)
      const { overdueDaysGt } = request.query as { overdueDaysGt?: number }
      const names = supplierNames(deps.db, auth.tenantId)
      const rows = deps.db
        .select()
        .from(purchaseOrders)
        .where(eq(purchaseOrders.tenantId, auth.tenantId))
        .all()
      const status = deliveryStatus(
        deps.db,
        auth.tenantId,
        rows.map((r) => r.id),
        deps.now(),
      )
      const items = rows
        .map((po) => ({
          ...po,
          supplierName: names.get(po.supplierId) ?? po.supplierId,
          purchasingOrgName: orgName(auth.tenantId, po.purchasingOrg),
          overdueDays: status.get(po.id)?.overdueDays ?? 0,
        }))
        .filter((po) => overdueDaysGt === undefined || po.overdueDays > overdueDaysGt)
        .sort((a, b) => b.overdueDays - a.overdueDays)
      logAccess(deps, auth.tenantId, auth.client, 'purchase-orders', items.length)
      return { items, total: items.length, filtered: false, visibleScope: ['全部'] }
    },
  )
}
