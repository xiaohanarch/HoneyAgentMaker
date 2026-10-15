/**
 * 三层资产的命名空间约定（主提示词 5.2 节）：
 * - platform               平台层
 * - vendor.<domain>[...]   厂商标品层
 * - tenant.<tenantId>[...] 租户层
 */
export const ASSET_LAYERS = ['platform', 'vendor', 'tenant'] as const
export type AssetLayer = (typeof ASSET_LAYERS)[number]

const SEGMENT = /^[a-z0-9][a-z0-9-]*$/

export class InvalidNamespaceError extends Error {
  constructor(namespace: string, reason: string) {
    super(`命名空间 "${namespace}" 不合法：${reason}`)
    this.name = 'InvalidNamespaceError'
  }
}

function splitNamespace(namespace: string): string[] {
  const segments = namespace.split('.')
  const bad = segments.find((s) => !SEGMENT.test(s))
  if (bad !== undefined) {
    throw new InvalidNamespaceError(namespace, `片段 "${bad}" 只能包含小写字母、数字和连字符`)
  }
  return segments
}

export function layerOfNamespace(namespace: string): AssetLayer {
  const [root, ...rest] = splitNamespace(namespace)
  if (root === 'platform') {
    if (rest.length > 0) throw new InvalidNamespaceError(namespace, '平台层命名空间只能是 platform')
    return 'platform'
  }
  if (root === 'vendor') {
    if (rest.length === 0)
      throw new InvalidNamespaceError(namespace, '厂商层需要至少一级子命名空间，例如 vendor.pur')
    return 'vendor'
  }
  if (root === 'tenant') {
    if (rest.length === 0)
      throw new InvalidNamespaceError(namespace, '租户层需要带租户编号，例如 tenant.t001')
    return 'tenant'
  }
  throw new InvalidNamespaceError(namespace, '必须以 platform、vendor 或 tenant 开头')
}

/** 租户层命名空间返回租户编号，其他层返回 undefined。 */
export function tenantIdOfNamespace(namespace: string): string | undefined {
  return layerOfNamespace(namespace) === 'tenant' ? splitNamespace(namespace)[1] : undefined
}
