import { SqliteAuditStore, type AuditStore } from './audit/audit-store'
import { openPlatformDb, type PlatformDb } from './db/client'
import { SqliteAssetRepository } from './registry/repository'
import { publishGate } from './registry/publish-gate'
import { RegistryService, type PublishGate } from './registry/service'
import type { Signer } from './registry/signing'

/** 平台主服务的依赖装配，测试和命令行用 ':memory:' 数据库与临时密钥 */
export interface PlatformServices {
  db: PlatformDb
  audit: AuditStore
  registry: RegistryService
}

export interface ServiceOptions {
  dbPath: string
  signer: Signer
  /** 替换发布门禁；只测注册中心机制的单元测试可以传入空门禁 */
  publishGate?: PublishGate
  now?: () => Date
}

export function createPlatformServices(options: ServiceOptions): PlatformServices {
  const db = openPlatformDb(options.dbPath)
  const audit = new SqliteAuditStore(db, options.now)
  const registry = new RegistryService({
    assets: new SqliteAssetRepository(db),
    audit,
    signer: options.signer,
    publishGate: options.publishGate ?? publishGate,
    ...(options.now === undefined ? {} : { now: options.now }),
  })
  return { db, audit, registry }
}
