import { useState, type FormEvent, type ReactNode } from 'react'
import { ArrowRightLeft, Search } from 'lucide-react'
import { DecisionBadge, cn, type DecisionOutcome } from '@eab/ui'
import { Button, Empty, ErrorLine, Loading, Pill, SectionHeader, shortTime } from './ui'
import { useHubQuery, useHubRole } from './use-hub'
import type { AuditEvent, CompareSide, Finding, IntegrationCompare } from './types'

const DECISIONS: readonly string[] = ['allow', 'deny', 'confirm', 'approve']
const BADGE_DECISIONS: readonly string[] = ['allow', 'deny', 'confirm']

function Decision({ value }: { value: string }): ReactNode {
  return BADGE_DECISIONS.includes(value) ? (
    <DecisionBadge outcome={value as DecisionOutcome} />
  ) : (
    <Pill tone="neutral">{value}</Pill>
  )
}

/** 审计：按条件查询、按业务对象反查“谁通过哪个 Agent 做了什么”、集成账号对照 */
export function AuditSection(): ReactNode {
  return (
    <section className="space-y-8" aria-label="审计">
      <SectionHeader
        title="审计"
        description="每次工具调用都记录了用户、Agent 和裁决；可以从一个业务对象反查到具体的运行"
      />
      <ReverseLookup />
      <IntegrationComparePanel />
      <RecentEvents />
    </section>
  )
}

function ReverseLookup(): ReactNode {
  const [input, setInput] = useState('PO-2026-0117')
  const [object, setObject] = useState<string>()
  const findings = useHubQuery<Finding[]>(
    `/hub/audit/reverse?object=${encodeURIComponent(object ?? '')}`,
    object !== undefined,
  )
  const submit = (e: FormEvent): void => {
    e.preventDefault()
    if (input.trim() !== '') setObject(input.trim())
  }
  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium">反查业务对象</h3>
      <form onSubmit={submit} className="flex max-w-md gap-2">
        <input
          aria-label="业务对象编号"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="例如 PO-2026-0117"
          className="min-w-0 flex-1 rounded-md border border-line bg-surface px-2.5 py-1.5 font-mono text-xs outline-none focus:border-line-strong"
        />
        <Button type="submit" variant="primary">
          <Search aria-hidden className="size-3.5" />
          反查
        </Button>
      </form>
      <ErrorLine error={findings.error} />
      {findings.isFetching && <Loading />}
      {findings.data !== undefined &&
        (findings.data.length === 0 ? (
          <Empty>没有涉及 {object} 的操作记录</Empty>
        ) : (
          <ol
            className="relative space-y-3 border-l border-line pl-5"
            data-testid="reverse-findings"
          >
            {findings.data.map((f, i) => (
              <li key={`${f.at}:${i}`} className="relative">
                <span
                  aria-hidden
                  className={cn(
                    'absolute top-1.5 -left-[25px] size-2.5 rounded-full ring-2 ring-canvas',
                    f.decision === 'deny' ? 'bg-deny' : 'bg-allow',
                  )}
                />
                <p className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="font-mono text-ink-faint">{shortTime(f.at)}</span>
                  <span className="font-medium">{f.userId}</span>
                  <span className="text-ink-faint">通过</span>
                  <span className="font-medium">{f.agent ?? '—'}</span>
                  <span className="text-ink-faint">调用</span>
                  <span className="font-mono">{f.operation ?? '—'}</span>
                  <Decision value={f.decision} />
                  {f.mode !== null && <Pill tone="neutral">{f.mode}</Pill>}
                </p>
                {f.reason !== null && <p className="mt-0.5 text-xs text-ink-muted">{f.reason}</p>}
                {f.runId !== null && (
                  <p className="mt-0.5 font-mono text-[10px] text-ink-faint">
                    运行 {f.runId}
                    {f.traceId !== null && ` · 追踪 ${f.traceId}`}
                  </p>
                )}
              </li>
            ))}
          </ol>
        ))}
    </div>
  )
}

function IntegrationComparePanel(): ReactNode {
  const { canWrite } = useHubRole()
  const [run, setRun] = useState(false)
  const compare = useHubQuery<IntegrationCompare>(
    '/hub/demo/integration-compare?user=zhangmin',
    run,
  )
  if (!canWrite) return null
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <h3 className="text-sm font-medium">集成账号对照</h3>
        <Button onClick={() => setRun(true)} disabled={compare.isFetching}>
          <ArrowRightLeft aria-hidden className="size-3.5" />
          以张敏的身份对照
        </Button>
      </div>
      <ErrorLine error={compare.error} />
      {compare.data !== undefined && (
        <div className="space-y-3" data-testid="integration-compare">
          <p className="text-xs text-ink-muted">问题：{compare.data.question}</p>
          <div className="grid gap-3 md:grid-cols-2">
            <CompareCard side={compare.data.bo} title="BO API · 用户身份透传" good />
            <CompareCard side={compare.data.open} title="Open API · 集成账号" good={false} />
          </div>
          <p className="text-xs text-ink-muted">{compare.data.explanation}</p>
        </div>
      )}
    </div>
  )
}

function CompareCard({
  side,
  title,
  good,
}: {
  side: CompareSide
  title: string
  good: boolean
}): ReactNode {
  return (
    <article
      className={cn(
        'rounded-xl border bg-surface p-4',
        good ? 'border-allow/30' : 'border-deny/30',
      )}
    >
      <p className="text-xs font-medium">{title}</p>
      <p className="mt-0.5 font-mono text-[10px] text-ink-faint">{side.identity}</p>
      <p className="mt-3 font-mono text-3xl font-semibold">{side.total}</p>
      <p className="text-[11px] text-ink-muted">{side.scope}</p>
      <ul className="mt-3 space-y-1 text-xs">
        {Object.entries(side.byOrg).map(([org, n]) => (
          <li key={org} className="flex justify-between">
            <span className="text-ink-muted">{org}</span>
            <span className="font-mono">{n}</span>
          </li>
        ))}
      </ul>
      <p className={cn('mt-3 text-[11px]', side.filtered ? 'text-allow' : 'text-deny')}>
        {side.filtered ? '按用户的数据范围过滤' : '没有按用户过滤，返回了全部数据'}
      </p>
    </article>
  )
}

function RecentEvents(): ReactNode {
  const [decision, setDecision] = useState('')
  const events = useHubQuery<AuditEvent[]>(
    `/audit/events?limit=50${decision === '' ? '' : `&decision=${decision}`}`,
  )
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <h3 className="text-sm font-medium">最近的审计事件</h3>
        <select
          aria-label="按裁决过滤"
          value={decision}
          onChange={(e) => setDecision(e.target.value)}
          className="rounded-md border border-line bg-surface px-2 py-1 text-xs"
        >
          <option value="">全部裁决</option>
          {DECISIONS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </div>
      <ErrorLine error={events.error} />
      {events.data !== undefined &&
        (events.data.length === 0 ? (
          <Empty>没有审计事件</Empty>
        ) : (
          <div className="max-h-96 overflow-auto rounded-xl border border-line bg-surface">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 border-b border-line bg-surface text-[11px] text-ink-faint">
                <tr>
                  <th className="px-3 py-2 font-medium">时间</th>
                  <th className="px-3 py-2 font-medium">动作</th>
                  <th className="px-3 py-2 font-medium">用户 / Agent</th>
                  <th className="px-3 py-2 font-medium">对象</th>
                  <th className="px-3 py-2 font-medium">裁决</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {events.data.map((e) => (
                  <tr key={e.id}>
                    <td className="px-3 py-1.5 font-mono text-ink-faint">{shortTime(e.at)}</td>
                    <td className="px-3 py-1.5 font-mono">{e.tool ?? e.action}</td>
                    <td className="px-3 py-1.5">
                      {e.userId ?? e.actor}
                      {e.agent !== null && (
                        <span className="block text-[10px] text-ink-faint">{e.agent}</span>
                      )}
                    </td>
                    <td
                      className="max-w-48 truncate px-3 py-1.5 text-ink-muted"
                      title={e.target ?? ''}
                    >
                      {e.target ?? '—'}
                    </td>
                    <td className="px-3 py-1.5" title={e.reason ?? ''}>
                      <Decision value={e.decision} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
    </div>
  )
}
