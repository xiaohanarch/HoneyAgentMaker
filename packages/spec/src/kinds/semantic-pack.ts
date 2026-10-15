import { z } from 'zod'
import { AssetRefSchema, RuleIdSchema, defineKind } from '../common'

/**
 * 元数据引用，指向元数据服务里的对象，例如 erp.pur.Supplier@v1。
 * 投影段只写这个引用，运行时实时从元数据服务取定义（主提示词 5.5 节）。
 */
const MetaRefSchema = z
  .string()
  .regex(
    /^erp\.[a-z]+\.[A-Za-z][A-Za-z0-9]*(@v\d+)?$/,
    '元数据引用格式应为 erp.<域>.<对象>[@v版本]，例如 erp.pur.Supplier@v1',
  )

const FieldSchema = z.strictObject({
  /** 投影段：字段名，取自元数据 */
  ref: z.string().min(1),
  /** 增量段：元数据里没有的业务说明 */
  description: z.string().optional(),
  enumMeaning: z.record(z.string(), z.string()).optional(),
})

const ObjectSchema = z.strictObject({
  ref: MetaRefSchema,
  description: z.string().optional(),
  fields: z.array(FieldSchema).optional(),
})

const RelationshipSchema = z.strictObject({
  from: z.string().min(1),
  to: z.string().min(1),
  verb: z.string().min(1),
  cardinality: z.enum(['one-to-one', 'one-to-many', 'many-to-one', 'many-to-many']),
  notes: z.string().optional(),
})

const MetricSchema = z.strictObject({
  name: z.string().regex(/^[a-z][a-z0-9_]*$/, '指标名用小写下划线风格，例如 overdue_days'),
  displayName: z.string().min(1),
  definition: z.string().min(1),
  unit: z.string().optional(),
})

const RuleSchema = z.strictObject({
  id: RuleIdSchema,
  /** 投影段：元数据服务里的规则引用 */
  ref: z.string().optional(),
  severity: z.enum(['block', 'warn', 'info']),
  description: z.string().optional(),
  remedy: z.string().min(1),
})
export type SemanticRule = z.infer<typeof RuleSchema>

const VerifiedTaskSchema = z.strictObject({
  id: z.string().regex(/^[A-Z][A-Z0-9]*-VT-\d{3}$/, '已验证任务编号格式应为 域-VT-三位数字'),
  question: z.string().min(1),
  expected_conclusion: z.string().min(1),
  fail_if: z.string().optional(),
  /** 期望调用的工具，评测时作为 calledTool 断言 */
  tools: z.array(z.string()).optional(),
})

const PermissionsSchema = z.strictObject({
  filter_dimensions: z.array(z.string()).default([]),
  inherit_from: z.string().optional(),
  disclose_filtered: z.boolean().default(true),
})

/** 语义包（主提示词 5.5 节），原生段的键名沿用白皮书的下划线风格 */
export const SemanticPackSchema = defineKind(
  'SemanticPack',
  z.strictObject({
    /** 租户层语义包可以扩展标品语义包，经验回流写到这里（主提示词第 11 节） */
    extends: AssetRefSchema.optional(),
    objects: z.array(ObjectSchema).default([]),
    relationships: z.array(RelationshipSchema).default([]),
    metrics: z.array(MetricSchema).default([]),
    rules: z.array(RuleSchema).default([]),
    agent_instructions: z.array(z.string().min(1)).default([]),
    verified_tasks: z.array(VerifiedTaskSchema).default([]),
    permissions: PermissionsSchema.optional(),
  }),
)
export type SemanticPackAsset = z.infer<typeof SemanticPackSchema>
