import type { WriteLevel } from './common'

export interface BuiltinTool {
  name: string
  description: string
  writeLevel: WriteLevel
}

/** 平台内置工具，Agent 用 `builtin: <name>` 挂载 */
export const BUILTIN_TOOLS: readonly BuiltinTool[] = [
  {
    name: 'calculator',
    description: '精确计算四则运算与百分比，避免模型心算出错',
    writeLevel: 'L0',
  },
]

/**
 * 语义查询工具（主提示词 5.5 节），经 MCP 网关提供。
 * Agent 只要挂了语义包，组装时自动获得这组工具，不需要在 tools 里声明。
 */
export const SEMANTIC_TOOLS: readonly BuiltinTool[] = [
  {
    name: 'semantic.describe_object',
    description: '查询业务对象的定义、字段含义与关系',
    writeLevel: 'L0',
  },
  { name: 'semantic.get_metric', description: '查询指标的口径定义', writeLevel: 'L0' },
  { name: 'semantic.find_rules', description: '查询与业务对象相关的业务规则', writeLevel: 'L0' },
  {
    name: 'semantic.search',
    description: '在语义包里按关键词检索对象、指标、规则',
    writeLevel: 'L0',
  },
]

export function findBuiltinTool(name: string): BuiltinTool | undefined {
  return BUILTIN_TOOLS.find((t) => t.name === name)
}
