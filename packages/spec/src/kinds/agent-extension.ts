import { z } from 'zod'
import { AssetRefSchema, ModelProfileSchema, defineKind } from '../common'
import { isAssetRef, parseAssetRef } from '../refs'
import {
  ApprovalRuleSchema,
  HOOK_EVENTS,
  HooksSchema,
  ToolEntryOrRefSchema,
  type ExtensionPoint,
  type ExtensionPointType,
  type HookEvent,
} from './agent-parts'

/** 扩展的目标必须带版本范围，标品升级时按范围解析（主提示词 5.4 节第 6 条） */
// zod 4 在前一个 refine 失败后仍会执行后面的 refine，这里不能假设引用一定合法
const TargetRefSchema = AssetRefSchema.refine(
  (ref) => !isAssetRef(ref) || parseAssetRef(ref).range !== undefined,
  '扩展的 target 必须带版本范围，例如 vendor.fin/transaction-anomaly-analyst@^1.0',
)

/** 租户对标品 Agent 的扩展（主提示词 5.3 节） */
export const AgentExtensionSpecSchema = z.strictObject({
  target: TargetRefSchema,
  instructions: z.strictObject({ append: z.string().min(1) }).optional(),
  constraints: z.strictObject({ append: z.array(z.string().min(1)).min(1) }).optional(),
  tools: z.strictObject({ add: z.array(ToolEntryOrRefSchema).min(1) }).optional(),
  skills: z.strictObject({ add: z.array(AssetRefSchema).min(1) }).optional(),
  /** 语义包或知识源 */
  knowledge: z.strictObject({ add: z.array(AssetRefSchema).min(1) }).optional(),
  hooks: HooksSchema.optional(),
  approvals: z.strictObject({ add: z.array(ApprovalRuleSchema).min(1) }).optional(),
  model: z.strictObject({ profile: ModelProfileSchema }).optional(),
  evals: z.strictObject({ suites: z.array(AssetRefSchema).default([]) }).optional(),
})

export const AgentExtensionSchema = defineKind('AgentExtension', AgentExtensionSpecSchema)
export type AgentExtensionAsset = z.infer<typeof AgentExtensionSchema>
type ExtensionSpec = AgentExtensionAsset['spec']

/** 扩展实际用到的扩展点类型，组装器与静态检查共用 */
export function usedExtensionPoints(spec: ExtensionSpec): ExtensionPointType[] {
  const used: ExtensionPointType[] = []
  if (spec.instructions !== undefined || spec.constraints !== undefined) {
    used.push('instructions.append')
  }
  if (spec.tools !== undefined) used.push('tools.add')
  if (spec.skills !== undefined) used.push('skills.add')
  if (spec.knowledge !== undefined) used.push('knowledge.add')
  if (spec.hooks !== undefined && usedHookEvents(spec).length > 0) used.push('hooks')
  if (spec.approvals !== undefined) used.push('approvals.add')
  if (spec.model !== undefined) used.push('model.select')
  return used
}

export function usedHookEvents(spec: ExtensionSpec): HookEvent[] {
  const hooks = spec.hooks
  if (hooks === undefined) return []
  return HOOK_EVENTS.filter((event) => (hooks[event]?.length ?? 0) > 0)
}

export interface ExtensionPointViolation {
  message: string
  path: string
}

/**
 * 扩展是否只使用了目标 Agent 声明过的扩展点（主提示词 5.3 节），
 * 包括钩子事件和模型档位的范围。静态检查与组装器共用。
 */
export function extensionPointViolations(
  spec: ExtensionSpec,
  declared: readonly ExtensionPoint[],
): ExtensionPointViolation[] {
  const violations: ExtensionPointViolation[] = usedExtensionPoints(spec)
    .filter((type) => !declared.some((p) => p.type === type))
    .map((type) => ({ message: `目标 Agent 没有声明扩展点 ${type}`, path: 'spec' }))

  const hookPoint = declared.find((p) => p.type === 'hooks')
  if (hookPoint?.events !== undefined) {
    for (const event of usedHookEvents(spec)) {
      if (!hookPoint.events.includes(event)) {
        violations.push({
          message: `目标 Agent 的 hooks 扩展点不允许挂在 ${event} 事件上`,
          path: `spec.hooks.${event}`,
        })
      }
    }
  }

  const modelPoint = declared.find((p) => p.type === 'model.select')
  const profile = spec.model?.profile
  if (profile !== undefined && modelPoint?.allowedProfiles?.includes(profile) === false) {
    violations.push({
      message: `目标 Agent 只允许选择模型档位 ${modelPoint.allowedProfiles.join('、')}，不能选 ${profile}`,
      path: 'spec.model.profile',
    })
  }
  return violations
}
