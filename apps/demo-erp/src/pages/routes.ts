import { and, eq } from 'drizzle-orm'
import type { FastifyInstance } from 'fastify'
import { findTenant } from '@eab/demo-directory'
import type { ErpDeps } from '../bo/context'
import { deliveryStatus, findPurchaseOrder, orgName } from '../bo/queries'
import { poLines, suppliers } from '../db/schema'

const escape = (value: unknown): string =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c,
  )

function page(title: string, rows: readonly (readonly [string, unknown])[], extra = ''): string {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escape(title)} · DemoERP</title>
<style>body{font-family:system-ui,'PingFang SC','Microsoft YaHei',sans-serif;margin:0;background:#f4f5f7;color:#1f2430}
header{background:#2b3440;color:#fff;padding:12px 24px;font-size:14px}main{max-width:880px;margin:24px auto;background:#fff;border:1px solid #dfe3e8;border-radius:8px;padding:24px}
h1{font-size:20px;margin:0 0 16px}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef0f3;font-size:14px}th{width:160px;color:#5b6472;font-weight:500}
.note{margin-top:16px;font-size:12px;color:#7a8290}</style></head><body><header>DemoERP · 只读页面（演示用）</header>
<main><h1>${escape(title)}</h1><table>${rows.map(([k, v]) => `<tr><th>${escape(k)}</th><td>${escape(v)}</td></tr>`).join('')}</table>${extra}
<p class="note">本页面供统一入口的导航型交互跳转，数据均为虚构。</p></main></body></html>`
}

/** 极简的只读页面（主提示词 6.4、9.1.1 节导航型交互的深链接目标） */
export function registerPageRoutes(app: FastifyInstance, deps: ErpDeps): void {
  app.get('/pages/suppliers/:id', { schema: { hide: true } }, async (request, reply) => {
    const { id } = request.params as { id: string }
    const tenantId = String((request.query as { tenant?: string }).tenant ?? 't001')
    const supplier = deps.db
      .select()
      .from(suppliers)
      .where(and(eq(suppliers.tenantId, tenantId), eq(suppliers.id, id)))
      .get()
    if (supplier === undefined)
      return reply.status(404).type('text/html').send(page('供应商不存在', []))
    return reply.type('text/html').send(
      page(`供应商 · ${supplier.name}`, [
        ['供应商编号', supplier.id],
        ['所属租户', findTenant(tenantId)?.name],
        ['采购组织', orgName(tenantId, supplier.purchasingOrg)],
        ['评级', supplier.rating],
        ['所在城市', supplier.city],
        ['价格条款', supplier.priceTerms],
        ['质保', `${supplier.warrantyMonths} 个月`],
        ['约定交期', `${supplier.leadTimeDays} 天`],
        ['付款条件', supplier.paymentTerms],
      ]),
    )
  })

  app.get('/pages/purchase-orders/:id', { schema: { hide: true } }, async (request, reply) => {
    const { id } = request.params as { id: string }
    const tenantId = String((request.query as { tenant?: string }).tenant ?? 't001')
    const po = findPurchaseOrder(deps.db, tenantId, id)
    if (po === undefined)
      return reply.status(404).type('text/html').send(page('采购订单不存在', []))
    const status = deliveryStatus(deps.db, tenantId, [po.id], deps.now()).get(po.id)
    const lines = deps.db
      .select()
      .from(poLines)
      .where(and(eq(poLines.tenantId, tenantId), eq(poLines.poId, po.id)))
      .all()
    const supplier = deps.db
      .select()
      .from(suppliers)
      .where(and(eq(suppliers.tenantId, tenantId), eq(suppliers.id, po.supplierId)))
      .get()
    return reply.type('text/html').send(
      page(
        `采购订单 · ${po.id}`,
        [
          ['供应商', supplier?.name],
          ['采购组织', orgName(tenantId, po.purchasingOrg)],
          ['状态', po.status],
          ['金额', `${po.totalAmount} ${po.currency}`],
          ['下单日期', po.createdAt],
          ['逾期天数', status?.overdueDays ?? 0],
        ],
        `<h1 style="font-size:16px;margin-top:24px">订单行</h1><table><tr><th>行号</th><th>物料</th><th>数量</th><th>计划交期</th></tr>${lines
          .map(
            (l) =>
              `<tr><td>${l.lineNo}</td><td>${escape(l.materialId)}</td><td>${l.quantity}</td><td>${escape(l.plannedDeliveryDate)}</td></tr>`,
          )
          .join('')}</table>`,
      ),
    )
  })
}
