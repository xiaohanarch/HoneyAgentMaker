import type { ModelProfile } from '@eab/spec'
import type { Span, Tracer } from '@eab/tracing'
import { estimateCost, type GatewayConfig } from './config'
import { INPUT_BLOCKED_REPLY, checkInput, filterOutput } from './pipeline/filters'
import type { Masker } from './pipeline/masking'
import {
  buildSystemPrompt,
  extractOutput,
  type KnowledgeSnippet,
  type PromptInput,
} from './pipeline/template'
import type { ChatMessage, ModelCallContext, ModelProvider, ModelResponse, ToolSpec } from './types'

export interface GatewayRequest {
  profile: ModelProfile
  temperature?: number
  prompt: PromptInput
  messages: readonly ChatMessage[]
  tools: readonly ToolSpec[]
  context: ModelCallContext
  /** 一次运行共用一个 Masker，占位符在多轮之间保持一致 */
  masker: Masker
  /** 知识检索注入：按本轮话术从挂载的语义包里取相关的指标口径和规则 */
  retrieve?: (query: string) => KnowledgeSnippet[]
}

export interface TraceTarget {
  tracer: Tracer
  parent: Span
  key: string
}

export interface GatewayResponse extends ModelResponse {
  costUsd: number
  /** 输入被内容过滤拦截时为 true，此时没有调用模型 */
  inputBlocked: boolean
  /** 回复里被遮蔽的保密片段编号，监督层据此产生告警 */
  leakedSegments: string[]
}

const lastUser = (messages: readonly ChatMessage[]): string =>
  [...messages].reverse().find((m) => m.role === 'user')?.content ?? ''

/**
 * 模型网关（主提示词 8.2 节）：按档位解析模型，模型调用走一条处理管道：
 * 提示模板 → 知识检索注入 → 敏感数据脱敏 → 输入过滤 → 调用模型 → 还原 → 输出过滤。
 * 每个环节在追踪里是一个子 span；模型 span 保存提示词片段，供按查看者裁剪。
 */
export class ModelGateway {
  constructor(
    private readonly provider: ModelProvider,
    private readonly config: Pick<GatewayConfig, 'profiles' | 'prices'>,
  ) {}

  get providerName(): string {
    return this.provider.name
  }

  modelFor(profile: ModelProfile): string {
    return this.config.profiles[profile]
  }

  async complete(request: GatewayRequest, trace: TraceTarget): Promise<GatewayResponse> {
    const { tracer } = trace
    const model = this.modelFor(request.profile)
    const span = tracer.start(`模型调用 · ${request.profile}`, {
      kind: 'model',
      key: trace.key,
      parent: trace.parent,
      attributes: {
        'gen_ai.system': this.provider.name,
        'gen_ai.request.model': model,
        'eab.model.profile': request.profile,
      },
    })
    const stage = <T>(
      name: string,
      fn: () => T,
      attributes: (r: T) => Record<string, string | number | boolean>,
    ): T => {
      const child = tracer.start(name, {
        kind: 'pipeline',
        key: `${trace.key}/${name}`,
        parent: span,
      })
      const result = fn()
      child.end({ attributes: attributes(result) })
      return result
    }

    try {
      const utterance = lastUser(request.messages)
      const knowledge = stage(
        'pipeline.retrieval',
        () => request.retrieve?.(utterance) ?? [],
        (k) => ({
          'eab.retrieval.count': k.length,
          'eab.retrieval.ids': k.map((s) => s.id).join(','),
        }),
      )
      const prompt = stage(
        'pipeline.template',
        () => buildSystemPrompt(request.prompt, knowledge),
        (p) => ({ 'eab.prompt.segments': p.segments.length }),
      )
      const masked = stage(
        'pipeline.masking',
        () => {
          let count = 0
          const maskText = (text: string): string => {
            const r = request.masker.mask(text)
            count += r.count
            return r.text
          }
          const messages = request.messages.map((m): ChatMessage => ({
            ...m,
            content: maskText(m.content),
          }))
          return { system: maskText(prompt.system), messages, count }
        },
        (m) => ({ 'eab.masking.count': m.count }),
      )
      const inputVerdict = stage(
        'pipeline.input-filter',
        () => checkInput(utterance),
        (v) => ({ 'eab.filter.blocked': v.blocked, 'eab.filter.rule': v.ruleId ?? '' }),
      )

      if (inputVerdict.blocked) {
        const blocked: GatewayResponse = {
          text: INPUT_BLOCKED_REPLY,
          toolCalls: [],
          usage: { inputTokens: 0, outputTokens: 0, estimated: false },
          provider: this.provider.name,
          model,
          stopReason: 'blocked',
          costUsd: 0,
          inputBlocked: true,
          leakedSegments: [],
        }
        span.end({
          attributes: { 'gen_ai.response.finish_reasons': 'blocked' },
          promptSegments: prompt.segments,
        })
        return blocked
      }

      const raw = await this.provider.complete({
        profile: request.profile,
        model,
        system: masked.system,
        messages: masked.messages,
        tools: request.tools,
        ...(request.temperature === undefined ? {} : { temperature: request.temperature }),
        context: request.context,
      })

      const confidential = request.prompt.segments
        .filter((s) => s.confidential)
        .map((s) => ({ id: s.id, text: s.text }))
      const filtered = stage(
        'pipeline.output-filter',
        () => {
          const unmasked = request.masker.unmask(raw.text)
          const extracted =
            raw.output === undefined
              ? extractOutput(unmasked)
              : { text: unmasked, output: raw.output }
          return { ...filterOutput(extracted.text, confidential), output: extracted.output }
        },
        (f) => ({
          'eab.filter.leaked_segments': f.leakedSegments.join(','),
          'eab.filter.markers_removed': f.markersRemoved,
        }),
      )

      const cost = estimateCost(this.config.prices, model, raw.usage)
      const response: GatewayResponse = {
        ...raw,
        text: filtered.text,
        toolCalls: raw.toolCalls.map((c) => ({
          ...c,
          arguments: request.masker.unmaskValue(c.arguments) as Record<string, unknown>,
        })),
        ...(filtered.output === undefined
          ? {}
          : { output: request.masker.unmaskValue(filtered.output) as Record<string, unknown> }),
        costUsd: cost.usd,
        inputBlocked: false,
        leakedSegments: filtered.leakedSegments,
      }
      span.end({
        attributes: {
          'gen_ai.response.model': raw.model,
          'gen_ai.response.finish_reasons': raw.stopReason,
          'gen_ai.usage.input_tokens': raw.usage.inputTokens,
          'gen_ai.usage.output_tokens': raw.usage.outputTokens,
          'eab.usage.estimated': raw.usage.estimated,
          'eab.cost.usd': cost.usd,
          'eab.cost.priced': cost.priced,
          'eab.tool_calls': raw.toolCalls.map((c) => c.name).join(','),
          'eab.response.text': filtered.text.slice(0, 2000),
          ...(raw.scriptId === undefined ? {} : { 'eab.script.id': raw.scriptId }),
        },
        promptSegments: prompt.segments,
      })
      return response
    } catch (error: unknown) {
      span.end({ status: 'error', message: error instanceof Error ? error.message : String(error) })
      throw error
    }
  }
}
