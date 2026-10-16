import { useState, type ReactNode } from 'react'
import { FlaskConical, PackageCheck, Play, ShieldAlert, ShieldCheck, Upload } from 'lucide-react'
import { cn } from '@eab/ui'
import { percent } from './geometry'
import { Button, Empty, ErrorLine, Loading, Pill, SectionHeader, shortTime, toneOf } from './ui'
import { useHubAction, useHubQuery, useHubRole } from './use-hub'
import type {
  Inventory,
  EvalSuite,
  Regression,
  SolutionEntry,
  SolutionPackage,
  SuiteRun,
  Upgrades,
} from './types'

/** 升级回归：厂商发布标品新版本后，用各租户扩展的评测集自动回归 */
export function UpgradeSection(): ReactNode {
  const data = useHubQuery<Upgrades>('/hub/upgrades')
  const { canWrite } = useHubRole()
  const simulate = useHubAction<Regression[]>('POST', () => '/hub/demo/upgrade')
  const preset = data.data?.presets[0]
  return (
    <section className="space-y-5" aria-label="升级回归">
      <SectionHeader
        title="升级回归"
        description="标品升级时，平台用依赖它的租户扩展的评测集先跑基线、再跑新版本；回归失败会通知租户管理员和扩展负责人"
        actions={
          canWrite &&
          preset !== undefined && (
            <Button onClick={() => simulate.mutate()} disabled={simulate.isPending}>
              <Upload aria-hidden className="size-3.5" />
              {simulate.isPending ? '回归中…' : `模拟发布 ${preset.toVersion}`}
            </Button>
          )
        }
      />
      {preset !== undefined && (
        <p className="text-xs text-ink-muted">
          演示升级：<span className="font-mono">{preset.agent}</span> {preset.fromVersion} →{' '}
          {preset.toVersion}，{preset.summary}
        </p>
      )}
      <ErrorLine error={data.error ?? simulate.error} />
      {data.isPending ? (
        <Loading />
      ) : (data.data?.regressions ?? []).length === 0 ? (
        <Empty>还没有升级回归记录</Empty>
      ) : (
        <ul className="space-y-3">
          {(data.data?.regressions ?? []).map((r) => (
            <RegressionCard key={r.id} regression={r} />
          ))}
        </ul>
      )}
    </section>
  )
}

function RegressionCard({ regression: r }: { regression: Regression }): ReactNode {
  const failed = r.status === 'failed'
  return (
    <li
      className={cn(
        'rounded-xl border border-l-4 bg-surface p-4',
        failed ? 'border-line border-l-deny' : 'border-line border-l-allow',
      )}
      data-testid="regression"
    >
      <div className="flex flex-wrap items-center gap-2">
        {failed ? (
          <ShieldAlert aria-hidden className="size-4 text-deny" />
        ) : (
          <ShieldCheck aria-hidden className="size-4 text-allow" />
        )}
        <p className="text-sm font-medium">
          {r.agent} {r.fromVersion} → {r.toVersion}
        </p>
        <Pill tone={toneOf(r.status)}>{failed ? '回归未通过' : '回归通过'}</Pill>
        <span className="ml-auto font-mono text-[11px] text-ink-faint">{shortTime(r.at)}</span>
      </div>
      <p className="mt-1 text-xs text-ink-muted">
        扩展 <span className="font-mono">{r.extension}</span> · 评测集{' '}
        <span className="font-mono">{r.suite}</span>
      </p>
      <div className="mt-3 grid grid-cols-2 gap-3 text-xs sm:max-w-sm">
        <Score label="升级前" summary={r.baseline} />
        <Score label="升级后" summary={r.after} />
      </div>
      {r.regressions.length > 0 && (
        <p className="mt-3 text-xs">
          <span className="text-deny">回归的用例：</span>
          <span className="font-mono">{r.regressions.join('、')}</span>
        </p>
      )}
      {r.issues.length > 0 && (
        <ul className="mt-2 space-y-1 text-xs text-ink-muted">
          {r.issues.map((i) => (
            <li key={i} className="font-mono text-[11px]">
              {i}
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

function Score({ label, summary }: { label: string; summary: Regression['baseline'] }): ReactNode {
  return (
    <div className="rounded-md bg-canvas px-3 py-2">
      <p className="text-[11px] text-ink-faint">{label}</p>
      <p className="font-mono text-base font-semibold">
        {summary === null ? '—' : `${summary.passed}/${summary.total}`}
      </p>
    </div>
  )
}

/** 方案包：把扩展及其依赖的租户资产编译成带清单、哈希、签名的包，按 dev → test → prod 部署 */
export function SolutionSection(): ReactNode {
  const list = useHubQuery<SolutionEntry[]>('/hub/solutions')
  const inventory = useHubQuery<Inventory>('/hub/inventory')
  const { canWrite } = useHubRole()
  // 方案以本租户对交易异常解析 Agent 的扩展为根：故事 B 在扩展编辑器里发布的、或演示完成态快照里的都可以
  const root =
    inventory.data?.edges.find(
      (e) => e.from.startsWith('tenant.') && e.to === 'vendor.fin/transaction-anomaly-analyst',
    )?.from ?? 'tenant.t001.finance/anomaly-analyst-ext'
  const compile = useHubAction<SolutionPackage>(
    'POST',
    () => '/hub/solutions/compile',
    () => ({ name: 'finance-extension', root }),
  )
  const deploy = useHubAction<unknown, { id: string; environment: string }>(
    'POST',
    (v) => `/hub/solutions/packages/${v.id}/deploy`,
    (v) => ({ environment: v.environment }),
  )
  return (
    <section className="space-y-5" aria-label="方案包">
      <SectionHeader
        title="方案包"
        description="部署前校验签名和清单哈希；test、prod 要求上一环境当前是同一个包"
        actions={
          canWrite && (
            <Button onClick={() => compile.mutate()} disabled={compile.isPending}>
              <PackageCheck aria-hidden className="size-3.5" />
              从财务异常扩展编译方案包
            </Button>
          )
        }
      />
      <ErrorLine error={list.error ?? compile.error ?? deploy.error} />
      {list.isPending ? (
        <Loading />
      ) : (list.data ?? []).length === 0 ? (
        <Empty>还没有方案包</Empty>
      ) : (
        <ul className="space-y-3">
          {(list.data ?? []).map(({ package: p, verified, environments }) => (
            <li key={p.id} className="rounded-xl border border-line bg-surface p-4">
              <div className="flex flex-wrap items-center gap-2">
                <p className="text-sm font-medium">
                  {p.solution} <span className="font-mono text-xs text-ink-muted">{p.version}</span>
                </p>
                <Pill tone={verified ? 'allow' : 'deny'}>
                  {verified ? '签名有效' : '签名校验失败'}
                </Pill>
                <span
                  className="ml-auto font-mono text-[10px] text-ink-faint"
                  title={p.manifestHash}
                >
                  {p.manifestHash.slice(0, 16)}…
                </span>
              </div>
              <div className="mt-3 grid gap-3 text-xs md:grid-cols-3">
                <ManifestList
                  title="包含资产"
                  items={p.manifest.assets.map((a) => `${a.ref}@${a.version}`)}
                />
                <ManifestList
                  title="依赖标品"
                  items={p.manifest.requires.map((a) => `${a.ref}@${a.version}`)}
                />
                <ManifestList title="连接别名" items={p.manifest.connections} />
              </div>
              <ol className="mt-4 flex flex-wrap items-center gap-2">
                {['dev', 'test', 'prod'].map((env, i) => (
                  <li key={env} className="flex items-center gap-2">
                    {i > 0 && <span aria-hidden className="h-px w-5 bg-line-strong" />}
                    {environments[env] !== undefined ? (
                      <Pill tone="allow">
                        {env} · {shortTime(environments[env])}
                      </Pill>
                    ) : canWrite ? (
                      <Button
                        disabled={deploy.isPending}
                        onClick={() => deploy.mutate({ id: p.id, environment: env })}
                      >
                        部署到 {env}
                      </Button>
                    ) : (
                      <Pill tone="neutral">{env}</Pill>
                    )}
                  </li>
                ))}
              </ol>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ManifestList({ title, items }: { title: string; items: readonly string[] }): ReactNode {
  return (
    <div>
      <p className="text-[11px] text-ink-faint">{title}</p>
      {items.length === 0 ? (
        <p className="text-ink-faint">—</p>
      ) : (
        <ul className="mt-1 space-y-0.5 font-mono text-[11px]">
          {items.map((i) => (
            <li key={i} className="truncate" title={i}>
              {i}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** 评测：本租户可见的评测集（含标品公开评测集），可以手动运行 */
export function EvalSection(): ReactNode {
  const suites = useHubQuery<EvalSuite[]>('/hub/evals/suites')
  const run = useHubAction<SuiteRun, string>(
    'POST',
    () => '/hub/evals/run',
    (suite) => ({ suite }),
  )
  const [running, setRunning] = useState<string>()
  return (
    <section className="space-y-5" aria-label="评测">
      <SectionHeader
        title="评测"
        description="冒烟集在每次发布前运行，完整集按需运行；场景式用例会重复多次，按最低通过率判定"
      />
      <ErrorLine error={suites.error ?? run.error} />
      {suites.isPending ? (
        <Loading />
      ) : (suites.data ?? []).length === 0 ? (
        <Empty>没有评测集</Empty>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
          {(suites.data ?? []).map((s) => (
            <li key={s.ref} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <FlaskConical aria-hidden className="size-4 text-ink-muted" />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{s.name}</p>
                <p className="font-mono text-[10px] text-ink-faint">
                  {s.ref}@{s.version} → {s.target}
                </p>
              </div>
              <Pill tone="neutral">{s.tier}</Pill>
              <span className="text-[11px] text-ink-muted">
                {s.cases} 个用例 · 最低 {percent(s.minPassRate)}
              </span>
              <Button
                disabled={run.isPending}
                onClick={() => {
                  setRunning(s.ref)
                  run.mutate(s.ref)
                }}
              >
                <Play aria-hidden className="size-3.5" />
                {run.isPending && running === s.ref ? '运行中…' : '运行'}
              </Button>
            </li>
          ))}
        </ul>
      )}
      {run.data !== undefined && <SuiteResult run={run.data} />}
    </section>
  )
}

function SuiteResult({ run }: { run: SuiteRun }): ReactNode {
  const r = run.result
  return (
    <div className="rounded-xl border border-line bg-surface p-4" data-testid="suite-result">
      <p className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-medium">{run.suite}</span>
        <Pill tone={toneOf(r.status)}>{r.status === 'passed' ? '通过' : '未通过'}</Pill>
        <span className="text-xs text-ink-muted">
          {r.passed}/{r.total}（{percent(r.passRate)}，要求 {percent(r.minPassRate)}）· {r.millis}{' '}
          ms
        </span>
      </p>
      <ul className="mt-3 space-y-2">
        {r.cases.map((c) => (
          <li key={c.id} className="text-xs">
            <p className="flex items-center gap-2">
              <span className={cn('size-2 rounded-full', c.passed ? 'bg-allow' : 'bg-deny')} />
              <span className="font-mono">{c.id}</span>
              <span className="text-ink-muted">
                {c.user}：{c.question}
              </span>
            </p>
            {c.assertions
              .filter((a) => !a.passed)
              .map((a) => (
                <p key={`${a.type}:${a.description}`} className="ml-4 text-deny">
                  {a.description}：{a.detail}
                </p>
              ))}
          </li>
        ))}
      </ul>
    </div>
  )
}
