import { and, eq, inArray } from 'drizzle-orm'
import { findTenant } from '@eab/demo-directory'
import type { ErpDb } from '../db/client'
import { deliveries, poLines, purchaseOrders, suppliers } from '../db/schema'
import { daysBetween, isoDate } from '../seed/random'

export function orgName(tenantId: string, orgId: string): string {
  return findTenant(tenantId)?.purchasingOrgs.find((o) => o.id === orgId)?.name ?? orgId
}

export function companyName(tenantId: string, companyId: string): string {
  return findTenant(tenantId)?.companies.find((c) => c.id === companyId)?.name ?? companyId
}

export interface PoDeliveryStatus {
  /** 逾期天数：未交付的行按今天算，已交付的行按实际交付日期算，取订单行最大值（语义包指标 overdue_days） */
  overdueDays: number
  /** 下一个未交付行的计划交期 */
  nextPlannedDate: string | null
}

/** 计算订单的交付逾期情况，口径与采购语义包的 overdue_days 指标一致 */
export function deliveryStatus(
  db: ErpDb,
  tenantId: string,
  poIds: readonly string[],
  today: Date,
): Map<string, PoDeliveryStatus> {
  const result = new Map<string, PoDeliveryStatus>()
  if (poIds.length === 0) return result
  const todayIso = isoDate(today)
  const lines = db
    .select()
    .from(poLines)
    .where(and(eq(poLines.tenantId, tenantId), inArray(poLines.poId, [...poIds])))
    .all()
  const delivered = db
    .select()
    .from(deliveries)
    .where(and(eq(deliveries.tenantId, tenantId), inArray(deliveries.poId, [...poIds])))
    .all()
  for (const poId of poIds) {
    let overdueDays = 0
    let nextPlannedDate: string | null = null
    for (const line of lines.filter((l) => l.poId === poId)) {
      const records = delivered.filter((d) => d.poLineId === line.id)
      const quantity = records.reduce((sum, d) => sum + d.quantity, 0)
      const lastActual = records
        .map((d) => d.actualDate)
        .filter((d): d is string => d !== null)
        .sort()
        .at(-1)
      const end = quantity >= line.quantity && lastActual !== undefined ? lastActual : todayIso
      overdueDays = Math.max(overdueDays, daysBetween(line.plannedDeliveryDate, end))
      if (
        quantity < line.quantity &&
        (nextPlannedDate === null || line.plannedDeliveryDate < nextPlannedDate)
      ) {
        nextPlannedDate = line.plannedDeliveryDate
      }
    }
    result.set(poId, { overdueDays: Math.max(0, overdueDays), nextPlannedDate })
  }
  return result
}

export function supplierNames(db: ErpDb, tenantId: string): Map<string, string> {
  return new Map(
    db
      .select({ id: suppliers.id, name: suppliers.name })
      .from(suppliers)
      .where(eq(suppliers.tenantId, tenantId))
      .all()
      .map((s) => [s.id, s.name]),
  )
}

export function findPurchaseOrder(db: ErpDb, tenantId: string, poId: string) {
  return db
    .select()
    .from(purchaseOrders)
    .where(and(eq(purchaseOrders.tenantId, tenantId), eq(purchaseOrders.id, poId)))
    .get()
}
