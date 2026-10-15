import { z } from 'zod'
import { AssetRefSchema, ExpressionSchema, ModelProfileSchema, exactlyOneKey } from '../common'

// ── 工具条目 ─────────────────────────────────────────────────────────────

const ToolSubsetSchema = z.strictObject({
  source: AssetRefSchema,
  /** 只挂载来源里的部分工具；省略表示全部 */
  tools: z.array(z.string().min(1)).min(1).optional(),
})

export const TOOL_ENTRY_TYPES = [
  'skill',
  'agent',
  'remoteAgent',
  'mcp',
  'api',
  'documents',
  'builtin',
] as const
export type ToolEntryType = (typeof TOOL_ENTRY_TYPES)[number]

/**
 * Agent 的一个工具条目，只能写一种来源（主提示词 5.1 节 spec.tools）。
 * api 对应 bo-api 与 http 类型的工具来源。
 */
export const ToolEntrySchema = z
  .strictObject({
    skill: AssetRefSchema.optional(),
    agent: AssetRefSchema.optional(),
    remoteAgent: AssetRefSchema.optional(),
    mcp: ToolSubsetSchema.optional(),
    api: ToolSubsetSchema.optional(),
    documents: AssetRefSchema.optional(),
    builtin: z.string().min(1).optional(),
  })
  .refine((entry) => exactlyOneKey(entry, TOOL_ENTRY_TYPES), {
    message: `每个工具条目只能写一种来源：${TOOL_ENTRY_TYPES.join('、')} 之一`,
  })
export type ToolEntry = z.infer<typeof ToolEntrySchema>

/** 扩展里的 tools.add 允许直接写资产引用，组装时按被引用资产的 kind 归一化 */
export const ToolEntryOrRefSchema = z.union([AssetRefSchema, ToolEntrySchema])
export type ToolEntryOrRef = z.infer<typeof ToolEntryOrRefSchema>

export type NormalizedToolEntry =
  | { type: 'skill' | 'agent' | 'remoteAgent' | 'documents'; ref: string }
  | { type: 'mcp' | 'api'; ref: string; tools?: string[] }
  | { type: 'builtin'; name: string }

export function normalizeToolEntry(entry: ToolEntry): NormalizedToolEntry {
  if (entry.skill !== undefined) return { type: 'skill', ref: entry.skill }
  if (entry.agent !== undefined) return { type: 'agent', ref: entry.agent }
  if (entry.remoteAgent !== undefined) return { type: 'remoteAgent', ref: entry.remoteAgent }
  if (entry.documents !== undefined) return { type: 'documents', ref: entry.documents }
  if (entry.mcp !== undefined) return withTools('mcp', entry.mcp)
  if (entry.api !== undefined) return withTools('api', entry.api)
  if (entry.builtin !== undefined) return { type: 'builtin', name: entry.builtin }
  throw new Error('工具条目没有任何来源')
}

function withTools(
  type: 'mcp' | 'api',
  subset: z.infer<typeof ToolSubsetSchema>,
): NormalizedToolEntry {
  return subset.tools === undefined
    ? { type, ref: subset.source }
    : { type, ref: subset.source, tools: subset.tools }
}

// ── 前置与后置步骤 ───────────────────────────────────────────────────────

export const ExecutionStepSchema = z
  .strictObject({
    skill: AssetRefSchema.optional(),
    /** 平台守卫，例如 platform/amount-threshold-check */
    guard: z.string().min(1).optional(),
  })
  .refine((step) => exactlyOneKey(step, ['skill', 'guard']), {
    message: '每个执行步骤只能写 skill 或 guard 之一',
  })

export const ExecutionStepsSchema = z.strictObject({
  pre: z.array(ExecutionStepSchema).default([]),
  post: z.array(ExecutionStepSchema).default([]),
})

// ── 钩子 ─────────────────────────────────────────────────────────────────

export const HOOK_EVENTS = ['beforeToolCall', 'afterToolCall', 'onResult', 'onFailure'] as const
export type HookEvent = (typeof HOOK_EVENTS)[number]

export const HookBindingSchema = z
  .strictObject({
    workflow: AssetRefSchema.optional(),
    skill: AssetRefSchema.optional(),
    /** CEL 条件，例如 result.severity in ['high','critical'] */
    when: ExpressionSchema.optional(),
  })
  .refine((hook) => exactlyOneKey(hook, ['workflow', 'skill']), {
    message: '钩子只能挂 workflow 或 skill 之一',
  })
export type HookBinding = z.infer<typeof HookBindingSchema>

/** 每个事件可以写单个钩子，也可以写数组，解析后统一成数组 */
const HookListSchema = z
  .union([HookBindingSchema, z.array(HookBindingSchema)])
  .transform((value) => (Array.isArray(value) ? value : [value]))

export const HooksSchema = z.strictObject({
  beforeToolCall: HookListSchema.optional(),
  afterToolCall: HookListSchema.optional(),
  onResult: HookListSchema.optional(),
  onFailure: HookListSchema.optional(),
})
export type Hooks = z.infer<typeof HooksSchema>

// ── 人工确认 ─────────────────────────────────────────────────────────────

/** 确认策略由宽到严排列，组装时取最严者（主提示词 5.4 节第 3 条） */
export const APPROVAL_POLICIES = ['auto', 'require-confirmation', 'require-approval'] as const
export type ApprovalPolicy = (typeof APPROVAL_POLICIES)[number]

export function approvalStrictness(policy: ApprovalPolicy): number {
  return APPROVAL_POLICIES.indexOf(policy)
}

export const ApprovalRuleSchema = z.strictObject({
  /** 工具名，或工具来源、技能的资产引用（匹配该来源下的全部工具） */
  tool: z.string().min(1),
  policy: z.enum(APPROVAL_POLICIES),
  /** CEL 条件，例如 input.amount > 100000 */
  when: ExpressionSchema.optional(),
  /** require-approval 时由哪个角色审批 */
  approverRole: z.string().optional(),
  description: z.string().optional(),
})
export type ApprovalRule = z.infer<typeof ApprovalRuleSchema>

// ── 扩展点 ───────────────────────────────────────────────────────────────

export const EXTENSION_POINT_TYPES = [
  'instructions.append',
  'tools.add',
  'skills.add',
  'knowledge.add',
  'hooks',
  'approvals.add',
  'model.select',
] as const
export type ExtensionPointType = (typeof EXTENSION_POINT_TYPES)[number]

export const ExtensionPointSchema = z
  .strictObject({
    type: z.enum(EXTENSION_POINT_TYPES),
    description: z.string().min(1),
    /** model.select：租户可选的模型档位 */
    allowedProfiles: z.array(ModelProfileSchema).min(1).optional(),
    /** hooks：允许挂载的事件，省略表示全部 */
    events: z.array(z.enum(HOOK_EVENTS)).min(1).optional(),
  })
  .superRefine((point, ctx) => {
    if (point.type === 'model.select' && point.allowedProfiles === undefined) {
      ctx.addIssue({
        code: 'custom',
        path: ['allowedProfiles'],
        message: 'model.select 扩展点必须声明 allowedProfiles',
      })
    }
  })
export type ExtensionPoint = z.infer<typeof ExtensionPointSchema>

// ── 输出结构 ─────────────────────────────────────────────────────────────

export const OUTPUT_FIELD_TYPES = ['string', 'int', 'double', 'bool', 'list', 'map'] as const

export const OutputFieldSchema = z.strictObject({
  type: z.enum(OUTPUT_FIELD_TYPES),
  description: z.string().optional(),
  enum: z.array(z.string()).optional(),
})

/**
 * Agent 结构化结果的字段声明。钩子条件里的 result.xxx 按这里做类型检查，
 * 标品升级改字段名时，租户钩子会在组装阶段被发现（ADR 0003）。
 */
export const OutputSchema = z.strictObject({
  schema: z.record(z.string().regex(/^[a-zA-Z_][a-zA-Z0-9_]*$/), OutputFieldSchema),
})
export type AgentOutput = z.infer<typeof OutputSchema>
