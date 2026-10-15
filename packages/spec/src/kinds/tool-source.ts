import { z } from 'zod'
import { ConnectionAliasSchema, WriteLevelSchema, defineKind } from '../common'

export const TOOL_SOURCE_TYPES = ['bo-api', 'mcp', 'http'] as const

/** 工具类别，用于写级别上限检查：付款、过账类最高只能到 L2（主提示词 7.4 节） */
export const TOOL_CATEGORIES = ['query', 'draft', 'write', 'payment', 'posting', 'notify'] as const

export const ToolDefinitionSchema = z.strictObject({
  name: z
    .string()
    .regex(/^[a-z][a-z0-9_.]*$/, '工具名用小写字母、数字、下划线和点，例如 supplier_list'),
  description: z.string().min(1),
  /** 写操作级别。schema 不强制，由静态检查要求必须声明，结果显示在发布面板上 */
  writeLevel: WriteLevelSchema.optional(),
  category: z.enum(TOOL_CATEGORIES).optional(),
  /** bo-api、http 工具对应的接口；地址由连接别名在部署时绑定 */
  operation: z
    .strictObject({
      method: z.enum(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']),
      path: z.string().startsWith('/', '接口路径必须以 / 开头，且不能包含主机地址'),
    })
    .optional(),
  inputSchema: z.record(z.string(), z.unknown()).optional(),
  outputSchema: z.record(z.string(), z.unknown()).optional(),
})
export type ToolDefinition = z.infer<typeof ToolDefinitionSchema>

/** 工具来源（主提示词 5.1 节 ToolSource），资产里只写连接别名 */
export const ToolSourceSchema = defineKind(
  'ToolSource',
  z.strictObject({
    type: z.enum(TOOL_SOURCE_TYPES),
    connection: ConnectionAliasSchema,
    /** bo-api：OpenAPI 文档在连接上的路径，P3 起据此自动生成工具定义 */
    openapiPath: z.string().startsWith('/').optional(),
    tools: z.array(ToolDefinitionSchema).default([]),
  }),
)
export type ToolSourceAsset = z.infer<typeof ToolSourceSchema>
