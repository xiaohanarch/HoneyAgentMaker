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

/** 审计事件。P1 只记录注册中心的写入与完整性裁决，P2 扩充为完整的审计模型 */
export const auditEvents = sqliteTable(
  'audit_events',
  {
    id: integer('id').primaryKey({ autoIncrement: true }),
    at: text('at').notNull(),
    actor: text('actor').notNull(),
    action: text('action').notNull(),
    target: text('target').notNull(),
    decision: text('decision', { enum: ['allow', 'deny'] }).notNull(),
    reason: text('reason').notNull(),
    detail: text('detail'),
  },
  (t) => [index('audit_at').on(t.at), index('audit_action').on(t.action)],
)
