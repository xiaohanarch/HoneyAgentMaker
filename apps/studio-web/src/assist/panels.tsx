import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Filter, Route, Waypoints } from 'lucide-react'
import { cn } from '@eab/ui'
import { getJson } from './api'
import type { Routing, RoutingLevel } from './turn'
import { ExchangeLog } from '../studio/external/ExchangeLog'
import type { Exchange } from '../studio/external/api'

function Panel({
  icon: Icon,
  title,
  children,
  testId,
}: {
  icon: typeof Route
  title: string
  children: ReactNode
  testId: string
}): ReactNode {
  return (
    <section className="rounded-xl border border-line bg-surface" data-testid={testId}>
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <Icon aria-hidden className="size-4 text-ink-muted" />
        {title}
      </header>
      <div className="px-4 py-3 text-xs leading-relaxed">{children}</div>
    </section>
  )
}

const LEVEL_NAMES: Record<number, string> = { 1: '第一级 · 岗位助手', 2: '第二级 · Agent 与导航' }
const OUTCOME_LABELS: Record<string, string> = {
  route: '路由',
  clarify: '请用户澄清',
  gap: '能力缺口',
}

function LevelView({ level }: { level: RoutingLevel }): ReactNode {
  const chosen = level.decision?.ref
  return (
    <div className="space-y-1.5">
      <p className="flex items-center justify-between text-[11px] font-medium text-ink-faint">
        {LEVEL_NAMES[level.level] ?? `第 ${level.level} 级`}
        <span>{OUTCOME_LABELS[level.outcome] ?? level.outcome}</span>
      </p>
      <ul className="space-y-1">
        {level.candidates.map((c) => (
          <li
            key={c.ref}
            className={cn(
              'rounded-md border px-2 py-1.5',
              c.ref === chosen ? 'border-allow/40 bg-allow-soft/50' : 'border-line',
              c.filtered !== undefined && 'opacity-70',
            )}
          >
            <span className="flex items-center gap-2">
              <span className="min-w-0 flex-1 truncate font-medium">{c.name}</span>
              {c.kind === 'remote' && (
                <span className="rounded bg-external-soft px-1 text-[10px] text-external">
                  外部 Agent · {c.action ?? 'agent-request'}
                </span>
              )}
              <span className="font-mono text-ink-faint">{c.score.toFixed(2)}</span>
            </span>
            {c.matched !== '' && <span className="block text-ink-faint">匹配：{c.matched}</span>}
            {c.filtered !== undefined && (
              <span className="mt-0.5 flex items-start gap-1 text-deny">
                <Filter aria-hidden className="mt-0.5 size-3 shrink-0" />
                {c.filtered}
              </span>
            )}
          </li>
        ))}
      </ul>
      {level.decision !== undefined && (
        <p className="text-ink-muted">
          选择 <span className="font-medium text-ink">{level.decision.name}</span>，置信度{' '}
          {level.decision.confidence.toFixed(2)}（
          {level.decision.decidedBy === 'model'
            ? '模型决策'
            : level.decision.decidedBy === 'user'
              ? '用户选择'
              : level.decision.decidedBy === 'session'
                ? '续接上一轮'
                : '按召回分数'}
          ）：
          {level.decision.reason}
        </p>
      )}
    </div>
  )
}

/** 路由说明：每一级召回的候选、分数、被权限过滤的原因，以及最终选择的理由 */
export function RoutingPanel({ routing }: { routing: Routing | undefined }): ReactNode {
  return (
    <Panel icon={Route} title="路由说明" testId="routing-panel">
      {routing === undefined ? (
        <p className="text-ink-muted">
          每次提问后在这里展开：召回的候选岗位助手和
          Agent、相似度分数、权限过滤的原因，以及选择的理由与置信度。
        </p>
      ) : (
        <div className="space-y-3">
          {routing.levels.map((l) => (
            <LevelView key={l.level} level={l} />
          ))}
        </div>
      )}
    </Panel>
  )
}

interface Span {
  spanId: string
  parentSpanId: string | null
  name: string
  kind: string
  status: string
  startTime: string
  endTime: string | null
  statusMessage: string | null
  attributes?: Record<string, unknown>
}

/** A2A span 记录的请求与响应原文（JSON 字符串），解析失败时不展示 */
function a2aExchanges(span: Span): Exchange[] {
  const raw = span.attributes?.['eab.a2a.exchanges']
  if (typeof raw !== 'string') return []
  try {
    return JSON.parse(raw) as Exchange[]
  } catch {
    return []
  }
}

const KIND_STYLES: Record<string, string> = {
  run: 'bg-ink',
  routing: 'bg-info',
  assemble: 'bg-vendor',
  pipeline: 'bg-ink-faint',
  model: 'bg-external',
  policy: 'bg-allow',
  tool: 'bg-tenant',
  skill: 'bg-tenant',
  guard: 'bg-pending',
  confirmation: 'bg-pending',
  hook: 'bg-platform',
  memory: 'bg-ink-faint',
}

function durationOf(span: Span): string {
  if (span.endTime === null) return ''
  const ms = Date.parse(span.endTime) - Date.parse(span.startTime)
  return Number.isFinite(ms) ? `${ms} ms` : ''
}

function flatten(spans: readonly Span[]): { span: Span; depth: number }[] {
  const ids = new Set(spans.map((s) => s.spanId))
  const children = new Map<string, Span[]>()
  const roots: Span[] = []
  for (const s of spans) {
    if (s.parentSpanId === null || !ids.has(s.parentSpanId)) roots.push(s)
    else children.set(s.parentSpanId, [...(children.get(s.parentSpanId) ?? []), s])
  }
  const result: { span: Span; depth: number }[] = []
  const visit = (span: Span, depth: number): void => {
    result.push({ span, depth })
    const sorted = [...(children.get(span.spanId) ?? [])].sort((a, b) =>
      a.startTime.localeCompare(b.startTime),
    )
    sorted.forEach((c) => visit(c, depth + 1))
  }
  roots.forEach((r) => visit(r, 0))
  return result
}

/** 本次链路：从路由到工具返回的 span 树，租户视角下标品指令已由平台裁剪 */
export function TracePanel({
  traceId,
  token,
  revision,
}: {
  traceId: string | undefined
  token: string | undefined
  revision: number
}): ReactNode {
  const { data, error } = useQuery({
    queryKey: ['trace', traceId, revision],
    queryFn: () =>
      getJson<{ spans: Span[] }>(`/traces/${encodeURIComponent(traceId ?? '')}`, token),
    enabled: traceId !== undefined && traceId !== '' && token !== undefined,
  })
  return (
    <Panel icon={Waypoints} title="本次链路" testId="trace-panel">
      {traceId === undefined ? (
        <p className="text-ink-muted">
          提问后显示完整的 span 树：路由、组装、模型调用、工具调用、鉴权、守卫和人工确认。
        </p>
      ) : error !== null ? (
        <p className="text-deny">追踪读取失败：{error.message}</p>
      ) : data === undefined ? (
        <p className="text-ink-muted">读取中…</p>
      ) : (
        <ol className="max-h-[420px] space-y-0.5 overflow-y-auto">
          {flatten(data.spans).map(({ span, depth }) => (
            <li
              key={span.spanId}
              className="flex flex-wrap items-center gap-1.5"
              style={{ paddingLeft: `${Math.min(depth, 6) * 10}px` }}
              title={span.statusMessage ?? undefined}
            >
              <span
                aria-hidden
                className={cn(
                  'size-1.5 shrink-0 rounded-full',
                  KIND_STYLES[span.kind] ?? 'bg-ink-faint',
                )}
              />
              <span className="w-16 shrink-0 font-mono text-[10px] text-ink-faint">
                {span.kind}
              </span>
              <span
                className={cn('min-w-0 flex-1 truncate', span.status === 'error' && 'text-deny')}
              >
                {span.name}
              </span>
              <span className="shrink-0 font-mono text-[10px] text-ink-faint">
                {durationOf(span)}
              </span>
              {a2aExchanges(span).length > 0 && (
                <details className="basis-full" data-testid="a2a-raw">
                  <summary className="cursor-pointer text-[10px] text-external">
                    A2A 请求与响应原文
                  </summary>
                  <ExchangeLog title="A2A 原文" exchanges={a2aExchanges(span)} />
                </details>
              )}
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}
