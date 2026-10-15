import { layerOfNamespace } from './layers'
import { Building2, Globe, Lock, ShieldCheck, type LucideIcon } from 'lucide-react'

/**
 * 界面上区分的资产来源。前三类对应命名空间的三层，
 * external 指运行在平台之外、经 A2A 或第三方 MCP 接入的能力。
 */
export const ASSET_SOURCES = ['vendor', 'tenant', 'external', 'platform'] as const
export type AssetSource = (typeof ASSET_SOURCES)[number]

export interface SourceStyle {
  label: string
  description: string
  icon: LucideIcon
  /** 实色文字与描边，用于图标、强调文字 */
  textClass: string
  /** 柔底标签 */
  badgeClass: string
  /** 卡片左侧色条 */
  accentClass: string
}

export const SOURCE_STYLES: Readonly<Record<AssetSource, SourceStyle>> = {
  vendor: {
    label: '标品',
    description: '厂商随产品交付，租户只读，指令原文不可见',
    icon: Lock,
    textClass: 'text-vendor',
    badgeClass: 'bg-vendor-soft text-vendor ring-vendor/25',
    accentClass: 'border-l-vendor',
  },
  tenant: {
    label: '租户',
    description: '本租户开发者创建或扩展，其他租户不可见',
    icon: Building2,
    textClass: 'text-tenant',
    badgeClass: 'bg-tenant-soft text-tenant ring-tenant/25',
    accentClass: 'border-l-tenant',
  },
  external: {
    label: '外部',
    description: '运行在平台之外，通过 A2A 或 MCP 接入',
    icon: Globe,
    textClass: 'text-external',
    badgeClass: 'bg-external-soft text-external ring-external/25',
    accentClass: 'border-l-external',
  },
  platform: {
    label: '平台',
    description: '平台安全规则与内置能力，不可覆盖',
    icon: ShieldCheck,
    textClass: 'text-platform',
    badgeClass: 'bg-platform-soft text-platform ring-platform/25',
    accentClass: 'border-l-platform',
  },
}

/** 外部来源的资产种类：不管登记在哪个命名空间，能力本身都在平台之外。 */
const EXTERNAL_KINDS: ReadonlySet<string> = new Set(['RemoteAgent'])

export interface SourceSubject {
  namespace: string
  kind?: string
}

export function resolveAssetSource({ namespace, kind }: SourceSubject): AssetSource {
  if (kind !== undefined && EXTERNAL_KINDS.has(kind)) return 'external'
  return layerOfNamespace(namespace)
}
