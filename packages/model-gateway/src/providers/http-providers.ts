import {
  ModelError,
  type ModelProvider,
  type ModelRequest,
  type ModelResponse,
  type ToolCall,
} from '../types'

/**
 * 两类真实提供方都用 fetch 直接实现，不引入厂商 SDK：请求与响应的映射一目了然，便于阅读（ADR 0006）。
 * 两家协议的工具名都只允许字母、数字、下划线和连字符，平台的工具名里有点（semantic.get_metric），
 * 发送前把点编码为两个下划线，收到后还原。
 */
export const encodeToolName = (name: string): string => name.replace(/\./g, '__')
export const decodeToolName = (name: string): string => name.replace(/__/g, '.')

const REQUEST_TIMEOUT_MS = 120_000
const MAX_TOKENS = 4096

async function postJson(
  fetchImpl: typeof fetch,
  url: string,
  headers: Record<string, string>,
  body: unknown,
): Promise<unknown> {
  let response: Response
  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
  } catch (error: unknown) {
    throw new ModelError(
      'NETWORK',
      `调用模型失败：${error instanceof Error ? error.message : String(error)}`,
    )
  }
  const text = await response.text()
  if (!response.ok) {
    // 不回显响应正文全文，避免把上游返回的敏感信息带进追踪
    throw new ModelError(
      `HTTP_${response.status}`,
      `模型服务返回 ${response.status}：${text.slice(0, 200)}`,
    )
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    throw new ModelError('BAD_RESPONSE', '模型服务返回的不是 JSON')
  }
}

function parseArguments(raw: unknown): Record<string, unknown> {
  if (typeof raw === 'object' && raw !== null) return raw as Record<string, unknown>
  if (typeof raw !== 'string' || raw.trim() === '') return {}
  try {
    const parsed: unknown = JSON.parse(raw)
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {}
  } catch {
    throw new ModelError('BAD_TOOL_ARGUMENTS', '模型给出的工具参数不是合法 JSON')
  }
}

// ── OpenAI 兼容协议（/chat/completions）──────────────────────────────────

interface OpenAiResponse {
  choices?: {
    message?: {
      content?: string | null
      tool_calls?: { id: string; function: { name: string; arguments: string } }[]
    }
    finish_reason?: string
  }[]
  usage?: { prompt_tokens?: number; completion_tokens?: number }
}

export function toOpenAiBody(request: ModelRequest & { model: string }): Record<string, unknown> {
  const messages = [
    { role: 'system', content: request.system },
    ...request.messages.map((m) => {
      if (m.role === 'tool') return { role: 'tool', tool_call_id: m.toolCallId, content: m.content }
      if (m.role === 'assistant' && m.toolCalls !== undefined && m.toolCalls.length > 0) {
        return {
          role: 'assistant',
          content: m.content === '' ? null : m.content,
          tool_calls: m.toolCalls.map((c) => ({
            id: c.id,
            type: 'function',
            function: { name: encodeToolName(c.name), arguments: JSON.stringify(c.arguments) },
          })),
        }
      }
      return { role: m.role, content: m.content }
    }),
  ]
  return {
    model: request.model,
    messages,
    ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
    ...(request.tools.length === 0
      ? {}
      : {
          tools: request.tools.map((t) => ({
            type: 'function',
            function: {
              name: encodeToolName(t.name),
              description: t.description,
              parameters: t.inputSchema,
            },
          })),
        }),
  }
}

export function fromOpenAiResponse(raw: unknown, model: string): ModelResponse {
  const body = raw as OpenAiResponse
  const choice = body.choices?.[0]
  if (choice?.message === undefined)
    throw new ModelError('BAD_RESPONSE', '模型响应缺少 choices[0].message')
  const toolCalls: ToolCall[] = (choice.message.tool_calls ?? []).map((c) => ({
    id: c.id,
    name: decodeToolName(c.function.name),
    arguments: parseArguments(c.function.arguments),
  }))
  return {
    text: choice.message.content ?? '',
    toolCalls,
    usage: {
      inputTokens: body.usage?.prompt_tokens ?? 0,
      outputTokens: body.usage?.completion_tokens ?? 0,
      estimated: false,
    },
    provider: 'openai',
    model,
    stopReason:
      toolCalls.length > 0 ? 'tool_use' : choice.finish_reason === 'length' ? 'max_tokens' : 'end',
  }
}

export class OpenAiProvider implements ModelProvider {
  readonly name = 'openai'
  constructor(
    private readonly options: { baseUrl: string; apiKey: string; fetch?: typeof fetch },
  ) {}

  async complete(request: ModelRequest & { model: string }): Promise<ModelResponse> {
    const url = `${this.options.baseUrl.replace(/\/$/, '')}/chat/completions`
    const raw = await postJson(
      this.options.fetch ?? fetch,
      url,
      { authorization: `Bearer ${this.options.apiKey}` },
      toOpenAiBody(request),
    )
    return fromOpenAiResponse(raw, request.model)
  }
}

// ── Anthropic 协议（/v1/messages）────────────────────────────────────────

type AnthropicBlock =
  | { type: 'text'; text: string }
  | { type: 'tool_use'; id: string; name: string; input: unknown }
  | { type: 'tool_result'; tool_use_id: string; content: string }

interface AnthropicResponse {
  content?: AnthropicBlock[]
  stop_reason?: string
  usage?: { input_tokens?: number; output_tokens?: number }
}

/** 连续的工具结果合并进同一条 user 消息，这是 Anthropic 协议的要求 */
export function toAnthropicBody(
  request: ModelRequest & { model: string },
): Record<string, unknown> {
  const messages: { role: 'user' | 'assistant'; content: string | AnthropicBlock[] }[] = []
  for (const m of request.messages) {
    if (m.role === 'tool') {
      const block: AnthropicBlock = {
        type: 'tool_result',
        tool_use_id: m.toolCallId,
        content: m.content,
      }
      const last = messages.at(-1)
      if (
        last?.role === 'user' &&
        Array.isArray(last.content) &&
        last.content[0]?.type === 'tool_result'
      ) {
        last.content.push(block)
      } else {
        messages.push({ role: 'user', content: [block] })
      }
      continue
    }
    if (m.role === 'assistant') {
      const blocks: AnthropicBlock[] = [
        ...(m.content === '' ? [] : [{ type: 'text' as const, text: m.content }]),
        ...(m.toolCalls ?? []).map((c) => ({
          type: 'tool_use' as const,
          id: c.id,
          name: encodeToolName(c.name),
          input: c.arguments,
        })),
      ]
      messages.push({ role: 'assistant', content: blocks })
      continue
    }
    messages.push({ role: 'user', content: m.content })
  }
  return {
    model: request.model,
    max_tokens: MAX_TOKENS,
    system: request.system,
    messages,
    ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
    ...(request.tools.length === 0
      ? {}
      : {
          tools: request.tools.map((t) => ({
            name: encodeToolName(t.name),
            description: t.description,
            input_schema: t.inputSchema,
          })),
        }),
  }
}

export function fromAnthropicResponse(raw: unknown, model: string): ModelResponse {
  const body = raw as AnthropicResponse
  if (body.content === undefined) throw new ModelError('BAD_RESPONSE', '模型响应缺少 content')
  const text = body.content
    .filter((b): b is Extract<AnthropicBlock, { type: 'text' }> => b.type === 'text')
    .map((b) => b.text)
    .join('')
  const toolCalls = body.content
    .filter((b): b is Extract<AnthropicBlock, { type: 'tool_use' }> => b.type === 'tool_use')
    .map((b) => ({ id: b.id, name: decodeToolName(b.name), arguments: parseArguments(b.input) }))
  return {
    text,
    toolCalls,
    usage: {
      inputTokens: body.usage?.input_tokens ?? 0,
      outputTokens: body.usage?.output_tokens ?? 0,
      estimated: false,
    },
    provider: 'anthropic',
    model,
    stopReason:
      toolCalls.length > 0 ? 'tool_use' : body.stop_reason === 'max_tokens' ? 'max_tokens' : 'end',
  }
}

export class AnthropicProvider implements ModelProvider {
  readonly name = 'anthropic'
  constructor(
    private readonly options: { baseUrl: string; apiKey: string; fetch?: typeof fetch },
  ) {}

  async complete(request: ModelRequest & { model: string }): Promise<ModelResponse> {
    const url = `${this.options.baseUrl.replace(/\/$/, '')}/v1/messages`
    const raw = await postJson(
      this.options.fetch ?? fetch,
      url,
      { 'x-api-key': this.options.apiKey, 'anthropic-version': '2023-06-01' },
      toAnthropicBody(request),
    )
    return fromAnthropicResponse(raw, request.model)
  }
}
