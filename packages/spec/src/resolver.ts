import semver from 'semver'
import type { Asset, AssetKind, AssetByKind } from './assets'
import { assetKey, parseAssetRef, type AssetRef } from './refs'

/** 资产生命周期状态（主提示词第 12 节） */
export const ASSET_STATUSES = ['draft', 'testing', 'published', 'retired'] as const
export type AssetStatus = (typeof ASSET_STATUSES)[number]

export interface AssetVersionInfo {
  version: string
  status: AssetStatus
}

/**
 * 按命名空间、名称、版本取资产的只读接口。组装器、静态检查、注册中心共用。
 * 签名校验等完整性检查由实现方负责：返回的资产必须是可信的。
 */
export interface AssetResolver {
  listVersions(namespace: string, name: string): readonly AssetVersionInfo[]
  get(namespace: string, name: string, version: string): Asset | undefined
}

export interface ResolveOptions {
  /** 参与解析的状态，默认只看已发布版本 */
  statuses?: readonly AssetStatus[]
}

const DEFAULT_STATUSES: readonly AssetStatus[] = ['published']

/** 在指定状态的版本里，取满足版本范围的最高版本号 */
export function resolveVersion(
  resolver: AssetResolver,
  ref: AssetRef,
  options: ResolveOptions = {},
): string | undefined {
  const statuses = options.statuses ?? DEFAULT_STATUSES
  const candidates = resolver
    .listVersions(ref.namespace, ref.name)
    .filter((v) => statuses.includes(v.status))
    .map((v) => v.version)
  const range = ref.range ?? '*'
  return semver.maxSatisfying(candidates, range) ?? undefined
}

export function resolveAsset(
  resolver: AssetResolver,
  ref: AssetRef | string,
  options: ResolveOptions = {},
): Asset | undefined {
  const parsed = typeof ref === 'string' ? parseAssetRef(ref) : ref
  const version = resolveVersion(resolver, parsed, options)
  return version === undefined ? undefined : resolver.get(parsed.namespace, parsed.name, version)
}

/** 解析并要求是指定 kind，kind 不符时返回 undefined */
export function resolveAssetOfKind<K extends AssetKind>(
  resolver: AssetResolver,
  ref: AssetRef | string,
  kind: K,
  options: ResolveOptions = {},
): AssetByKind[K] | undefined {
  const asset = resolveAsset(resolver, ref, options)
  return asset?.kind === kind ? (asset as AssetByKind[K]) : undefined
}

export interface StoredAssetEntry {
  asset: Asset
  status: AssetStatus
}

/** 内存实现，用于测试、命令行和静态检查 */
export class InMemoryAssetResolver implements AssetResolver {
  readonly #entries = new Map<string, Map<string, StoredAssetEntry>>()

  constructor(entries: readonly StoredAssetEntry[] = []) {
    for (const entry of entries) this.add(entry)
  }

  add(entry: StoredAssetEntry): void {
    const { namespace, name, version } = entry.asset.metadata
    const key = assetKey(namespace, name)
    const versions = this.#entries.get(key) ?? new Map<string, StoredAssetEntry>()
    versions.set(version, entry)
    this.#entries.set(key, versions)
  }

  listVersions(namespace: string, name: string): AssetVersionInfo[] {
    const versions = this.#entries.get(assetKey(namespace, name))
    if (versions === undefined) return []
    return [...versions.values()]
      .map((e) => ({ version: e.asset.metadata.version, status: e.status }))
      .sort((a, b) => semver.rcompare(a.version, b.version))
  }

  get(namespace: string, name: string, version: string): Asset | undefined {
    return this.#entries.get(assetKey(namespace, name))?.get(version)?.asset
  }

  all(): StoredAssetEntry[] {
    return [...this.#entries.values()].flatMap((versions) => [...versions.values()])
  }
}
