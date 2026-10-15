import {
  HOOK_EVENTS,
  layerOf,
  type AgentAsset,
  type AgentExtensionAsset,
  type Asset,
  type HookEvent,
  type Hooks,
} from '@eab/spec'
import { checkExpression, hookEnvironment } from './conditions'
import { versionedRef } from './segments'
import type { AssemblyIssue, EffectiveHook } from './types'

function hooksOf(owner: Asset, hooks: Hooks | undefined, event: HookEvent): EffectiveHook[] {
  const layer = layerOf(owner)
  return (hooks?.[event] ?? []).map((binding) => ({
    event,
    layer,
    source: versionedRef(owner),
    ...(binding.workflow === undefined ? {} : { workflow: binding.workflow }),
    ...(binding.skill === undefined ? {} : { skill: binding.skill }),
    ...(binding.when === undefined ? {} : { when: binding.when }),
    failurePolicy: layer === 'tenant' ? 'isolate' : 'propagate',
  }))
}

/**
 * 钩子按“标品钩子 → 租户钩子”顺序排列（主提示词 5.4 节第 4 条）。
 * 条件按事件做类型检查：标品钩子条件无效是错误；租户钩子条件无效时该钩子停用并给出警告，
 * 标品升级改了输出字段导致租户钩子失效时，就是这种情况（故事 F）。
 */
export function mergeHooks(
  agent: AgentAsset,
  extensions: readonly AgentExtensionAsset[],
): { hooks: EffectiveHook[]; issues: AssemblyIssue[] } {
  const all = HOOK_EVENTS.flatMap((event) => [
    ...hooksOf(agent, agent.spec.hooks, event),
    ...extensions.flatMap((ext) => hooksOf(ext, ext.spec.hooks, event)),
  ])

  const issues: AssemblyIssue[] = []
  const hooks = all.filter((hook) => {
    if (hook.when === undefined) return true
    const check = checkExpression(hookEnvironment(hook.event, agent.spec.output), hook.when)
    if (check.valid) return true
    const isTenant = hook.failurePolicy === 'isolate'
    issues.push({
      code: 'HOOK_CONDITION_INVALID',
      severity: isTenant ? 'warning' : 'error',
      message: `${hook.event} 钩子的条件 "${hook.when}" 无效：${check.reason}${isTenant ? '。该租户钩子已停用' : ''}`,
      source: hook.source,
      path: `spec.hooks.${hook.event}`,
    })
    return false
  })
  return { hooks, issues }
}

export interface HookFailure {
  hook: EffectiveHook
  error: string
}

export interface HookRunReport {
  executed: EffectiveHook[]
  skipped: EffectiveHook[]
  /** 已隔离的租户钩子失败，主流程继续，结果里要提示 */
  isolatedFailures: HookFailure[]
}

export interface HookRunner {
  /** 判断钩子条件是否满足；没有条件的钩子不会调用它 */
  shouldRun(hook: EffectiveHook): boolean | Promise<boolean>
  run(hook: EffectiveHook): Promise<void>
}

/**
 * 按顺序执行某个事件的钩子：租户钩子失败只记录，标品钩子失败向上抛出。
 * 运行时（P3）用它执行钩子，具体怎么跑工作流或技能由 runner 决定。
 */
export async function runHooks(
  hooks: readonly EffectiveHook[],
  event: HookEvent,
  runner: HookRunner,
): Promise<HookRunReport> {
  const report: HookRunReport = { executed: [], skipped: [], isolatedFailures: [] }
  for (const hook of hooks.filter((h) => h.event === event)) {
    try {
      if (hook.when !== undefined && !(await runner.shouldRun(hook))) {
        report.skipped.push(hook)
        continue
      }
      await runner.run(hook)
      report.executed.push(hook)
    } catch (error: unknown) {
      if (hook.failurePolicy === 'propagate') throw error
      report.isolatedFailures.push({
        hook,
        error: error instanceof Error ? error.message : String(error),
      })
    }
  }
  return report
}
