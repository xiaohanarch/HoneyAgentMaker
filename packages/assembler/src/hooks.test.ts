import { describe, expect, it, vi } from 'vitest'
import { runHooks, type HookRunner } from './hooks'
import type { EffectiveHook } from './types'

const hook = (overrides: Partial<EffectiveHook>): EffectiveHook => ({
  event: 'onResult',
  layer: 'vendor',
  source: 'vendor.fin/transaction-anomaly-analyst@1.2.0',
  skill: 'vendor.fin.skills/x',
  failurePolicy: 'propagate',
  ...overrides,
})

const VENDOR_HOOK = hook({ source: 'vendor' })
const TENANT_HOOK = hook({ layer: 'tenant', source: 'tenant-a', failurePolicy: 'isolate' })
const TENANT_HOOK_2 = hook({ layer: 'tenant', source: 'tenant-b', failurePolicy: 'isolate' })

function runnerThatFailsOn(failing: ReadonlySet<string>, order: string[]): HookRunner {
  return {
    shouldRun: () => true,
    run: async (h) => {
      order.push(h.source)
      if (failing.has(h.source)) throw new Error(`${h.source} 失败`)
    },
  }
}

describe('runHooks', () => {
  it('按顺序执行，只执行指定事件的钩子', async () => {
    const order: string[] = []
    const other = hook({ event: 'onFailure', source: 'failure-hook' })
    const report = await runHooks(
      [VENDOR_HOOK, other, TENANT_HOOK],
      'onResult',
      runnerThatFailsOn(new Set(), order),
    )
    expect(order).toEqual(['vendor', 'tenant-a'])
    expect(report.executed).toHaveLength(2)
  })

  it('租户钩子失败被隔离，后续钩子继续执行，失败记录在报告里', async () => {
    const order: string[] = []
    const report = await runHooks(
      [VENDOR_HOOK, TENANT_HOOK, TENANT_HOOK_2],
      'onResult',
      runnerThatFailsOn(new Set(['tenant-a']), order),
    )
    expect(order).toEqual(['vendor', 'tenant-a', 'tenant-b'])
    expect(report.isolatedFailures).toEqual([{ hook: TENANT_HOOK, error: 'tenant-a 失败' }])
    expect(report.executed.map((h) => h.source)).toEqual(['vendor', 'tenant-b'])
  })

  it('标品钩子失败向上抛出，中止主流程', async () => {
    await expect(
      runHooks([VENDOR_HOOK, TENANT_HOOK], 'onResult', runnerThatFailsOn(new Set(['vendor']), [])),
    ).rejects.toThrow('vendor 失败')
  })

  it('条件不满足的钩子跳过，没有条件的钩子不询问 shouldRun', async () => {
    const shouldRun = vi.fn(() => false)
    const run = vi.fn(async () => {})
    const conditional = hook({ source: 'conditional', when: "result.severity == 'high'" })
    const report = await runHooks([VENDOR_HOOK, conditional], 'onResult', { shouldRun, run })
    expect(shouldRun).toHaveBeenCalledTimes(1)
    expect(report.skipped).toEqual([conditional])
    expect(report.executed).toEqual([VENDOR_HOOK])
  })
})
