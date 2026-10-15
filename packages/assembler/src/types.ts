import type {
  AgentAsset,
  ApprovalPolicy,
  AssetLayer,
  HookEvent,
  ModelProfile,
  WriteLevel,
} from '@eab/spec'

/** 指令片段的来源类别，决定拼接顺序（主提示词 5.4 节第 1 条） */
export type SegmentKind =
  | 'platform-rule'
  | 'vendor-instructions'
  | 'vendor-context'
  | 'vendor-constraint'
  | 'semantic-instruction'
  | 'tenant-instructions'
  | 'tenant-constraint'

export interface Segment {
  /** 稳定编号，便于在追踪里回放和比较 */
  id: string
  kind: SegmentKind
  layer: AssetLayer
  /** 来源资产，namespace/name@version */
  source: string
  /** 来源字段，例如 spec.instructions、spec.rules[0] */
  field: string
  text: string
  /** 规则编号或平台规则名，用于界面标注 */
  label?: string
  /** 对租户保密：标品指令原文、平台规则原文 */
  confidential: boolean
}

export type EffectiveToolType =
  'skill' | 'agent' | 'remoteAgent' | 'mcp' | 'api' | 'documents' | 'builtin' | 'semantic'

export interface EffectiveTool {
  /** 暴露给模型的工具名，在一个 Agent 内唯一 */
  name: string
  type: EffectiveToolType
  layer: AssetLayer
  /** 工具所在的资产，namespace/name@version；内置工具为 platform/builtin */
  source: string
  description: string
  writeLevel?: WriteLevel
  /** 工具类别（query、write、payment、posting 等），付款与过账类有写级别上限 */
  category?: string
}

export interface EffectiveApproval {
  tool: string
  policy: ApprovalPolicy
  when?: string
  approverRole?: string
  layer: AssetLayer
  source: string
}

export interface EffectiveHook {
  event: HookEvent
  layer: AssetLayer
  source: string
  workflow?: string
  skill?: string
  when?: string
  /** 标品钩子失败中止主流程；租户钩子失败只记录并提示（主提示词 5.4 节第 4 条） */
  failurePolicy: 'propagate' | 'isolate'
}

export interface EffectiveModel {
  profile: ModelProfile
  temperature?: number
  /** 档位由谁决定 */
  layer: AssetLayer
}

export interface Provenance {
  /** 组装出的 Agent 身份（主提示词 7.1 节） */
  identity: string
  tenantId?: string
  /** 实际生效的标品版本 */
  agent: string
  /** 扩展的 target 范围及其解析结果 */
  resolvedTarget?: { ranges: string[]; version: string }
  extensions: string[]
  platformRules: string[]
  semanticPacks: string[]
}

/** 有效定义：本次运行实际使用的 Agent 定义（主提示词 5.4 节第 7 条） */
export interface EffectiveDefinition {
  name: string
  displayName: string
  expertise: string
  segments: Segment[]
  tools: EffectiveTool[]
  approvals: EffectiveApproval[]
  hooks: EffectiveHook[]
  model: EffectiveModel
  semanticPacks: string[]
  writePolicy: AgentAsset['spec']['writePolicy']
  executionSteps: NonNullable<AgentAsset['spec']['executionSteps']>
  output?: AgentAsset['spec']['output']
  routing: AgentAsset['spec']['routing']
  provenance: Provenance
}

export type AssemblyIssueCode =
  | 'AGENT_NOT_FOUND'
  | 'TARGET_UNRESOLVED'
  | 'EXTENSION_POINT_UNDECLARED'
  | 'TOOL_NAME_CONFLICT'
  | 'APPROVAL_RELAXED'
  | 'MODEL_NOT_ALLOWED'
  | 'REFERENCE_UNRESOLVED'
  | 'HOOK_CONDITION_INVALID'
  | 'APPROVAL_CONDITION_INVALID'
  | 'TOOL_WRITE_LEVEL_EXCEEDED'

export interface AssemblyIssue {
  code: AssemblyIssueCode
  /** error 阻止组装；warning 不阻止，但发布门禁会拦下 */
  severity: 'error' | 'warning'
  message: string
  /** 出问题的资产 */
  source?: string
  path?: string
}

export type AssembleResult =
  | { ok: true; definition: EffectiveDefinition; issues: AssemblyIssue[] }
  | { ok: false; issues: AssemblyIssue[] }
