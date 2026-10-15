import type { BoOperation } from '@eab/demo-directory'

/**
 * BO API 每个操作的数据权限维度与写操作级别（主提示词 6.3、7.4 节）。
 * 采购域按采购组织过滤，库存与财务按公司主体过滤，物料主数据和跟进任务只做租户隔离。
 */
export type ScopeDimension = 'purchasingOrg' | 'company' | 'none'

export interface OperationSpec {
  dimension: ScopeDimension
  writeLevel: 'L0' | 'L1' | 'L2' | 'L3'
}

export const OPERATION_SPECS: Readonly<Record<BoOperation, OperationSpec>> = {
  supplier_list: { dimension: 'purchasingOrg', writeLevel: 'L0' },
  supplier_get: { dimension: 'purchasingOrg', writeLevel: 'L0' },
  rfq_list: { dimension: 'purchasingOrg', writeLevel: 'L0' },
  purchase_order_list: { dimension: 'purchasingOrg', writeLevel: 'L0' },
  purchase_order_change_delivery_date: { dimension: 'purchasingOrg', writeLevel: 'L2' },
  purchase_order_change_approve: { dimension: 'purchasingOrg', writeLevel: 'L2' },
  delivery_list: { dimension: 'purchasingOrg', writeLevel: 'L0' },
  material_list: { dimension: 'none', writeLevel: 'L0' },
  inventory_balance_list: { dimension: 'company', writeLevel: 'L0' },
  stock_movement_list: { dimension: 'company', writeLevel: 'L0' },
  ap_invoice_list: { dimension: 'company', writeLevel: 'L0' },
  payment_plan_list: { dimension: 'company', writeLevel: 'L0' },
  transaction_anomaly_list: { dimension: 'company', writeLevel: 'L0' },
  transaction_anomaly_get: { dimension: 'company', writeLevel: 'L0' },
  period_close_checklist: { dimension: 'company', writeLevel: 'L0' },
  followup_task_create: { dimension: 'none', writeLevel: 'L2' },
}

export const DIMENSION_LABELS: Readonly<Record<ScopeDimension, string>> = {
  purchasingOrg: '采购组织',
  company: '公司主体',
  none: '租户',
}
