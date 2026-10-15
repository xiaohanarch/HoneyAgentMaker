import { and, desc, eq, gte, like, lte, type SQL } from 'drizzle-orm'
import type { PlatformDb } from '../db/client'
import { auditEvents } from '../db/schema'

export type AuditEvent = typeof auditEvents.$inferSelect
export type AuditDecision = AuditEvent['decision']

export interface NewAuditEvent {
  actor: string
  action: string
  target: string
  decision: AuditDecision
  reason: string
  detail?: Record<string, unknown>
  tenantId?: string
  userId?: string
  agent?: string
  extensions?: readonly string[]
  sessionId?: string
  mode?: string
  checkpoint?: string
  tool?: string
  dataScope?: string
  ruleId?: string
  resultSummary?: string
}

export interface AuditFilter {
  action?: string
  decision?: AuditDecision
  tenantId?: string
  userId?: string
  agent?: string
  tool?: string
  checkpoint?: string
  /** 目标包含的文本，例如单据编号 PO-2026-0117 */
  target?: string
  from?: string
  to?: string
  limit?: number
}

/** 审计写入与查询（主提示词 7.5 节） */
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
        tenantId: event.tenantId ?? null,
        userId: event.userId ?? null,
        agent: event.agent ?? null,
        extensions: event.extensions === undefined ? null : JSON.stringify(event.extensions),
        sessionId: event.sessionId ?? null,
        mode: event.mode ?? null,
        checkpoint: event.checkpoint ?? null,
        tool: event.tool ?? null,
        dataScope: event.dataScope ?? null,
        ruleId: event.ruleId ?? null,
        resultSummary: event.resultSummary ?? null,
      })
      .returning()
      .get()
  }

  list(filter: AuditFilter = {}): AuditEvent[] {
    const conditions: SQL[] = []
    const exact = [
      [auditEvents.action, filter.action],
      [auditEvents.decision, filter.decision],
      [auditEvents.tenantId, filter.tenantId],
      [auditEvents.userId, filter.userId],
      [auditEvents.tool, filter.tool],
      [auditEvents.checkpoint, filter.checkpoint],
    ] as const
    for (const [column, value] of exact) if (value !== undefined) conditions.push(eq(column, value))
    if (filter.agent !== undefined) conditions.push(like(auditEvents.agent, `${filter.agent}%`))
    if (filter.target !== undefined) conditions.push(like(auditEvents.target, `%${filter.target}%`))
    if (filter.from !== undefined) conditions.push(gte(auditEvents.at, filter.from))
    if (filter.to !== undefined) conditions.push(lte(auditEvents.at, filter.to))
    return this.db
      .select()
      .from(auditEvents)
      .where(conditions.length === 0 ? undefined : and(...conditions))
      .orderBy(desc(auditEvents.id))
      .limit(filter.limit ?? DEFAULT_LIMIT)
      .all()
  }
}
