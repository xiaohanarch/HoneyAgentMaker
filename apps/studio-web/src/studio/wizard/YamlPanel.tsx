import { useState, type ReactNode } from 'react'
import { Code2, Eye, PencilLine, TriangleAlert } from 'lucide-react'
import { cn } from '@eab/ui'
import type { DraftView, SchemaIssue } from '../api'
import { errorText, useStudioApi } from '../use-studio'

function Issues({ issues }: { issues: readonly SchemaIssue[] }): ReactNode {
  if (issues.length === 0) return null
  return (
    <ul className="space-y-1 border-t border-line px-3 py-2 text-xs" data-testid="schema-issues">
      {issues.map((issue, i) => (
        <li key={i} className="flex gap-1.5 text-deny">
          <TriangleAlert aria-hidden className="mt-0.5 size-3 shrink-0" />
          <span>
            {issue.path !== null && <span className="font-mono">{issue.path} </span>}
            {issue.line !== undefined && (
              <span className="text-ink-faint">{`第 ${issue.line} 行 `}</span>
            )}
            {issue.message}
          </span>
        </li>
      ))}
    </ul>
  )
}

/**
 * 右侧的实时 YAML：默认只读预览，每一步保存后跟着更新；切到编辑模式可以直接改，保存时由平台校验，
 * 有 schema 问题仍会保存并在下方列出。
 */
export function YamlPanel({
  view,
  onSaved,
}: {
  view: DraftView
  onSaved: (view: DraftView) => void
}): ReactNode {
  const { api } = useStudioApi()
  const [editing, setEditing] = useState<string | null>(null)
  const [state, setState] = useState<{ busy: boolean; error?: string }>({ busy: false })

  const save = (): void => {
    if (editing === null) return
    setState({ busy: true })
    api.putYaml(view.draft.id, editing).then(
      (next) => {
        onSaved(next)
        setEditing(null)
        setState({ busy: false })
      },
      (error: unknown) => setState({ busy: false, error: errorText(error) }),
    )
  }

  return (
    <section
      className="flex max-h-[calc(100dvh-9rem)] flex-col rounded-xl border border-line bg-surface"
      data-testid="yaml-panel"
    >
      <header className="flex items-center gap-2 border-b border-line px-3 py-2 text-sm font-medium">
        <Code2 aria-hidden className="size-4 text-ink-muted" />
        实时 YAML
        <span className="ml-1 truncate font-mono text-[11px] font-normal text-ink-faint">
          {`${view.draft.namespace}/${view.draft.name}`}
        </span>
        {editing === null ? (
          <button
            type="button"
            onClick={() => setEditing(view.yaml)}
            className="ml-auto inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-xs font-normal text-ink-muted hover:bg-surface-sunken"
          >
            <PencilLine aria-hidden className="size-3" />
            编辑
          </button>
        ) : (
          <span className="ml-auto flex gap-1.5">
            <button
              type="button"
              onClick={() => setEditing(null)}
              className="inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-xs font-normal text-ink-muted hover:bg-surface-sunken"
            >
              <Eye aria-hidden className="size-3" />
              预览
            </button>
            <button
              type="button"
              onClick={save}
              disabled={state.busy}
              className="rounded-md bg-ink px-2 py-0.5 text-xs font-medium text-surface disabled:opacity-50"
            >
              保存 YAML
            </button>
          </span>
        )}
      </header>
      {editing === null ? (
        <pre
          className="min-h-0 flex-1 overflow-auto px-3 py-2 font-mono text-[11px] leading-relaxed text-ink"
          data-testid="yaml-preview"
        >
          {view.yaml}
        </pre>
      ) : (
        <textarea
          aria-label="编辑 YAML"
          value={editing}
          onChange={(e) => setEditing(e.target.value)}
          spellCheck={false}
          className={cn(
            'min-h-[420px] flex-1 resize-none bg-canvas px-3 py-2 font-mono text-[11px] leading-relaxed outline-none',
          )}
        />
      )}
      {state.error !== undefined && <p className="px-3 py-1 text-xs text-deny">{state.error}</p>}
      <Issues issues={view.issues} />
    </section>
  )
}
