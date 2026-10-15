/**
 * 开发生命周期相关的 kind：评测集、开发需求、方案。
 */
import { z } from 'zod'
import { AssetRefSchema, ExpressionSchema, PinnedAssetRefSchema, defineKind } from '../common'

export const ASSERTION_TYPES = [
  'calledTool',
  'notCalledTool',
  'contains',
  'notContains',
  'numberRange',
  'invariant',
  'judge',
] as const

const REQUIRED_ASSERTION_FIELDS: Readonly<Record<(typeof ASSERTION_TYPES)[number], string[]>> = {
  calledTool: ['tool'],
  notCalledTool: ['tool'],
  contains: ['text'],
  notContains: ['text'],
  numberRange: ['path'],
  invariant: ['expression'],
  judge: ['rubric'],
}

const AssertionSchema = z
  .strictObject({
    type: z.enum(ASSERTION_TYPES),
    tool: z.string().optional(),
    text: z.string().optional(),
    /** numberRange：结果里的字段路径 */
    path: z.string().optional(),
    min: z.number().optional(),
    max: z.number().optional(),
    /** invariant：CEL 不变量，例如“列出的订单都属于华东” */
    expression: ExpressionSchema.optional(),
    /** judge：模型裁判的评分标准 */
    rubric: z.string().optional(),
    description: z.string().optional(),
  })
  .superRefine((assertion, ctx) => {
    for (const field of REQUIRED_ASSERTION_FIELDS[assertion.type]) {
      if ((assertion as Record<string, unknown>)[field] === undefined) {
        ctx.addIssue({
          code: 'custom',
          path: [field],
          message: `${assertion.type} 断言必须写 ${field}`,
        })
      }
    }
  })

const EvalCaseSchema = z.strictObject({
  id: z.string().min(1),
  /** 以哪个演示用户的身份运行 */
  user: z.string().min(1),
  question: z.string().min(1),
  /** 来自语义包 verified_tasks 时记录来源编号 */
  verifiedTask: z.string().optional(),
  assertions: z.array(AssertionSchema).min(1),
})

/** 评测集（主提示词 10.8 节） */
export const EvalSuiteSchema = defineKind(
  'EvalSuite',
  z.strictObject({
    target: AssetRefSchema,
    /** internal 的评测集对其他层不可见（主提示词 5.2 节） */
    visibility: z.enum(['public', 'internal']).default('internal'),
    /** smoke 冒烟集全部用例须 3 分钟内完成 */
    tier: z.enum(['smoke', 'full']).default('full'),
    /** 按通过率判定，大模型输出不完全稳定 */
    minPassRate: z.number().min(0).max(1).default(1),
    cases: z.array(EvalCaseSchema).min(1),
  }),
)
export type EvalSuiteAsset = z.infer<typeof EvalSuiteSchema>

/** 业务用户提交的开发需求（主提示词 9.3 节、故事 E） */
export const DevRequestSchema = defineKind(
  'DevRequest',
  z.strictObject({
    requester: z.string().min(1),
    originalText: z.string().min(1),
    supplements: z.array(z.string()).default([]),
    /** 提交时自动附加的业务上下文 */
    context: z
      .strictObject({
        assistant: AssetRefSchema.optional(),
        semanticObjects: z.array(z.string()).default([]),
        rules: z.array(z.string()).default([]),
      })
      .default({ semanticObjects: [], rules: [] }),
    status: z.enum(['submitted', 'in-progress', 'delivered', 'rejected']).default('submitted'),
  }),
)
export type DevRequestAsset = z.infer<typeof DevRequestSchema>

/** 方案：编译、签名、发布、晋级、回滚的单位（主提示词第 12 节） */
export const SolutionSchema = defineKind(
  'Solution',
  z.strictObject({
    assets: z.array(PinnedAssetRefSchema).min(1),
  }),
)
export type SolutionAsset = z.infer<typeof SolutionSchema>
