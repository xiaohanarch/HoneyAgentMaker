import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Waypoints } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../identity/identity-context'
import { ErrorLine, Empty, Loading, Pill, SectionHeader, shortTime, toneOf } from '../hub/ui'
import { delegatedPrelude, mapSpan } from '../architecture/mapSpan'
import { Diagram } from '../architecture/Diagram'
import { listRuns, type RunSummary } from '../architecture/api'
import { ReplayControls } from '../architecture/ReplayControls'
import { formatDuration, layoutTimeline, SPAN_BAR_STYLES, type TimelineRow } from '../studio/trace-layout'
import { SpanDetail } from '../studio/wizard/TraceTimeline'
import type { Span } from '../studio/api'
import { useStudioApi } from '../studio/use-studio'

/** 回放总时长（归一化，非真实耗时）、开场帧占比与播放节拍 */
const REPLAY_MS = 12_000
const OPENING = 0.1
const TICK_MS = 100

/** 鉴权点 1（入口校验令牌）没有独立 span，回放第 0 帧点亮入口与鉴权，图例里说明 */
const ENTRY_PRELUDE: ReadonlySet<string> = new Set(['ux-entry', 'wing-auth'])
const NO_SPANS: readonly Span[] = []

function parseTime(value: string | null): number | null {
  if (value === null) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 运行选择器：最近 50 次运行，标注人、模式与状态 */
function RunPicker({
  runs,
  run,
  onSelect,
}: {
  runs: readonly RunSummary[]
  run: RunSummary | undefined
  onSelect: (runId: string) => void
}): ReactNode {
  const { users } = useIdentity()
  const nameOf = (id: string): string => users.find((u) => u.id === id)?.name ?? id
  return (
    <div className="flex flex-wrap items-center gap-2">
      <select
        aria-label="选择运行"
        data-testid="run-picker"
        value={run === undefined ? '' : run.runId}
        onChange={(e) => onSelect(e.target.value)}
        className="max-w-[24rem] rounded-md border border-line bg-surface px-2 py-1.5 text-xs text-ink"
      >
        {run === undefined && <option value="">还没有运行</option>}
        {runs.map((r) => (
          <option key={r.runId} value={r.runId}>
            {`${shortTime(r.createdAt)} · ${nameOf(r.userId)} · ${r.utterance.slice(0, 18)}`}
          </option>
        ))}
      </select>
      {run !== undefined && (
        <>
          <Pill tone={toneOf(run.status)}>{run.status}</Pill>
          <Pill tone="neutral">{run.mode === 'delegated' ? '受托运行' : '本人提问'}</Pill>
          <span className="font-mono text-[11px] text-ink-faint">{run.agent}</span>
        </>
      )}
    </div>
  )
}

/** 时间线条：与链路追踪同源的布局，点击 span 跳到该处并选中 */
function RunStrip({
  rows,
  playhead,
  selected,
  onPick,
}: {
  rows: readonly TimelineRow[]
  playhead: number
  selected: string | null
  onPick: (span: Span, offsetRatio: number) => void
}): ReactNode {
  return (
    <ol className="relative max-h-[360px] space-y-px overflow-y-auto pr-2" data-testid="run-strip">
      <span
        aria-hidden
        className="pointer-events-none absolute inset-y-0 z-10 w-px bg-deny"
        style={{ left: `${playhead}%` }}
      />
      {rows.map((row) => (
        <li key={row.span.spanId}>
          <button
            type="button"
            onClick={() => onPick(row.span, row.offset / 100)}
            data-testid="strip-span"
            className={cn(
              'grid w-full grid-cols-[minmax(0,13rem)_minmax(0,1fr)_4rem] items-center gap-2 rounded px-1.5 py-0.5 text-left text-[11px] transition-colors',
              row.span.spanId === selected ? 'bg-surface-sunken ring-1 ring-line' : 'hover:bg-surface-sunken',
            )}
          >
            <span className="truncate" style={{ paddingLeft: `${Math.min(row.depth, 8) * 10}px` }}>
              {row.span.name}
            </span>
            <span className="relative h-2.5 rounded-sm bg-surface-sunken">
              <span
                className={cn(
                  'absolute inset-y-0 rounded-sm',
                  SPAN_BAR_STYLES[row.span.kind] ?? 'bg-ink-faint',
                  row.span.status === 'error' && 'bg-deny',
                )}
                style={{ left: `${row.offset}%`, width: `${row.width}%` }}
              />
            </span>
            <span className="text-right font-mono text-[10px] text-ink-faint">
              {formatDuration(row.durationMs)}
            </span>
          </button>
        </li>
      ))}
    </ol>
  )
}

/**
 * 一次运行的回放（架构视图主体）。外层用 key=runId 挂载，换运行时整段状态自然重置。
 * 回放时长归一化为 12 秒；开场帧合成鉴权点 1（受托运行则是业务事件），不伪造后端数据。
 */
function RunPlayback({ run }: { run: RunSummary }): ReactNode {
  const { api, ready } = useStudioApi()
  const [progress, setProgress] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null)

  const traceQuery = useQuery({
    queryKey: ['arch', 'trace', run.traceId],
    queryFn: () => api.trace(run.traceId),
    enabled: ready,
  })
  const trace = traceQuery.data

  // 播放：按速度把归一化进度往前推；到末尾由 isPlaying 派生值停下
  const isPlaying = playing && progress < 1
  useEffect(() => {
    if (!isPlaying) return
    const id = setInterval(() => {
      setProgress((p) => Math.min(p + (TICK_MS * speed) / REPLAY_MS, 1))
    }, TICK_MS)
    return () => clearInterval(id)
  }, [isPlaying, speed])

  const spans = useMemo(() => trace?.spans ?? NO_SPANS, [trace])
  const times = useMemo(
    () =>
      spans.map((s) => ({
        span: s,
        start: parseTime(s.startTime) ?? Number.NaN,
        end: parseTime(s.endTime),
      })),
    [spans],
  )
  const spanWindow = useMemo(() => {
    const starts = times.map((t) => t.start).filter((t) => Number.isFinite(t))
    if (starts.length === 0) return null
    const begin = Math.min(...starts)
    const finish = Math.max(...times.map((t) => t.end ?? t.start), begin)
    return { begin, finish, total: Math.max(finish - begin, 1) }
  }, [times])

  /** 真实时间 → 回放进度；开场帧单独占进度轴的前 10% */
  const toProgress = useCallback(
    (msValue: number): number =>
      spanWindow === null
        ? 0
        : Math.min(Math.max(OPENING + ((msValue - spanWindow.begin) / spanWindow.total) * (1 - OPENING), OPENING), 1),
    [spanWindow],
  )
  /** 回放进度 → 真实时间；末尾回退半毫秒，让最后一段 span 保持点亮 */
  const currentTime = useCallback(
    (p: number): number => {
      if (spanWindow === null) return 0
      if (p <= OPENING) return spanWindow.begin - 1
      return Math.min(spanWindow.begin + ((p - OPENING) / (1 - OPENING)) * spanWindow.total, spanWindow.finish - 0.5)
    },
    [spanWindow],
  )

  /** 单步的落点：0、开场帧结束、每个 span 起止换算到进度轴、1 */
  const steps = useMemo(() => {
    if (spanWindow === null) return [0, 1]
    const marks = new Set<number>([0, 1, OPENING])
    for (const t of times) {
      if (Number.isFinite(t.start)) marks.add(toProgress(t.start))
      if (t.end !== null) marks.add(toProgress(t.end))
    }
    return [...marks].sort((a, b) => a - b)
  }, [times, spanWindow, toProgress])

  const activeSpans = useMemo(() => {
    const cur = currentTime(progress)
    return times.filter((t) => t.start <= cur && (t.end === null || cur < t.end)).map((t) => t.span)
  }, [times, progress, currentTime])

  const delegated = run.mode === 'delegated'
  const opening = progress <= OPENING
  const highlighted = useMemo(() => {
    if (spanWindow === null) return new Set<string>()
    if (opening) return delegated ? new Set(delegatedPrelude()) : new Set(ENTRY_PRELUDE)
    const ids = new Set<string>()
    for (const span of activeSpans) for (const id of mapSpan(span)) ids.add(id)
    return ids
  }, [spanWindow, opening, delegated, activeSpans])

  /** 字幕：开场帧讲鉴权点 1 或业务事件，之后报最近开始的 span */
  const caption = useMemo(() => {
    if (spanWindow === null) return ''
    if (opening) {
      return delegated
        ? '业务事件 · DemoERP 推送事件，平台按委托鉴权后启动 Agent（鉴权点 1，合成帧）'
        : '鉴权点 1 · 入口先校验用户令牌（本步没有独立 span，合成帧）'
    }
    const latest = activeSpans.reduce<Span | undefined>(
      (acc, s) =>
        acc === undefined || (parseTime(s.startTime) ?? 0) > (parseTime(acc.startTime) ?? 0) ? s : acc,
      undefined,
    )
    const suffix = !isPlaying && progress >= 1 ? ' · 回放结束' : ''
    return latest === undefined ? `运行结束${suffix}` : `${latest.name}${suffix}`
  }, [spanWindow, opening, delegated, activeSpans, isPlaying, progress])

  const timeline = useMemo(() => (spans.length === 0 ? undefined : layoutTimeline(spans)), [spans])
  const selectedSpan = selectedSpanId === null ? undefined : spans.find((s) => s.spanId === selectedSpanId)
  const playhead = opening || spanWindow === null ? 0 : ((progress - OPENING) / (1 - OPENING)) * 100

  const step = (delta: 1 | -1): void => {
    setPlaying(false)
    setProgress((p) => {
      if (delta === 1) return steps.find((s) => s > p + 1e-9) ?? 1
      return [...steps].reverse().find((s) => s < p - 1e-9) ?? 0
    })
  }

  return (
    <>
      <Diagram highlighted={highlighted} replaying={trace !== undefined} />
      <ReplayControls
        playing={isPlaying}
        onToggle={() => {
          if (progress >= 1) setProgress(0)
          setPlaying((p) => (progress >= 1 ? true : !p))
        }}
        onStep={step}
        speed={speed}
        onSpeed={setSpeed}
        progress={progress}
        onSeek={(p) => {
          setPlaying(false)
          setProgress(p)
        }}
        caption={caption}
        atEnd={!isPlaying && progress >= 1}
      />
      <section
        className="grid grid-cols-[minmax(0,1fr)_18rem] gap-3 rounded-xl border border-line bg-surface px-3 py-3"
        data-testid="run-section"
      >
        <div>
          <header className="mb-2 flex items-center gap-2 text-sm font-medium">
            <Waypoints aria-hidden className="size-4 text-ink-muted" />
            时间线
            {timeline !== undefined && (
              <span className="ml-auto text-xs font-normal text-ink-faint">
                {`${timeline.rows.length} 个 span · ${formatDuration(timeline.totalMs)}`}
              </span>
            )}
          </header>
          {traceQuery.error !== null ? (
            <ErrorLine error={traceQuery.error} />
          ) : timeline === undefined ? (
            <p className="py-6 text-xs text-ink-muted">
              {spans.length === 0 ? '这次运行没有留下 span。' : '读取中…'}
            </p>
          ) : (
            <RunStrip
              rows={timeline.rows}
              playhead={playhead}
              selected={selectedSpanId}
              onPick={(span, offsetRatio) => {
                setPlaying(false)
                setSelectedSpanId(span.spanId)
                if (spanWindow !== null) setProgress(toProgress(spanWindow.begin + offsetRatio * spanWindow.total))
              }}
            />
          )}
        </div>
        <aside className="max-h-[380px] overflow-y-auto border-l border-line pl-3">
          {selectedSpan === undefined ? (
            <p className="text-xs text-ink-muted">点击时间线上的 span 跳到该处并查看详情。</p>
          ) : (
            <SpanDetail span={selectedSpan} />
          )}
        </aside>
      </section>
    </>
  )
}

/** 架构视图（主提示词 10.9 节）：五层两翼图加选中运行的按时间线回放 */
export function ArchitecturePage(): ReactNode {
  const { ready } = useStudioApi()
  const { token, user } = useIdentity()
  const [runId, setRunId] = useState<string | null>(null)

  const runsQuery = useQuery({
    queryKey: ['arch', user.id, 'runs'],
    queryFn: () => listRuns(token),
    enabled: ready,
  })
  const runs = runsQuery.data ?? []
  const run = runId === null ? runs[0] : (runs.find((r) => r.runId === runId) ?? runs[0])

  return (
    <div className="space-y-4">
      <SectionHeader
        title="架构视图"
        description="五层两翼：选中一次运行，按时间线回放点亮经过的组件，可暂停与单步"
        actions={<RunPicker runs={runs} run={run} onSelect={setRunId} />}
      />
      {runsQuery.error !== null && <ErrorLine error={runsQuery.error} />}
      {runsQuery.isLoading && <Loading />}
      {!runsQuery.isLoading && runsQuery.error === null && runs.length === 0 && (
        <Empty>还没有运行。先在统一入口问一个问题，或用演示控制台跳到故事起点。</Empty>
      )}
      {run !== undefined && <RunPlayback key={run.runId} run={run} />}
    </div>
  )
}
