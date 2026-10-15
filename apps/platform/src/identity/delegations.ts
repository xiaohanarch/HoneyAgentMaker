import { desc, eq } from 'drizzle-orm'
import type { PlatformDb } from '../db/client'
import { delegations } from '../db/schema'

export type DelegationRecord = typeof delegations.$inferSelect

export interface Delegation {
  id: string
  tenantId: string
  delegatorUserId: string
  agent: string
  scope: string[]
  validFrom: string
  validUntil: string
  revokedAt: string | null
  revokedBy: string | null
  note: string | null
  createdBy: string
  createdAt: string
}

export interface NewDelegation {
  tenantId: string
  delegatorUserId: string
  agent: string
  scope: readonly string[]
  validFrom: string
  validUntil: string
  note?: string
  createdBy: string
}

export type DelegationStatus =
  | { active: true; delegation: Delegation }
  | {
      active: false
      reason: 'not-found' | 'revoked' | 'not-yet-valid' | 'expired'
      delegation?: Delegation
    }

function toDelegation(record: DelegationRecord): Delegation {
  return { ...record, scope: JSON.parse(record.scope) as string[] }
}

/** 受托自主运行的委托：登记、撤销、核实（主提示词 7.2 节） */
export class DelegationStore {
  constructor(
    private readonly db: PlatformDb,
    private readonly now: () => Date = () => new Date(),
  ) {}

  create(input: NewDelegation, id?: string): Delegation {
    const record = this.db
      .insert(delegations)
      .values({
        id: id ?? `DLG-${crypto.randomUUID().slice(0, 8).toUpperCase()}`,
        tenantId: input.tenantId,
        delegatorUserId: input.delegatorUserId,
        agent: input.agent,
        scope: JSON.stringify([...input.scope]),
        validFrom: input.validFrom,
        validUntil: input.validUntil,
        revokedAt: null,
        revokedBy: null,
        note: input.note ?? null,
        createdBy: input.createdBy,
        createdAt: this.now().toISOString(),
      })
      .returning()
      .get()
    return toDelegation(record)
  }

  get(id: string): Delegation | undefined {
    const record = this.db.select().from(delegations).where(eq(delegations.id, id)).get()
    return record === undefined ? undefined : toDelegation(record)
  }

  listByTenant(tenantId: string): Delegation[] {
    return this.db
      .select()
      .from(delegations)
      .where(eq(delegations.tenantId, tenantId))
      .orderBy(desc(delegations.createdAt))
      .all()
      .map(toDelegation)
  }

  /** 撤销即时生效：之后的令牌交换和 BO API 核实都会失败 */
  revoke(id: string, revokedBy: string): Delegation | undefined {
    const record = this.db
      .update(delegations)
      .set({ revokedAt: this.now().toISOString(), revokedBy })
      .where(eq(delegations.id, id))
      .returning()
      .get()
    return record === undefined ? undefined : toDelegation(record)
  }

  status(id: string): DelegationStatus {
    const delegation = this.get(id)
    if (delegation === undefined) return { active: false, reason: 'not-found' }
    const now = this.now().toISOString()
    if (delegation.revokedAt !== null) return { active: false, reason: 'revoked', delegation }
    if (now < delegation.validFrom) return { active: false, reason: 'not-yet-valid', delegation }
    if (now > delegation.validUntil) return { active: false, reason: 'expired', delegation }
    return { active: true, delegation }
  }
}
