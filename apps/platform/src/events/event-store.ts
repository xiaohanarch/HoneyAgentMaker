import { EventEmitter } from 'node:events'
import { desc, eq } from 'drizzle-orm'
import type { PlatformDb } from '../db/client'
import { businessEvents } from '../db/schema'

/** 业务事件（主提示词 6.4 节），P4 起作为 Agent 的触发器 */
export const BUSINESS_EVENT_TYPES = [
  'delivery.overdue',
  'inventory.below_safety_stock',
  'transaction.anomaly_created',
  'period.close_check_failed',
] as const
export type BusinessEventType = (typeof BUSINESS_EVENT_TYPES)[number]

export interface BusinessEvent {
  id: number
  receivedAt: string
  source: string
  type: string
  tenantId: string
  payload: Record<string, unknown>
}

/** 平台内的事件总线：持久化后在进程内广播，订阅方（P4 的触发器）按类型监听 */
export class EventStore {
  readonly #bus = new EventEmitter()

  constructor(
    private readonly db: PlatformDb,
    private readonly now: () => Date = () => new Date(),
  ) {}

  append(event: Omit<BusinessEvent, 'id' | 'receivedAt'>): BusinessEvent {
    const row = this.db
      .insert(businessEvents)
      .values({
        receivedAt: this.now().toISOString(),
        source: event.source,
        type: event.type,
        tenantId: event.tenantId,
        payload: JSON.stringify(event.payload),
      })
      .returning()
      .get()
    const stored: BusinessEvent = { ...row, payload: event.payload }
    this.#bus.emit('event', stored)
    return stored
  }

  list(tenantId?: string, limit = 50): BusinessEvent[] {
    return this.db
      .select()
      .from(businessEvents)
      .where(tenantId === undefined ? undefined : eq(businessEvents.tenantId, tenantId))
      .orderBy(desc(businessEvents.id))
      .limit(limit)
      .all()
      .map((row) => ({ ...row, payload: JSON.parse(row.payload) as Record<string, unknown> }))
  }

  subscribe(listener: (event: BusinessEvent) => void): () => void {
    this.#bus.on('event', listener)
    return () => this.#bus.off('event', listener)
  }
}
