import { useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRight,
  CircleCheck,
  CircleX,
  History,
  ListChecks,
  LoaderCircle,
  Rocket,
  Undo2,
} from 'lucide-react'
import { cn } from '@eab/ui'
import { ApiError } from '../../assist/api'
import { ENVIRONMENTS, type DraftView, type Environment, type Gate, type LintIssue } from '../api'
import { identityOf } from '../draft-values'
import { errorText, useStudioApi } from '../use-studio'

const ENV_LABELS: Record<Environment, string> = {
  dev: '开发 dev',
  test: '测试 test',
  prod: '生产 prod',
}

/** 平台门禁结果在 409 的 error.details 里 */
export function gatesOf(error: unknown): Gate[] | null {
  if (!(error instanceof ApiError) || !Array.isArray(error.details)) return null
  return error.details.filter(
    (g): g is Gate =>
      typeof g === 'object' && g !== null && 'name' in g && 'passed' in g && 'detail' in g,
  )
}

function Gates({ gates }: { gates: readonly Gate[] }): ReactNode {
  return (
    <ul className="space-y-0.5 text-xs" data-testid="gates">
      {gates.map((g) => (
        <li key={g.name} className={cn('flex gap-1.5', g.passed ? 'text-allow' : 'text-deny')}>
          {g.passed ? (
            <CircleCheck aria-hidden className="mt-0.5 size-3 shrink-0" />
          ) : (
            <CircleX aria-hidden className="mt-0.5 size-3 shrink-0" />
          )}
          <span>
            <span className="font-medium">{g.name}</span>：{g.detail}
          </span>
        </li>
      ))}
    </ul>
  )
}

function LintPanel({ issues }: { issues: readonly LintIssue[] }): ReactNode {
  const groups = [
    { severity: 'error' as const, label: '错误', className: 'text-deny' },
    { severity: 'warning' as const, label: '警告', className: 'text-pending' },
  ]
  if (issues.length === 0) return <p className="text-xs text-allow">静态检查通过，没有问题。</p>
  return (
    <div className="space-y-2" data-testid="lint-issues">
      {groups.map(({ severity, label, className }) => {
        const items = issues.filter((i) => i.severity === severity)
        if (items.length === 0) return null
        return (
          <div key={severity}>
            <p className={cn('text-xs font-medium', className)}>{`${label}（${items.length}）`}</p>
            <ul className="mt-0.5 space-y-0.5 text-xs text-ink-muted">
              {items.map((i, n) => (
                <li key={n}>
                  <span className="font-mono text-ink-faint">{i.rule}</span> {i.message}
                  {i.path !== null && <span className="font-mono text-ink-faint"> · {i.path}</span>}
                </li>
              ))}
            </ul>
          </div>
        )
      })}
    </div>
  )
}

/** 发布：静态检查、写入注册中心并签名，部署到 dev，再按 dev → test → prod 晋级 */
export function PublishStep({
  view,
  onPublished,
}: {
  view: DraftView
  onPublished: () => void
}): ReactNode {
  const { api, ready } = useStudioApi()
  const client = useQueryClient()
  const ref = identityOf(view.asset).ref
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<{ text: string; error: boolean; gates?: Gate[] } | null>(
    null,
  )
  const lint = useQuery({
    queryKey: ['studio', 'lint', view.draft.id, view.yaml],
    queryFn: () => api.lint(view.draft.id),
    enabled: ready,
  })
  const deployments = useQuery({
    queryKey: ['studio', 'deployments', ref],
    queryFn: () => api.deployments(ref),
    enabled: ready && view.draft.publishedVersion !== null,
  })

  const act = <T,>(
    label: string,
    task: () => Promise<T>,
    done: (value: T) => string,
    gates?: (v: T) => Gate[],
  ): void => {
    setBusy(label)
    setMessage(null)
    task().then(
      (value) => {
        setBusy(null)
        setMessage({ text: done(value), error: false, gates: gates?.(value) })
        void client.invalidateQueries({ queryKey: ['studio', 'deployments', ref] })
      },
      (error: unknown) => {
        setBusy(null)
        setMessage({ text: errorText(error), error: true, gates: gatesOf(error) ?? undefined })
      },
    )
  }

  const publish = (): void =>
    act(
      'publish',
      () => api.publish(view.draft.id),
      (r) => {
        onPublished()
        return `已发布 ${r.ref}@${r.version}，并部署到 dev`
      },
    )
  const environments = deployments.data?.environments ?? {}
  const lintErrors = (lint.data?.issues ?? []).filter((i) => i.severity === 'error').length

  return (
    <div className="space-y-4">
      <section
        className="rounded-xl border border-line bg-surface px-4 py-3"
        data-testid="lint-panel"
      >
        <header className="flex items-center gap-2 text-sm font-medium">
          <ListChecks aria-hidden className="size-4 text-ink-muted" />
          静态检查
          <button
            type="button"
            onClick={() => void lint.refetch()}
            disabled={lint.isFetching}
            className="ml-auto rounded-md border border-line px-2 py-0.5 text-xs font-normal text-ink-muted hover:bg-surface-sunken disabled:opacity-50"
          >
            重新检查
          </button>
        </header>
        <div className="mt-2">
          {lint.isFetching ? (
            <p className="text-xs text-ink-muted">检查中…</p>
          ) : lint.error !== null ? (
            <p className="text-xs text-deny">检查失败：{errorText(lint.error)}</p>
          ) : (
            <LintPanel issues={lint.data?.issues ?? []} />
          )}
        </div>
        <div className="mt-3 flex items-center gap-3 border-t border-line pt-3">
          <button
            type="button"
            onClick={publish}
            disabled={busy !== null || lintErrors > 0 || lint.isFetching}
            className="inline-flex items-center gap-1 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface hover:opacity-90 disabled:opacity-50"
          >
            {busy === 'publish' ? (
              <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
            ) : (
              <Rocket aria-hidden className="size-3.5" />
            )}
            发布并部署到 dev
          </button>
          <span className="text-xs text-ink-muted">
            {view.draft.publishedVersion === null
              ? '还没有发布过'
              : `最近发布的版本 ${view.draft.publishedVersion}；内容有变化时自动升补丁版本`}
          </span>
        </div>
      </section>

      {message !== null && (
        <div
          className={cn(
            'space-y-1.5 rounded-lg border px-3 py-2 text-xs',
            message.error
              ? 'border-deny/40 bg-deny-soft/40 text-deny'
              : 'border-allow/40 bg-allow-soft/40 text-allow',
          )}
          data-testid="publish-message"
        >
          <p>{message.text}</p>
          {message.gates !== undefined && message.gates.length > 0 && (
            <Gates gates={message.gates} />
          )}
        </div>
      )}

      {view.draft.publishedVersion !== null && (
        <section
          className="rounded-xl border border-line bg-surface px-4 py-3"
          data-testid="env-pipeline"
        >
          <header className="text-sm font-medium">环境晋级</header>
          <ol className="mt-3 grid grid-cols-[1fr_auto_1fr_auto_1fr] items-stretch gap-2">
            {ENVIRONMENTS.map((env, i) => {
              const current = environments[env]
              const before = ENVIRONMENTS[i - 1]
              const previous = before === undefined ? undefined : environments[before]
              return [
                i > 0 && (
                  <li key={`${env}-arrow`} className="flex items-center">
                    <ArrowRight aria-hidden className="size-4 text-ink-faint" />
                  </li>
                ),
                <li
                  key={env}
                  className="rounded-lg border border-line bg-canvas px-3 py-2 text-xs"
                  data-testid={`env-${env}`}
                >
                  <p className="font-medium text-ink">{ENV_LABELS[env]}</p>
                  <p className="mt-1 font-mono text-ink">{current?.version ?? '未部署'}</p>
                  {current !== undefined && (
                    <p className="text-[11px] text-ink-faint">{`${current.deployedBy} · ${new Date(current.deployedAt).toLocaleString('zh-CN')}`}</p>
                  )}
                  <div className="mt-2 flex flex-wrap gap-1.5">
                    {env !== 'dev' &&
                      previous !== undefined &&
                      previous.version !== current?.version && (
                        <button
                          type="button"
                          disabled={busy !== null}
                          onClick={() =>
                            act(
                              `promote-${env}`,
                              () => api.promote(ref, previous.version, env),
                              (r) => `已把 ${r.deployment.version} 晋级到 ${env}`,
                              (r) => r.gates,
                            )
                          }
                          className="rounded-md bg-ink px-2 py-0.5 text-[11px] font-medium text-surface disabled:opacity-50"
                        >
                          {`晋级 ${previous.version}`}
                        </button>
                      )}
                    {current !== undefined && (
                      <button
                        type="button"
                        disabled={busy !== null}
                        onClick={() =>
                          act(
                            `rollback-${env}`,
                            () => api.rollback(ref, env),
                            (r) => `${env} 已回滚到 ${r.deployment.version}`,
                          )
                        }
                        className="inline-flex items-center gap-0.5 rounded-md border border-line px-2 py-0.5 text-[11px] text-ink-muted hover:bg-surface-sunken disabled:opacity-50"
                      >
                        <Undo2 aria-hidden className="size-3" />
                        回滚
                      </button>
                    )}
                  </div>
                </li>,
              ]
            })}
          </ol>
          {(deployments.data?.history.length ?? 0) > 0 && (
            <details className="mt-3 text-xs">
              <summary className="flex cursor-pointer items-center gap-1 text-ink-muted">
                <History aria-hidden className="size-3.5" />
                部署历史
              </summary>
              <ul className="mt-1 space-y-0.5 text-ink-muted">
                {deployments.data?.history.map((d, n) => (
                  <li key={n}>
                    <span className="font-mono">{`${d.environment} ${d.version}`}</span>
                    {` · ${d.deployedBy} · ${new Date(d.deployedAt).toLocaleString('zh-CN')}`}
                    {d.note !== null && ` · ${d.note}`}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </section>
      )}
    </div>
  )
}
