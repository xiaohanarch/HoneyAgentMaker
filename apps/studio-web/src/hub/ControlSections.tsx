import type { ReactNode } from 'react'
import { Check, KeyRound, Rocket, Siren, X } from 'lucide-react'
import { cn } from '@eab/ui'
import { Button, Empty, ErrorLine, Loading, Pill, SectionHeader, shortTime, toneOf } from './ui'
import { useHubAction, useHubQuery, useHubRole } from './use-hub'
import type { AdmissionItem, Alert, Delegation } from './types'

/** 上架准入：租户资产晋级到 prod 前的检查项，全部通过后由管理员批准 */
export function AdmissionSection(): ReactNode {
  const items = useHubQuery<AdmissionItem[]>('/hub/admission')
  const { canWrite } = useHubRole()
  const approve = useHubAction<unknown, AdmissionItem>(
    'POST',
    () => '/hub/admission/approve',
    (i) => ({ ref: i.ref, version: i.version }),
  )
  return (
    <section className="space-y-5" aria-label="上架准入">
      <SectionHeader
        title="上架准入"
        description="尚未进入 prod 的租户 Agent 与扩展；评测门禁、写级别、MCP 审批与完整性全部通过才能批准"
      />
      <ErrorLine error={items.error ?? approve.error} />
      {items.isPending ? (
        <Loading />
      ) : (items.data ?? []).length === 0 ? (
        <Empty>没有待上架的资产</Empty>
      ) : (
        <ul className="space-y-3">
          {(items.data ?? []).map((i) => (
            <li
              key={`${i.ref}@${i.version}`}
              className="rounded-xl border border-line border-l-4 border-l-tenant bg-surface p-4"
            >
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-sm font-medium">{i.name}</p>
                <span className="font-mono text-[11px] text-ink-faint">
                  {i.ref}@{i.version}
                </span>
                <Pill tone={i.ready ? 'allow' : 'pending'}>{i.ready ? '可以上架' : '未满足'}</Pill>
                <span className="ml-auto text-[11px] text-ink-muted">
                  {Object.entries(i.environments)
                    .map(([env, v]) => `${env} ${v}`)
                    .join(' · ') || '尚未部署'}
                </span>
              </div>
              <ul className="mt-3 grid gap-2 md:grid-cols-2">
                {i.checks.map((c) => (
                  <li key={c.name} className="flex gap-2 text-xs">
                    {c.passed ? (
                      <Check aria-label="通过" className="mt-0.5 size-3.5 shrink-0 text-allow" />
                    ) : (
                      <X aria-label="未通过" className="mt-0.5 size-3.5 shrink-0 text-deny" />
                    )}
                    <span>
                      <span className="font-medium">{c.name}</span>
                      <span className="block text-ink-muted">{c.detail}</span>
                    </span>
                  </li>
                ))}
              </ul>
              {canWrite && (
                <div className="mt-3 flex justify-end">
                  <Button
                    variant="primary"
                    disabled={!i.ready || approve.isPending}
                    onClick={() => approve.mutate(i)}
                  >
                    <Rocket aria-hidden className="size-3.5" />
                    批准上架到 prod
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** 委托管理：受托自主运行（事件触发、定时）所依据的委托，撤销后对应 Agent 不能再以委托人身份运行 */
export function DelegationSection(): ReactNode {
  const list = useHubQuery<Delegation[]>('/identity/v1/delegations')
  const { canWrite } = useHubRole()
  const revoke = useHubAction<unknown, string>('DELETE', (id) => `/identity/v1/delegations/${id}`)
  return (
    <section className="space-y-5" aria-label="委托管理">
      <SectionHeader
        title="委托管理"
        description="事件触发和定时运行的 Agent 以委托人的身份、在委托范围内访问 ERP；撤销立即生效"
      />
      <ErrorLine error={list.error ?? revoke.error} />
      {list.isPending ? (
        <Loading />
      ) : (list.data ?? []).length === 0 ? (
        <Empty>没有委托</Empty>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
          {(list.data ?? []).map((d) => {
            const revoked = d.revokedAt !== null
            return (
              <li
                key={d.id}
                className={cn(
                  'flex flex-wrap items-center gap-3 px-4 py-3',
                  revoked && 'opacity-60',
                )}
              >
                <KeyRound aria-hidden className="size-4 text-ink-muted" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm">
                    <span className="font-mono text-xs text-ink-faint">{d.id}</span>{' '}
                    <span className="font-medium">{d.delegatorUserId}</span>
                    <span className="text-ink-muted"> 委托 </span>
                    <span className="font-medium">{d.agent}</span>
                  </p>
                  <p className="mt-0.5 text-[11px] text-ink-muted">
                    范围：{d.scope.join('、')} · 有效期至 {shortTime(d.validUntil)}
                    {d.note !== null && ` · ${d.note}`}
                  </p>
                </div>
                {revoked ? (
                  <Pill tone="deny">已于 {shortTime(d.revokedAt)} 撤销</Pill>
                ) : (
                  canWrite && (
                    <Button
                      variant="danger"
                      disabled={revoke.isPending}
                      onClick={() => revoke.mutate(d.id)}
                      label={`撤销委托 ${d.id}`}
                    >
                      撤销
                    </Button>
                  )
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

/** 风险告警：监督层发现的异常行为（循环调用、越权尝试、成本超限等） */
export function AlertSection(): ReactNode {
  const alerts = useHubQuery<Alert[]>('/runtime/alerts')
  return (
    <section className="space-y-5" aria-label="风险告警">
      <SectionHeader title="风险告警" description="监督层在运行中发现的异常行为，按时间倒序" />
      <ErrorLine error={alerts.error} />
      {alerts.isPending ? (
        <Loading />
      ) : (alerts.data ?? []).length === 0 ? (
        <Empty>没有告警</Empty>
      ) : (
        <ul className="space-y-2">
          {(alerts.data ?? []).map((a) => (
            <li
              key={a.id}
              className="flex gap-3 rounded-lg border border-line bg-surface px-4 py-3"
            >
              <Siren
                aria-hidden
                className={cn(
                  'mt-0.5 size-4 shrink-0',
                  a.severity === 'high' ? 'text-deny' : 'text-pending',
                )}
              />
              <div className="min-w-0 flex-1 text-xs">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{a.message}</span>
                  <Pill tone={toneOf(a.severity)}>{a.severity}</Pill>
                  <Pill tone="neutral">{a.type}</Pill>
                </p>
                <p className="mt-0.5 text-ink-muted">
                  {shortTime(a.at)} · {a.userId ?? '—'} · {a.agent ?? '—'}
                  {a.runId !== null && <span className="font-mono"> · {a.runId}</span>}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
