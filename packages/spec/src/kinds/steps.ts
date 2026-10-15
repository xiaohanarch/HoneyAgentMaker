import { z } from 'zod'
import {
  AssetRefSchema,
  ExpressionSchema,
  NameSchema,
  RoutingSchema,
  WriteLevelSchema,
  defineKind,
} from '../common'

/** 工作流引擎支持的步骤类型（主提示词 8.7 节） */
export const STEP_TYPES = [
  'tool',
  'skill',
  'agent',
  'transform',
  'condition',
  'parallel',
  'confirm',
  'notify',
  'waitEvent',
] as const
export type StepType = (typeof STEP_TYPES)[number]

/** 技能只允许确定性步骤，不调用 Agent，也不并行或等待事件（主提示词 5.1 节 Skill） */
export const SKILL_STEP_TYPES: readonly StepType[] = [
  'tool',
  'skill',
  'transform',
  'condition',
  'confirm',
  'notify',
]

/** 每种步骤类型必须提供的字段 */
const REQUIRED_FIELDS: Readonly<Record<StepType, readonly string[]>> = {
  tool: ['tool'],
  skill: ['skill'],
  agent: ['agent'],
  transform: ['expression'],
  condition: ['if', 'then'],
  parallel: ['branches'],
  confirm: ['message'],
  notify: ['message'],
  waitEvent: ['event'],
}

export interface Step {
  id: string
  type: StepType
  name?: string | undefined
  tool?: string | undefined
  source?: string | undefined
  skill?: string | undefined
  agent?: string | undefined
  with?: Record<string, unknown> | undefined
  expression?: string | undefined
  if?: string | undefined
  then?: Step[] | undefined
  else?: Step[] | undefined
  branches?: Step[][] | undefined
  message?: string | undefined
  channel?: string | undefined
  event?: string | undefined
  timeoutSeconds?: number | undefined
  output?: string | undefined
}

export const StepSchema: z.ZodType<Step> = z
  .strictObject({
    id: NameSchema,
    type: z.enum(STEP_TYPES),
    /** 界面上显示的步骤名 */
    name: z.string().optional(),
    /** tool：工具名 */
    tool: z.string().min(1).optional(),
    /** tool：工具所在的工具来源，例如 vendor.erp.tools/demo-erp-bo */
    source: AssetRefSchema.optional(),
    skill: AssetRefSchema.optional(),
    agent: AssetRefSchema.optional(),
    /** 入参映射，值可以是常量或 CEL 表达式字符串 */
    with: z.record(z.string(), z.unknown()).optional(),
    /** transform：CEL 表达式 */
    expression: ExpressionSchema.optional(),
    /** condition：CEL 条件 */
    if: ExpressionSchema.optional(),
    get then() {
      return z.array(StepSchema).min(1).optional()
    },
    get else() {
      return z.array(StepSchema).optional()
    },
    get branches() {
      return z.array(z.array(StepSchema).min(1)).min(2).optional()
    },
    /** confirm、notify：展示给人的内容 */
    message: z.string().optional(),
    /** notify：通知渠道 */
    channel: z.string().optional(),
    /** waitEvent：等待的业务事件名 */
    event: z.string().optional(),
    timeoutSeconds: z.number().int().positive().optional(),
    /** 步骤结果保存到哪个变量，后续步骤用 steps.<id> 或该变量引用 */
    output: z.string().optional(),
  })
  .superRefine((step, ctx) => {
    for (const field of REQUIRED_FIELDS[step.type]) {
      if ((step as Record<string, unknown>)[field] === undefined) {
        ctx.addIssue({ code: 'custom', path: [field], message: `${step.type} 步骤必须写 ${field}` })
      }
    }
  })

/** 步骤 id 在同一流程内必须唯一（含嵌套分支） */
export function duplicateStepIds(steps: readonly Step[]): string[] {
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  const visit = (list: readonly Step[]): void => {
    for (const step of list) {
      if (seen.has(step.id)) duplicates.add(step.id)
      seen.add(step.id)
      visit(step.then ?? [])
      visit(step.else ?? [])
      for (const branch of step.branches ?? []) visit(branch)
    }
  }
  visit(steps)
  return [...duplicates]
}

function flatten(steps: readonly Step[]): Step[] {
  return steps.flatMap((s) => [
    s,
    ...flatten(s.then ?? []),
    ...flatten(s.else ?? []),
    ...(s.branches ?? []).flatMap((b) => flatten(b)),
  ])
}

const StepListSchema = (allowed: readonly StepType[]) =>
  z
    .array(StepSchema)
    .min(1)
    .superRefine((steps, ctx) => {
      for (const id of duplicateStepIds(steps)) {
        ctx.addIssue({ code: 'custom', message: `步骤 id "${id}" 重复` })
      }
      for (const step of flatten(steps)) {
        if (!allowed.includes(step.type)) {
          ctx.addIssue({
            code: 'custom',
            message: `步骤 "${step.id}" 的类型 ${step.type} 不能用在这里，允许的类型：${allowed.join('、')}`,
          })
        }
      }
    })

/** JSON Schema 对象，原样保存，由运行时校验入参出参 */
const JsonSchemaObject = z.record(z.string(), z.unknown())

export const SkillSchema = defineKind(
  'Skill',
  z.strictObject({
    inputSchema: JsonSchemaObject,
    outputSchema: JsonSchemaObject,
    steps: StepListSchema(SKILL_STEP_TYPES),
    /** 只允许被 Agent 调用，不出现在统一入口的路由候选里 */
    agentOnly: z.boolean().default(false),
    writeLevel: WriteLevelSchema,
    routing: RoutingSchema.optional(),
  }),
)
export type SkillAsset = z.infer<typeof SkillSchema>

export const WorkflowSchema = defineKind(
  'Workflow',
  z.strictObject({
    inputSchema: JsonSchemaObject.optional(),
    steps: StepListSchema(STEP_TYPES),
  }),
)
export type WorkflowAsset = z.infer<typeof WorkflowSchema>
