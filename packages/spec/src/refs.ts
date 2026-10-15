import semver from 'semver'
import { layerOfNamespace } from './layers'

/**
 * 资产引用：`命名空间/名称[@版本范围]`。
 * 版本范围用 semver 语法，省略时表示“当前生效的最新版本”。
 */
export interface AssetRef {
  namespace: string
  name: string
  range?: string
}

const REF_PATTERN = /^([a-z0-9][a-z0-9.-]*)\/([a-z0-9][a-z0-9-]*)(?:@(.+))?$/

export class InvalidAssetRefError extends Error {
  constructor(ref: string, reason: string) {
    super(`资产引用 "${ref}" 不合法：${reason}`)
    this.name = 'InvalidAssetRefError'
  }
}

export function parseAssetRef(ref: string): AssetRef {
  const match = REF_PATTERN.exec(ref)
  if (match === null) throw new InvalidAssetRefError(ref, '格式应为 命名空间/名称[@版本范围]')
  const [, namespace = '', name = '', range] = match
  try {
    layerOfNamespace(namespace)
  } catch (error: unknown) {
    throw new InvalidAssetRefError(ref, error instanceof Error ? error.message : String(error))
  }
  if (range !== undefined && semver.validRange(range) === null) {
    throw new InvalidAssetRefError(ref, `版本范围 "${range}" 不是合法的 semver 范围`)
  }
  return range === undefined ? { namespace, name } : { namespace, name, range }
}

export function isAssetRef(ref: string): boolean {
  try {
    parseAssetRef(ref)
    return true
  } catch {
    return false
  }
}

/** 锁定到具体版本的引用，例如 vendor.pur/procurement@1.0.0 */
export function isPinnedAssetRef(ref: string): boolean {
  try {
    const parsed = parseAssetRef(ref)
    return parsed.range !== undefined && semver.valid(parsed.range) !== null
  } catch {
    return false
  }
}

export function formatAssetRef(ref: AssetRef): string {
  return ref.range === undefined
    ? `${ref.namespace}/${ref.name}`
    : `${ref.namespace}/${ref.name}@${ref.range}`
}

/** 不带版本的资产标识，用作注册中心的键 */
export function assetKey(namespace: string, name: string): string {
  return `${namespace}/${name}`
}
