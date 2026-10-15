import { eq } from 'drizzle-orm'
import type { PlatformDb } from '../db/client'
import { tenantSettings } from '../db/schema'

export interface TenantSettings {
  tenantId: string
  /** L3 自主写是否开启（主提示词 7.4 节：需要租户管理员显式开启） */
  l3Enabled: boolean
  /** 岗位助手开放给哪些角色；没有登记的助手对所有有业务权限的角色开放 */
  assistantRoles: Record<string, string[]>
}

/** 演示租户的默认设置：L3 关闭，岗位助手按岗位开放 */
const DEFAULT_ASSISTANT_ROLES: Record<string, string[]> = {
  'vendor.pur/procurement-assistant': ['purchasing-manager', 'purchaser', 'inventory-manager'],
  'vendor.scm/inventory-assistant': ['inventory-manager', 'purchasing-manager'],
  'vendor.fin/finance-assistant': ['finance-manager'],
}

export class TenantSettingsStore {
  constructor(
    private readonly db: PlatformDb,
    private readonly now: () => Date = () => new Date(),
  ) {}

  get(tenantId: string): TenantSettings {
    const row = this.db
      .select()
      .from(tenantSettings)
      .where(eq(tenantSettings.tenantId, tenantId))
      .get()
    if (row === undefined)
      return { tenantId, l3Enabled: false, assistantRoles: DEFAULT_ASSISTANT_ROLES }
    return {
      tenantId,
      l3Enabled: row.l3Enabled,
      assistantRoles: JSON.parse(row.assistantRoles) as Record<string, string[]>,
    }
  }

  set(settings: TenantSettings): TenantSettings {
    const values = {
      tenantId: settings.tenantId,
      l3Enabled: settings.l3Enabled,
      assistantRoles: JSON.stringify(settings.assistantRoles),
      updatedAt: this.now().toISOString(),
    }
    this.db
      .insert(tenantSettings)
      .values(values)
      .onConflictDoUpdate({ target: tenantSettings.tenantId, set: values })
      .run()
    return this.get(settings.tenantId)
  }
}
