import { useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Blocks, Copy, Lock, PencilLine, Plus, TriangleAlert } from 'lucide-react'
import { SourceBadge, cn } from '@eab/ui'
import { errorText } from '../use-studio'
import type { VendorAgentSummary } from './api'
import { EffectiveSegments } from './EffectivePreview'
import { SECTIONS } from './extension-values'
import { useExtendApi } from './use-extend'
import { Level } from '../tools/Level'

function pointLabel(type: string): string {
  return SECTIONS.find((s) => s.point === type)?.label ?? type
}

function AgentList({
  agents,
  selected,
  onSelect,
}: {
  agents: readonly VendorAgentSummary[]
  selected: string | undefined
  onSelect: (ref: string) => void
}): ReactNode {
  return (
    <ul className="space-y-2" data-testid="vendor-agents">
      {agents.map((a) => (
        <li key={a.ref}>
          <button
            type="button"
            onClick={() => onSelect(a.ref)}
            className={cn(
              'w-full rounded-xl border border-l-4 border-l-vendor bg-surface px-4 py-3 text-left transition-colors',
              a.ref === selected ? 'border-line-strong' : 'border-line hover:border-line-strong',
            )}
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              {a.name}
              <span className="ml-auto font-mono text-[11px] text-ink-faint">{a.version}</span>
            </span>
            <span className="mt-1 block font-mono text-[11px] text-ink-faint">{a.ref}</span>
            <span className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px] text-ink-muted">
              <Level level={a.writeLevel} />
              {`${(a.extensionPoints ?? []).length} 个扩展点`}
              {a.extensions.some((e) => e.status === 'published') && (
                <span className="rounded bg-tenant-soft px-1.5 py-px text-tenant">已扩展</span>
              )}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}

/** 复制为租户 Agent 之前的提示：副本不再随标品升级 */
function CopyConfirm({
  onConfirm,
  onCancel,
  busy,
}: {
  onConfirm: () => void
  onCancel: () => void
  busy: boolean
}): ReactNode {
  return (
    <div
      role="alertdialog"
      aria-label="复制为租户 Agent"
      className="space-y-2 rounded-lg border border-pending/40 bg-pending-soft/50 px-3 py-2.5 text-xs"
    >
      <p className="flex items-start gap-1.5">
        <TriangleAlert aria-hidden className="mt-0.5 size-3.5 shrink-0 text-pending" />
        <span>
          副本归本租户所有，不再随标品升级，标品后续修复和改进都要自己合并。副本只带标品的公开说明，
          不含标品指令原文。一般情况下优先用扩展。
        </span>
      </p>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-ink-muted hover:text-ink">
          取消
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={busy}
          className="rounded-md bg-ink px-2.5 py-1 text-surface disabled:opacity-40"
        >
          仍然复制
        </button>
      </div>
    </div>
  )
}

function AgentDetail({ agentRef }: { agentRef: string }): ReactNode {
  const { api, ready } = useExtendApi()
  const navigate = useNavigate()
  const [copying, setCopying] = useState(false)
  const [action, setAction] = useState<{ busy: boolean; error?: string }>({ busy: false })
  const { data, error } = useQuery({
    queryKey: ['studio', 'vendor-agent', agentRef],
    queryFn: () => api.vendorAgent(agentRef),
    enabled: ready,
  })
  if (error !== null) return <p className="text-xs text-deny">读取失败：{errorText(error)}</p>
  if (data === undefined) return <p className="text-xs text-ink-muted">读取中…</p>
  const { summary } = data
  const draft = summary.extensions.find((e) => e.status === 'draft')
  const published = summary.extensions.find((e) => e.status === 'published')

  const run = (work: () => Promise<string>): void => {
    setAction({ busy: true })
    work().then(
      (path) => navigate(path),
      (e: unknown) => setAction({ busy: false, error: errorText(e) }),
    )
  }

  return (
    <article className="space-y-4" data-testid="vendor-agent-detail">
      <header className="rounded-xl border border-l-4 border-line border-l-vendor bg-surface p-5">
        <div className="flex items-center gap-2">
          <SourceBadge source="vendor" />
          <Level level={summary.writeLevel} />
          <span className="ml-auto font-mono text-xs text-ink-faint">{`${summary.ref} · ${summary.version}`}</span>
        </div>
        <h2 className="mt-3 text-lg font-semibold">{summary.name}</h2>
        <p className="mt-1 text-sm leading-relaxed text-ink-muted">{summary.description}</p>
        <div className="mt-4 flex flex-wrap items-center gap-2">
          {draft?.draftId !== undefined ? (
            <Link
              to={`/studio/extensions/${draft.draftId}`}
              className="inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface"
            >
              <PencilLine aria-hidden className="size-4" />
              继续编辑扩展草稿
            </Link>
          ) : (
            <button
              type="button"
              disabled={action.busy}
              onClick={() =>
                run(() =>
                  api.createExtension(summary.ref).then((v) => `/studio/extensions/${v.draft.id}`),
                )
              }
              className="inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
            >
              <Plus aria-hidden className="size-4" />
              {published === undefined ? '新建扩展' : `修改扩展（当前 ${published.version ?? ''}）`}
            </button>
          )}
          <button
            type="button"
            onClick={() => setCopying(true)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 text-sm text-ink-muted hover:text-ink"
          >
            <Copy aria-hidden className="size-4" />
            复制为租户 Agent
          </button>
        </div>
        {copying && (
          <div className="mt-3">
            <CopyConfirm
              busy={action.busy}
              onCancel={() => setCopying(false)}
              onConfirm={() =>
                run(() =>
                  api.copyAsTenant(summary.ref).then((r) => `/studio/drafts/${r.draft.draft.id}`),
                )
              }
            />
          </div>
        )}
        {action.error !== undefined && <p className="mt-2 text-xs text-deny">{action.error}</p>}
      </header>

      <section className="rounded-xl border border-line bg-surface">
        <h3 className="border-b border-line px-4 py-2.5 text-sm font-medium">扩展点</h3>
        {(summary.extensionPoints ?? []).length === 0 ? (
          <p className="px-4 py-3 text-xs text-ink-muted">
            这个标品 Agent 没有声明扩展点，只能复制为租户 Agent。
          </p>
        ) : (
          <ul className="divide-y divide-line text-sm" data-testid="extension-points">
            {(summary.extensionPoints ?? []).map((p) => (
              <li key={p.type} className="flex gap-3 px-4 py-2.5">
                <span className="w-20 shrink-0 font-medium">{pointLabel(p.type)}</span>
                <span className="text-ink-muted">
                  {p.description}
                  {p.events !== undefined && (
                    <span className="ml-1 font-mono text-[11px] text-ink-faint">
                      {p.events.join(' · ')}
                    </span>
                  )}
                  {p.allowedProfiles !== undefined && (
                    <span className="ml-1 font-mono text-[11px] text-ink-faint">
                      {p.allowedProfiles.join(' · ')}
                    </span>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {data.effective !== undefined && (
        <section className="rounded-xl border border-line bg-surface">
          <h3 className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
            <Lock aria-hidden className="size-3.5 text-vendor" />
            本租户看到的有效定义
            <span className="text-xs font-normal text-ink-faint">
              标品指令运行时注入，租户不可见
            </span>
          </h3>
          <div className="p-4">
            <EffectiveSegments segments={data.effective.segments} />
          </div>
        </section>
      )}
    </article>
  )
}

/** 标品 Agent 目录：选一个查看扩展点和有效定义，然后新建扩展或复制为租户 Agent */
export function VendorAgentsPage(): ReactNode {
  const { api, ready } = useExtendApi()
  const [params, setParams] = useSearchParams()
  const { data, error } = useQuery({
    queryKey: ['studio', 'vendor-agents'],
    queryFn: () => api.vendorAgents(),
    enabled: ready,
  })
  const selected = params.get('ref') ?? data?.[0]?.ref

  return (
    <div className="space-y-5">
      <header className="flex items-end gap-3">
        <Link to="/studio" aria-label="返回 Studio" className="text-ink-muted hover:text-ink">
          <ArrowLeft className="size-4" />
        </Link>
        <div>
          <p className="flex items-center gap-1.5 text-xs text-ink-faint">
            <Blocks aria-hidden className="size-3.5" />
            Studio
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">扩展标品 Agent</h1>
        </div>
      </header>
      {error !== null && (
        <p className="text-xs text-deny">读取标品 Agent 失败：{errorText(error)}</p>
      )}
      {data === undefined ? (
        error === null && <p className="text-xs text-ink-muted">读取中…</p>
      ) : (
        <div className="grid grid-cols-[18rem_1fr] gap-5">
          <AgentList
            agents={data}
            selected={selected}
            onSelect={(ref) => setParams({ ref }, { replace: true })}
          />
          {selected !== undefined && <AgentDetail key={selected} agentRef={selected} />}
        </div>
      )}
    </div>
  )
}
