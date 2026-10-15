import semver from 'semver'
import { z } from 'zod'
import { layerOfNamespace } from './layers'
import { isAssetRef, isPinnedAssetRef } from './refs'

export const API_VERSION = 'eab.dev/v1'

export const NameSchema = z
  .string()
  .regex(/^[a-z0-9][a-z0-9-]*$/, '只能包含小写字母、数字和连字符，且不能以连字符开头')

export const NamespaceSchema = z.string().superRefine((value, ctx) => {
  try {
    layerOfNamespace(value)
  } catch (error: unknown) {
    ctx.addIssue({
      code: 'custom',
      message: error instanceof Error ? error.message : String(error),
    })
  }
})

export const SemverSchema = z
  .string()
  .refine((v) => semver.valid(v) !== null, '必须是语义化版本号，例如 1.2.0')

/** 资产引用：namespace/name，可带版本范围，例如 vendor.fin/transaction-anomaly-analyst@^1.0 */
export const AssetRefSchema = z
  .string()
  .refine(isAssetRef, '资产引用格式应为 命名空间/名称[@版本范围]，例如 vendor.pur/procurement@^1.0')

/** 方案清单里的引用必须锁定到具体版本，例如 tenant.t001.finance/anomaly-analyst-ext@0.3.0 */
export const PinnedAssetRefSchema = z
  .string()
  .refine(isPinnedAssetRef, '方案里的资产引用必须带具体版本号，例如 vendor.pur/procurement@1.0.0')

/** 写操作级别（主提示词 7.4 节） */
export const WriteLevelSchema = z.enum(['L0', 'L1', 'L2', 'L3'])
export type WriteLevel = z.infer<typeof WriteLevelSchema>

/** 模型档位（主提示词 8.2 节），Agent 只引用档位，不写模型名 */
export const ModelProfileSchema = z.enum(['fast', 'reasoning', 'judge'])
export type ModelProfile = z.infer<typeof ModelProfileSchema>

/** 连接别名（主提示词第 3 节“连接”），例如 DEMO_ERP */
export const ConnectionAliasSchema = z
  .string()
  .regex(/^[A-Z][A-Z0-9_]*$/, '连接别名只能包含大写字母、数字和下划线，例如 DEMO_ERP')

/** 语义包规则编号，例如 PUR-R-007、PLT-R-001 */
export const RULE_ID_PATTERN = /^[A-Z][A-Z0-9]*-R-\d{3}$/
export const RuleIdSchema = z
  .string()
  .regex(RULE_ID_PATTERN, '规则编号格式应为 域-R-三位数字，例如 PUR-R-007')

export function isRuleId(value: string): boolean {
  return RULE_ID_PATTERN.test(value)
}

/** CEL 表达式，语法与类型检查在组装和静态检查阶段完成（ADR 0003） */
export const ExpressionSchema = z.string().min(1, '表达式不能为空')

export const MetadataSchema = z.strictObject({
  name: NameSchema,
  namespace: NamespaceSchema,
  version: SemverSchema,
  displayName: z.string().min(1).optional(),
  description: z.string().optional(),
  owner: z.string().optional(),
  /** 业务流程归属，取值来自流程目录，例如 source-to-pay */
  businessProcess: z.array(z.string()).optional(),
  labels: z.record(z.string(), z.string()).optional(),
})
export type Metadata = z.infer<typeof MetadataSchema>

/** 按 kind 包一层公共信封：apiVersion、kind、metadata、spec */
export function defineKind<K extends string, S extends z.ZodType>(kind: K, spec: S) {
  return z.strictObject({
    apiVersion: z.literal(API_VERSION, { error: `apiVersion 必须是 ${API_VERSION}` }),
    kind: z.literal(kind),
    metadata: MetadataSchema,
    spec,
  })
}

export const RoutingSchema = z.strictObject({
  /** 路由描述，进入能力目录的场景 */
  description: z.string().min(1),
  examples: z.array(z.string().min(1)).default([]),
  /** 能力目录里标记为“可直达”时，统一入口可以跳过岗位助手直接路由过来 */
  direct: z.boolean().default(false),
})
export type Routing = z.infer<typeof RoutingSchema>

/** 精确一个键的对象：用于“每个条目只能写一种来源”这类结构，报错比 union 清楚 */
export function exactlyOneKey<T extends Record<string, unknown>>(
  value: T,
  keys: readonly (keyof T)[],
): boolean {
  return keys.filter((k) => value[k] !== undefined).length === 1
}
