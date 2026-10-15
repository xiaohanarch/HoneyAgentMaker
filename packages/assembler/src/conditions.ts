import { Environment } from '@marcbachmann/cel-js'
import type { AgentOutput, HookEvent } from '@eab/spec'

/**
 * 钩子与确认点条件使用 CEL（ADR 0003）。这里按事件准备类型环境：
 * onResult 的 result 按标品 Agent 声明的 output.schema 注册字段类型，
 * 引用不存在的字段会在组装阶段被发现，而不是运行时静默变成 false。
 */

/** int 也按 double 处理，运行时不必把数字转成 BigInt，比较运算照常可用 */
const CEL_TYPES = {
  string: 'string',
  int: 'double',
  double: 'double',
  bool: 'bool',
  list: 'list',
  map: 'map',
} as const

function resultSchema(output: AgentOutput | undefined): Record<string, string> | undefined {
  if (output === undefined) return undefined
  return Object.fromEntries(
    Object.entries(output.schema).map(([field, def]) => [field, CEL_TYPES[def.type]]),
  )
}

export function hookEnvironment(event: HookEvent, output: AgentOutput | undefined): Environment {
  const env = new Environment()
  if (event === 'onResult') {
    const schema = resultSchema(output)
    return schema === undefined
      ? env.registerVariable('result', 'map')
      : env.registerVariable({ name: 'result', schema })
  }
  if (event === 'onFailure') return env.registerVariable('error', 'map')
  return env
    .registerVariable('tool', 'string')
    .registerVariable('input', 'map')
    .registerVariable('output', 'map')
}

/** 确认点条件可以引用工具入参 input 和调用者 user */
export function approvalEnvironment(): Environment {
  return new Environment().registerVariable('input', 'map').registerVariable('user', 'map')
}

export type CheckOutcome = { valid: true } | { valid: false; reason: string }

export function checkExpression(env: Environment, expression: string): CheckOutcome {
  try {
    const result = env.check(expression)
    if (result.valid) {
      return result.type === 'bool' || result.type === 'dyn'
        ? { valid: true }
        : { valid: false, reason: `条件的结果类型是 ${String(result.type)}，应为 bool` }
    }
    return { valid: false, reason: firstLine(result.error?.message ?? '无法通过类型检查') }
  } catch (error: unknown) {
    return {
      valid: false,
      reason: firstLine(error instanceof Error ? error.message : String(error)),
    }
  }
}

function firstLine(text: string): string {
  return text.split('\n')[0]?.trim() ?? text
}
