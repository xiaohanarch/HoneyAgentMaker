import { timingSafeEqual } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import type { ErpDeps } from '../bo/context'

export const BUSINESS_EVENTS = [
  'delivery.overdue',
  'inventory.below_safety_stock',
  'transaction.anomaly_created',
  'period.close_check_failed',
] as const

/** 各事件的默认载荷，取自演示故事数据 */
const DEFAULT_PAYLOADS: Readonly<
  Record<(typeof BUSINESS_EVENTS)[number], Record<string, unknown>>
> = {
  'delivery.overdue': {
    poId: 'PO-2026-0117',
    supplierId: 'S-0001',
    supplierName: '百润精密',
    overdueDays: 120,
    materialId: 'M-0007',
  },
  'inventory.below_safety_stock': {
    materialId: 'M-0007',
    materialName: '精密级连接器',
    warehouseId: 'W-01',
    quantity: 120,
    safetyStock: 800,
  },
  'transaction.anomaly_created': {
    anomalyId: 'ANO-2026-0003',
    severity: 'critical',
    amount: 1_280_000,
    currency: 'CNY',
  },
  'period.close_check_failed': { product: 'asset-accounting', companyId: 'C100', failed: 3 },
}

function isInternal(header: unknown, secret: string): boolean {
  if (typeof header !== 'string') return false
  const a = Buffer.from(header)
  const b = Buffer.from(secret)
  return a.length === b.length && timingSafeEqual(a, b)
}

/**
 * 业务事件（主提示词 6.4 节）：演示控制台手工触发，DemoERP 通过 webhook 推送给平台。
 * 只接受带内部调用密钥的请求（演示控制台经平台转发）。
 */
export function registerEventRoutes(
  app: FastifyInstance,
  deps: ErpDeps,
  internalSecret: string,
): void {
  app.post(
    '/demo/v1/events/trigger',
    {
      schema: {
        tags: ['demo'],
        summary: '演示控制台：触发一个业务事件',
        body: z.object({
          type: z.enum(BUSINESS_EVENTS),
          tenantId: z.string().default('t001'),
          payload: z.record(z.string(), z.unknown()).optional(),
        }),
      },
    },
    async (request, reply) => {
      if (!isInternal(request.headers['x-eab-internal-token'], internalSecret)) {
        return reply.status(401).send({
          error: {
            code: 'INTERNAL_ONLY',
            reason: '只接受内部调用',
            suggestion: '通过平台的演示控制台触发',
          },
        })
      }
      const body = request.body as {
        type: (typeof BUSINESS_EVENTS)[number]
        tenantId: string
        payload?: Record<string, unknown>
      }
      const event = {
        type: body.type,
        tenantId: body.tenantId,
        payload: {
          ...DEFAULT_PAYLOADS[body.type],
          ...body.payload,
          occurredAt: deps.now().toISOString(),
        },
      }
      await deps.link.publish(event)
      return reply.status(202).send({ accepted: true, event })
    },
  )
}
