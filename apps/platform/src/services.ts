import { DEFAULT_RULES, type PolicyRuleSet } from '@eab/policy'
import { SqliteAuditStore, type AuditStore } from './audit/audit-store'
import { openPlatformDb, type PlatformDb } from './db/client'
import { EventStore } from './events/event-store'
import { ClientRegistry } from './identity/clients'
import { DelegationStore } from './identity/delegations'
import type { IdentityKeys } from './identity/keys'
import { IdentityService } from './identity/service'
import { TokenService } from './identity/tokens'
import { TenantSettingsStore } from './policy/tenant-settings'
import { publishGate } from './registry/publish-gate'
import { SqliteAssetRepository } from './registry/repository'
import { RegistryService, type PublishGate } from './registry/service'
import type { Signer } from './registry/signing'

/** 平台主服务的依赖装配，测试和命令行用 ':memory:' 数据库与临时密钥 */
export interface PlatformServices {
  db: PlatformDb
  audit: AuditStore
  registry: RegistryService
  tokens: TokenService
  identity: IdentityService
  delegations: DelegationStore
  clients: ClientRegistry
  tenantSettings: TenantSettingsStore
  events: EventStore
  policyRules: PolicyRuleSet
  /** 平台与 DemoERP 之间的内部调用密钥（事件推送签名、委托核实），由 pnpm demo 每次随机生成 */
  internalSecret: string
}

export interface ServiceOptions {
  dbPath: string
  signer: Signer
  identityKeys: IdentityKeys
  clients?: ClientRegistry
  internalSecret?: string
  /** 替换发布门禁；只测注册中心机制的单元测试可以传入空门禁 */
  publishGate?: PublishGate
  now?: () => Date
}

export function createPlatformServices(options: ServiceOptions): PlatformServices {
  const db = openPlatformDb(options.dbPath)
  const now = options.now
  const audit = new SqliteAuditStore(db, now)
  const registry = new RegistryService({
    assets: new SqliteAssetRepository(db),
    audit,
    signer: options.signer,
    publishGate: options.publishGate ?? publishGate,
    ...(now === undefined ? {} : { now }),
  })
  const tokens = new TokenService(options.identityKeys, now)
  const delegations = new DelegationStore(db, now)
  const clients = options.clients ?? ClientRegistry.ephemeral()
  return {
    db,
    audit,
    registry,
    tokens,
    identity: new IdentityService({ tokens, delegations, clients, audit }),
    delegations,
    clients,
    tenantSettings: new TenantSettingsStore(db, now),
    events: new EventStore(db, now),
    policyRules: DEFAULT_RULES,
    internalSecret: options.internalSecret ?? crypto.randomUUID(),
  }
}

/** 演示用的预置委托编号，故事 D 中由赵管理员撤销 */
export const DEMO_DELEGATION_ID = 'DLG-0001'

/**
 * 预置演示数据：孙库管把库存只读操作委托给库存经营分析 Agent，用于受托自主运行（主提示词 7.2 节）。
 * 已存在时不重复创建。
 */
export function ensureDemoDefaults(services: PlatformServices, now: Date = new Date()): void {
  if (services.delegations.get(DEMO_DELEGATION_ID) !== undefined) return
  const validUntil = new Date(now.getTime() + 90 * 24 * 3600 * 1000)
  services.delegations.create(
    {
      tenantId: 't001',
      delegatorUserId: 'sunkg',
      agent: 'vendor.scm/inventory-risk-analyst',
      scope: ['inventory_balance_list', 'material_list', 'stock_movement_list', 'purchase_order_list', 'delivery_list'],
      validFrom: now.toISOString(),
      validUntil: validUntil.toISOString(),
      note: '交付逾期、库存低于安全线时，库存经营分析 Agent 以孙库管的委托自主分析库存影响',
      createdBy: 'zhaoadmin',
    },
    DEMO_DELEGATION_ID,
  )
}
