import { useState, type ReactNode } from 'react'
import { Plus, Save, X } from 'lucide-react'
import type { ExtensionPoint } from './api'
import {
  HOOK_EVENTS,
  sectionPayload,
  type ApprovalRow,
  type SectionKey,
  type SectionValues,
} from './extension-values'

const INPUT =
  'rounded-md border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-line-strong'

/** 引用列表：一行一个资产引用，带候选 */
function RefList({
  label,
  value,
  onChange,
  suggestions,
}: {
  label: string
  value: readonly string[]
  onChange: (next: string[]) => void
  suggestions: readonly string[]
}): ReactNode {
  const [input, setInput] = useState('')
  const listId = `refs-${label}`
  const add = (): void => {
    const ref = input.trim()
    if (ref === '' || value.includes(ref)) return
    onChange([...value, ref])
    setInput('')
  }
  return (
    <div className="space-y-2">
      <ul className="flex flex-wrap gap-1.5">
        {value.map((ref) => (
          <li
            key={ref}
            className="inline-flex items-center gap-1 rounded-md bg-tenant-soft px-2 py-0.5 font-mono text-xs text-tenant"
          >
            {ref}
            <button
              type="button"
              aria-label={`移除 ${ref}`}
              onClick={() => onChange(value.filter((r) => r !== ref))}
            >
              <X aria-hidden className="size-3" />
            </button>
          </li>
        ))}
        {value.length === 0 && <li className="text-xs text-ink-faint">还没有增加</li>}
      </ul>
      <div className="flex gap-2">
        <input
          aria-label={label}
          list={listId}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              add()
            }
          }}
          placeholder="资产引用，例如 tenant.t001.tools/notify-im"
          className={`${INPUT} flex-1 font-mono text-xs`}
        />
        <datalist id={listId}>
          {suggestions.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
        <button
          type="button"
          onClick={add}
          className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
        >
          <Plus aria-hidden className="size-3.5" />
          增加
        </button>
      </div>
    </div>
  )
}

function ApprovalRows({
  rows,
  onChange,
  tools,
}: {
  rows: readonly ApprovalRow[]
  onChange: (next: ApprovalRow[]) => void
  tools: readonly string[]
}): ReactNode {
  const set = (i: number, patch: Partial<ApprovalRow>): void =>
    onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  return (
    <div className="space-y-2">
      {rows.map((row, i) => (
        <div key={i} className="flex flex-wrap items-center gap-2">
          <input
            aria-label="确认点工具"
            list="approval-tools"
            value={row.tool}
            onChange={(e) => set(i, { tool: e.target.value })}
            placeholder="工具名或工具引用"
            className={`${INPUT} w-64 font-mono text-xs`}
          />
          <select
            aria-label="确认方式"
            value={row.policy}
            onChange={(e) => set(i, { policy: e.target.value as ApprovalRow['policy'] })}
            className={INPUT}
          >
            <option value="require-confirmation">需要用户确认</option>
            <option value="require-approval">需要审批</option>
          </select>
          <input
            aria-label="确认点说明"
            value={row.description}
            onChange={(e) => set(i, { description: e.target.value })}
            placeholder="说明（可选）"
            className={`${INPUT} flex-1`}
          />
          <button
            type="button"
            aria-label="删除确认点"
            onClick={() => onChange(rows.filter((_, j) => j !== i))}
          >
            <X aria-hidden className="size-3.5 text-ink-muted" />
          </button>
        </div>
      ))}
      <datalist id="approval-tools">
        {tools.map((t) => (
          <option key={t} value={t} />
        ))}
      </datalist>
      <button
        type="button"
        onClick={() =>
          onChange([...rows, { tool: '', policy: 'require-confirmation', description: '' }])
        }
        className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
      >
        <Plus aria-hidden className="size-3.5" />
        增加确认点
      </button>
    </div>
  )
}

function HookRows({
  hooks,
  events,
  onChange,
  suggestions,
}: {
  hooks: SectionValues['hooks']
  events: readonly string[]
  onChange: (next: SectionValues['hooks']) => void
  suggestions: readonly string[]
}): ReactNode {
  return (
    <div className="space-y-2">
      {HOOK_EVENTS.filter((e) => events.includes(e)).map((event) => {
        const binding = hooks[event] ?? { kind: 'workflow' as const, ref: '', when: '' }
        const set = (patch: Partial<typeof binding>): void =>
          onChange({ ...hooks, [event]: { ...binding, ...patch } })
        return (
          <div key={event} className="flex flex-wrap items-center gap-2">
            <span className="w-28 font-mono text-xs">{event}</span>
            <select
              aria-label={`${event} 挂载类型`}
              value={binding.kind}
              onChange={(e) => set({ kind: e.target.value as 'workflow' | 'skill' })}
              className={INPUT}
            >
              <option value="workflow">工作流</option>
              <option value="skill">技能</option>
            </select>
            <input
              aria-label={`${event} 引用`}
              list="hook-refs"
              value={binding.ref}
              onChange={(e) => set({ ref: e.target.value })}
              placeholder="留空表示不挂"
              className={`${INPUT} w-72 font-mono text-xs`}
            />
            <input
              aria-label={`${event} 条件`}
              value={binding.when}
              onChange={(e) => set({ when: e.target.value })}
              placeholder="条件（可选），例如 result.severity in ['high']"
              className={`${INPUT} flex-1 font-mono text-xs`}
            />
          </div>
        )
      })}
      <datalist id="hook-refs">
        {suggestions.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </div>
  )
}

/** 一个扩展点的编辑表单；保存时提交这一页的取值，平台按扩展点规则校验 */
export function SectionEditor({
  section,
  initial,
  point,
  suggestions,
  onSave,
  justSaved = false,
}: {
  section: SectionKey
  initial: SectionValues
  point: ExtensionPoint
  suggestions: { tools: string[]; skills: string[]; knowledge: string[]; hooks: string[] }
  onSave: (value: Record<string, unknown>) => Promise<void>
  /** 保存后草稿刷新会让这一页重新挂载，父组件记下刚保存的页，提示才不会丢 */
  justSaved?: boolean
}): ReactNode {
  const [values, setValues] = useState(initial)
  const [state, setState] = useState<{ busy: boolean; error?: string; saved?: boolean }>({
    busy: false,
    saved: justSaved,
  })
  const patch = (p: Partial<SectionValues>): void => {
    setValues((v) => ({ ...v, ...p }))
    setState({ busy: false })
  }

  const save = (): void => {
    setState({ busy: true })
    onSave(sectionPayload(section, values)).then(
      () => setState({ busy: false, saved: true }),
      (e: unknown) => setState({ busy: false, error: e instanceof Error ? e.message : '保存失败' }),
    )
  }

  let body: ReactNode
  switch (section) {
    case 'instructions':
      body = (
        <div className="space-y-3">
          <label className="block space-y-1 text-xs text-ink-muted">
            追加指令
            <textarea
              aria-label="追加指令"
              value={values.instructions.append}
              onChange={(e) =>
                patch({ instructions: { ...values.instructions, append: e.target.value } })
              }
              rows={6}
              className={`${INPUT} block w-full leading-relaxed`}
            />
          </label>
          <label className="block space-y-1 text-xs text-ink-muted">
            追加约束（一行一条）
            <textarea
              aria-label="追加约束"
              value={values.instructions.constraints.join('\n')}
              onChange={(e) =>
                patch({
                  instructions: {
                    ...values.instructions,
                    constraints: e.target.value.split('\n'),
                  },
                })
              }
              rows={3}
              className={`${INPUT} block w-full`}
            />
          </label>
        </div>
      )
      break
    case 'tools':
    case 'skills':
    case 'knowledge':
      body = (
        <RefList
          label={section}
          value={values[section]}
          onChange={(next) => patch({ [section]: next })}
          suggestions={suggestions[section]}
        />
      )
      break
    case 'hooks':
      body = (
        <HookRows
          hooks={values.hooks}
          events={point.events ?? HOOK_EVENTS}
          onChange={(hooks) => patch({ hooks })}
          suggestions={suggestions.hooks}
        />
      )
      break
    case 'approvals':
      body = (
        <ApprovalRows
          rows={values.approvals}
          onChange={(approvals) => patch({ approvals })}
          tools={[...values.tools, ...suggestions.tools]}
        />
      )
      break
    case 'model':
      body = (
        <select
          aria-label="模型档位"
          value={values.model}
          onChange={(e) => patch({ model: e.target.value })}
          className={INPUT}
        >
          <option value="">沿用标品默认</option>
          {(point.allowedProfiles ?? []).map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </select>
      )
      break
  }

  return (
    <div className="space-y-4" data-testid={`section-${section}`}>
      {body}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={save}
          disabled={state.busy}
          className="inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
        >
          <Save aria-hidden className="size-4" />
          保存这一页
        </button>
        {state.saved === true && (
          <span className="text-xs text-allow">已保存，右侧 YAML 和有效定义已更新</span>
        )}
        {state.error !== undefined && <span className="text-xs text-deny">{state.error}</span>}
      </div>
    </div>
  )
}
