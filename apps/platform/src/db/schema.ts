import { index, integer, sqliteTable, text, uniqueIndex } from 'drizzle-orm/sqlite-core'

/** 资产版本表：一行一个资产的一个版本，版本发布后内容不可变 */
export const assets = sqliteTable(
  'assets',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    kind: text('kind').notNull(),
    namespace: text('namespace').notNull(),
    name: text('name').notNull(),
    version: text('version').notNull(),
    layer: text('layer', { enum: ['platform', 'vendor', 'tenant'] }).notNull(),
    tenantId: text('tenant_id'),
    status: text('status', { enum: ['draft', 'testing', 'published', 'retired'] }).notNull(),
    /** 作者提交的 YAML 原文，用于展示和差异对比 */
    contentYaml: text('content_yaml').notNull(),
    /** 解析后（含默认值）的规范化 JSON，签名和哈希都基于它 */
    contentJson: text('content_json').notNull(),
    contentHash: text('content_hash').notNull(),
    /** 标品层、平台层发布时的 Ed25519 签名（base64） */
    signature: text('signature'),
    changeNote: text('change_note'),
    createdBy: text('created_by').notNull(),
    createdAt: text('created_at').notNull(),
    updatedAt: text('updated_at').notNull(),
  },
  (t) => [
    uniqueIndex('assets_ns_name_version').on(t.namespace, t.name, t.version),
    index('assets_kind').on(t.kind),
    index('assets_tenant').on(t.tenantId),
  ],
)

/**
 * 审计事件（主提示词 7.5 节）：注册中心的写入与完整性裁决、鉴权点 1 至 3 的裁决、
 * DemoERP 推送过来的鉴权点 4 裁决与业务写入，都记在这一张表里，“谁通过哪个 Agent 做了什么”在一处可查。
 */
export const auditEvents = sqliteTable(
  'audit_events',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    at: text('at').notNull(),
    actor: text('actor').notNull(),
    action: text('action').notNull(),
    target: text('target').notNull(),
    decision: text('decision', { enum: ['allow', 'deny', 'confirm', 'approve'] }).notNull(),
    reason: text('reason').notNull(),
    detail: text('detail'),
    tenantId: text('tenant_id'),
    userId: text('user_id'),
    /** Agent 身份与版本，例如 vendor.fin/transaction-anomaly-analyst@1.2.0 */
    agent: text('agent'),
    /** 生效的租户扩展及版本，JSON 数组 */
    extensions: text('extensions'),
    sessionId: text('session_id'),
    /** on-behalf 代用户操作、delegated 受托自主运行、integration 机机集成 */
    mode: text('mode'),
    /** entry、handoff、tool、bo-api */
    checkpoint: text('checkpoint'),
    tool: text('tool'),
    /** 访问的数据范围，例如“采购组织：华东” */
    dataScope: text('data_scope'),
    ruleId: text('rule_id'),
    resultSummary: text('result_summary'),
  },
  (t) => [
    index('audit_at').on(t.at),
    index('audit_action').on(t.action),
    index('audit_tenant_user').on(t.tenantId, t.userId),
    index('audit_agent').on(t.agent),
  ],
)

/** 受托自主运行的委托（主提示词 7.2 节）：委托人、Agent、授权范围、有效期，可撤销 */
export const delegations = sqliteTable(
  'delegations',
  {
    id: text('id').primaryKey(),
    tenantId: text('tenant_id').notNull(),
    delegatorUserId: text('delegator_user_id').notNull(),
    /** 被委托的 Agent，namespace/name */
    agent: text('agent').notNull(),
    /** 授权范围：允许的 BO 操作，JSON 数组 */
    scope: text('scope').notNull(),
    validFrom: text('valid_from').notNull(),
    validUntil: text('valid_until').notNull(),
    revokedAt: text('revoked_at'),
    revokedBy: text('revoked_by'),
    note: text('note'),
    createdBy: text('created_by').notNull(),
    createdAt: text('created_at').notNull(),
  },
  (t) => [index('delegations_tenant').on(t.tenantId)],
)

/** 租户级的鉴权开关：L3 自主写是否开启、岗位助手开放给哪些角色 */
export const tenantSettings = sqliteTable('tenant_settings', {
  tenantId: text('tenant_id').primaryKey(),
  l3Enabled: integer('l3_enabled', { mode: 'boolean' }).notNull(),
  /** { 助手引用: [角色] }，JSON */
  assistantRoles: text('assistant_roles').notNull(),
  updatedAt: text('updated_at').notNull(),
})

/** DemoERP 推送的业务事件，P4 起作为 Agent 的触发器 */
export const businessEvents = sqliteTable(
  'business_events',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    receivedAt: text('received_at').notNull(),
    source: text('source').notNull(),
    type: text('type').notNull(),
    tenantId: text('tenant_id').notNull(),
    payload: text('payload').notNull(),
  },
  (t) => [index('events_type').on(t.type)],
)
