import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ChevronDown, ChevronRight, Plus, Workflow, Wrench } from 'lucide-react'
import { SourceBadge, cn, type AssetSource } from '@eab/ui'
import { errorText } from '../use-studio'
import type { SkillView, ToolSourceView } from '../extend/api'
import { useExtendApi } from '../extend/use-extend'
import { Level } from './Level'
import { BoImport, McpRegister, McpApprovals } from './ToolImport'

export const TOOLS_KEY = ['studio', 'tools'] as const

const TYPE_LABELS: Record<string, string> = { 'bo-api': 'BO API', mcp: 'MCP Server', http: 'HTTP' }

const APPROVAL_LABELS: Record<string, { text: string; className: string }> = {
  pending: { text: '待审批', className: 'bg-pending-soft text-pending' },
  approved: { text: '已审批', className: 'bg-allow-soft text-allow' },
  rejected: { text: '已拒绝', className: 'bg-deny-soft text-deny' },
  unregistered: { text: '未登记审批', className: 'bg-surface-sunken text-ink-muted' },
}

function sourceOf(s: { layer: string; type?: string }): AssetSource {
  if (s.type === 'mcp' && s.layer === 'tenant') return 'external'
  return s.layer === 'vendor' || s.layer === 'tenant' ? s.layer : 'platform'
}

function UsedBy({ refs }: { refs: readonly string[] }): ReactNode {
  if (refs.length === 0) return <span className="text-ink-faint">未被引用</span>
  return <span title={refs.join('\n')}>{`被 ${refs.length} 个资产引用`}</span>
}

function SourceRow({ source }: { source: ToolSourceView }): ReactNode {
  const [open, setOpen] = useState(false)
  const approval = source.approval === undefined ? undefined : APPROVAL_LABELS[source.approval]
  return (
    <li>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm hover:bg-surface-sunken"
      >
        {open ? (
          <ChevronDown aria-hidden className="size-4" />
        ) : (
          <ChevronRight aria-hidden className="size-4" />
        )}
        <span className="font-medium">{source.name}</span>
        <span className="font-mono text-[11px] text-ink-faint">{`${source.ref} · ${source.version}`}</span>
        <span className="rounded bg-surface-sunken px-1.5 py-px text-[11px] text-ink-muted">
          {TYPE_LABELS[source.type] ?? source.type}
        </span>
        {approval !== undefined && (
          <span className={cn('rounded px-1.5 py-px text-[11px]', approval.className)}>
            {approval.text}
          </span>
        )}
        <span className="ml-auto flex items-center gap-3 text-xs text-ink-muted">
          {`${source.tools.length} 个工具`}
          <UsedBy refs={source.usedBy} />
          <SourceBadge source={sourceOf(source)} />
        </span>
      </button>
      {open && (
        <ul className="divide-y divide-line border-t border-line bg-canvas text-xs">
          {source.tools.map((t) => (
            <li key={t.name} className="flex items-center gap-2 py-1.5 pr-4 pl-10">
              <span className="font-mono">{t.name}</span>
              <Level level={t.writeLevel} />
              <span className="truncate text-ink-muted">{t.description}</span>
              <span className="ml-auto shrink-0 text-ink-muted">
                <UsedBy refs={t.usedBy} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

function NewSkill({ skills }: { skills: readonly SkillView[] }): ReactNode {
  const { api } = useExtendApi()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [from, setFrom] = useState('')
  const [state, setState] = useState<{ busy: boolean; error?: string }>({ busy: false })
  const submit = (e: FormEvent): void => {
    e.preventDefault()
    setState({ busy: true })
    api.createSkill(name.trim(), displayName.trim(), from === '' ? undefined : from).then(
      (view) => navigate(`/studio/skills/${view.draft.id}`),
      (error: unknown) => setState({ busy: false, error: errorText(error) }),
    )
  }
  const input =
    'rounded-md border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-line-strong'
  return (
    <form
      onSubmit={submit}
      className="flex flex-wrap items-center gap-2 border-t border-line px-4 py-3"
      data-testid="new-skill"
    >
      <input
        aria-label="技能名"
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="技能名（英文，如 overdue-reminder）"
        pattern="[a-z0-9][a-z0-9-]*"
        required
        className={`${input} w-60 font-mono text-xs`}
      />
      <input
        aria-label="显示名"
        value={displayName}
        onChange={(e) => setDisplayName(e.target.value)}
        placeholder="显示名"
        className={`${input} w-40`}
      />
      <select
        aria-label="从已有技能复制"
        value={from}
        onChange={(e) => setFrom(e.target.value)}
        className={input}
      >
        <option value="">从空白开始</option>
        {skills.map((s) => (
          <option key={s.ref} value={s.ref}>{`复制 ${s.name}`}</option>
        ))}
      </select>
      <button
        type="submit"
        disabled={state.busy || name.trim() === ''}
        className="inline-flex items-center gap-1 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
      >
        <Plus aria-hidden className="size-4" />
        新建技能
      </button>
      {state.error !== undefined && <span className="text-xs text-deny">{state.error}</span>}
    </form>
  )
}

/** 工具中心：本租户可用的工具源和技能（写级别、被谁引用），从 BO API 导入工具，登记 MCP Server 并审批 */
export function ToolCenterPage(): ReactNode {
  const { api, ready } = useExtendApi()
  const client = useQueryClient()
  const { data, error } = useQuery({
    queryKey: TOOLS_KEY,
    queryFn: () => api.tools(),
    enabled: ready,
  })
  const refresh = (): void => void client.invalidateQueries({ queryKey: TOOLS_KEY })

  return (
    <div className="space-y-5">
      <header className="flex items-end gap-3">
        <Link to="/studio" aria-label="返回 Studio" className="text-ink-muted hover:text-ink">
          <ArrowLeft className="size-4" />
        </Link>
        <div>
          <p className="text-xs text-ink-faint">Studio</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">工具中心</h1>
        </div>
      </header>
      {error !== null && <p className="text-xs text-deny">读取工具失败：{errorText(error)}</p>}

      <div className="grid grid-cols-[1fr_24rem] gap-5">
        <div className="min-w-0 space-y-5">
          <section className="rounded-xl border border-line bg-surface">
            <h2 className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
              <Wrench aria-hidden className="size-4 text-ink-muted" />
              工具源
            </h2>
            <ul className="divide-y divide-line" data-testid="tool-sources">
              {(data?.sources ?? []).map((s) => (
                <SourceRow key={s.ref} source={s} />
              ))}
            </ul>
          </section>
          <section id="skills" className="scroll-mt-4 rounded-xl border border-line bg-surface">
            <h2 className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
              <Workflow aria-hidden className="size-4 text-ink-muted" />
              技能
            </h2>
            <ul className="divide-y divide-line text-sm" data-testid="skills">
              {(data?.skills ?? []).map((s) => (
                <li key={s.ref} className="flex items-center gap-2 px-4 py-2.5">
                  <span className="font-medium">{s.name}</span>
                  <span className="font-mono text-[11px] text-ink-faint">{`${s.ref} · ${s.version}`}</span>
                  <Level level={s.writeLevel} />
                  <span className="ml-auto flex items-center gap-3 text-xs text-ink-muted">
                    {`${s.steps} 个步骤`}
                    <UsedBy refs={s.usedBy} />
                    <SourceBadge source={sourceOf(s)} />
                  </span>
                </li>
              ))}
            </ul>
            <NewSkill skills={data?.skills ?? []} />
          </section>
        </div>
        <aside className="space-y-5">
          <BoImport onImported={refresh} />
          <McpRegister onRegistered={refresh} />
          <McpApprovals onDecided={refresh} />
        </aside>
      </div>
    </div>
  )
}
