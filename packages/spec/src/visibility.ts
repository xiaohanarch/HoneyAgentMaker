import { layerOf, type Asset } from './assets'
import { isRuleId } from './common'
import { layerOfNamespace, tenantIdOfNamespace } from './layers'

/**
 * 查看者身份。P1 由请求头传入，P2 起从令牌里取。
 * platform 是平台管理员，vendor 是厂商研发，tenant 是某个租户的用户或开发者。
 */
export type Viewer =
  { type: 'platform' } | { type: 'vendor' } | { type: 'tenant'; tenantId: string }

export function parseViewer(value: string): Viewer {
  if (value === 'platform') return { type: 'platform' }
  if (value === 'vendor') return { type: 'vendor' }
  const match = /^tenant:([a-z0-9][a-z0-9-]*)$/.exec(value)
  if (match?.[1] !== undefined) return { type: 'tenant', tenantId: match[1] }
  throw new Error(`无法识别的查看者身份 "${value}"，应为 platform、vendor 或 tenant:<租户编号>`)
}

export function formatViewer(viewer: Viewer): string {
  return viewer.type === 'tenant' ? `tenant:${viewer.tenantId}` : viewer.type
}

/** 只有本层身份能写本层命名空间；租户只能写自己的命名空间（主提示词 5.2 节） */
export function canWriteNamespace(viewer: Viewer, namespace: string): boolean {
  try {
    const layer = layerOfNamespace(namespace)
    if (viewer.type === 'tenant') return tenantIdOfNamespace(namespace) === viewer.tenantId
    return layer === viewer.type
  } catch {
    // 不合法的命名空间一律不可写，调用方不需要先校验
    return false
  }
}

/**
 * 资产所在命名空间能引用哪些命名空间（防止通过引用越界读取，主提示词 5.2 节）：
 * 平台层只能引用平台层；标品层能引用平台层和标品层；租户层能引用平台层、标品层和本租户。
 */
export function canReferenceNamespace(fromNamespace: string, toNamespace: string): boolean {
  try {
    const from = layerOfNamespace(fromNamespace)
    const to = layerOfNamespace(toNamespace)
    if (to === 'platform') return true
    if (to === 'vendor') return from !== 'platform'
    return (
      from === 'tenant' && tenantIdOfNamespace(fromNamespace) === tenantIdOfNamespace(toNamespace)
    )
  } catch {
    return false
  }
}

/**
 * 能否看到这个资产（裁剪前）。租户看不到其他租户的资产，也看不到其他层的内部评测集；
 * 厂商看不到任何租户的资产。
 */
export function canReadAsset(viewer: Viewer, asset: Asset): boolean {
  const layer = layerOf(asset)
  if (viewer.type === 'platform') return true
  if (layer === 'tenant') {
    return (
      viewer.type === 'tenant' && tenantIdOfNamespace(asset.metadata.namespace) === viewer.tenantId
    )
  }
  if (viewer.type === 'vendor') return true
  if (asset.kind === 'EvalSuite' && asset.spec.visibility === 'internal') return false
  return true
}

/**
 * 被隐藏的字段统一替换成这个占位对象；digest 只用于判断“是否已变更”。
 * 服务端应传入带密钥的摘要函数（见 RedactOptions），否则短文本可能被字典比对猜出。
 */
export interface Redacted {
  $redacted: true
  label: string
  digest: string
}

export const VENDOR_INSTRUCTION_LABEL = '标品指令（不可见，运行时注入）'
export const PLATFORM_RULE_LABEL = '平台安全规则原文（不可见）'

export function isRedacted(value: unknown): value is Redacted {
  return typeof value === 'object' && value !== null && (value as Redacted).$redacted === true
}

export type DigestFn = (text: string) => string

export interface RedactOptions {
  /** 摘要函数，服务端传入带密钥的 HMAC；省略时用不带密钥的 FNV-1a，只适合测试 */
  digest?: DigestFn
}

export function redact(text: string, label: string, options: RedactOptions = {}): Redacted {
  return { $redacted: true, label, digest: (options.digest ?? digestOf)(text) }
}

/** FNV-1a 32 位，纯 JS 实现，前端也能用；只做变更检测，不是安全哈希，生产路径要用带密钥的摘要 */
export function digestOf(text: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

/** 裁剪后的视图：spec 里的部分字符串可能被替换成 Redacted */
export type AssetView = Omit<Asset, 'spec'> & { spec: Record<string, unknown> }

/**
 * 按查看者裁剪资产（主提示词 5.2 节）。只有租户查看标品层或平台层资产时需要裁剪：
 * - Agent 的 instructions、additionalContext、自由文本约束隐藏，规则编号保留；
 * - 平台规则只保留名称和说明。
 * 调用方应先用 canReadAsset 判断能否读取。
 */
export function redactAsset(asset: Asset, viewer: Viewer, options: RedactOptions = {}): AssetView {
  if (viewer.type !== 'tenant' || layerOf(asset) === 'tenant') return asset as AssetView
  const hide = (text: string, label: string): Redacted => redact(text, label, options)
  if (asset.kind === 'Agent') {
    const { instructions, additionalContext, constraints, ...rest } = asset.spec
    return {
      ...asset,
      spec: {
        ...rest,
        instructions: hide(instructions, VENDOR_INSTRUCTION_LABEL),
        ...(additionalContext === undefined
          ? {}
          : { additionalContext: hide(additionalContext, VENDOR_INSTRUCTION_LABEL) }),
        constraints: constraints.map((c) => (isRuleId(c) ? c : hide(c, VENDOR_INSTRUCTION_LABEL))),
      },
    }
  }
  if (asset.kind === 'PlatformRuleSet') {
    return {
      ...asset,
      spec: {
        rules: asset.spec.rules.map(({ instruction, ...rule }) => ({
          ...rule,
          instruction: hide(instruction, PLATFORM_RULE_LABEL),
        })),
      },
    }
  }
  return asset as AssetView
}
