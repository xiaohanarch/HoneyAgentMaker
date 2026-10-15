import { z } from 'zod'
import {
  AssetRefSchema,
  ModelProfileSchema,
  RoutingSchema,
  WriteLevelSchema,
  defineKind,
} from '../common'
import {
  ApprovalRuleSchema,
  ExecutionStepsSchema,
  ExtensionPointSchema,
  HooksSchema,
  OutputSchema,
  ToolEntrySchema,
} from './agent-parts'

export const TRIGGER_TYPES = ['assistant', 'event', 'schedule', 'agent'] as const

export const TriggerSchema = z
  .strictObject({
    type: z.enum(TRIGGER_TYPES),
    /** event 触发时的业务事件名，例如 delivery.overdue */
    event: z.string().optional(),
    /** schedule 触发时的 cron 表达式 */
    cron: z.string().optional(),
  })
  .superRefine((trigger, ctx) => {
    if (trigger.type === 'event' && trigger.event === undefined) {
      ctx.addIssue({ code: 'custom', path: ['event'], message: '事件触发器必须写 event' })
    }
    if (trigger.type === 'schedule' && trigger.cron === undefined) {
      ctx.addIssue({ code: 'custom', path: ['cron'], message: '定时触发器必须写 cron' })
    }
  })

export const CHANNELS = ['assistant-web', 'im', 'api'] as const

export const ModelConfigSchema = z.strictObject({
  profile: ModelProfileSchema,
  temperature: z.number().min(0).max(2).optional(),
})

/** Agent 的 spec，字段名与主提示词 5.1 节对齐 */
export const AgentSpecSchema = z.strictObject({
  expertise: z.string().min(1),
  instructions: z.string().min(1),
  /** 公开版指令，“复制为租户 Agent”时带上（ADR 0004） */
  publicInstructions: z.string().min(1).optional(),
  additionalContext: z.string().optional(),
  /** 引用语义包规则编号（如 PUR-R-007），也可以写自由文本约束 */
  constraints: z.array(z.string().min(1)).default([]),
  model: ModelConfigSchema,
  executionSteps: ExecutionStepsSchema.optional(),
  tools: z.array(ToolEntrySchema).default([]),
  semanticPacks: z.array(AssetRefSchema).default([]),
  triggers: z.array(TriggerSchema).default([{ type: 'assistant' }]),
  channels: z.array(z.enum(CHANNELS)).default(['assistant-web']),
  memory: z.strictObject({ longTerm: z.boolean().default(false) }).optional(),
  writePolicy: z.strictObject({ maxLevel: WriteLevelSchema }),
  approvals: z.array(ApprovalRuleSchema).default([]),
  hooks: HooksSchema.optional(),
  output: OutputSchema.optional(),
  routing: RoutingSchema,
  /** 仅标品 Agent 声明 */
  extensionPoints: z.array(ExtensionPointSchema).default([]),
  evals: z.strictObject({ suites: z.array(AssetRefSchema).default([]) }).optional(),
})

export const AgentSchema = defineKind('Agent', AgentSpecSchema)
export type AgentAsset = z.infer<typeof AgentSchema>
