import type { ReactNode } from 'react'
import { ArrowDownRight, ArrowUpRight, History } from 'lucide-react'
import { cn } from '@eab/ui'
import { RunBars, Sparkline } from './charts'
import { duration, percent, round, trend } from './geometry'
import { Button, Empty, ErrorLine, Loading, SectionHeader } from './ui'
import { useHubAction, useHubQuery, useHubRole } from './use-hub'
import type { AssistantKpis, MetricsOverview, Series } from './types'

/** 运行指标：技术指标（每个 Agent）、流程指标、业务结果三层，以及岗位助手 KPI */
export function MetricsSection(): ReactNode {
  const metrics = useHubQuery<MetricsOverview>('/hub/metrics?days=30')
  const { canWrite } = useHubRole()
  const history = useHubAction<{ runs: number; metricPoints: number }>(
    'POST',
    () => '/hub/demo/history',
  )
  const data = metrics.data
  const totals = data?.daily.reduce(
    (acc, d) => ({
      runs: acc.runs + d.runs,
      failed: acc.failed + d.failed,
      cost: acc.cost + d.costUsd,
    }),
    { runs: 0, failed: 0, cost: 0 },
  )

  return (
    <section className="space-y-6" aria-label="运行指标">
      <SectionHeader
        title="运行指标"
        description="技术指标看 Agent 是否稳定，流程指标看业务环节是否变快，业务结果看最终收益"
        actions={
          canWrite && (
            <Button onClick={() => history.mutate()} disabled={history.isPending}>
              <History aria-hidden className="size-3.5" />
              生成 30 天演示数据
            </Button>
          )
        }
      />
      <ErrorLine error={metrics.error ?? history.error} />
      {history.data !== undefined && (
        <p className="text-xs text-ink-muted">
          已生成 {history.data.runs} 次模拟运行、{history.data.metricPoints} 个指标数据点
        </p>
      )}
      {metrics.isPending ? (
        <Loading />
      ) : data === undefined || data.agents.length === 0 ? (
        <Empty>还没有运行记录。可以先在统一入口提几个问题，或生成演示数据。</Empty>
      ) : (
        <>
          <div className="grid gap-4 rounded-xl border border-line bg-surface p-5 md:grid-cols-[14rem_minmax(0,1fr)]">
            <dl className="grid grid-cols-3 gap-4 md:grid-cols-1">
              <Figure label={`近 ${data.days} 天运行`} value={String(totals?.runs ?? 0)} />
              <Figure
                label="失败率"
                value={percent(
                  totals !== undefined && totals.runs > 0 ? totals.failed / totals.runs : 0,
                )}
              />
              <Figure label="模型费用" value={`$${round(totals?.cost ?? 0)}`} />
            </dl>
            <div>
              <RunBars days={data.daily} />
              <div className="mt-1 flex justify-between font-mono text-[10px] text-ink-faint">
                <span>{data.daily[0]?.day}</span>
                <span>{data.daily[data.daily.length - 1]?.day}</span>
              </div>
            </div>
          </div>

          <AgentTable data={data} />

          <div className="grid gap-4 lg:grid-cols-2">
            <SeriesGroup title="流程指标" series={data.process} tone="text-info" />
            <SeriesGroup title="业务结果" series={data.business} tone="text-allow" />
          </div>

          {data.assistants.length > 0 && <AssistantKpiCards assistants={data.assistants} />}
        </>
      )}
    </section>
  )
}

function Figure({ label, value }: { label: string; value: string }): ReactNode {
  return (
    <div>
      <dt className="text-[11px] text-ink-faint">{label}</dt>
      <dd className="mt-0.5 font-mono text-2xl font-semibold tracking-tight">{value}</dd>
    </div>
  )
}

function AgentTable({ data }: { data: MetricsOverview }): ReactNode {
  return (
    <div className="overflow-x-auto rounded-xl border border-line bg-surface">
      <table className="w-full text-left text-xs">
        <caption className="px-4 pt-3 text-left text-sm font-medium">技术指标</caption>
        <thead className="border-b border-line text-[11px] text-ink-faint">
          <tr>
            <th className="px-4 py-2 font-medium">Agent</th>
            <th className="px-3 py-2 text-right font-medium">运行</th>
            <th className="px-3 py-2 text-right font-medium">完成率</th>
            <th className="px-3 py-2 text-right font-medium">工具正确率</th>
            <th className="px-3 py-2 text-right font-medium">人工介入</th>
            <th className="px-3 py-2 text-right font-medium">失败追踪</th>
            <th className="px-3 py-2 text-right font-medium">P50 / P95</th>
            <th className="px-4 py-2 text-right font-medium">费用</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line font-mono">
          {data.agents.map((a) => (
            <tr key={a.agent}>
              <td className="px-4 py-2 font-sans">
                <span className="block font-medium">{a.name}</span>
                <span className="block text-[10px] text-ink-faint">{a.agent}</span>
              </td>
              <td className="px-3 py-2 text-right">{a.runs}</td>
              <td className={cn('px-3 py-2 text-right', a.completionRate < 0.9 && 'text-deny')}>
                {percent(a.completionRate)}
              </td>
              <td className="px-3 py-2 text-right">{percent(a.toolCorrectness)}</td>
              <td className="px-3 py-2 text-right">{percent(a.interventionRate)}</td>
              <td className="px-3 py-2 text-right">{a.failedTraces}</td>
              <td className="px-3 py-2 text-right text-ink-muted">
                {duration(a.p50Ms)} / {duration(a.p95Ms)}
              </td>
              <td className="px-4 py-2 text-right">${round(a.costUsd)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SeriesGroup({
  title,
  series,
  tone,
}: {
  title: string
  series: readonly Series[]
  tone: string
}): ReactNode {
  return (
    <div className="rounded-xl border border-line bg-surface p-4">
      <h3 className="text-sm font-medium">{title}</h3>
      {series.length === 0 ? (
        <p className="mt-3 text-xs text-ink-faint">暂无数据</p>
      ) : (
        <ul className="mt-3 grid gap-4 sm:grid-cols-2">
          {series.map((s) => {
            const first = s.points[0]?.value
            const change =
              first !== undefined && s.latest !== null ? round(s.latest - first, 1) : undefined
            return (
              <li key={`${s.metric}:${s.agent ?? ''}`}>
                <p className="text-xs text-ink-muted">{s.label}</p>
                <p className="mt-0.5 flex items-baseline gap-1.5">
                  <span className="font-mono text-lg font-semibold">
                    {s.latest === null ? '—' : round(s.latest, 1)}
                  </span>
                  <span className="text-[11px] text-ink-faint">{s.unit}</span>
                  {change !== undefined && change !== 0 && (
                    <span className="font-mono text-[11px] text-ink-faint">
                      {change > 0 ? `+${change}` : change}
                    </span>
                  )}
                </p>
                <Sparkline
                  className={tone}
                  label={`${s.label}趋势`}
                  values={s.points.map((p) => ({ label: p.day, value: p.value }))}
                />
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

function AssistantKpiCards({ assistants }: { assistants: readonly AssistantKpis[] }): ReactNode {
  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium">岗位助手 KPI</h3>
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {assistants.map((a) => (
          <article key={a.ref} className="rounded-xl border border-line bg-surface p-4">
            <p className="text-sm font-medium">{a.name}</p>
            <p className="font-mono text-[10px] text-ink-faint">{a.ref}</p>
            <ul className="mt-3 space-y-2.5">
              {a.kpis.map((k) => {
                const t = trend(k.value, k.previous, k.direction)
                const reached =
                  k.value !== null &&
                  k.target !== null &&
                  (k.direction === 'lower' ? k.value <= k.target : k.value >= k.target)
                return (
                  <li key={k.name} className="flex items-center gap-2 text-xs">
                    <span className="min-w-0 flex-1 truncate text-ink-muted">{k.displayName}</span>
                    <span className="font-mono font-semibold">
                      {k.value === null ? '—' : round(k.value, 1)}
                      <span className="ml-0.5 font-sans text-[10px] font-normal text-ink-faint">
                        {k.unit}
                      </span>
                    </span>
                    {t !== undefined && (
                      <span
                        className={cn(
                          'inline-flex items-center font-mono text-[10px]',
                          t.good ? 'text-allow' : 'text-deny',
                        )}
                      >
                        {t.delta >= 0 ? (
                          <ArrowUpRight aria-hidden className="size-3" />
                        ) : (
                          <ArrowDownRight aria-hidden className="size-3" />
                        )}
                        {Math.abs(t.delta)}
                      </span>
                    )}
                    {k.target !== null && (
                      <span
                        className={cn(
                          'rounded px-1 text-[10px]',
                          reached ? 'bg-allow-soft text-allow' : 'bg-surface-sunken text-ink-faint',
                        )}
                      >
                        目标 {k.target}
                      </span>
                    )}
                  </li>
                )
              })}
            </ul>
          </article>
        ))}
      </div>
    </div>
  )
}
