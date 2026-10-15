import { Environment } from '@marcbachmann/cel-js'
import {
  PolicyRuleSetSchema,
  type Checkpoint,
  type Decision,
  type PolicyRule,
  type PolicyRuleSet,
  type Requirement,
} from './types'

const ENVIRONMENT = new Environment().registerVariable('input', 'map')

export class PolicyRuleError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PolicyRuleError'
  }
}

/** 加载规则集：schema 校验 + 每条条件都必须能通过 CEL 语法检查，不合格的规则集拒绝加载 */
export function loadRuleSet(raw: unknown): PolicyRuleSet {
  const parsed = PolicyRuleSetSchema.safeParse(raw)
  if (!parsed.success) {
    throw new PolicyRuleError(`策略规则集不合法：${parsed.error.issues.map((i) => i.message).join('；')}`)
  }
  const ids = new Set<string>()
  for (const rule of parsed.data.rules) {
    if (ids.has(rule.id)) throw new PolicyRuleError(`规则编号 ${rule.id} 重复`)
    ids.add(rule.id)
    const check = ENVIRONMENT.check(rule.when)
    if (!check.valid) {
      throw new PolicyRuleError(`规则 ${rule.id} 的条件无效：${check.error?.message.split('\n')[0] ?? ''}`)
    }
  }
  return parsed.data
}

/** 规则原因里的 {{a.b}} 用事实填充，便于给出具体说明 */
function renderReason(template: string, input: Record<string, unknown>): string {
  return template.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (_, path: string) => {
    const value = path.split('.').reduce<unknown>(
      (acc, key) => (acc !== null && typeof acc === 'object' ? (acc as Record<string, unknown>)[key] : undefined),
      input,
    )
    return value === undefined ? '' : String(value)
  })
}

function matches(rule: PolicyRule, input: Record<string, unknown>): boolean {
  const value: unknown = ENVIRONMENT.evaluate(rule.when, { input })
  if (typeof value !== 'boolean') throw new PolicyRuleError(`规则 ${rule.id} 的条件结果不是布尔值`)
  return value
}

/**
 * 对一个鉴权点做判定（纯函数）。
 * - 依次判断该鉴权点的规则；命中 deny 立即拒绝；
 * - 命中 approve、confirm 的累积起来，结果取最严的一类；
 * - 条件求值出错一律拒绝（失败即拒绝），不会因为规则写错而放行；
 * - 没有命中任何规则时允许。
 */
export function evaluate(
  ruleSet: PolicyRuleSet,
  checkpoint: Checkpoint,
  input: Record<string, unknown>,
  now: Date = new Date(),
): Decision {
  const base = { checkpoint, rulesVersion: ruleSet.version, at: now.toISOString(), facts: input }
  const requirements: Requirement[] = []
  for (const rule of ruleSet.rules.filter((r) => r.checkpoint === checkpoint)) {
    let hit: boolean
    try {
      hit = matches(rule, input)
    } catch (error: unknown) {
      const detail = error instanceof Error ? error.message.split('\n')[0] : String(error)
      return {
        ...base,
        outcome: 'deny',
        ruleId: rule.id,
        reason: `策略规则 ${rule.id} 求值失败，按失败即拒绝处理：${detail ?? ''}`,
        requirements: [],
      }
    }
    if (!hit) continue
    const reason = renderReason(rule.reason, input)
    if (rule.effect === 'deny') return { ...base, outcome: 'deny', ruleId: rule.id, reason, requirements: [] }
    requirements.push({ ruleId: rule.id, effect: rule.effect, reason })
  }
  const approval = requirements.find((r) => r.effect === 'approve')
  const first = approval ?? requirements[0]
  if (first === undefined) {
    return { ...base, outcome: 'allow', ruleId: null, reason: '没有命中限制规则', requirements }
  }
  return {
    ...base,
    outcome: approval === undefined ? 'confirm' : 'approve',
    ruleId: first.ruleId,
    reason: requirements.map((r) => r.reason).join('；'),
    requirements,
  }
}
