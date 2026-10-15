import { createHash, randomBytes } from 'node:crypto'
import type {
  AttributeMap,
  PromptSegment,
  SpanKind,
  SpanRecord,
  SpanStatus,
  TraceStore,
} from './types'

export interface SpanOptions {
  kind: SpanKind
  /**
   * span 在本次运行里的结构化路径，例如 turn:2/tool:call_1。spanId 由 traceId 和它派生，
   * 运行从人工确认处恢复、重放到同一位置时得到同一个 spanId（ADR 0006）。
   */
  key: string
  parent: Span | null
  attributes?: AttributeMap
}

export interface EndOptions {
  status?: SpanStatus
  message?: string
  attributes?: AttributeMap
  promptSegments?: PromptSegment[]
}

/** 一个进行中的 span。end 之后写入存储，重复 end 无效 */
export class Span {
  readonly startTime: string
  #attributes: AttributeMap
  #ended = false

  constructor(
    private readonly tracer: Tracer,
    readonly spanId: string,
    readonly key: string,
    readonly parentSpanId: string | null,
    readonly name: string,
    readonly kind: SpanKind,
    attributes: AttributeMap,
  ) {
    this.startTime = tracer.now().toISOString()
    this.#attributes = { ...attributes }
  }

  get traceId(): string {
    return this.tracer.traceId
  }

  get ended(): boolean {
    return this.#ended
  }

  setAttributes(attributes: AttributeMap): void {
    this.#attributes = { ...this.#attributes, ...attributes }
  }

  end(options: EndOptions = {}): void {
    if (this.#ended) return
    this.#ended = true
    this.tracer.finish(this, {
      ...options,
      attributes: { ...this.#attributes, ...options.attributes },
    })
  }

  /** 运行挂起时调用：把尚未结束的 span 以 suspended 落库，但不标记为结束 */
  flushSuspended(): void {
    if (this.#ended) return
    this.tracer.write(this, { status: 'suspended', attributes: this.#attributes })
  }
}

export interface TracerOptions {
  traceId?: string
  store: TraceStore
  tenantId?: string
  now?: () => Date
}

/** 挂起信号的 name，由运行时定义；tracer 只需要识别它，挂起不算错误 */
export const SUSPEND_SIGNAL = 'SuspendSignal'

export function spanIdOf(traceId: string, key: string): string {
  return createHash('sha256').update(`${traceId}:${key}`).digest('hex').slice(0, 16)
}

/**
 * 一次运行一个 Tracer。父子关系由调用方显式传入，不依赖调用栈，
 * 并行分支里的 span 也能挂到正确的父节点上。
 */
export class Tracer {
  readonly traceId: string
  readonly now: () => Date
  #open = new Set<Span>()

  constructor(private readonly options: TracerOptions) {
    this.traceId = options.traceId ?? randomBytes(16).toString('hex')
    this.now = options.now ?? (() => new Date())
  }

  start(name: string, options: SpanOptions): Span {
    const span = new Span(
      this,
      spanIdOf(this.traceId, options.key),
      options.key,
      options.parent?.spanId ?? null,
      name,
      options.kind,
      options.attributes ?? {},
    )
    this.#open.add(span)
    return span
  }

  /**
   * 在 span 内执行 fn。fn 抛错时 span 以 error 结束并继续抛出；
   * 挂起信号不算错误，span 保持打开，由 suspendOpenSpans 以 suspended 落库。
   */
  async within<T>(
    name: string,
    options: SpanOptions,
    fn: (span: Span) => Promise<T>,
    onResult?: (result: T) => EndOptions,
  ): Promise<T> {
    const span = this.start(name, options)
    try {
      const result = await fn(span)
      span.end(onResult?.(result) ?? {})
      return result
    } catch (error: unknown) {
      if (!(error instanceof Error && error.name === SUSPEND_SIGNAL)) {
        span.end({
          status: 'error',
          message: error instanceof Error ? error.message : String(error),
        })
      }
      throw error
    }
  }

  /** 把全部未结束的 span 以 suspended 落库 */
  suspendOpenSpans(): void {
    for (const span of this.#open) span.flushSuspended()
  }

  /** @internal */
  finish(span: Span, options: EndOptions): void {
    this.#open.delete(span)
    this.write(span, options)
  }

  /** @internal */
  write(span: Span, options: EndOptions): void {
    this.options.store.upsert({
      traceId: this.traceId,
      spanId: span.spanId,
      parentSpanId: span.parentSpanId,
      name: span.name,
      kind: span.kind,
      startTime: span.startTime,
      endTime: this.now().toISOString(),
      status: options.status ?? 'ok',
      attributes: { 'eab.span.key': span.key, ...options.attributes },
      ...(options.message === undefined ? {} : { statusMessage: options.message }),
      ...(options.promptSegments === undefined ? {} : { promptSegments: options.promptSegments }),
      ...(this.options.tenantId === undefined ? {} : { tenantId: this.options.tenantId }),
    })
  }
}

/** 按存储约定合并：已有最终状态时忽略，已有 suspended 时覆盖并保留原开始时间 */
export function mergeSpan(
  existing: SpanRecord | undefined,
  incoming: SpanRecord,
): SpanRecord | undefined {
  if (existing === undefined) return incoming
  if (existing.status !== 'suspended') return undefined
  return { ...incoming, startTime: existing.startTime }
}

/** 内存存储，测试和命令行使用 */
export class MemoryTraceStore implements TraceStore {
  readonly #spans = new Map<string, SpanRecord>()

  upsert(span: SpanRecord): void {
    const key = `${span.traceId}:${span.spanId}`
    const merged = mergeSpan(this.#spans.get(key), span)
    if (merged !== undefined) this.#spans.set(key, merged)
  }

  list(traceId: string): SpanRecord[] {
    return [...this.#spans.values()]
      .filter((s) => s.traceId === traceId)
      .sort((a, b) => a.startTime.localeCompare(b.startTime))
  }
}
