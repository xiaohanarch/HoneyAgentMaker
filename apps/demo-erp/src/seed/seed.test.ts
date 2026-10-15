import { and, eq } from 'drizzle-orm'
import { describe, expect, it } from 'vitest'
import { openErpDb } from '../db/client'
import * as schema from '../db/schema'
import { STORY, generateTenant } from './generate'
import { isSeeded, seedErp } from './index'

const TODAY = new Date('2026-10-15T00:00:00Z')

describe('种子数据', () => {
  it('同一天重复生成结果完全相同', () => {
    expect(generateTenant('t001', TODAY)).toEqual(generateTenant('t001', TODAY))
  })

  it('规模符合主提示词 6.2 节', () => {
    const data = generateTenant('t001', TODAY)
    expect(data.suppliers).toHaveLength(40)
    expect(data.purchaseOrders).toHaveLength(200)
    expect(data.materials).toHaveLength(100)
    expect(data.transactionAnomalies).toHaveLength(30)
    expect(data.deliveries.length).toBeGreaterThan(400)
    expect(data.deliveries.length).toBeLessThan(650)
  })

  it('百润精密属于华东，PO-2026-0117 计划交期在 120 天前且未交付', () => {
    const data = generateTenant('t001', TODAY)
    expect(data.suppliers.find((s) => s.name === STORY.supplierName)).toMatchObject({
      id: 'S-0001',
      purchasingOrg: 'P-EAST',
    })
    const po = data.purchaseOrders.find((p) => p.id === STORY.poId)
    expect(po).toMatchObject({ supplierId: 'S-0001', status: 'open', companyId: 'C100' })
    expect(data.poLines.find((l) => l.poId === STORY.poId)?.plannedDeliveryDate).toBe('2026-06-17')
    expect(data.deliveries.filter((d) => d.poId === STORY.poId)).toEqual([])
  })

  it('华东和华南的供应商数量明显不同', () => {
    const data = generateTenant('t001', TODAY)
    expect(data.suppliers.filter((s) => s.purchasingOrg === 'P-EAST')).toHaveLength(26)
    expect(data.suppliers.filter((s) => s.purchasingOrg === 'P-SOUTH')).toHaveLength(14)
  })

  it('本月有金额超过 50 万的高风险异常，资产核算有 3 个未通过的检查项', () => {
    const data = generateTenant('t001', TODAY)
    const big = data.transactionAnomalies.filter(
      (a) =>
        ['high', 'critical'].includes(a.severity) &&
        a.amount > 500_000 &&
        a.detectedAt >= '2026-10-01' &&
        a.companyId === 'C100',
    )
    expect(big.length).toBeGreaterThanOrEqual(2)
    expect(
      data.closeCheckItems.filter((c) => c.companyId === 'C100' && c.status === 'failed'),
    ).toHaveLength(3)
  })

  it('百润精密最近一次询价是 RFQ-2026-0058，有 3 行报价', () => {
    const data = generateTenant('t001', TODAY)
    const latest = data.rfqs
      .filter((r) => r.supplierId === 'S-0001')
      .sort((a, b) => b.issuedAt.localeCompare(a.issuedAt))[0]
    expect(latest?.id).toBe(STORY.latestRfqId)
    expect(
      data.quotations.filter((q) => q.rfqId === STORY.latestRfqId).map((q) => q.unitPrice),
    ).toEqual([46.5, 45.8, 47.2])
  })

  it('t002 使用另一套供应商名称，没有百润精密', () => {
    const t001 = new Set(generateTenant('t001', TODAY).suppliers.map((s) => s.name))
    const t002 = generateTenant('t002', TODAY).suppliers.map((s) => s.name)
    expect(t002).not.toContain(STORY.supplierName)
    expect(t002.filter((n) => t001.has(n)).length).toBeLessThan(5)
  })

  it('写入数据库可重复执行，按租户隔离', () => {
    const db = openErpDb(':memory:')
    expect(isSeeded(db)).toBe(false)
    seedErp(db, TODAY)
    seedErp(db, TODAY)
    expect(
      db.select().from(schema.suppliers).where(eq(schema.suppliers.tenantId, 't001')).all(),
    ).toHaveLength(40)
    expect(
      db
        .select()
        .from(schema.suppliers)
        .where(
          and(eq(schema.suppliers.tenantId, 't002'), eq(schema.suppliers.name, STORY.supplierName)),
        )
        .all(),
    ).toEqual([])
    db.$client.close()
  })
})
