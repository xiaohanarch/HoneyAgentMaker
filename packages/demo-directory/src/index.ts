/**
 * 演示目录：租户、组织、角色、用户与数据权限（主提示词 6.2 节）。人名、公司名均为虚构。
 * 平台身份服务从这里读用户与租户，DemoERP 从这里读角色权限与数据范围，前端从平台接口读用户列表。
 */

export interface Organization {
  id: string
  name: string
}

export interface Tenant {
  id: string
  name: string
  /** 公司主体，库存与财务数据的权限维度 */
  companies: readonly Organization[]
  /** 采购组织，采购数据的权限维度 */
  purchasingOrgs: readonly Organization[]
}

export const TENANTS: readonly Tenant[] = [
  {
    id: 't001',
    name: '星海制造',
    companies: [
      { id: 'C100', name: '星海制造本部' },
      { id: 'C200', name: '星海制造华南分公司' },
    ],
    purchasingOrgs: [
      { id: 'P-EAST', name: '华东' },
      { id: 'P-SOUTH', name: '华南' },
    ],
  },
  {
    id: 't002',
    name: '远航电子',
    companies: [{ id: 'C100', name: '远航电子总部' }],
    purchasingOrgs: [
      { id: 'P-EAST', name: '华东' },
      { id: 'P-NORTH', name: '华北' },
    ],
  },
]

/**
 * DemoERP 的 BO API 操作，与工具来源 vendor.erp.tools/demo-erp-bo 的工具名、OpenAPI 的 operationId 一一对应。
 */
export const BO_OPERATIONS = [
  'supplier_list',
  'supplier_get',
  'rfq_list',
  'purchase_order_list',
  'purchase_order_change_delivery_date',
  'purchase_order_change_approve',
  'delivery_list',
  'material_list',
  'inventory_balance_list',
  'stock_movement_list',
  'ap_invoice_list',
  'payment_plan_list',
  'transaction_anomaly_list',
  'transaction_anomaly_get',
  'period_close_checklist',
  'followup_task_create',
] as const
export type BoOperation = (typeof BO_OPERATIONS)[number]

export function isBoOperation(value: string): value is BoOperation {
  return (BO_OPERATIONS as readonly string[]).includes(value)
}

/** ERP 角色与它允许的业务操作（业务权限）。没有业务操作的角色只能用 Studio 或治理中心 */
export const ROLES = {
  'purchasing-manager': {
    name: '采购经理',
    operations: [
      'supplier_list',
      'supplier_get',
      'rfq_list',
      'purchase_order_list',
      'purchase_order_change_delivery_date',
      'purchase_order_change_approve',
      'delivery_list',
      'material_list',
      'inventory_balance_list',
      'followup_task_create',
    ],
  },
  purchaser: {
    name: '采购员',
    operations: [
      'supplier_list',
      'supplier_get',
      'rfq_list',
      'purchase_order_list',
      'purchase_order_change_delivery_date',
      'delivery_list',
      'material_list',
    ],
  },
  'finance-manager': {
    name: '财务经理',
    operations: [
      'supplier_list',
      'supplier_get',
      'ap_invoice_list',
      'payment_plan_list',
      'transaction_anomaly_list',
      'transaction_anomaly_get',
      'period_close_checklist',
      'followup_task_create',
    ],
  },
  'inventory-manager': {
    name: '库存经理',
    operations: [
      'material_list',
      'inventory_balance_list',
      'stock_movement_list',
      'purchase_order_list',
      'delivery_list',
      'followup_task_create',
    ],
  },
  'tenant-developer': { name: '租户开发者', operations: [] },
  'tenant-admin': { name: '租户管理员', operations: [] },
  'vendor-developer': { name: '标品开发者', operations: [] },
  'platform-operator': { name: '平台运维', operations: [] },
} as const satisfies Record<string, { name: string; operations: readonly BoOperation[] }>

export type RoleId = keyof typeof ROLES

/** 数据权限：采购域按采购组织过滤，库存与财务按公司主体过滤 */
export interface DataScope {
  purchasingOrgs: readonly string[]
  companies: readonly string[]
}

export type Workspace = 'assistant' | 'studio' | 'hub'

export interface DemoUser {
  id: string
  name: string
  /** 租户编号；厂商研发为 vendor，平台运维为 platform */
  tenantId: string
  roles: readonly RoleId[]
  dataScope: DataScope
  /** 界面上展示的数据权限说明 */
  dataScopeLabel: string
  /** 切换到该用户时默认进入的入口 */
  home: Workspace
}

const NO_SCOPE: DataScope = { purchasingOrgs: [], companies: [] }

export const USERS: readonly DemoUser[] = [
  {
    id: 'zhangmin',
    name: '张敏',
    tenantId: 't001',
    roles: ['purchasing-manager'],
    dataScope: { purchasingOrgs: ['P-EAST'], companies: ['C100'] },
    dataScopeLabel: '采购组织：华东',
    home: 'assistant',
  },
  {
    id: 'liqiang',
    name: '李强',
    tenantId: 't001',
    roles: ['purchaser'],
    dataScope: { purchasingOrgs: ['P-SOUTH'], companies: ['C200'] },
    dataScopeLabel: '采购组织：华南',
    home: 'assistant',
  },
  {
    id: 'wangfang',
    name: '王芳',
    tenantId: 't001',
    roles: ['finance-manager'],
    dataScope: { purchasingOrgs: [], companies: ['C100'] },
    dataScopeLabel: '公司主体：星海制造本部',
    home: 'assistant',
  },
  {
    id: 'sunkg',
    name: '孙库管',
    tenantId: 't001',
    roles: ['inventory-manager'],
    dataScope: { purchasingOrgs: ['P-EAST', 'P-SOUTH'], companies: ['C100', 'C200'] },
    dataScopeLabel: '公司主体：全部；采购组织：全部（只读）',
    home: 'assistant',
  },
  {
    id: 'chengong',
    name: '陈工',
    tenantId: 't001',
    roles: ['tenant-developer'],
    dataScope: NO_SCOPE,
    dataScopeLabel: '无业务数据权限，有 Studio 权限',
    home: 'studio',
  },
  {
    id: 'zhaoadmin',
    name: '赵管理员',
    tenantId: 't001',
    roles: ['tenant-admin'],
    dataScope: NO_SCOPE,
    dataScopeLabel: '管理 Agent 授权与委托',
    home: 'hub',
  },
  {
    id: 'zhouting',
    name: '周婷',
    tenantId: 't002',
    roles: ['purchasing-manager'],
    dataScope: { purchasingOrgs: ['P-EAST'], companies: ['C100'] },
    dataScopeLabel: '采购组织：华东',
    home: 'assistant',
  },
  {
    id: 'vendordev',
    name: '厂商研发',
    tenantId: 'vendor',
    roles: ['vendor-developer'],
    dataScope: NO_SCOPE,
    dataScopeLabel: '无任何租户业务数据',
    home: 'studio',
  },
  {
    id: 'platformops',
    name: '平台运维',
    tenantId: 'platform',
    roles: ['platform-operator'],
    dataScope: NO_SCOPE,
    dataScopeLabel: '平台管理，无业务数据',
    home: 'hub',
  },
]

/** 机机集成账号，只能调用 Open API（主提示词 6.3、7.2 节）；密钥由平台首次启动时生成 */
export const INTEGRATION_CLIENTS: readonly { id: string; tenantId: string; name: string }[] = [
  { id: 't001-integration', tenantId: 't001', name: '星海制造集成账号' },
  { id: 't002-integration', tenantId: 't002', name: '远航电子集成账号' },
]

export function findUser(id: string): DemoUser | undefined {
  return USERS.find((u) => u.id === id)
}

export function findTenant(id: string): Tenant | undefined {
  return TENANTS.find((t) => t.id === id)
}

/** 用户的全部业务权限（各角色允许操作的并集） */
export function permittedOperations(user: DemoUser): BoOperation[] {
  const ops = new Set<BoOperation>()
  for (const role of user.roles) for (const op of ROLES[role].operations) ops.add(op)
  return [...ops]
}

export function roleNames(user: DemoUser): string {
  return user.roles.map((r) => ROLES[r].name).join('、')
}

/** 对外展示的用户信息（不含权限细节之外的任何内容） */
export interface PublicUser {
  id: string
  name: string
  tenantId: string
  tenantName: string
  role: string
  dataScope: string
  home: Workspace
}

export function toPublicUser(user: DemoUser): PublicUser {
  const tenantName =
    user.tenantId === 'vendor' ? '厂商' : user.tenantId === 'platform' ? '平台' : (findTenant(user.tenantId)?.name ?? user.tenantId)
  return {
    id: user.id,
    name: user.name,
    tenantId: user.tenantId,
    tenantName,
    role: roleNames(user),
    dataScope: user.dataScopeLabel,
    home: user.home,
  }
}
