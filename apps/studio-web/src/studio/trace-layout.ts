import type { Span } from './api'

export interface TimelineRow {
  span: Span
  depth: number
  /** 条形起点，占整条追踪时长的百分比 */
  offset: number
  /** 条形宽度百分比，最小 0.5，保证瞬时 span 也看得见 */
  width: number
  durationMs: number | null
  /** 约束守卫触发的 span，时间线上突出显示 */
  constraint: boolean
}

export interface Timeline {
  rows: TimelineRow[]
  totalMs: number
}

const MIN_WIDTH = 0.5

/** span 种类的条形颜色，完整类名写死，不拼接（链路追踪与架构视图的时间线共用） */
export const SPAN_BAR_STYLES: Record<string, string> = {
  run: 'bg-ink/70',
  routing: 'bg-info',
  assemble: 'bg-vendor',
  pipeline: 'bg-ink-faint',
  model: 'bg-external',
  policy: 'bg-allow',
  tool: 'bg-tenant',
  skill: 'bg-tenant',
  workflow: 'bg-tenant',
  agent: 'bg-platform',
  guard: 'bg-pending',
  hook: 'bg-platform',
  confirmation: 'bg-pending',
  memory: 'bg-ink-faint',
}

function time(value: string | null): number | null {
  if (value === null) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function isConstraintSpan(span: Span): boolean {
  return span.kind === 'guard' && span.name.startsWith('约束') && span.name.endsWith('触发')
}

/**
 * 把扁平的 span 列表排成时间线：按父子关系深度优先，同级按开始时间；父节点缺失的 span 作为根。
 * 没有结束时间的 span（挂起中）延续到整条追踪的末尾。
 */
export function layoutTimeline(spans: readonly Span[]): Timeline {
  const starts = spans.map((s) => time(s.startTime)).filter((t): t is number => t !== null)
  const ends = spans
    .map((s) => time(s.endTime) ?? time(s.startTime))
    .filter((t): t is number => t !== null)
  if (starts.length === 0) return { rows: [], totalMs: 0 }
  const begin = Math.min(...starts)
  const finish = Math.max(...ends, begin)
  const total = Math.max(finish - begin, 1)

  const ids = new Set(spans.map((s) => s.spanId))
  const children = new Map<string, Span[]>()
  const roots: Span[] = []
  for (const span of spans) {
    if (span.parentSpanId === null || !ids.has(span.parentSpanId)) roots.push(span)
    else children.set(span.parentSpanId, [...(children.get(span.parentSpanId) ?? []), span])
  }
  const byStart = (a: Span, b: Span): number => a.startTime.localeCompare(b.startTime)

  const rows: TimelineRow[] = []
  const visit = (span: Span, depth: number): void => {
    const start = time(span.startTime) ?? begin
    const end = time(span.endTime)
    const until = end ?? finish
    const offset = ((start - begin) / total) * 100
    const width = Math.max(((until - start) / total) * 100, MIN_WIDTH)
    rows.push({
      span,
      depth,
      offset: Math.min(offset, 100 - MIN_WIDTH),
      width: Math.min(width, 100 - Math.min(offset, 100 - MIN_WIDTH)),
      durationMs: end === null ? null : end - start,
      constraint: isConstraintSpan(span),
    })
    ;[...(children.get(span.spanId) ?? [])].sort(byStart).forEach((c) => visit(c, depth + 1))
  }
  ;[...roots].sort(byStart).forEach((r) => visit(r, 0))
  return { rows, totalMs: finish - begin }
}

export function formatDuration(ms: number | null): string {
  if (ms === null) return '进行中'
  if (ms < 1000) return `${ms} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}
