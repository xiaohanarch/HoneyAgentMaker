/**
 * @eab/policy：鉴权点 1 至 3 的策略引擎（主提示词 7.3、7.4 节）。
 * 规则是 JSON，条件用 CEL；引擎是纯函数，输出结构化的 Decision，由调用方写入追踪和审计。
 */
import defaultRules from '../rules/default.json' with { type: 'json' }
import { loadRuleSet } from './engine'
import type { PolicyRuleSet } from './types'

export { evaluate, loadRuleSet, PolicyRuleError } from './engine'
export {
  buildEntryInput,
  buildHandoffInput,
  buildToolInput,
  type CallMode,
  type EntryContext,
  type HandoffContext,
  type SubjectFacts,
  type ToolContext,
} from './inputs'
export * from './types'

/** 平台内置的默认规则集，启动时校验，不合格直接报错 */
export const DEFAULT_RULES: PolicyRuleSet = loadRuleSet(defaultRules)
