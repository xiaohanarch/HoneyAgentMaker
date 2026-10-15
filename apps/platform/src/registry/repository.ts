import { and, asc, eq, type SQL } from 'drizzle-orm'
import type { AssetLayer, AssetStatus } from '@eab/spec'
import type { PlatformDb } from '../db/client'
import { assets } from '../db/schema'

export type AssetRecord = typeof assets.$inferSelect
export type NewAssetRecord = typeof assets.$inferInsert

export interface AssetFilter {
  kind?: string
  namespace?: string
  layer?: AssetLayer
  tenantId?: string
  status?: AssetStatus
}

/** 资产存储接口。业务逻辑只依赖它，不直接写 SQL */
export interface AssetRepository {
  insert(record: NewAssetRecord): AssetRecord
  find(namespace: string, name: string, version: string): AssetRecord | undefined
  listVersions(namespace: string, name: string): AssetRecord[]
  list(filter?: AssetFilter): AssetRecord[]
  updateStatus(
    id: number,
    status: AssetStatus,
    patch: { signature?: string | null; updatedAt: string },
  ): AssetRecord
}

export class SqliteAssetRepository implements AssetRepository {
  constructor(private readonly db: PlatformDb) {}

  insert(record: NewAssetRecord): AssetRecord {
    return this.db.insert(assets).values(record).returning().get()
  }

  find(namespace: string, name: string, version: string): AssetRecord | undefined {
    return this.db
      .select()
      .from(assets)
      .where(
        and(eq(assets.namespace, namespace), eq(assets.name, name), eq(assets.version, version)),
      )
      .get()
  }

  listVersions(namespace: string, name: string): AssetRecord[] {
    return this.db
      .select()
      .from(assets)
      .where(and(eq(assets.namespace, namespace), eq(assets.name, name)))
      .all()
  }

  list(filter: AssetFilter = {}): AssetRecord[] {
    const conditions: SQL[] = []
    if (filter.kind !== undefined) conditions.push(eq(assets.kind, filter.kind))
    if (filter.namespace !== undefined) conditions.push(eq(assets.namespace, filter.namespace))
    if (filter.layer !== undefined) conditions.push(eq(assets.layer, filter.layer))
    if (filter.tenantId !== undefined) conditions.push(eq(assets.tenantId, filter.tenantId))
    if (filter.status !== undefined) conditions.push(eq(assets.status, filter.status))
    return this.db
      .select()
      .from(assets)
      .where(conditions.length === 0 ? undefined : and(...conditions))
      .orderBy(asc(assets.namespace), asc(assets.name), asc(assets.id))
      .all()
  }

  updateStatus(
    id: number,
    status: AssetStatus,
    patch: { signature?: string | null; updatedAt: string },
  ): AssetRecord {
    return this.db
      .update(assets)
      .set({ status, ...patch })
      .where(eq(assets.id, id))
      .returning()
      .get()
  }
}
