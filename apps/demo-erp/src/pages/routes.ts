import { and, eq } from 'drizzle-orm'
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify'
import { findTenant, findUser, type DemoUser } from '@eab/demo-directory'
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
<meta name="referrer" content="no-referrer">
<title>${escape(title)} · DemoERP</title>
<style>body{font-family:system-ui,'PingFang SC','Microsoft YaHei',sans-serif;margin:0;background:#f4f5f7;color:#1f2430}
header{background:#2b3440;color:#fff;padding:12px 24px;font-size:14px}main{max-width:880px;margin:24px auto;background:#fff;border:1px solid #dfe3e8;border-radius:8px;padding:24px}
h1{font-size:20px;margin:0 0 16px}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef0f3;font-size:14px}th{width:160px;color:#5b6472;font-weight:500}
.note{margin-top:16px;font-size:12px;color:#7a8290}</style></head><body><header>DemoERP · 只读页面（演示用）</header>
<main><h1>${escape(title)}</h1><table>${rows.map(([k, v]) => `<tr><th>${escape(k)}</th><td>${escape(v)}</td></tr>`).join('')}</table>${extra}
<p class="note">本页面供统一入口的导航型交互跳转，数据均为虚构。</p></main></body></html>`
}

/**
 * 页面链接里的令牌由平台签发（受众 demo-erp-pages，有效期 10 分钟，带用户和租户），
 * 页面按这个用户的数据权限展示：没有令牌、令牌无效、或单据不在数据权限内，都看不到内容。
 */
async function pageViewer(deps: ErpDeps, request: FastifyRequest): Promise<DemoUser | undefined> {
  const token = (request.query as { t?: unknown }).t
  if (typeof token !== 'string' || token === '') return undefined
  try {
    const claims = await deps.link.verify(token, 'demo-erp-pages')
    const user = claims.kind === 'page' ? findUser(String(claims.sub)) : undefined
    return user !== undefined && user.tenantId === claims.tid ? user : undefined
  } catch {
    return undefined
  }
}

function denied(reply: FastifyReply): FastifyReply {
  return reply
    .status(404)
    .header('cache-control', 'no-store')
    .type('text/html')
    .send(page('页面不存在或链接已过期', [['说明', '请从统一入口重新打开这个链接']]))
}

/** 极简的只读页面（主提示词 6.4、9.1.1 节导航型交互的深链接目标） */
export function registerPageRoutes(app: FastifyInstance, deps: ErpDeps): void {
  app.get('/pages/suppliers/:id', { schema: { hide: true } }, async (request, reply) => {
    const user = await pageViewer(deps, request)
    if (user === undefined) return denied(reply)
    const { id } = request.params as { id: string }
    const supplier = deps.db
      .select()
      .from(suppliers)
      .where(and(eq(suppliers.tenantId, user.tenantId), eq(suppliers.id, id)))
      .get()
    if (supplier === undefined || !user.dataScope.purchasingOrgs.includes(supplier.purchasingOrg)) {
      return denied(reply)
    }
    return reply
      .header('cache-control', 'no-store')
      .type('text/html')
      .send(
        page(`供应商 · ${supplier.name}`, [
          ['供应商编号', supplier.id],
          ['所属租户', findTenant(user.tenantId)?.name],
          ['采购组织', orgName(user.tenantId, supplier.purchasingOrg)],
          ['评级', supplier.rating],
          ['所在城市', supplier.city],
          ['价格条款', supplier.priceTerms],
          ['质保', `${supplier.warrantyMonths} 个月`],
          ['约定交期', `${supplier.leadTimeDays} 天`],
          ['付款条件', supplier.paymentTerms],
          ['查看人', `${user.name}（${user.dataScopeLabel}）`],
        ]),
      )
  })

  app.get('/pages/purchase-orders/:id', { schema: { hide: true } }, async (request, reply) => {
    const user = await pageViewer(deps, request)
    if (user === undefined) return denied(reply)
    const { id } = request.params as { id: string }
    const po = findPurchaseOrder(deps.db, user.tenantId, id)
    if (po === undefined || !user.dataScope.purchasingOrgs.includes(po.purchasingOrg))
      return denied(reply)
    const status = deliveryStatus(deps.db, user.tenantId, [po.id], deps.now()).get(po.id)
    const lines = deps.db
      .select()
      .from(poLines)
      .where(and(eq(poLines.tenantId, user.tenantId), eq(poLines.poId, po.id)))
      .all()
    const supplier = deps.db
      .select()
      .from(suppliers)
      .where(and(eq(suppliers.tenantId, user.tenantId), eq(suppliers.id, po.supplierId)))
      .get()
    return reply
      .header('cache-control', 'no-store')
      .type('text/html')
      .send(
        page(
          `采购订单 · ${po.id}`,
          [
            ['供应商', supplier?.name],
            ['采购组织', orgName(user.tenantId, po.purchasingOrg)],
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
