/**
 * @eab/assembler：运行时组装器，把平台安全规则、标品定义、租户扩展组装成有效定义。
 * 规则见 docs/brief/02-主提示词.md 5.4 节，修改前先读，改完必须补测试。
 */
export { assemble, type AssembleInput } from './assemble'
export {
  approvalMatchesTool,
  documentsToolName,
  matchingApprovals,
  strictestPolicy,
} from './capabilities'
export {
  checkExpression,
  hookEnvironment,
  approvalEnvironment,
  type CheckOutcome,
} from './conditions'
export {
  mergeHooks,
  runHooks,
  type HookFailure,
  type HookRunReport,
  type HookRunner,
} from './hooks'
export { versionedRef } from './segments'
export * from './types'
export {
  renderInstructions,
  viewEffectiveDefinition,
  type EffectiveDefinitionView,
  type SegmentView,
} from './view'
