import Mustache from 'mustache'
import { z } from 'zod'
import { evaluateExpression } from '@eab/assembler'
import { estimateTokens } from '../config'
import type { ChatMessage, ModelProvider, ModelRequest, ModelResponse, ToolCall } from '../types'

/**
 * 脚本化模型（主提示词 8.2 节）：按“Agent + 用户话术”匹配演示剧本，产出确定性的工具调用和回复，
 * 断网、没有 API Key 时演示也能完整跑通。剧本格式见 demo/scripts/README.md。
 *
 * 一个剧本由若干步组成，按“本轮已经完成的工具调用批次数”决定走到哪一步：
 * calls 步返回一批工具调用，reply 步用模板基于真实的工具结果渲染最终回复，
 * 这样回复里的单号和金额都来自 DemoERP 的数据，而不是写死在剧本里。
 */

/** 以 = 开头的字符串是 CEL 表达式，其余按 mustache 模板渲染（数字、布尔原样保留） */
const ValueSchema = z.unknown()

const CallSchema = z.strictObject({
  tool: z.string().min(1),
  args: z.record(z.string(), ValueSchema).default({}),
})

const StepSchema = z.union([
  z.strictObject({
    /** 思考步骤摘要，展示给用户 */
    thought: z.string().optional(),
    calls: z.array(CallSchema).min(1),
  }),
  z.strictObject({
    reply: z.string().min(1),
    /** 结构化结果，供钩子条件使用 */
    output: z.record(z.string(), ValueSchema).optional(),
  }),
])

export const ScriptSchema = z.strictObject({
  id: z.string().regex(/^[a-z0-9][a-z0-9-]*$/, '剧本编号用小写字母、数字和连字符'),
  description: z.string().optional(),
  /** namespace/name */
  agent: z.string().regex(/^[a-z0-9.-]+\/[a-z0-9-]+$/),
  match: z.strictObject({
    /** 全部出现才匹配 */
    all: z.array(z.string().min(1)).default([]),
    /** 任意一个出现即可；省略表示不要求 */
    any: z.array(z.string().min(1)).default([]),
    /** 正则，命名分组写入 vars，例如 (?<supplier>百润精密) */
    pattern: z.string().optional(),
    /** 只对某个用户生效 */
    user: z.string().optional(),
  }),
  /** 变量默认值，正则命名分组会覆盖 */
  vars: z.record(z.string(), z.string()).default({}),
  steps: z.array(StepSchema).min(1),
})
export type Script = z.infer<typeof ScriptSchema>

export const ScriptFileSchema = z.strictObject({ scripts: z.array(ScriptSchema).min(1) })

export interface ScriptMatch {
  script: Script
  vars: Record<string, string>
}

/** 本轮的用户话术：最后一条 user 消息 */
function lastUserIndex(messages: readonly ChatMessage[]): number {
  for (let i = messages.length - 1; i >= 0; i -= 1) if (messages[i]?.role === 'user') return i
  return -1
}

export function matchScript(
  scripts: readonly Script[],
  agent: string,
  userId: string,
  utterance: string,
): ScriptMatch | undefined {
  for (const script of scripts) {
    if (script.agent !== agent) continue
    const { all, any, pattern, user } = script.match
    if (user !== undefined && user !== userId) continue
    if (!all.every((k) => utterance.includes(k))) continue
    if (any.length > 0 && !any.some((k) => utterance.includes(k))) continue
    let groups: Record<string, string> = {}
    if (pattern !== undefined) {
      const found = new RegExp(pattern, 'u').exec(utterance)
      if (found === null) continue
      groups = Object.fromEntries(
        Object.entries(found.groups ?? {}).filter((e): e is [string, string] => e[1] !== undefined),
      )
    }
    return { script, vars: { ...script.vars, ...groups } }
  }
  return undefined
}

/** 工具名作为模板变量时把点和连字符换成下划线：supplier-lookup → tools.supplier_lookup */
export const templateKey = (toolName: string): string => toolName.replace(/[^A-Za-z0-9_]/g, '_')

function parseContent(content: string): unknown {
  try {
    return JSON.parse(content) as unknown
  } catch {
    return content
  }
}

/** 模板变量：vars、本轮的工具结果（同名工具取最后一次）、当前用户、今天 */
export function templateView(
  messages: readonly ChatMessage[],
  vars: Record<string, string>,
  userId: string,
  today: string,
): Record<string, unknown> {
  const turn = messages.slice(lastUserIndex(messages) + 1)
  const tools: Record<string, unknown> = {}
  for (const m of turn) if (m.role === 'tool') tools[templateKey(m.name)] = parseContent(m.content)
  return { vars, tools, user: { id: userId }, today }
}

const render = (template: string, view: Record<string, unknown>): string =>
  Mustache.render(template, view, {}, { escape: (value: unknown) => String(value) })

/** 剧本里的参数值和结构化结果：= 开头按 CEL 求值，字符串按模板渲染，其余原样 */
export function resolveValue(value: unknown, view: Record<string, unknown>): unknown {
  if (typeof value !== 'string') return value
  if (value.startsWith('=')) return evaluateExpression(value.slice(1), view)
  return render(value, view)
}

function resolveRecord(
  record: Record<string, unknown>,
  view: Record<string, unknown>,
): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(record)
      .map(([k, v]) => [k, resolveValue(v, view)] as const)
      // 渲染为空字符串的参数视为未提供，便于剧本写可选参数
      .filter(([, v]) => v !== '' && v !== undefined && v !== null),
  )
}

export interface ScriptedOptions {
  scripts: readonly Script[]
  /** 模板里的 today，测试固定日期 */
  today?: () => string
}

export class ScriptedProvider implements ModelProvider {
  readonly name = 'scripted'
  constructor(private readonly options: ScriptedOptions) {}

  async complete(request: ModelRequest & { model: string }): Promise<ModelResponse> {
    const { messages, context } = request
    const userIndex = lastUserIndex(messages)
    const utterance = userIndex < 0 ? '' : (messages[userIndex] as { content: string }).content
    const matched = matchScript(this.options.scripts, context.agent, context.userId, utterance)
    const usageOf = (output: string): ModelResponse['usage'] => ({
      inputTokens:
        estimateTokens(request.system) +
        messages.reduce((n, m) => n + estimateTokens(m.content), 0),
      outputTokens: estimateTokens(output),
      estimated: true,
    })

    if (matched === undefined) {
      const text = fallbackReply(context.agentDisplayName, context.examples)
      return {
        text,
        toolCalls: [],
        usage: usageOf(text),
        provider: this.name,
        model: request.model,
        stopReason: 'end',
        scriptId: 'fallback',
      }
    }

    const batches = messages
      .slice(userIndex + 1)
      .filter((m) => m.role === 'assistant' && (m.toolCalls?.length ?? 0) > 0).length
    const today = this.options.today?.() ?? new Date().toISOString().slice(0, 10)
    const view = templateView(messages, matched.vars, context.userId, today)
    const step = matched.script.steps[batches] ?? matched.script.steps.at(-1)
    const base = { provider: this.name, model: request.model, scriptId: matched.script.id }

    if (step !== undefined && 'calls' in step) {
      const toolCalls: ToolCall[] = step.calls.map((call, i) => ({
        id: `call_${batches + 1}_${i + 1}`,
        name: call.tool,
        arguments: resolveRecord(call.args, view),
      }))
      const text = step.thought === undefined ? '' : render(step.thought, view)
      return {
        ...base,
        text,
        toolCalls,
        usage: usageOf(JSON.stringify(toolCalls)),
        stopReason: 'tool_use',
      }
    }
    if (step !== undefined && 'reply' in step) {
      const text = render(step.reply, view).trim()
      return {
        ...base,
        text,
        toolCalls: [],
        ...(step.output === undefined ? {} : { output: resolveRecord(step.output, view) }),
        usage: usageOf(text),
        stopReason: 'end',
      }
    }
    const text = fallbackReply(context.agentDisplayName, context.examples)
    return { ...base, text, toolCalls: [], usage: usageOf(text), stopReason: 'end' }
  }
}

/** 剧本未覆盖时的规则回复，界面上标注“脚本化模型” */
export function fallbackReply(agentName: string, examples: readonly string[]): string {
  const list =
    examples.length === 0
      ? ''
      : `\n\n可以试试这些问法：\n${examples.map((e) => `- ${e}`).join('\n')}`
  return `（脚本化模型）这句话不在演示剧本里，${agentName}暂时无法给出分析。配置真实模型后可以回答任意问法。${list}`
}
