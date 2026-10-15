import semver from 'semver'
import {
  canReadAsset,
  canWriteNamespace,
  formatViewer,
  layerOfNamespace,
  outOfScopeReferences,
  parseAssetObject,
  parseAssetYaml,
  redactAsset,
  tenantIdOfNamespace,
  type Asset,
  type AssetIssue,
  type AssetStatus,
  type AssetView,
  type Viewer,
} from '@eab/spec'
import type { AuditStore } from '../audit/audit-store'
import type { AssetFilter, AssetRecord, AssetRepository } from './repository'
import { ScopedResolver } from './scoped-resolver'
import { canonicalJson, contentHashOf, signingPayload, type Signer } from './signing'

export type RegistryErrorCode =
  | 'ASSET_INVALID'
  | 'REGISTRY_WRITE_FORBIDDEN'
  | 'VERSION_EXISTS'
  | 'NOT_FOUND'
  | 'INVALID_TRANSITION'
  | 'INTEGRITY_VIOLATION'
  | 'PUBLISH_BLOCKED'

export interface RegistryError {
  code: RegistryErrorCode
  message: string
  issues?: AssetIssue[]
}

export type RegistryResult<T> = { ok: true; value: T } | { ok: false; error: RegistryError }

export interface AssetSummary {
  kind: string
  namespace: string
  name: string
  version: string
  layer: string
  status: AssetStatus
  contentHash: string
  /** 签名列非空；是否验签通过以读取时的完整性检查为准 */
  signed: boolean
  changeNote: string | null
  updatedAt: string
}

/**
 * 发布门禁：发布前运行静态检查和试组装，返回阻止发布的问题。
 * 由装配层注入，注册中心本身不依赖组装器。
 */
export type PublishGate = (
  asset: Asset,
  publisher: Viewer,
  registry: RegistryService,
) => AssetIssue[]

/** 状态流转（主提示词第 12 节）：草稿 → 测试中 → 已发布 → 已下线，草稿可以直接发布 */
const TRANSITIONS: Readonly<Record<AssetStatus, readonly AssetStatus[]>> = {
  draft: ['testing', 'published', 'retired'],
  testing: ['draft', 'published', 'retired'],
  published: ['retired'],
  retired: [],
}

/** 标品层和平台层资产发布时签名，读取时验签 */
const isSignedLayer = (namespace: string): boolean => layerOfNamespace(namespace) !== 'tenant'

/** 租户身份的操作在审计里带上租户编号，租户管理员可以查到本租户的记录 */
const tenantOf = (viewer: Viewer): { tenantId?: string } =>
  viewer.type === 'tenant' ? { tenantId: viewer.tenantId } : {}

const refOf = (r: { namespace: string; name: string; version: string }): string =>
  `${r.namespace}/${r.name}@${r.version}`

const ok = <T>(value: T): RegistryResult<T> => ({ ok: true, value })
const fail = <T>(
  code: RegistryErrorCode,
  message: string,
  issues?: AssetIssue[],
): RegistryResult<T> => ({
  ok: false,
  error: issues === undefined ? { code, message } : { code, message, issues },
})

export interface RegistryDeps {
  assets: AssetRepository
  audit: AuditStore
  signer: Signer
  publishGate?: PublishGate
  now?: () => Date
}

export interface LatestPublished {
  assets: Asset[]
  /** 完整性检查没通过而被拒绝加载的资产 */
  rejected: string[]
}

/**
 * 资产注册中心：存储、版本、状态、命名空间与按层可见性（主提示词 5.2 节）。
 * 组装器通过 resolverFor(viewer) 取解析器，只能读到该身份可见的资产，读出的标品层资产都验过签名。
 */
export class RegistryService {
  readonly #now: () => Date
  /** 同一条完整性失败只写一次审计，避免反复读取时把审计表写爆 */
  readonly #reportedIntegrity = new Set<string>()

  constructor(private readonly deps: RegistryDeps) {
    this.#now = deps.now ?? (() => new Date())
  }

  get signer(): Signer {
    return this.deps.signer
  }

  // ── 写入 ───────────────────────────────────────────────────────────────

  /** 提交 YAML，创建一个新的草稿版本。版本号已存在时拒绝，已有版本不可改 */
  put(viewer: Viewer, yaml: string, changeNote?: string): RegistryResult<AssetSummary> {
    const parsed = parseAssetYaml(yaml)
    return parsed.ok
      ? this.#insert(viewer, parsed.asset, yaml, changeNote)
      : fail('ASSET_INVALID', '资产校验未通过', parsed.issues)
  }

  /** 变更状态；发布前过门禁，标品层、平台层发布时签名，下线时作废签名 */
  setStatus(
    viewer: Viewer,
    namespace: string,
    name: string,
    version: string,
    status: AssetStatus,
  ): RegistryResult<AssetSummary> {
    const target = `${namespace}/${name}@${version}`
    if (!this.#authorizeWrite(viewer, namespace, target, 'registry.status')) {
      return fail(
        'REGISTRY_WRITE_FORBIDDEN',
        `身份 ${formatViewer(viewer)} 不能修改 ${namespace} 下的资产`,
      )
    }
    const record = this.deps.assets.find(namespace, name, version)
    if (record === undefined) return fail('NOT_FOUND', `资产 ${target} 不存在`)
    if (!TRANSITIONS[record.status].includes(status)) {
      return fail('INVALID_TRANSITION', `不能从 ${record.status} 变更为 ${status}`)
    }

    let signature: string | null = status === 'retired' ? null : record.signature
    if (status === 'published') {
      // 签名前重新核对内容哈希，库里被改过的内容不能拿到合法签名
      const asset = this.#verifyContent(record)
      if (asset === undefined)
        return fail('INTEGRITY_VIOLATION', `${target} 的存储内容与哈希不一致，拒绝发布`)
      const blocked = this.deps.publishGate?.(asset, viewer, this) ?? []
      if (blocked.length > 0) {
        this.deps.audit.append({
          actor: formatViewer(viewer),
      ...tenantOf(viewer),
          action: 'registry.publish',
          target,
          decision: 'deny',
          reason: `发布门禁未通过：${blocked.length} 个问题`,
        })
        return fail('PUBLISH_BLOCKED', '发布门禁未通过', blocked)
      }
      if (isSignedLayer(namespace)) {
        signature = this.deps.signer.sign(signingPayload(target, record.contentHash))
      }
    }

    const updated = this.deps.assets.updateStatus(record.id, status, {
      signature,
      updatedAt: this.#now().toISOString(),
    })
    this.deps.audit.append({
      actor: formatViewer(viewer),
      ...tenantOf(viewer),
      action: 'registry.status',
      target,
      decision: 'allow',
      reason: `${record.status} → ${status}${status === 'published' && signature !== null ? '，已签名' : ''}`,
    })
    return ok(summarize(updated))
  }

  /**
   * 以本层身份写入一份已解析的资产并保持为草稿，用于启动灌数的第一轮。
   * 同版本同内容时跳过；同版本不同内容时报错，标品改动必须升版本号；已有版本完整性不过时报错。
   */
  stageAsset(viewer: Viewer, asset: Asset, yaml: string): RegistryResult<AssetSummary> {
    const { namespace, name, version } = asset.metadata
    const existing = this.deps.assets.find(namespace, name, version)
    if (existing === undefined) return this.#insert(viewer, asset, yaml)
    if (existing.contentHash !== contentHashOf(canonicalJson(asset))) {
      return fail(
        'VERSION_EXISTS',
        `${refOf(asset.metadata)} 已存在且内容不同，修改资产必须升版本号`,
      )
    }
    if (this.#trusted(existing) === undefined) {
      return fail(
        'INTEGRITY_VIOLATION',
        `${refOf(asset.metadata)} 的已存版本完整性校验失败，拒绝加载`,
      )
    }
    return ok(summarize(existing))
  }

  /** 写入并发布；已发布的同内容版本直接返回 */
  publishAsset(viewer: Viewer, asset: Asset, yaml: string): RegistryResult<AssetSummary> {
    const staged = this.stageAsset(viewer, asset, yaml)
    if (!staged.ok || staged.value.status === 'published') return staged
    const { namespace, name, version } = asset.metadata
    return this.setStatus(viewer, namespace, name, version, 'published')
  }

  #insert(
    viewer: Viewer,
    asset: Asset,
    yaml: string,
    changeNote?: string,
  ): RegistryResult<AssetSummary> {
    const { namespace, name, version } = asset.metadata
    const target = refOf(asset.metadata)
    if (!this.#authorizeWrite(viewer, namespace, target, 'registry.write')) {
      return fail(
        'REGISTRY_WRITE_FORBIDDEN',
        `身份 ${formatViewer(viewer)} 不能写入 ${namespace} 下的资产`,
      )
    }
    // 引用越界在写入时就拒绝：租户不能引用其他租户的资产，标品不能引用租户资产
    const outOfScope = outOfScopeReferences(asset)
    if (outOfScope.length > 0) {
      return fail(
        'ASSET_INVALID',
        '资产引用了不允许引用的命名空间',
        outOfScope.map((r) => ({
          code: 'REFERENCE_OUT_OF_SCOPE',
          message: `不能引用 ${r.ref}：只能引用平台层、标品层和本租户的资产`,
          path: r.path,
        })),
      )
    }
    if (this.deps.assets.find(namespace, name, version) !== undefined) {
      return fail('VERSION_EXISTS', `${target} 已存在，资产版本不可修改，请使用新的版本号`)
    }
    const canonical = canonicalJson(asset)
    const now = this.#now().toISOString()
    const record = this.deps.assets.insert({
      kind: asset.kind,
      namespace,
      name,
      version,
      layer: layerOfNamespace(namespace),
      tenantId: tenantIdOfNamespace(namespace) ?? null,
      status: 'draft',
      contentYaml: yaml,
      contentJson: canonical,
      contentHash: contentHashOf(canonical),
      signature: null,
      changeNote: changeNote ?? null,
      createdBy: formatViewer(viewer),
      createdAt: now,
      updatedAt: now,
    })
    this.deps.audit.append({
      actor: formatViewer(viewer),
      ...tenantOf(viewer),
      action: 'registry.write',
      target,
      decision: 'allow',
      reason: `新建 ${asset.kind} 版本`,
    })
    return ok(summarize(record))
  }

  /** 只有本层身份能写本层命名空间，越层写入在这里拒绝并写审计 */
  #authorizeWrite(viewer: Viewer, namespace: string, target: string, action: string): boolean {
    if (canWriteNamespace(viewer, namespace)) return true
    this.deps.audit.append({
      actor: formatViewer(viewer),
      ...tenantOf(viewer),
      action,
      target,
      decision: 'deny',
      reason: `${formatViewer(viewer)} 无权写入命名空间 ${namespace}`,
    })
    return false
  }

  // ── 按查看者读取 ───────────────────────────────────────────────────────

  /** 按查看者读取单个资产（省略版本时取该身份可见的最新版本），返回裁剪后的视图 */
  view(
    viewer: Viewer,
    namespace: string,
    name: string,
    version?: string,
  ): RegistryResult<{ summary: AssetSummary; asset: AssetView }> {
    const candidates =
      version === undefined
        ? this.deps.assets.listVersions(namespace, name)
        : [this.deps.assets.find(namespace, name, version)].filter((r) => r !== undefined)
    const readable = candidates
      .filter((r) => this.canRead(viewer, r))
      .sort((a, b) => semver.rcompare(a.version, b.version))
    const record = readable[0]
    const asset = record === undefined ? undefined : this.#trusted(record)
    // 无权读取与不存在返回同样的结果，不泄露资产是否存在
    if (record === undefined || asset === undefined) {
      return fail(
        'NOT_FOUND',
        `资产 ${namespace}/${name}${version === undefined ? '' : `@${version}`} 不存在`,
      )
    }
    return ok({
      summary: summarize(record),
      asset: redactAsset(asset, viewer, { digest: (t) => this.deps.signer.digest(t) }),
    })
  }

  /** 列出查看者能看到的资产版本摘要 */
  list(viewer: Viewer, filter: AssetFilter = {}): AssetSummary[] {
    return this.deps.assets
      .list(filter)
      .filter((record) => this.canRead(viewer, record))
      .map(summarize)
  }

  /**
   * 能否读取某条记录：按层可见性（canReadAsset），另外租户只能看到其他层的已发布版本。
   * 只看元数据和 kind 相关字段，不做完整性检查。
   */
  canRead(viewer: Viewer, record: AssetRecord): boolean {
    const asset = parseStored(record)
    if (asset === undefined || !canReadAsset(viewer, asset)) return false
    return viewer.type !== 'tenant' || record.layer === 'tenant' || record.status === 'published'
  }

  // ── 组装用的解析器 ─────────────────────────────────────────────────────

  /** 按查看者限定范围的解析器，组装器只能读到该身份可见的资产 */
  resolverFor(viewer: Viewer): ScopedResolver {
    return new ScopedResolver(viewer, {
      records: (namespace, name) => this.deps.assets.listVersions(namespace, name),
      canRead: (record) => this.canRead(viewer, record),
      trusted: (record) => this.#trusted(record),
    })
  }

  /** 已发布的某类资产，每个资产取最新版本；可按查看者过滤，完整性不过的单独列出 */
  latestPublished(filter: AssetFilter, viewer?: Viewer): LatestPublished {
    const byKey = new Map<string, AssetRecord>()
    for (const record of this.deps.assets.list({ ...filter, status: 'published' })) {
      if (viewer !== undefined && !this.canRead(viewer, record)) continue
      const key = `${record.namespace}/${record.name}`
      const current = byKey.get(key)
      if (current === undefined || semver.gt(record.version, current.version))
        byKey.set(key, record)
    }
    const result: LatestPublished = { assets: [], rejected: [] }
    for (const record of byKey.values()) {
      const asset = this.#trusted(record)
      if (asset === undefined) result.rejected.push(refOf(record))
      else result.assets.push(asset)
    }
    return result
  }

  /** 内容与哈希一致时返回解析后的资产（不验签） */
  #verifyContent(record: AssetRecord): Asset | undefined {
    if (contentHashOf(record.contentJson) !== record.contentHash) return undefined
    return parseStored(record)
  }

  /**
   * 取出可信的资产内容：内容哈希必须与存储内容一致；标品层、平台层的已发布版本还必须验签。
   * 不符时拒绝加载并写审计（主提示词 5.2 节“签名不符拒绝加载”）。
   */
  #trusted(record: AssetRecord): Asset | undefined {
    const target = refOf(record)
    const reject = (reason: string): undefined => {
      const key = `${target}|${record.contentHash}|${record.signature ?? ''}|${reason}`
      if (!this.#reportedIntegrity.has(key)) {
        this.#reportedIntegrity.add(key)
        this.deps.audit.append({
          actor: 'platform',
          action: 'registry.integrity',
          target,
          decision: 'deny',
          reason,
        })
      }
      return undefined
    }
    if (contentHashOf(record.contentJson) !== record.contentHash) {
      return reject('内容哈希与存储内容不一致，可能被篡改')
    }
    if (record.status === 'published' && isSignedLayer(record.namespace)) {
      if (record.signature === null) return reject('标品资产缺少签名')
      if (!this.deps.signer.verify(signingPayload(target, record.contentHash), record.signature)) {
        return reject('签名校验失败')
      }
    }
    return parseStored(record) ?? reject('存储内容无法通过 schema 校验')
  }
}

function parseStored(record: AssetRecord): Asset | undefined {
  try {
    const parsed = parseAssetObject(JSON.parse(record.contentJson))
    return parsed.ok ? parsed.asset : undefined
  } catch {
    return undefined
  }
}

function summarize(record: AssetRecord): AssetSummary {
  return {
    kind: record.kind,
    namespace: record.namespace,
    name: record.name,
    version: record.version,
    layer: record.layer,
    status: record.status,
    contentHash: record.contentHash,
    signed: record.signature !== null,
    changeNote: record.changeNote,
    updatedAt: record.updatedAt,
  }
}
