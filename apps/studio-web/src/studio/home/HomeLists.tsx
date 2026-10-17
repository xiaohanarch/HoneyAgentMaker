import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Cable, FileClock, Inbox, Rocket } from 'lucide-react'
import { ToneBadge } from '@eab/ui'
import { ENVIRONMENTS } from '../api'
import { Avatar } from '../Avatar'
import { errorText, useStudioApi } from '../use-studio'
import { DRAFT_KIND_LABELS, draftPath } from '../extend/use-extend'
import { REQUEST_STATUS_VIEWS, requestPath } from '../requests/request-model'

function Section({
  icon: Icon,
  title,
  testId,
  children,
}: {
  icon: typeof Inbox
  title: string
  testId: string
  children: ReactNode
}): ReactNode {
  return (
    <section className="rounded-xl border border-line bg-surface" data-testid={testId}>
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <Icon aria-hidden className="size-4 text-ink-muted" />
        {title}
      </header>
      <div className="px-4 py-3 text-sm">{children}</div>
    </section>
  )
}

function Empty({ children }: { children: ReactNode }): ReactNode {
  return <p className="text-xs text-ink-muted">{children}</p>
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

/** 首页下半部分：我的草稿、我的 Agent（各环境版本）、待处理的开发需求、连接中心入口 */
export function HomeLists(): ReactNode {
  const { api, ready } = useStudioApi()
  const { data, error, isLoading } = useQuery({
    queryKey: ['studio', 'home'],
    queryFn: () => api.home(),
    enabled: ready,
  })

  if (error !== null) {
    return <p className="text-xs text-deny">读取 Studio 首页失败：{errorText(error)}</p>
  }
  if (isLoading || data === undefined) return <p className="text-xs text-ink-muted">读取中…</p>

  return (
    <div className="grid grid-cols-3 gap-4">
      <Section icon={FileClock} title="我的草稿与最近编辑" testId="home-drafts">
        {data.drafts.length === 0 ? (
          <Empty>还没有草稿。从上面的意图输入框开始新建一个 Agent。</Empty>
        ) : (
          <ul className="space-y-2">
            {data.drafts.map((d) => (
              <li key={d.id}>
                <Link
                  to={draftPath(d)}
                  className="flex items-center gap-2.5 rounded-md px-1.5 py-1 transition-colors hover:bg-surface-sunken"
                >
                  <Avatar name={d.name} className="size-8 rounded-lg" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{d.intent}</span>
                    <span className="block text-[11px] text-ink-faint">
                      {`${d.kind === 'Agent' ? `第 ${d.step} 步` : DRAFT_KIND_LABELS[d.kind]} · ${formatTime(d.updatedAt)}`}
                      {d.publishedVersion != null && ` · 已发布 ${d.publishedVersion}`}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section icon={Rocket} title="我的 Agent" testId="home-agents">
        {data.agents.length === 0 ? (
          <Empty>还没有发布过的租户 Agent。</Empty>
        ) : (
          <ul className="space-y-2.5">
            {data.agents.map((a) => (
              <li key={a.ref} className="space-y-1">
                <p className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate font-medium">{a.name}</span>
                  <span className="font-mono text-[11px] text-ink-faint">{a.version}</span>
                </p>
                <p className="flex gap-1.5">
                  {ENVIRONMENTS.map((env) => (
                    <span
                      key={env}
                      className={
                        a.environments[env] === undefined
                          ? 'rounded border border-dashed border-line-strong px-1.5 font-mono text-[10px] text-ink-faint'
                          : 'rounded bg-allow-soft px-1.5 font-mono text-[10px] text-allow'
                      }
                    >
                      {env}
                      {a.environments[env] !== undefined && ` ${a.environments[env]}`}
                    </span>
                  ))}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <div className="flex flex-col gap-4">
        <Section icon={Inbox} title="待处理的开发需求" testId="home-requests">
          {data.devRequests.length === 0 ? (
            <Empty>统一入口里提交的扩展需求会出现在这里。</Empty>
          ) : (
            <ul className="space-y-2">
              {data.devRequests.map((r) => {
                const status = REQUEST_STATUS_VIEWS[r.status]
                const to = requestPath(r.ref)
                const summary = (
                  <>
                    <p className="font-medium text-ink">{r.originalText}</p>
                    <p className="mt-0.5 flex flex-wrap items-center gap-1.5 text-ink-faint">
                      <ToneBadge tone={status.tone}>{status.label}</ToneBadge>
                      <span>{`${r.requester} · ${formatTime(r.createdAt)}`}</span>
                    </p>
                  </>
                )
                return (
                  <li key={r.ref} className="text-xs">
                    {to === null ? (
                      <div className="px-1.5 py-1">{summary}</div>
                    ) : (
                      <Link
                        to={to}
                        className="block rounded-md px-1.5 py-1 transition-colors hover:bg-surface-sunken"
                      >
                        {summary}
                      </Link>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </Section>
        <Link
          to="/studio/connections"
          className="flex items-center gap-2 rounded-xl border border-line bg-surface px-4 py-3 text-sm transition-colors hover:border-line-strong"
        >
          <Cable aria-hidden className="size-4 text-ink-muted" />
          连接中心
          <span className="ml-auto text-xs text-ink-faint">连接别名与各环境的绑定</span>
        </Link>
      </div>
    </div>
  )
}
