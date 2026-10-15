/**
 * 元数据服务的内容（主提示词 6.4 节）：对象、字段、枚举、规则、权限维度，带版本。
 * 语义包的投影段只写引用（如 erp.pur.Supplier@v1），运行时从这里取定义。
 */
export interface MetaField {
  name: string
  type: 'string' | 'number' | 'date' | 'enum'
  label: string
  enum?: string[]
}

export interface MetaObject {
  ref: string
  label: string
  domain: 'pur' | 'scm' | 'fin'
  version: number
  /** 数据权限维度 */
  permissionDimension: '采购组织' | '公司主体' | null
  fields: MetaField[]
}

const f = (name: string, type: MetaField['type'], label: string, values?: string[]): MetaField =>
  values === undefined ? { name, type, label } : { name, type, label, enum: values }

export const META_OBJECTS: readonly MetaObject[] = [
  {
    ref: 'erp.pur.Supplier',
    label: '供应商',
    domain: 'pur',
    version: 1,
    permissionDimension: '采购组织',
    fields: [
      f('id', 'string', '供应商编号'),
      f('name', 'string', '名称'),
      f('rating', 'enum', '评级', ['A', 'B', 'C']),
      f('purchasingOrg', 'string', '采购组织'),
      f('leadTimeDays', 'number', '约定交期天数'),
    ],
  },
  {
    ref: 'erp.pur.PurchasingOrg',
    label: '采购组织',
    domain: 'pur',
    version: 1,
    permissionDimension: null,
    fields: [f('id', 'string', '编号'), f('name', 'string', '名称')],
  },
  {
    ref: 'erp.pur.RFQ',
    label: '询价单',
    domain: 'pur',
    version: 1,
    permissionDimension: '采购组织',
    fields: [
      f('id', 'string', '询价单号'),
      f('supplierId', 'string', '供应商'),
      f('issuedAt', 'date', '发出日期'),
      f('targetPrice', 'number', '目标价'),
      f('status', 'enum', '状态', ['open', 'quoted', 'closed']),
    ],
  },
  {
    ref: 'erp.pur.Quotation',
    label: '报价行',
    domain: 'pur',
    version: 1,
    permissionDimension: '采购组织',
    fields: [
      f('rfqId', 'string', '询价单号'),
      f('lineNo', 'number', '行号'),
      f('unitPrice', 'number', '单价'),
      f('targetPrice', 'number', '目标价（取自询价单）'),
    ],
  },
  {
    ref: 'erp.pur.PurchaseOrder',
    label: '采购订单',
    domain: 'pur',
    version: 2,
    permissionDimension: '采购组织',
    fields: [
      f('id', 'string', '订单号'),
      f('supplierId', 'string', '供应商'),
      f('status', 'enum', '状态', ['open', 'partially_delivered', 'delivered']),
      f('totalAmount', 'number', '金额'),
      f('currency', 'string', '币种'),
      f('plannedDeliveryDate', 'date', '计划交期（订单行）'),
    ],
  },
  {
    ref: 'erp.pur.Delivery',
    label: '交付记录',
    domain: 'pur',
    version: 1,
    permissionDimension: '采购组织',
    fields: [
      f('poId', 'string', '订单号'),
      f('plannedDate', 'date', '计划交付日期'),
      f('actualDate', 'date', '实际交付日期'),
      f('quantity', 'number', '数量'),
    ],
  },
  {
    ref: 'erp.pur.Contract',
    label: '合同条款',
    domain: 'pur',
    version: 1,
    permissionDimension: '采购组织',
    fields: [
      f('priceTerms', 'string', '价格条款'),
      f('warrantyMonths', 'number', '质保月数'),
      f('leadTimeDays', 'number', '交期天数'),
      f('paymentTerms', 'string', '付款条件'),
    ],
  },
  {
    ref: 'erp.scm.Material',
    label: '物料',
    domain: 'scm',
    version: 1,
    permissionDimension: null,
    fields: [
      f('id', 'string', '物料编号'),
      f('name', 'string', '名称'),
      f('unit', 'string', '计量单位'),
      f('safetyStock', 'number', '安全库存'),
    ],
  },
  {
    ref: 'erp.scm.Warehouse',
    label: '仓库',
    domain: 'scm',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('id', 'string', '仓库编号'),
      f('name', 'string', '名称'),
      f('companyId', 'string', '公司主体'),
    ],
  },
  {
    ref: 'erp.scm.InventoryBalance',
    label: '库存余额',
    domain: 'scm',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('materialId', 'string', '物料'),
      f('quantity', 'number', '数量'),
      f('ageDays', 'number', '库龄天数'),
      f('slowMoving', 'enum', '呆滞标识', ['none', 'watch', 'slow']),
    ],
  },
  {
    ref: 'erp.scm.StockMovement',
    label: '出入库流水',
    domain: 'scm',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('materialId', 'string', '物料'),
      f('direction', 'enum', '方向', ['in', 'out']),
      f('quantity', 'number', '数量'),
      f('date', 'date', '日期'),
    ],
  },
  {
    ref: 'erp.fin.APInvoice',
    label: '应付发票',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('id', 'string', '发票号'),
      f('amount', 'number', '金额'),
      f('currency', 'string', '币种'),
      f('matchStatus', 'enum', '匹配状态', ['matched', 'price_diff', 'qty_diff', 'unmatched']),
    ],
  },
  {
    ref: 'erp.fin.PaymentPlan',
    label: '付款计划',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('invoiceId', 'string', '发票号'),
      f('dueDate', 'date', '到期日'),
      f('status', 'enum', '状态', ['planned', 'paid', 'on_hold']),
    ],
  },
  {
    ref: 'erp.fin.TransactionAnomaly',
    label: '交易异常',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('id', 'string', '异常编号'),
      f('anomalyType', 'enum', '类型', [
        'duplicate_payment',
        'split_invoice',
        'round_amount',
        'off_hours',
        'reversal',
      ]),
      f('severity', 'enum', '严重程度', ['low', 'medium', 'high', 'critical']),
      f('amount', 'number', '涉及金额'),
      f('currency', 'string', '币种'),
    ],
  },
  {
    ref: 'erp.fin.AccountingPeriod',
    label: '会计期间',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('id', 'string', '期间 YYYY-MM'),
      f('status', 'enum', '状态', ['open', 'closing', 'closed']),
    ],
  },
  {
    ref: 'erp.fin.CloseCheckItem',
    label: '结账检查项',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('name', 'string', '检查项'),
      f('status', 'enum', '状态', ['passed', 'failed', 'not_started']),
      f('owner', 'string', '责任人'),
      f('blocker', 'string', '阻塞原因'),
    ],
  },
  {
    ref: 'erp.fin.FixedAsset',
    label: '固定资产卡片',
    domain: 'fin',
    version: 1,
    permissionDimension: '公司主体',
    fields: [
      f('id', 'string', '资产编号'),
      f('category', 'string', '资产类别'),
      f('depreciationMethod', 'string', '折旧方法'),
    ],
  },
]

export const META_RULES = [
  {
    ref: 'erp.pur.rules.delivery_date_change',
    label: '交期变更须确认',
    description: '修改采购订单交期属于需确认的写操作',
  },
  {
    ref: 'erp.pur.rules.segregation_of_duties',
    label: '职责分离',
    description: '同一用户不能既提交又审批同一张单据',
  },
  {
    ref: 'erp.pur.rules.negotiation_eligibility',
    label: '降价谈判前置条件',
    description: '交付逾期超过 90 天的供应商不建议发起降价谈判',
  },
] as const

/** erp.pur.Supplier@v1 → 对象；版本不符时返回 undefined */
export function findMetaObject(ref: string): MetaObject | undefined {
  const match = /^(erp\.[a-z]+\.[A-Za-z]+)(?:@v(\d+))?$/.exec(ref)
  if (match === null) return undefined
  const object = META_OBJECTS.find((o) => o.ref === match[1])
  if (object === undefined) return undefined
  return match[2] === undefined || Number(match[2]) === object.version ? object : undefined
}
