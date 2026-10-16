import { useState, type ReactNode } from 'react'
import { CircleCheck, CircleX, FlaskConical, LoaderCircle, Play, Wand2 } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../../identity/identity-context'
import type { DraftView, EvalRunView } from '../api'
import { errorText, useStudioApi } from '../use-studio'

/** 业务用户才适合作为测试用户；开发者和管理员没有业务数据 */
export function useTestUsers(): { id: string; label: string }[] {
  const { users } = useIdentity()
  return users
    .filter((u) => u.home === 'assistant')
    .map((u) => ({ id: u.id, label: `${u.name} · ${u.role} · ${u.dataScope}` }))
}

export function TestUserSelect({
  value,
  onChange,
}: {
  value: string
  onChange: (id: string) => void
}): ReactNode {
  const users = useTestUsers()
  return (
    <label className="flex items-center gap-2 text-xs text-ink-muted">
      测试用户
      <select
        aria-label="测试用户"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-md border border-line bg-canvas px-2 py-1 text-xs text-ink"
      >
        {users.length === 0 && <option value={value}>{value}</option>}
        {users.map((u) => (
          <option key={u.id} value={u.id}>
            {u.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function Results({ run }: { run: EvalRunView }): ReactNode {
  const rate = Math.round(run.passRate * 100)
  return (
    <section className="rounded-xl border border-line bg-surface" data-testid="eval-results">
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        {run.status === 'passed' ? (
          <CircleCheck aria-hidden className="size-4 text-allow" />
        ) : (
          <CircleX aria-hidden className="size-4 text-deny" />
        )}
        {run.status === 'passed' ? '评测通过' : '评测未通过'}
        <span className="ml-auto text-xs font-normal text-ink-muted">
          {`${run.passed}/${run.total} 通过，通过率 ${rate}%（要求 ${Math.round(run.minPassRate * 100)}%）`}
        </span>
      </header>
      <ul className="divide-y divide-line">
        {run.cases.map((c) => (
          <li key={c.id} className="px-4 py-3 text-xs" data-testid="eval-case">
            <p className="flex items-center gap-2">
              {c.passed ? (
                <CircleCheck aria-label="通过" className="size-3.5 text-allow" />
              ) : (
                <CircleX aria-label="未通过" className="size-3.5 text-deny" />
              )}
              <span className="font-mono text-ink-faint">{c.id}</span>
              <span className="font-medium text-ink">{c.question}</span>
              <span className="ml-auto text-ink-faint">{c.user}</span>
            </p>
            <ul className="mt-1.5 space-y-0.5 pl-5">
              {c.assertions.map((a, i) => (
                <li key={i} className={cn(a.passed ? 'text-ink-muted' : 'text-deny')}>
                  <span className="font-mono">{a.type}</span> {a.description}：{a.detail}
                </li>
              ))}
            </ul>
            {c.text !== '' && (
              <p className="mt-1.5 line-clamp-3 whitespace-pre-wrap pl-5 text-ink-faint">
                {c.text}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

/** 评测集：从已选语义包的已验证任务生成初稿，可以编辑，然后在私有环境里运行 */
export function EvalStep({
  view,
  onSaved,
}: {
  view: DraftView
  onSaved: (view: DraftView) => void
}): ReactNode {
  const { api } = useStudioApi()
  const [testUser, setTestUser] = useState('zhangmin')
  const [yaml, setYaml] = useState(view.evalYaml ?? '')
  const [run, setRun] = useState<EvalRunView | null>(null)
  const [state, setState] = useState<{ busy?: string; error?: string }>({})

  const act = <T,>(label: string, task: () => Promise<T>, done: (value: T) => void): void => {
    setState({ busy: label })
    task().then(
      (value) => {
        done(value)
        setState({})
      },
      (error: unknown) => setState({ error: errorText(error) }),
    )
  }

  const generate = (): void =>
    act(
      'generate',
      () => api.generateEvals(view.draft.id, testUser),
      (next) => {
        setYaml(next.evalYaml ?? '')
        onSaved(next)
      },
    )
  const save = (): void =>
    act(
      'save',
      () => api.putEvals(view.draft.id, yaml),
      (next) => {
        setYaml(next.evalYaml ?? '')
        onSaved(next)
      },
    )
  const execute = (): void => act('run', () => api.runEvals(view.draft.id), setRun)
  const busy = state.busy !== undefined

  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-line bg-surface px-4 py-3">
        <header className="flex flex-wrap items-center gap-3 text-sm font-medium">
          <FlaskConical aria-hidden className="size-4 text-ink-muted" />
          评测集
          <span className="ml-auto flex items-center gap-3 font-normal">
            <TestUserSelect value={testUser} onChange={setTestUser} />
            <button
              type="button"
              disabled={busy}
              onClick={generate}
              className="inline-flex items-center gap-1 rounded-md border border-line-strong px-2.5 py-1 text-xs hover:bg-surface-sunken disabled:opacity-50"
            >
              <Wand2 aria-hidden className="size-3.5" />
              从已验证任务生成
            </button>
          </span>
        </header>
        <p className="mt-1 text-xs text-ink-muted">
          用例按语义包的 verified_tasks
          生成，断言优先用不变量：调用了任务要求的工具，结论由裁判按期望结论判断。
        </p>
        <textarea
          aria-label="评测集 YAML"
          value={yaml}
          onChange={(e) => setYaml(e.target.value)}
          placeholder="还没有评测集。点“从已验证任务生成”。"
          spellCheck={false}
          rows={14}
          className="mt-3 w-full rounded-lg border border-line bg-canvas px-3 py-2 font-mono text-[11px] leading-relaxed outline-none focus:border-line-strong"
        />
        <div className="mt-2 flex items-center gap-3">
          <button
            type="button"
            disabled={busy || yaml.trim() === ''}
            onClick={save}
            className="rounded-md border border-line-strong px-3 py-1.5 text-xs hover:bg-surface-sunken disabled:opacity-50"
          >
            保存评测集
          </button>
          <button
            type="button"
            disabled={busy || view.evalYaml === null}
            onClick={execute}
            className="inline-flex items-center gap-1 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface hover:opacity-90 disabled:opacity-50"
          >
            {state.busy === 'run' ? (
              <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
            ) : (
              <Play aria-hidden className="size-3.5" />
            )}
            运行评测
          </button>
          {state.busy === 'run' && (
            <span className="text-xs text-ink-muted">
              逐个用例运行草稿，真实模型下可能需要几分钟
            </span>
          )}
          {state.error !== undefined && <span className="text-xs text-deny">{state.error}</span>}
        </div>
      </section>
      {run !== null && <Results run={run} />}
    </div>
  )
}
