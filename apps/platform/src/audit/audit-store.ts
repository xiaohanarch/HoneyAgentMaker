import { and, desc, eq, type SQL } from 'drizzle-orm'
import type { PlatformDb } from '../db/client'
import { auditEvents } from '../db/schema'

export type AuditEvent = typeof auditEvents.$inferSelect

export interface NewAuditEvent {
  actor: string
  action: string
  target: string
  decision: 'allow' | 'deny'
  reason: string
  detail?: Record<string, unknown>
}

export interface AuditFilter {
  action?: string
  decision?: 'allow' | 'deny'
  limit?: number
}

/** 审计写入与查询。P1 只覆盖注册中心，P2 扩充调用模式、数据范围等字段（主提示词 7.5 节） */
export interface AuditStore {
  append(event: NewAuditEvent): AuditEvent
  list(filter?: AuditFilter): AuditEvent[]
}

const DEFAULT_LIMIT = 100

export class SqliteAuditStore implements AuditStore {
  constructor(
    private readonly db: PlatformDb,
    private readonly now: () => Date = () => new Date(),
  ) {}

  append(event: NewAuditEvent): AuditEvent {
    return this.db
      .insert(auditEvents)
      .values({
        at: this.now().toISOString(),
        actor: event.actor,
        action: event.action,
        target: event.target,
        decision: event.decision,
        reason: event.reason,
        detail: event.detail === undefined ? null : JSON.stringify(event.detail),
      })
      .returning()
      .get()
  }

  list(filter: AuditFilter = {}): AuditEvent[] {
    const conditions: SQL[] = []
    if (filter.action !== undefined) conditions.push(eq(auditEvents.action, filter.action))
    if (filter.decision !== undefined) conditions.push(eq(auditEvents.decision, filter.decision))
    return this.db
      .select()
      .from(auditEvents)
      .where(conditions.length === 0 ? undefined : and(...conditions))
      .orderBy(desc(auditEvents.id))
      .limit(filter.limit ?? DEFAULT_LIMIT)
      .all()
  }
}
