import { and, count, desc, eq, inArray } from 'drizzle-orm'
import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import {
  deliveries,
  deliveryDateChanges,
  poLines,
  purchaseOrders,
  quotations,
  rfqs,
  suppliers,
} from '../db/schema'
import { isoDate, pad } from '../seed/random'
import { BoError, defineBoRoute, inScope, listBody, notFound, type ErpDeps } from './context'
import { deliveryStatus, findPurchaseOrder, orgName, supplierNames } from './queries'

const Limit = z.coerce.number().int().min(1).max(200).default(50).describe('最多返回条数')
const IsoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '日期格式为 YYYY-MM-DD')

/** 采购域的 BO API（主提示词 6.1 节），数据权限按采购组织过滤 */
export function registerPurchasingRoutes(app: FastifyInstance, deps: ErpDeps): void {
  const { db } = deps

  defineBoRoute(app, deps, {
    operation: 'supplier_list',
    method: 'GET',
    url: '/bo/v1/suppliers',
    summary: '查询供应商',
    description:
      '按名称（模糊匹配）、评级查询供应商。评级含义：A 战略供应商、B 合格供应商、C 观察期供应商。结果按当前用户的采购组织过滤。',
    querystring: z.object({
      name: z.string().optional().describe('供应商名称，模糊匹配，例如 百润'),
      rating: z.enum(['A', 'B', 'C']).optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const rows = db
        .select()
        .from(suppliers)
        .where(
          and(
            eq(suppliers.tenantId, ctx.tenantId),
            inArray(suppliers.purchasingOrg, ctx.scope.values),
          ),
        )
        .all()
        .filter(
          (s) =>
            (query.name === undefined || s.name.includes(query.name)) &&
            (query.rating === undefined || s.rating === query.rating),
        )
        .slice(0, query.limit)
        .map((s) => ({ ...s, purchasingOrgName: orgName(ctx.tenantId, s.purchasingOrg) }))
      return {
        body: listBody(ctx, rows),
        target: query.name ?? '供应商',
        summary: `返回 ${rows.length} 个供应商`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'supplier_get',
    method: 'GET',
    url: '/bo/v1/suppliers/:supplierId',
    summary: '查询单个供应商',
    description: '供应商主数据、评级与合同条款（价格、质保月数、交期天数、付款条件）。',
    params: z.object({ supplierId: z.string().describe('供应商编号，例如 S-0001') }),
    handler: (ctx, { params }) => {
      const supplier = db
        .select()
        .from(suppliers)
        .where(and(eq(suppliers.tenantId, ctx.tenantId), eq(suppliers.id, params.supplierId)))
        .get()
      if (supplier === undefined || !inScope(ctx, supplier.purchasingOrg))
        throw notFound(`供应商 ${params.supplierId}`)
      return {
        body: {
          item: { ...supplier, purchasingOrgName: orgName(ctx.tenantId, supplier.purchasingOrg) },
          filtered: ctx.scope.filtered,
          visibleScope: ctx.scope.names,
        },
        target: supplier.id,
        summary: supplier.name,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'rfq_list',
    method: 'GET',
    url: '/bo/v1/rfqs',
    summary: '查询询价单与报价',
    description:
      '按供应商查询询价单，按发出时间倒序。每张询价单附报价行，以及最低报价与目标价的差异率（语义包指标 quote_price_variance）。',
    querystring: z.object({
      supplierId: z.string().optional(),
      supplierName: z.string().optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const names = supplierNames(db, ctx.tenantId)
      const rows = db
        .select()
        .from(rfqs)
        .where(and(eq(rfqs.tenantId, ctx.tenantId), inArray(rfqs.purchasingOrg, ctx.scope.values)))
        .orderBy(desc(rfqs.issuedAt))
        .all()
        .filter(
          (r) =>
            (query.supplierId === undefined || r.supplierId === query.supplierId) &&
            (query.supplierName === undefined ||
              (names.get(r.supplierId) ?? '').includes(query.supplierName)),
        )
        .slice(0, query.limit)
      const quotes =
        rows.length === 0
          ? []
          : db
              .select()
              .from(quotations)
              .where(
                and(
                  eq(quotations.tenantId, ctx.tenantId),
                  inArray(
                    quotations.rfqId,
                    rows.map((r) => r.id),
                  ),
                ),
              )
              .all()
      const items = rows.map((r) => {
        const lines = quotes.filter((q) => q.rfqId === r.id).sort((a, b) => a.lineNo - b.lineNo)
        const lowest = lines.length === 0 ? null : Math.min(...lines.map((q) => q.unitPrice))
        return {
          ...r,
          supplierName: names.get(r.supplierId) ?? r.supplierId,
          quotationLines: lines.map((q) => ({
            lineNo: q.lineNo,
            quantity: q.quantity,
            unitPrice: q.unitPrice,
          })),
          lowestPrice: lowest,
          priceVariance:
            lowest === null
              ? null
              : Math.round(((lowest - r.targetPrice) / r.targetPrice) * 10_000) / 100,
        }
      })
      return {
        body: listBody(ctx, items),
        target: query.supplierName ?? query.supplierId ?? '询价单',
        summary: `返回 ${items.length} 张询价单`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'purchase_order_list',
    method: 'GET',
    url: '/bo/v1/purchase-orders',
    summary: '查询采购订单',
    description:
      '按供应商、状态、逾期天数查询采购订单。逾期天数口径：计划交付日期到实际交付日期（未交付则到今天）的自然日差，取订单行最大值。',
    querystring: z.object({
      supplierId: z.string().optional(),
      supplierName: z.string().optional(),
      status: z.enum(['open', 'partially_delivered', 'delivered']).optional(),
      overdueDaysGt: z.coerce
        .number()
        .int()
        .min(0)
        .optional()
        .describe('只返回逾期天数大于该值的订单'),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const names = supplierNames(db, ctx.tenantId)
      const rows = db
        .select()
        .from(purchaseOrders)
        .where(
          and(
            eq(purchaseOrders.tenantId, ctx.tenantId),
            inArray(purchaseOrders.purchasingOrg, ctx.scope.values),
          ),
        )
        .all()
        .filter(
          (po) =>
            (query.supplierId === undefined || po.supplierId === query.supplierId) &&
            (query.status === undefined || po.status === query.status) &&
            (query.supplierName === undefined ||
              (names.get(po.supplierId) ?? '').includes(query.supplierName)),
        )
      const status = deliveryStatus(
        db,
        ctx.tenantId,
        rows.map((r) => r.id),
        deps.now(),
      )
      const items = rows
        .map((po) => ({
          ...po,
          supplierName: names.get(po.supplierId) ?? po.supplierId,
          purchasingOrgName: orgName(ctx.tenantId, po.purchasingOrg),
          overdueDays: status.get(po.id)?.overdueDays ?? 0,
          nextPlannedDate: status.get(po.id)?.nextPlannedDate ?? null,
        }))
        .filter((po) => query.overdueDaysGt === undefined || po.overdueDays > query.overdueDaysGt)
        .sort((a, b) => b.overdueDays - a.overdueDays)
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.supplierName ?? '采购订单',
        summary: `返回 ${items.length} 张订单`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'purchase_order_change_delivery_date',
    method: 'POST',
    url: '/bo/v1/purchase-orders/:poId/delivery-date-changes',
    summary: '提交交期变更',
    description:
      '为采购订单提交一张交期变更单（状态为已提交），审批通过后才改写订单交期。写操作级别 L2，支持 Idempotency-Key。',
    params: z.object({ poId: z.string().describe('采购订单号，例如 PO-2026-0117') }),
    body: z.object({
      newDate: IsoDate.describe('新的计划交期'),
      reason: z.string().min(1).describe('变更原因'),
    }),
    handler: (ctx, { params, body }) => {
      const po = findPurchaseOrder(db, ctx.tenantId, params.poId)
      if (po === undefined || !inScope(ctx, po.purchasingOrg))
        throw notFound(`采购订单 ${params.poId}`)
      if (body.newDate <= isoDate(deps.now())) {
        throw new BoError(422, 'INVALID_DATE', '新交期必须晚于今天', '选择今天之后的日期')
      }
      // 同一张订单只能有一张待审批的变更单，避免审批时按过期的原交期改写
      const pending = db
        .select()
        .from(deliveryDateChanges)
        .where(
          and(
            eq(deliveryDateChanges.tenantId, ctx.tenantId),
            eq(deliveryDateChanges.poId, po.id),
            eq(deliveryDateChanges.status, 'submitted'),
          ),
        )
        .get()
      if (pending !== undefined) {
        throw new BoError(
          409,
          'CHANGE_PENDING',
          `${po.id} 已有待审批的交期变更单 ${pending.id}`,
          '等待该变更单审批后再提交',
        )
      }
      const oldDate = deliveryStatus(db, ctx.tenantId, [po.id], deps.now()).get(
        po.id,
      )?.nextPlannedDate
      if (oldDate === null || oldDate === undefined) {
        throw new BoError(
          409,
          'ALREADY_DELIVERED',
          `${po.id} 已全部交付，不能修改交期`,
          '确认订单号',
        )
      }
      const n =
        (db
          .select({ n: count() })
          .from(deliveryDateChanges)
          .where(eq(deliveryDateChanges.tenantId, ctx.tenantId))
          .get()?.n ?? 0) + 1
      const change = db
        .insert(deliveryDateChanges)
        .values({
          id: `DC-2026-${pad(n, 4)}`,
          tenantId: ctx.tenantId,
          poId: po.id,
          oldDate,
          newDate: body.newDate,
          reason: body.reason,
          status: 'submitted',
          requestedBy: ctx.user.id,
          requestedVia: ctx.agent,
          createdAt: deps.now().toISOString(),
        })
        .returning()
        .get()
      return {
        status: 201,
        body: { item: change },
        target: `${po.id} ${change.id}`,
        summary: `交期 ${oldDate} → ${body.newDate}，变更单 ${change.id} 待审批`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'purchase_order_change_approve',
    method: 'POST',
    url: '/bo/v1/delivery-date-changes/:changeId/approve',
    summary: '审批交期变更',
    description:
      '审批交期变更单并改写订单未交付行的计划交期。职责分离：提交人不能审批自己提交的变更单。写操作级别 L2。',
    params: z.object({ changeId: z.string().describe('交期变更单号，例如 DC-2026-0001') }),
    handler: (ctx, { params }) => {
      const change = db
        .select()
        .from(deliveryDateChanges)
        .where(
          and(
            eq(deliveryDateChanges.tenantId, ctx.tenantId),
            eq(deliveryDateChanges.id, params.changeId),
          ),
        )
        .get()
      const po = change === undefined ? undefined : findPurchaseOrder(db, ctx.tenantId, change.poId)
      if (change === undefined || po === undefined || !inScope(ctx, po.purchasingOrg))
        throw notFound(`交期变更单 ${params.changeId}`)
      if (change.status !== 'submitted')
        throw new BoError(
          409,
          'INVALID_STATE',
          `变更单 ${change.id} 当前状态为 ${change.status}`,
          '只能审批已提交的变更单',
        )
      // ERP 自身的职责分离检查：即使平台侧漏了，这里也拦得住
      if (change.requestedBy === ctx.user.id) {
        throw new BoError(
          403,
          'SOD_VIOLATION',
          `变更单 ${change.id} 由 ${ctx.user.name} 提交，同一用户不能审批（职责分离）`,
          '请由其他有审批权限的同事审批',
        )
      }
      const approved = db
        .update(deliveryDateChanges)
        .set({
          status: 'approved',
          approvedBy: ctx.user.id,
          approvedVia: ctx.agent,
          approvedAt: deps.now().toISOString(),
        })
        .where(
          and(
            eq(deliveryDateChanges.tenantId, ctx.tenantId),
            eq(deliveryDateChanges.id, change.id),
          ),
        )
        .returning()
        .get()
      db.update(poLines)
        .set({ plannedDeliveryDate: change.newDate })
        .where(
          and(
            eq(poLines.tenantId, ctx.tenantId),
            eq(poLines.poId, change.poId),
            eq(poLines.plannedDeliveryDate, change.oldDate),
          ),
        )
        .run()
      return {
        body: { item: approved },
        target: `${change.poId} ${change.id}`,
        summary: `审批通过，${change.poId} 交期改为 ${change.newDate}`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'delivery_list',
    method: 'GET',
    url: '/bo/v1/deliveries',
    summary: '查询交付记录',
    description:
      '查询交付记录（计划交付日期、实际交付日期、数量），未交付的订单行不出现在这里，用采购订单查询看逾期。',
    querystring: z.object({
      poId: z.string().optional(),
      supplierName: z.string().optional(),
      from: IsoDate.optional(),
      to: IsoDate.optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const names = supplierNames(db, ctx.tenantId)
      const pos = new Map(
        db
          .select()
          .from(purchaseOrders)
          .where(
            and(
              eq(purchaseOrders.tenantId, ctx.tenantId),
              inArray(purchaseOrders.purchasingOrg, ctx.scope.values),
            ),
          )
          .all()
          .map((p) => [p.id, p]),
      )
      const items = db
        .select()
        .from(deliveries)
        .where(eq(deliveries.tenantId, ctx.tenantId))
        .all()
        .filter((d) => pos.has(d.poId))
        .filter(
          (d) =>
            (query.poId === undefined || d.poId === query.poId) &&
            (query.from === undefined || d.plannedDate >= query.from) &&
            (query.to === undefined || d.plannedDate <= query.to),
        )
        .map((d) => ({ ...d, supplierName: names.get(pos.get(d.poId)?.supplierId ?? '') ?? '' }))
        .filter(
          (d) => query.supplierName === undefined || d.supplierName.includes(query.supplierName),
        )
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.poId ?? query.supplierName ?? '交付记录',
        summary: `返回 ${items.length} 条交付记录`,
      }
    },
  })
}
