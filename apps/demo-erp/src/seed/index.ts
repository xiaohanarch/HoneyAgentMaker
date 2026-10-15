import { count } from 'drizzle-orm'
import type { SQLiteTable } from 'drizzle-orm/sqlite-core'
import { TENANTS } from '@eab/demo-directory'
import type { ErpDb } from '../db/client'
import * as schema from '../db/schema'
import { generateTenant, type TenantData } from './generate'

export { STORY } from './generate'

const TABLES: { [K in keyof TenantData]: SQLiteTable } = {
  suppliers: schema.suppliers,
  rfqs: schema.rfqs,
  quotations: schema.quotations,
  purchaseOrders: schema.purchaseOrders,
  poLines: schema.poLines,
  deliveries: schema.deliveries,
  materials: schema.materials,
  warehouses: schema.warehouses,
  inventoryBalances: schema.inventoryBalances,
  stockMovements: schema.stockMovements,
  apInvoices: schema.apInvoices,
  paymentPlans: schema.paymentPlans,
  transactionAnomalies: schema.transactionAnomalies,
  accountingPeriods: schema.accountingPeriods,
  closeCheckItems: schema.closeCheckItems,
}

/** 运行期产生的数据，重置时一并清空 */
const RUNTIME_TABLES: readonly SQLiteTable[] = [
  schema.deliveryDateChanges,
  schema.followupTasks,
  schema.idempotencyKeys,
  schema.accessLog,
]

const CHUNK = 200

export function isSeeded(db: ErpDb): boolean {
  return (db.select({ n: count() }).from(schema.suppliers).get()?.n ?? 0) > 0
}

/** 清空后按 today 重新生成全部租户的演示数据（pnpm seed:reset、演示控制台的“重置”） */
export function seedErp(db: ErpDb, today: Date = new Date()): void {
  db.transaction((tx) => {
    for (const table of [...Object.values(TABLES), ...RUNTIME_TABLES]) tx.delete(table).run()
    for (const tenant of TENANTS) {
      const data = generateTenant(tenant.id, today)
      for (const [key, table] of Object.entries(TABLES) as [keyof TenantData, SQLiteTable][]) {
        const rows = data[key] as Record<string, unknown>[]
        for (let i = 0; i < rows.length; i += CHUNK)
          tx.insert(table)
            .values(rows.slice(i, i + CHUNK))
            .run()
      }
    }
  })
}
