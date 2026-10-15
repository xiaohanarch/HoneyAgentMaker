import semver from 'semver'
import type { Asset, AssetResolver, AssetVersionInfo, Viewer } from '@eab/spec'
import type { AssetRecord } from './repository'

export interface ScopedResolverSource {
  records(namespace: string, name: string): AssetRecord[]
  canRead(record: AssetRecord): boolean
  /** 完整性检查通过时返回资产，否则返回 undefined（并由来源写审计） */
  trusted(record: AssetRecord): Asset | undefined
}

/**
 * 按查看者限定范围的解析器。组装器经由它读资产时：
 * - 看不到该身份无权读取的资产（其他租户的资产、对租户而言其他层的草稿），表现为“不存在”；
 * - 完整性检查失败的资产被拒绝加载，并记录在 integrityFailures 里，由调用方决定让组装失败，
 *   而不是静默地少一条平台规则或一个扩展。
 */
export class ScopedResolver implements AssetResolver {
  readonly #failures = new Set<string>()

  constructor(
    readonly viewer: Viewer,
    private readonly source: ScopedResolverSource,
  ) {}

  listVersions(namespace: string, name: string): AssetVersionInfo[] {
    return this.source
      .records(namespace, name)
      .filter((r) => this.source.canRead(r))
      .map((r) => ({ version: r.version, status: r.status }))
      .sort((a, b) => semver.rcompare(a.version, b.version))
  }

  get(namespace: string, name: string, version: string): Asset | undefined {
    const record = this.source
      .records(namespace, name)
      .find((r) => r.version === version && this.source.canRead(r))
    if (record === undefined) return undefined
    const asset = this.source.trusted(record)
    if (asset === undefined) this.#failures.add(`${namespace}/${name}@${version}`)
    return asset
  }

  integrityFailures(): string[] {
    return [...this.#failures]
  }
}
