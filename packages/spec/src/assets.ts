import type { z } from 'zod'
import { layerOfNamespace, type AssetLayer } from './layers'
import { AgentSchema, type AgentAsset } from './kinds/agent'
import { AgentExtensionSchema, type AgentExtensionAsset } from './kinds/agent-extension'
import {
  AssistantSchema,
  ConnectionSchema,
  KnowledgeSourceSchema,
  PlatformRuleSetSchema,
  RemoteAgentSchema,
  type AssistantAsset,
  type ConnectionAsset,
  type KnowledgeSourceAsset,
  type PlatformRuleSetAsset,
  type RemoteAgentAsset,
} from './kinds/catalog'
import {
  DevRequestSchema,
  EvalSuiteSchema,
  SolutionSchema,
  type DevRequestAsset,
  type EvalSuiteAsset,
  type SolutionAsset,
} from './kinds/lifecycle'
import { SemanticPackSchema, type SemanticPackAsset } from './kinds/semantic-pack'
import { SkillSchema, WorkflowSchema, type SkillAsset, type WorkflowAsset } from './kinds/steps'
import { ToolSourceSchema, type ToolSourceAsset } from './kinds/tool-source'

/** 全部资产种类与对应的 schema（主提示词 5.1 节，外加 PlatformRuleSet） */
export const KIND_SCHEMAS = {
  Assistant: AssistantSchema,
  Agent: AgentSchema,
  Skill: SkillSchema,
  ToolSource: ToolSourceSchema,
  SemanticPack: SemanticPackSchema,
  KnowledgeSource: KnowledgeSourceSchema,
  AgentExtension: AgentExtensionSchema,
  RemoteAgent: RemoteAgentSchema,
  Workflow: WorkflowSchema,
  EvalSuite: EvalSuiteSchema,
  DevRequest: DevRequestSchema,
  Solution: SolutionSchema,
  Connection: ConnectionSchema,
  PlatformRuleSet: PlatformRuleSetSchema,
} as const satisfies Record<string, z.ZodType>

export type AssetKind = keyof typeof KIND_SCHEMAS
export const ASSET_KINDS = Object.keys(KIND_SCHEMAS) as AssetKind[]

export function isAssetKind(value: unknown): value is AssetKind {
  return typeof value === 'string' && Object.hasOwn(KIND_SCHEMAS, value)
}

export interface AssetByKind {
  Assistant: AssistantAsset
  Agent: AgentAsset
  Skill: SkillAsset
  ToolSource: ToolSourceAsset
  SemanticPack: SemanticPackAsset
  KnowledgeSource: KnowledgeSourceAsset
  AgentExtension: AgentExtensionAsset
  RemoteAgent: RemoteAgentAsset
  Workflow: WorkflowAsset
  EvalSuite: EvalSuiteAsset
  DevRequest: DevRequestAsset
  Solution: SolutionAsset
  Connection: ConnectionAsset
  PlatformRuleSet: PlatformRuleSetAsset
}

export type Asset = AssetByKind[AssetKind]

export function isKind<K extends AssetKind>(asset: Asset, kind: K): asset is AssetByKind[K] {
  return asset.kind === kind
}

/**
 * 只能出现在特定层的 kind。扩展、开发需求、连接只属于租户；平台规则只属于平台层。
 * 其余 kind 三层都可以有。
 */
export const KIND_LAYERS: Readonly<Partial<Record<AssetKind, readonly AssetLayer[]>>> = {
  AgentExtension: ['tenant'],
  DevRequest: ['tenant'],
  Connection: ['tenant'],
  PlatformRuleSet: ['platform'],
}

export function layerOf(asset: Asset): AssetLayer {
  return layerOfNamespace(asset.metadata.namespace)
}

export function assetLabel(asset: Asset): string {
  return `${asset.kind} ${asset.metadata.namespace}/${asset.metadata.name}@${asset.metadata.version}`
}
