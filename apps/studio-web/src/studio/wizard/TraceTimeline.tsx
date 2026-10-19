import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { EyeOff, ShieldAlert, Waypoints } from 'lucide-react'
import { cn } from '@eab/ui'
import type { Span } from '../api'
import { formatDuration, layoutTimeline, SPAN_BAR_STYLES, type TimelineRow } from '../trace-layout'
import { errorText, useStudioApi } from '../use-studio'

function Row({
  row,
  selected,
  onSelect,
}: {
  row: TimelineRow
  selected: boolean
  onSelect: () => void
}): ReactNode {
  const { span } = row
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        data-testid={row.constraint ? 'constraint-span' : 'trace-span'}
        className={cn(
          'grid w-full grid-cols-[minmax(0,15rem)_minmax(0,1fr)_4.5rem] items-center gap-2 rounded px-1.5 py-0.5 text-left text-[11px] transition-colors',
          selected ? 'bg-surface-sunken ring-1 ring-line' : 'hover:bg-surface-sunken',
          row.constraint && 'bg-pending-soft/60',
        )}
      >
        <span
          className="flex min-w-0 items-center gap-1"
          style={{ paddingLeft: `${Math.min(row.depth, 8) * 10}px` }}
        >
          {row.constraint && <ShieldAlert aria-hidden className="size-3 shrink-0 text-pending" />}
          <span
            className={cn(
              'truncate',
              span.status === 'error' && 'text-deny',
              row.constraint && 'font-medium text-pending',
            )}
          >
            {span.name}
          </span>
        </span>
        <span className="relative h-2.5 rounded-sm bg-surface-sunken">
          <span
            className={cn(
              'absolute inset-y-0 rounded-sm',
              SPAN_BAR_STYLES[span.kind] ?? 'bg-ink-faint',
              span.status === 'error' && 'bg-deny',
            )}
            style={{ left: `${row.offset}%`, width: `${row.width}%` }}
          />
        </span>
        <span className="text-right font-mono text-[10px] text-ink-faint">
          {formatDuration(row.durationMs)}
        </span>
      </button>
    </li>
  )
}

function value(v: unknown): string {
  return typeof v === 'string' ? v : JSON.stringify(v)
}

/** 选中 span 的详情：状态、属性、提示词片段（租户视角下标品指令显示为占位） */
export function SpanDetail({ span }: { span: Span }): ReactNode {
  const attributes = Object.entries(span.attributes ?? {})
  const segments = span.promptSegments ?? []
  return (
    <div className="space-y-2 text-xs" data-testid="span-detail">
      <p className="flex flex-wrap items-center gap-2">
        <span className="font-medium text-ink">{span.name}</span>
        <span className="font-mono text-ink-faint">{span.kind}</span>
        <span
          className={cn(
            'rounded px-1.5 text-[10px]',
            span.status === 'ok' && 'bg-allow-soft text-allow',
            span.status === 'error' && 'bg-deny-soft text-deny',
            span.status === 'suspended' && 'bg-pending-soft text-pending',
          )}
        >
          {span.status}
        </span>
      </p>
      {(span.statusMessage ?? '') !== '' && <p className="text-deny">{span.statusMessage}</p>}
      {attributes.length > 0 && (
        <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-0.5">
          {attributes.map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="font-mono text-ink-faint">{k}</dt>
              <dd className="break-all">{value(v)}</dd>
            </div>
          ))}
        </dl>
      )}
      {segments.length > 0 && (
        <ol className="space-y-1">
          {segments.map((seg) => (
            <li
              key={seg.id}
              className={cn(
                'rounded border px-2 py-1',
                seg.redacted ? 'border-dashed border-line-strong bg-surface-sunken' : 'border-line',
              )}
            >
              <p className="flex items-center gap-1.5 text-[10px] text-ink-faint">
                {seg.redacted && <EyeOff aria-label="已裁剪" className="size-3" />}
                <span className="font-mono">{seg.kind}</span>
                <span>{seg.source}</span>
              </p>
              <p
                className={cn(
                  'whitespace-pre-wrap',
                  seg.redacted ? 'italic text-ink-faint' : 'text-ink',
                )}
              >
                {seg.text ?? '（无内容）'}
              </p>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/** 一次运行的链路追踪：时间线加树形缩进，点击 span 看详情 */
export function TraceTimeline({
  traceId,
  revision,
}: {
  traceId: string
  revision: number
}): ReactNode {
  const { api, ready } = useStudioApi()
  const [selected, setSelected] = useState<string | null>(null)
  const { data, error } = useQuery({
    queryKey: ['studio', 'trace', traceId, revision],
    queryFn: () => api.trace(traceId),
    enabled: ready,
  })
  const timeline = data === undefined ? undefined : layoutTimeline(data.spans)
  const current = data?.spans.find((s) => s.spanId === selected)

  return (
    <section className="rounded-xl border border-line bg-surface" data-testid="trace-timeline">
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <Waypoints aria-hidden className="size-4 text-ink-muted" />
        链路追踪
        {timeline !== undefined && (
          <span className="ml-auto text-xs font-normal text-ink-faint">
            {`${timeline.rows.length} 个 span · ${formatDuration(timeline.totalMs)}`}
          </span>
        )}
      </header>
      <div className="grid grid-cols-[minmax(0,1fr)_18rem] gap-3 px-3 py-3">
        {error !== null ? (
          <p className="text-xs text-deny">追踪读取失败：{errorText(error)}</p>
        ) : timeline === undefined ? (
          <p className="text-xs text-ink-muted">读取中…</p>
        ) : (
          <ol className="max-h-[420px] space-y-px overflow-y-auto">
            {timeline.rows.map((row) => (
              <Row
                key={row.span.spanId}
                row={row}
                selected={row.span.spanId === selected}
                onSelect={() => setSelected(row.span.spanId)}
              />
            ))}
          </ol>
        )}
        <aside className="max-h-[420px] overflow-y-auto border-l border-line pl-3">
          {current === undefined ? (
            <p className="text-xs text-ink-muted">
              点击左侧的 span 查看输入输出、鉴权裁决和提示词片段。
            </p>
          ) : (
            <SpanDetail span={current} />
          )}
        </aside>
      </div>
    </section>
  )
}
