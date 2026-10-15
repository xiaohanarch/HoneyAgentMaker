/**
 * DemoERP 的业务数据（主提示词 6.1 节）：采购、库存、财务三个域，外加交期变更单、跟进任务、
 * 幂等键与访问日志。每张业务表都带 tenant_id，所有查询都按租户隔离。
 * 公司主体与采购组织来自 @eab/demo-directory，不单独建表。日期统一存 YYYY-MM-DD 字符串。
 */
import { index, integer, primaryKey, real, sqliteTable, text } from 'drizzle-orm/sqlite-core'

const tenant = () => text('tenant_id').notNull()

// ── 采购 ───────────────────────────────────────────────────────────────

export const suppliers = sqliteTable(
  'suppliers',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    name: text('name').notNull(),
    rating: text('rating', { enum: ['A', 'B', 'C'] }).notNull(),
    purchasingOrg: text('purchasing_org').notNull(),
    city: text('city').notNull(),
    priceTerms: text('price_terms').notNull(),
    warrantyMonths: integer('warranty_months').notNull(),
    leadTimeDays: integer('lead_time_days').notNull(),
    paymentTerms: text('payment_terms').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] }), index('suppliers_tenant').on(t.tenantId)],
)

export const rfqs = sqliteTable(
  'rfqs',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    supplierId: text('supplier_id').notNull(),
    purchasingOrg: text('purchasing_org').notNull(),
    materialId: text('material_id').notNull(),
    issuedAt: text('issued_at').notNull(),
    status: text('status', { enum: ['open', 'quoted', 'closed'] }).notNull(),
    targetPrice: real('target_price').notNull(),
    currency: text('currency').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] }), index('rfqs_tenant').on(t.tenantId)],
)

export const quotations = sqliteTable(
  'quotations',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    rfqId: text('rfq_id').notNull(),
    lineNo: integer('line_no').notNull(),
    quantity: real('quantity').notNull(),
    unitPrice: real('unit_price').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const purchaseOrders = sqliteTable(
  'purchase_orders',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    supplierId: text('supplier_id').notNull(),
    purchasingOrg: text('purchasing_org').notNull(),
    companyId: text('company_id').notNull(),
    createdAt: text('created_at').notNull(),
    status: text('status', { enum: ['open', 'partially_delivered', 'delivered'] }).notNull(),
    currency: text('currency').notNull(),
    totalAmount: real('total_amount').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] }), index('po_tenant').on(t.tenantId)],
)

export const poLines = sqliteTable(
  'po_lines',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    poId: text('po_id').notNull(),
    lineNo: integer('line_no').notNull(),
    materialId: text('material_id').notNull(),
    quantity: real('quantity').notNull(),
    unitPrice: real('unit_price').notNull(),
    plannedDeliveryDate: text('planned_delivery_date').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] }), index('po_lines_po').on(t.poId)],
)

export const deliveries = sqliteTable(
  'deliveries',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    poId: text('po_id').notNull(),
    poLineId: text('po_line_id').notNull(),
    plannedDate: text('planned_date').notNull(),
    /** 未交付时为空，逾期天数按今天计算 */
    actualDate: text('actual_date'),
    quantity: real('quantity').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] }), index('deliveries_po').on(t.poId)],
)

/** 交期变更单：提交人和审批人分开记录，并记下经由哪个 Agent，用于职责分离和审计反查 */
export const deliveryDateChanges = sqliteTable(
  'delivery_date_changes',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    poId: text('po_id').notNull(),
    oldDate: text('old_date').notNull(),
    newDate: text('new_date').notNull(),
    reason: text('reason').notNull(),
    status: text('status', { enum: ['submitted', 'approved', 'rejected'] }).notNull(),
    requestedBy: text('requested_by').notNull(),
    requestedVia: text('requested_via'),
    approvedBy: text('approved_by'),
    approvedVia: text('approved_via'),
    createdAt: text('created_at').notNull(),
    approvedAt: text('approved_at'),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

// ── 库存 ───────────────────────────────────────────────────────────────

export const materials = sqliteTable(
  'materials',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    name: text('name').notNull(),
    category: text('category').notNull(),
    unit: text('unit').notNull(),
    safetyStock: real('safety_stock').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const warehouses = sqliteTable(
  'warehouses',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    name: text('name').notNull(),
    companyId: text('company_id').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const inventoryBalances = sqliteTable(
  'inventory_balances',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    materialId: text('material_id').notNull(),
    warehouseId: text('warehouse_id').notNull(),
    companyId: text('company_id').notNull(),
    quantity: real('quantity').notNull(),
    value: real('value').notNull(),
    ageDays: integer('age_days').notNull(),
    slowMoving: text('slow_moving', { enum: ['none', 'watch', 'slow'] }).notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const stockMovements = sqliteTable(
  'stock_movements',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    materialId: text('material_id').notNull(),
    warehouseId: text('warehouse_id').notNull(),
    companyId: text('company_id').notNull(),
    direction: text('direction', { enum: ['in', 'out'] }).notNull(),
    quantity: real('quantity').notNull(),
    date: text('date').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

// ── 财务 ───────────────────────────────────────────────────────────────

export const apInvoices = sqliteTable(
  'ap_invoices',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    supplierId: text('supplier_id').notNull(),
    poId: text('po_id').notNull(),
    companyId: text('company_id').notNull(),
    invoiceDate: text('invoice_date').notNull(),
    amount: real('amount').notNull(),
    currency: text('currency').notNull(),
    matchStatus: text('match_status', {
      enum: ['matched', 'price_diff', 'qty_diff', 'unmatched'],
    }).notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const paymentPlans = sqliteTable(
  'payment_plans',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    invoiceId: text('invoice_id').notNull(),
    companyId: text('company_id').notNull(),
    dueDate: text('due_date').notNull(),
    amount: real('amount').notNull(),
    currency: text('currency').notNull(),
    status: text('status', { enum: ['planned', 'paid', 'on_hold'] }).notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const transactionAnomalies = sqliteTable(
  'transaction_anomalies',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    companyId: text('company_id').notNull(),
    anomalyType: text('anomaly_type').notNull(),
    severity: text('severity', { enum: ['low', 'medium', 'high', 'critical'] }).notNull(),
    amount: real('amount').notNull(),
    currency: text('currency').notNull(),
    detectedAt: text('detected_at').notNull(),
    status: text('status', { enum: ['open', 'closed'] }).notNull(),
    description: text('description').notNull(),
    /** 关联的发票编号，JSON 数组 */
    invoiceIds: text('invoice_ids').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

export const accountingPeriods = sqliteTable(
  'accounting_periods',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    companyId: text('company_id').notNull(),
    status: text('status', { enum: ['open', 'closing', 'closed'] }).notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.companyId, t.id] })],
)

export const closeCheckItems = sqliteTable(
  'close_check_items',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    periodId: text('period_id').notNull(),
    companyId: text('company_id').notNull(),
    product: text('product').notNull(),
    name: text('name').notNull(),
    status: text('status', { enum: ['passed', 'failed', 'not_started'] }).notNull(),
    owner: text('owner').notNull(),
    blocker: text('blocker'),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

// ── 运营 ───────────────────────────────────────────────────────────────

export const followupTasks = sqliteTable(
  'followup_tasks',
  {
    id: text('id').notNull(),
    tenantId: tenant(),
    title: text('title').notNull(),
    assignee: text('assignee').notNull(),
    relatedObject: text('related_object'),
    dueDate: text('due_date'),
    createdBy: text('created_by').notNull(),
    createdVia: text('created_via'),
    createdAt: text('created_at').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.id] })],
)

/** 写操作的业务幂等键：同一租户、同一操作、同一键只执行一次，重放时返回首次的结果 */
export const idempotencyKeys = sqliteTable(
  'idempotency_keys',
  {
    tenantId: tenant(),
    operation: text('operation').notNull(),
    key: text('key').notNull(),
    /** 首次请求的用户与请求指纹：其他用户或不同内容复用同一个键时拒绝，不回放别人的结果 */
    userId: text('user_id').notNull().default(''),
    fingerprint: text('fingerprint').notNull().default(''),
    statusCode: integer('status_code').notNull(),
    response: text('response').notNull(),
    createdAt: text('created_at').notNull(),
  },
  (t) => [primaryKey({ columns: [t.tenantId, t.operation, t.key] })],
)

/** 鉴权点 4 的裁决记录，同时推送到平台审计 */
export const accessLog = sqliteTable(
  'access_log',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    at: text('at').notNull(),
    tenantId: text('tenant_id'),
    api: text('api', { enum: ['bo', 'open'] }).notNull(),
    operation: text('operation').notNull(),
    principal: text('principal').notNull(),
    agent: text('agent'),
    mode: text('mode').notNull(),
    outcome: text('outcome', { enum: ['allow', 'deny'] }).notNull(),
    code: text('code').notNull(),
    reason: text('reason').notNull(),
    dataScope: text('data_scope').notNull(),
    target: text('target').notNull(),
  },
  (t) => [index('access_log_at').on(t.at)],
)
