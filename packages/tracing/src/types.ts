/**
 * span 模型，字段与 OpenTelemetry 的 Span 对齐：traceId、spanId、parentSpanId、name、kind、起止时间、状态、属性。
 * 属性名尽量沿用 OpenTelemetry 语义约定，模型调用用 gen_ai.*，工具调用用 eab.tool.*。
 */

/** 平台自定义的 span 种类，决定追踪树里的图标和颜色（主提示词 8.1.1 节） */
export const SPAN_KINDS = [
  'run',
  'routing',
  'assemble',
  'pipeline',
  'model',
  'policy',
  'tool',
  'skill',
  'workflow',
  'agent',
  'guard',
  'hook',
  'confirmation',
  'memory',
] as const
export type SpanKind = (typeof SPAN_KINDS)[number]

/**
 * ok、error 与 OpenTelemetry 的状态码对应；suspended 是平台扩展：
 * 运行等待人工确认时，尚未结束的 span 先以 suspended 落库，恢复后再写入最终状态。
 */
export type SpanStatus = 'ok' | 'error' | 'suspended'

export type AttributeValue = string | number | boolean | null | AttributeValue[] | AttributeMap
export interface AttributeMap {
  [key: string]: AttributeValue
}

/**
 * 发给模型的系统提示词按片段保存，而不是拼接后的原文：
 * 读取追踪时按查看者把保密片段（标品指令、平台规则原文）换成占位块（ADR 0004、0006）。
 */
export interface PromptSegment {
  id: string
  kind: string
  source: string
  confidential: boolean
  text: string
}

export interface SpanRecord {
  traceId: string
  spanId: string
  parentSpanId: string | null
  name: string
  kind: SpanKind
  startTime: string
  endTime: string
  status: SpanStatus
  statusMessage?: string
  attributes: AttributeMap
  /** 仅 model span：系统提示词片段 */
  promptSegments?: PromptSegment[]
  /** 本 span 所属的租户，用于按查看者过滤 */
  tenantId?: string
}

export interface TraceStore {
  /**
   * 写入一个已结束的 span。同一 spanId 已经以最终状态存在时忽略；
   * 已存在但状态为 suspended 时，用新状态覆盖并保留原开始时间。
   */
  upsert(span: SpanRecord): void
  list(traceId: string): SpanRecord[]
}
