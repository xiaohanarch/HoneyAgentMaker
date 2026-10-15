/**
 * @eab/tracing：链路追踪，span 兼容 OpenTelemetry 语义（主提示词 8.1.1 节）。
 * 存储由平台实现（SQLite），这里提供 span 模型、Tracer、按查看者裁剪的视图和 OTLP 转换。
 */
export { exportOtlp, toOtlp } from './otlp'
export {
  MemoryTraceStore,
  SUSPEND_SIGNAL,
  Span,
  Tracer,
  mergeSpan,
  spanIdOf,
  type EndOptions,
  type SpanOptions,
  type TracerOptions,
} from './tracer'
export * from './types'
export {
  buildTree,
  canViewTrace,
  viewTrace,
  type PromptSegmentView,
  type SpanNode,
  type SpanView,
} from './view'
