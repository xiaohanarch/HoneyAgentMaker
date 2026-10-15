import { and, count, desc, eq, inArray } from 'drizzle-orm'
import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import {
  accountingPeriods,
  apInvoices,
  closeCheckItems,
  followupTasks,
  inventoryBalances,
  materials,
  paymentPlans,
  stockMovements,
  transactionAnomalies,
  warehouses,
} from '../db/schema'
import { isoDate, pad } from '../seed/random'
import { defineBoRoute, inScope, listBody, notFound, type ErpDeps } from './context'
import { companyName, supplierNames } from './queries'

const Limit = z.coerce.number().int().min(1).max(200).default(50).describe('最多返回条数')
const BooleanQuery = z.enum(['true', 'false']).transform((v) => v === 'true')

/** 库存与财务域的 BO API，数据权限按公司主体过滤；物料主数据与跟进任务只做租户隔离 */
export function registerInventoryFinanceRoutes(app: FastifyInstance, deps: ErpDeps): void {
  const { db } = deps

  defineBoRoute(app, deps, {
    operation: 'material_list',
    method: 'GET',
    url: '/bo/v1/materials',
    summary: '查询物料',
    description: '物料主数据与安全库存设置。物料是租户级主数据，不按公司主体过滤。',
    querystring: z.object({ name: z.string().optional(), id: z.string().optional(), limit: Limit }),
    handler: (ctx, { query }) => {
      const items = db
        .select()
        .from(materials)
        .where(eq(materials.tenantId, ctx.tenantId))
        .all()
        .filter(
          (m) =>
            (query.id === undefined || m.id === query.id) &&
            (query.name === undefined || m.name.includes(query.name)),
        )
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.id ?? query.name ?? '物料',
        summary: `返回 ${items.length} 种物料`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'inventory_balance_list',
    method: 'GET',
    url: '/bo/v1/inventory-balances',
    summary: '查询库存余额',
    description:
      '各仓库的库存余额、库龄与呆滞标识（none 正常、watch 库龄超过 90 天、slow 库龄超过 180 天）。belowSafetyStock=true 只返回低于安全库存的记录。',
    querystring: z.object({
      materialId: z.string().optional(),
      slowMoving: z.enum(['none', 'watch', 'slow']).optional(),
      belowSafetyStock: BooleanQuery.optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const safety = new Map(
        db
          .select()
          .from(materials)
          .where(eq(materials.tenantId, ctx.tenantId))
          .all()
          .map((m) => [m.id, m]),
      )
      const names = new Map(
        db
          .select()
          .from(warehouses)
          .where(eq(warehouses.tenantId, ctx.tenantId))
          .all()
          .map((w) => [w.id, w.name]),
      )
      const items = db
        .select()
        .from(inventoryBalances)
        .where(
          and(
            eq(inventoryBalances.tenantId, ctx.tenantId),
            inArray(inventoryBalances.companyId, ctx.scope.values),
          ),
        )
        .all()
        .map((b) => {
          const material = safety.get(b.materialId)
          return {
            ...b,
            materialName: material?.name ?? b.materialId,
            unit: material?.unit ?? '',
            safetyStock: material?.safetyStock ?? 0,
            belowSafetyStock: b.quantity < (material?.safetyStock ?? 0),
            warehouseName: names.get(b.warehouseId) ?? b.warehouseId,
            companyName: companyName(ctx.tenantId, b.companyId),
          }
        })
        .filter(
          (b) =>
            (query.materialId === undefined || b.materialId === query.materialId) &&
            (query.slowMoving === undefined || b.slowMoving === query.slowMoving) &&
            (query.belowSafetyStock !== true || b.belowSafetyStock),
        )
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.materialId ?? '库存余额',
        summary: `返回 ${items.length} 条库存余额`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'stock_movement_list',
    method: 'GET',
    url: '/bo/v1/stock-movements',
    summary: '查询出入库流水',
    description: '最近 90 天的出入库流水，direction 为 in 入库、out 出库。',
    querystring: z.object({ materialId: z.string().optional(), limit: Limit }),
    handler: (ctx, { query }) => {
      const items = db
        .select()
        .from(stockMovements)
        .where(
          and(
            eq(stockMovements.tenantId, ctx.tenantId),
            inArray(stockMovements.companyId, ctx.scope.values),
          ),
        )
        .orderBy(desc(stockMovements.date))
        .all()
        .filter((m) => query.materialId === undefined || m.materialId === query.materialId)
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.materialId ?? '出入库流水',
        summary: `返回 ${items.length} 条流水`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'ap_invoice_list',
    method: 'GET',
    url: '/bo/v1/ap-invoices',
    summary: '查询应付发票',
    description:
      '应付发票及三单匹配状态：matched 三单匹配、price_diff 价格差异、qty_diff 数量差异、unmatched 未匹配。金额币种见 currency。',
    querystring: z.object({
      invoiceNo: z.string().optional(),
      supplierName: z.string().optional(),
      matchStatus: z.enum(['matched', 'price_diff', 'qty_diff', 'unmatched']).optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const names = supplierNames(db, ctx.tenantId)
      const items = db
        .select()
        .from(apInvoices)
        .where(
          and(
            eq(apInvoices.tenantId, ctx.tenantId),
            inArray(apInvoices.companyId, ctx.scope.values),
          ),
        )
        .all()
        .map((i) => ({ ...i, supplierName: names.get(i.supplierId) ?? i.supplierId }))
        .filter(
          (i) =>
            (query.invoiceNo === undefined || i.id === query.invoiceNo) &&
            (query.matchStatus === undefined || i.matchStatus === query.matchStatus) &&
            (query.supplierName === undefined || i.supplierName.includes(query.supplierName)),
        )
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: query.invoiceNo ?? '应付发票',
        summary: `返回 ${items.length} 张发票`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'payment_plan_list',
    method: 'GET',
    url: '/bo/v1/payment-plans',
    summary: '查询付款计划',
    description: '付款计划：planned 待付、paid 已付、on_hold 暂停。',
    querystring: z.object({
      status: z.enum(['planned', 'paid', 'on_hold']).optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const items = db
        .select()
        .from(paymentPlans)
        .where(
          and(
            eq(paymentPlans.tenantId, ctx.tenantId),
            inArray(paymentPlans.companyId, ctx.scope.values),
          ),
        )
        .all()
        .filter((p) => query.status === undefined || p.status === query.status)
        .slice(0, query.limit)
      return {
        body: listBody(ctx, items),
        target: '付款计划',
        summary: `返回 ${items.length} 条付款计划`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'transaction_anomaly_list',
    method: 'GET',
    url: '/bo/v1/transaction-anomalies',
    summary: '查询交易异常',
    description:
      '交易异常记录。severity：low 低、medium 中、high 高、critical 严重；anomalyType：duplicate_payment 重复付款、split_invoice 拆分发票、round_amount 整数金额、off_hours 非工作时间过账、reversal 冲销。period 为 YYYY-MM。',
    querystring: z.object({
      severity: z.string().optional().describe('逗号分隔，例如 high,critical'),
      period: z
        .string()
        .regex(/^\d{4}-\d{2}$/)
        .optional(),
      status: z.enum(['open', 'closed']).optional(),
      limit: Limit,
    }),
    handler: (ctx, { query }) => {
      const severities = query.severity?.split(',').map((s) => s.trim())
      const items = db
        .select()
        .from(transactionAnomalies)
        .where(
          and(
            eq(transactionAnomalies.tenantId, ctx.tenantId),
            inArray(transactionAnomalies.companyId, ctx.scope.values),
          ),
        )
        .orderBy(desc(transactionAnomalies.amount))
        .all()
        .filter(
          (a) =>
            (severities === undefined || severities.includes(a.severity)) &&
            (query.period === undefined || a.detectedAt.startsWith(query.period)) &&
            (query.status === undefined || a.status === query.status),
        )
        .slice(0, query.limit)
        .map((a) => ({
          ...a,
          invoiceIds: JSON.parse(a.invoiceIds) as string[],
          companyName: companyName(ctx.tenantId, a.companyId),
        }))
      return {
        body: listBody(ctx, items),
        target: query.period ?? '交易异常',
        summary: `返回 ${items.length} 条异常`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'transaction_anomaly_get',
    method: 'GET',
    url: '/bo/v1/transaction-anomalies/:anomalyId',
    summary: '查询单条交易异常',
    description: '交易异常明细，附关联的应付发票。',
    params: z.object({ anomalyId: z.string().describe('异常编号，例如 ANO-2026-0003') }),
    handler: (ctx, { params }) => {
      const anomaly = db
        .select()
        .from(transactionAnomalies)
        .where(
          and(
            eq(transactionAnomalies.tenantId, ctx.tenantId),
            eq(transactionAnomalies.id, params.anomalyId),
          ),
        )
        .get()
      if (anomaly === undefined || !inScope(ctx, anomaly.companyId))
        throw notFound(`交易异常 ${params.anomalyId}`)
      const invoiceIds = JSON.parse(anomaly.invoiceIds) as string[]
      const invoices =
        invoiceIds.length === 0
          ? []
          : db
              .select()
              .from(apInvoices)
              .where(and(eq(apInvoices.tenantId, ctx.tenantId), inArray(apInvoices.id, invoiceIds)))
              .all()
      return {
        body: {
          item: {
            ...anomaly,
            invoiceIds,
            invoices,
            companyName: companyName(ctx.tenantId, anomaly.companyId),
          },
          filtered: ctx.scope.filtered,
          visibleScope: ctx.scope.names,
        },
        target: anomaly.id,
        summary: `${anomaly.anomalyType} ${anomaly.amount} ${anomaly.currency}`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'period_close_checklist',
    method: 'GET',
    url: '/bo/v1/accounting-periods/:periodId/close-checks',
    summary: '查询结账检查项',
    description:
      '会计期间的结账检查项（演示只覆盖资产核算产品 asset-accounting）。status：passed 已通过、failed 未通过、not_started 未开始；未通过时 blocker 说明阻塞原因。',
    params: z.object({
      periodId: z
        .string()
        .regex(/^\d{4}-\d{2}$|^current$/)
        .describe('会计期间 YYYY-MM，或 current'),
    }),
    querystring: z.object({ product: z.string().default('asset-accounting') }),
    handler: (ctx, { params, query }) => {
      const periodId =
        params.periodId === 'current' ? isoDate(deps.now()).slice(0, 7) : params.periodId
      const periods = db
        .select()
        .from(accountingPeriods)
        .where(
          and(
            eq(accountingPeriods.tenantId, ctx.tenantId),
            eq(accountingPeriods.id, periodId),
            inArray(accountingPeriods.companyId, ctx.scope.values),
          ),
        )
        .all()
      const items = db
        .select()
        .from(closeCheckItems)
        .where(
          and(
            eq(closeCheckItems.tenantId, ctx.tenantId),
            eq(closeCheckItems.periodId, periodId),
            eq(closeCheckItems.product, query.product),
            inArray(closeCheckItems.companyId, ctx.scope.values),
          ),
        )
        .all()
        .map((c) => ({ ...c, companyName: companyName(ctx.tenantId, c.companyId) }))
      const passed = items.filter((c) => c.status === 'passed').length
      return {
        body: {
          ...listBody(ctx, items),
          periods,
          readinessRate:
            items.length === 0 ? null : Math.round((passed / items.length) * 1000) / 10,
        },
        target: periodId,
        summary: `检查项 ${items.length} 个，已通过 ${passed} 个`,
      }
    },
  })

  defineBoRoute(app, deps, {
    operation: 'followup_task_create',
    method: 'POST',
    url: '/bo/v1/followup-tasks',
    summary: '创建跟进任务',
    description: '为业务对象创建跟进任务并指派负责人。写操作级别 L2，支持 Idempotency-Key。',
    body: z.object({
      title: z.string().min(1),
      assignee: z.string().min(1).describe('负责人或责任部门'),
      relatedObject: z.string().optional().describe('关联的单据编号'),
      dueDate: z
        .string()
        .regex(/^\d{4}-\d{2}-\d{2}$/)
        .optional(),
    }),
    handler: (ctx, { body }) => {
      const n =
        (db
          .select({ n: count() })
          .from(followupTasks)
          .where(eq(followupTasks.tenantId, ctx.tenantId))
          .get()?.n ?? 0) + 1
      const task = db
        .insert(followupTasks)
        .values({
          id: `TASK-2026-${pad(n, 4)}`,
          tenantId: ctx.tenantId,
          title: body.title,
          assignee: body.assignee,
          relatedObject: body.relatedObject ?? null,
          dueDate: body.dueDate ?? null,
          createdBy: ctx.user.id,
          createdVia: ctx.agent,
          createdAt: deps.now().toISOString(),
        })
        .returning()
        .get()
      return {
        status: 201,
        body: { item: task },
        target: body.relatedObject ?? task.id,
        summary: `创建跟进任务 ${task.id}，指派给 ${body.assignee}`,
      }
    },
  })
}
