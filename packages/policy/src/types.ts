import { z } from 'zod'

/** 平台负责的三个鉴权点（主提示词 7.3 节）；第四处在 DemoERP 的 BO API */
export const CHECKPOINTS = ['entry', 'handoff', 'tool'] as const
export type Checkpoint = (typeof CHECKPOINTS)[number]

export const CHECKPOINT_LABELS: Readonly<Record<Checkpoint | 'bo-api', string>> = {
  entry: '鉴权点 1 · 入口',
  handoff: '鉴权点 2 · Agent 交接',
  tool: '鉴权点 3 · 工具与 MCP 网关',
  'bo-api': '鉴权点 4 · BO API',
}

/**
 * deny 拒绝；approve 需要有权限的人审批；confirm 需要发起人确认。
 * 判定优先级 deny > approve > confirm > allow。
 */
export const RULE_EFFECTS = ['deny', 'approve', 'confirm'] as const
export type RuleEffect = (typeof RULE_EFFECTS)[number]

export const PolicyRuleSchema = z.strictObject({
  id: z.string().regex(/^[A-Z]+-\d{3}$/, '规则编号格式应为 前缀-三位数字，例如 TOOL-005'),
  checkpoint: z.enum(CHECKPOINTS),
  description: z.string().min(1),
  effect: z.enum(RULE_EFFECTS),
  /** CEL 条件，变量为 input；条件为真时规则命中 */
  when: z.string().min(1),
  /** 命中时给出的原因，展示给用户和写入审计 */
  reason: z.string().min(1),
})
export type PolicyRule = z.infer<typeof PolicyRuleSchema>

export const PolicyRuleSetSchema = z.strictObject({
  version: z.string(),
  rules: z.array(PolicyRuleSchema).min(1),
})
export type PolicyRuleSet = z.infer<typeof PolicyRuleSetSchema>

export type DecisionOutcome = 'allow' | 'deny' | 'confirm' | 'approve'

export interface Requirement {
  ruleId: string
  effect: Exclude<RuleEffect, 'deny'>
  reason: string
}

/** 每一次裁决的结构化记录，写入追踪和审计 */
export interface Decision {
  checkpoint: Checkpoint
  outcome: DecisionOutcome
  /** 决定结果的规则；allow 时为 null */
  ruleId: string | null
  reason: string
  /** 需要确认或审批时列出全部命中的规则 */
  requirements: Requirement[]
  /** 本次判定的规则集版本 */
  rulesVersion: string
  at: string
  /** 判定依据的事实（不含敏感内容），便于在追踪里回放 */
  facts: Record<string, unknown>
}
