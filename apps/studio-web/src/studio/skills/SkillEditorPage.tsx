import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  FlaskConical,
  Plus,
  Rocket,
  Save,
  Trash2,
} from 'lucide-react'
import { SourceBadge, cn } from '@eab/ui'
import type { DraftView } from '../api'
import { errorText, useDraft } from '../use-studio'
import { TestUserSelect } from '../wizard/EvalStep'
import { YamlPanel } from '../wizard/YamlPanel'
import type { SkillTestView } from '../extend/api'
import { useExtendApi } from '../extend/use-extend'
import { SkillGraph } from './SkillGraph'
import { duplicateIds, type SkillStep } from './skill-graph'
import { exampleInput, newStep, parseWith, SKILL_STEP_TYPES, stepsOf } from './skill-steps'

const INPUT =
  'rounded-md border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-line-strong'

function StepCard({
  step,
  index,
  count,
  selected,
  onChange,
  onMove,
  onRemove,
  onSelect,
}: {
  step: SkillStep
  index: number
  count: number
  selected: boolean
  onChange: (step: SkillStep) => void
  onMove: (delta: number) => void
  onRemove: () => void
  onSelect: () => void
}): ReactNode {
  const [withText, setWithText] = useState(step.with === undefined ? '' : JSON.stringify(step.with))
  const withError = parseWith(withText).error
  const field = (key: keyof SkillStep, label: string, mono = true): ReactNode => (
    <label className="flex items-center gap-2 text-xs text-ink-muted">
      <span className="w-14 shrink-0">{label}</span>
      <input
        aria-label={`${step.id} ${label}`}
        value={typeof step[key] === 'string' ? (step[key] as string) : ''}
        onChange={(e) => onChange({ ...step, [key]: e.target.value })}
        className={cn(INPUT, 'flex-1 text-xs', mono && 'font-mono')}
      />
    </label>
  )
  const branches = (step.then?.length ?? 0) + (step.else?.length ?? 0)

  return (
    <li
      onFocus={onSelect}
      onClick={onSelect}
      className={cn(
        'space-y-2 rounded-lg border bg-surface p-3',
        selected ? 'border-line-strong' : 'border-line',
      )}
      data-testid="skill-step"
    >
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11px] text-ink-faint">{index + 1}</span>
        <input
          aria-label="步骤 id"
          value={step.id}
          onChange={(e) => onChange({ ...step, id: e.target.value })}
          className={`${INPUT} w-32 font-mono text-xs`}
        />
        <input
          aria-label="步骤名称"
          value={step.name ?? ''}
          onChange={(e) => onChange({ ...step, name: e.target.value })}
          placeholder="名称"
          className={`${INPUT} flex-1 text-xs`}
        />
        <select
          aria-label="步骤类型"
          value={step.type}
          onChange={(e) => onChange(newStep(e.target.value, step))}
          className={`${INPUT} text-xs`}
        >
          {SKILL_STEP_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
        <button
          type="button"
          aria-label="上移"
          disabled={index === 0}
          onClick={() => onMove(-1)}
          className="disabled:opacity-30"
        >
          <ArrowUp aria-hidden className="size-3.5 text-ink-muted" />
        </button>
        <button
          type="button"
          aria-label="下移"
          disabled={index === count - 1}
          onClick={() => onMove(1)}
          className="disabled:opacity-30"
        >
          <ArrowDown aria-hidden className="size-3.5 text-ink-muted" />
        </button>
        <button type="button" aria-label="删除步骤" onClick={onRemove}>
          <Trash2 aria-hidden className="size-3.5 text-ink-muted" />
        </button>
      </div>
      {step.type === 'tool' && (
        <>
          {field('tool', '工具')}
          {field('source', '工具源')}
        </>
      )}
      {step.type === 'skill' && field('skill', '技能')}
      {step.type === 'transform' && field('expression', '表达式')}
      {step.type === 'condition' && (
        <>
          {field('if', '条件')}
          <p className="text-[11px] text-ink-faint">{`是、否分支里共 ${branches} 个步骤，分支内的步骤在右侧 YAML 里编辑`}</p>
        </>
      )}
      {(step.type === 'confirm' || step.type === 'notify') && field('message', '消息', false)}
      {step.type === 'notify' && field('channel', '渠道')}
      {(step.type === 'tool' || step.type === 'skill') && (
        <label className="flex items-center gap-2 text-xs text-ink-muted">
          <span className="w-14 shrink-0">入参</span>
          <input
            aria-label={`${step.id} 入参`}
            value={withText}
            onChange={(e) => {
              setWithText(e.target.value)
              const parsed = parseWith(e.target.value)
              if (parsed.error === undefined) onChange({ ...step, with: parsed.value })
            }}
            placeholder='JSON，例如 {"supplierName": "input.supplierName"}'
            className={cn(
              INPUT,
              'flex-1 font-mono text-xs',
              withError !== undefined && 'border-deny',
            )}
          />
        </label>
      )}
      {(step.type === 'tool' || step.type === 'skill' || step.type === 'transform') &&
        field('output', '输出变量')}
    </li>
  )
}

function TestResult({ result }: { result: SkillTestView }): ReactNode {
  return (
    <div className="space-y-2" data-testid="skill-test-result">
      <p
        className={cn(
          'text-sm font-medium',
          result.status === 'ok'
            ? 'text-allow'
            : result.status === 'failed'
              ? 'text-deny'
              : 'text-pending',
        )}
      >
        {result.status === 'ok'
          ? '技能执行成功'
          : result.status === 'failed'
            ? `执行失败：${result.error ?? ''}`
            : `停在确认点：${result.pending ?? ''}（单独测试不会真的执行写操作）`}
      </p>
      <ol className="space-y-1.5 text-xs">
        {result.steps.map((s) => (
          <li key={s.id} className="rounded-lg border border-line bg-canvas px-3 py-2">
            <p className="flex items-center gap-2">
              <span className="font-mono">{s.id}</span>
              <span className="text-ink-muted">{s.name}</span>
              <span
                className={cn(
                  'ml-auto',
                  s.status === 'ok'
                    ? 'text-allow'
                    : s.status === 'error'
                      ? 'text-deny'
                      : 'text-pending',
                )}
              >
                {`${s.status} · ${s.durationMs} ms`}
              </span>
            </p>
            {s.input !== null && (
              <pre className="mt-1 overflow-x-auto font-mono text-[11px] text-ink-muted">{`输入 ${s.input}`}</pre>
            )}
            {s.output !== null && (
              <pre className="mt-1 overflow-x-auto font-mono text-[11px]">{`输出 ${s.output}`}</pre>
            )}
            {s.error !== null && <p className="mt-1 text-deny">{s.error}</p>}
          </li>
        ))}
      </ol>
      {result.output !== null && result.output !== undefined && (
        <pre className="overflow-x-auto rounded-lg bg-surface-sunken px-3 py-2 font-mono text-[11px]">
          {JSON.stringify(result.output, null, 2)}
        </pre>
      )}
    </div>
  )
}

function Editor({
  view,
  setDraft,
}: {
  view: DraftView
  setDraft: (v: DraftView) => void
}): ReactNode {
  const { api } = useExtendApi()
  const id = view.draft.id
  const [steps, setSteps] = useState<SkillStep[]>(() => stepsOf(view.asset))
  const [selected, setSelected] = useState<string | undefined>()
  const [input, setInput] = useState(() => JSON.stringify(exampleInput(view.asset), null, 2))
  const [asUser, setAsUser] = useState('zhangmin')
  const [result, setResult] = useState<SkillTestView | null>(null)
  const [state, setState] = useState<{ busy: string | null; error?: string; done?: string }>({
    busy: null,
  })
  const duplicates = duplicateIds(steps)

  const act = <T,>(name: string, work: () => Promise<T>, done: (value: T) => void): void => {
    setState({ busy: name })
    work().then(
      (value) => {
        setState({ busy: null })
        done(value)
      },
      (e: unknown) => setState({ busy: null, error: errorText(e) }),
    )
  }
  const saveSteps = (): Promise<DraftView> =>
    api.putSteps(id, steps).then((next) => (setDraft(next), next))
  const test = (): void => {
    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(input) as Record<string, unknown>
    } catch {
      setState({ busy: null, error: '测试入参不是合法的 JSON' })
      return
    }
    act('test', () => saveSteps().then(() => api.testSkill(id, parsed, asUser)), setResult)
  }
  const update = (i: number, step: SkillStep): void =>
    setSteps(steps.map((s, j) => (j === i ? step : s)))
  const move = (i: number, delta: number): void => {
    const next = [...steps]
    const [item] = next.splice(i, 1)
    if (item !== undefined) next.splice(i + delta, 0, item)
    setSteps(next)
  }

  return (
    <div className="grid grid-cols-[1fr_26rem] gap-5">
      <div className="min-w-0 space-y-4">
        <section className="rounded-xl border border-line bg-surface p-4">
          <SkillGraph
            steps={steps}
            results={result?.steps}
            selected={selected}
            onSelect={setSelected}
          />
        </section>
        <section className="space-y-2">
          <ol className="space-y-2">
            {steps.map((step, i) => (
              <StepCard
                key={i}
                step={step}
                index={i}
                count={steps.length}
                selected={step.id === selected}
                onChange={(s) => update(i, s)}
                onMove={(d) => move(i, d)}
                onRemove={() => setSteps(steps.filter((_, j) => j !== i))}
                onSelect={() => setSelected(step.id)}
              />
            ))}
          </ol>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() =>
                setSteps([
                  ...steps,
                  newStep('transform', { id: `step-${steps.length + 1}`, type: 'transform' }),
                ])
              }
              className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
            >
              <Plus aria-hidden className="size-3.5" />
              增加步骤
            </button>
            {duplicates.length > 0 && (
              <span className="text-xs text-deny">{`步骤 id 重复：${duplicates.join('、')}`}</span>
            )}
            <button
              type="button"
              disabled={state.busy !== null || duplicates.length > 0}
              onClick={() =>
                act('save', saveSteps, () => setState({ busy: null, done: '步骤已保存' }))
              }
              className="ml-auto inline-flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 text-sm text-ink-muted hover:text-ink disabled:opacity-40"
            >
              <Save aria-hidden className="size-4" />
              保存步骤
            </button>
          </div>
        </section>
        <section
          className="space-y-3 rounded-xl border border-line bg-surface p-4"
          data-testid="skill-test"
        >
          <header className="flex items-center gap-2 text-sm font-medium">
            <FlaskConical aria-hidden className="size-4 text-ink-muted" />
            单独测试
            <span className="ml-auto font-normal">
              <TestUserSelect value={asUser} onChange={setAsUser} />
            </span>
          </header>
          <textarea
            aria-label="测试入参"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            rows={4}
            className="block w-full rounded-lg border border-line bg-canvas px-3 py-2 font-mono text-xs outline-none focus:border-line-strong"
          />
          <div className="flex gap-2">
            <button
              type="button"
              onClick={test}
              disabled={state.busy !== null || duplicates.length > 0}
              className="rounded-lg border border-line px-3 py-1.5 text-sm text-ink-muted hover:text-ink disabled:opacity-40"
            >
              保存并测试
            </button>
            <button
              type="button"
              disabled={state.busy !== null}
              onClick={() =>
                act(
                  'publish',
                  () => api.publishSkill(id),
                  (r) =>
                    setState({
                      busy: null,
                      done: r.unchanged
                        ? `内容没有变化，仍是 ${r.ref} ${r.version}`
                        : `已发布 ${r.ref} ${r.version}`,
                    }),
                )
              }
              className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
            >
              <Rocket aria-hidden className="size-4" />
              发布技能
            </button>
          </div>
          {state.error !== undefined && <p className="text-xs text-deny">{state.error}</p>}
          {state.done !== undefined && <p className="text-xs text-allow">{state.done}</p>}
          {result !== null && <TestResult result={result} />}
        </section>
      </div>
      <aside className="sticky top-4 self-start">
        <YamlPanel
          view={view}
          onSaved={(next) => {
            setDraft(next)
            setSteps(stepsOf(next.asset))
          }}
        />
      </aside>
    </div>
  )
}

/** 技能编辑器：步骤表单与流程图，单独测试看每一步的输入输出，发布 */
export function SkillEditorPage(): ReactNode {
  const { id = '' } = useParams()
  const { draft, error, setDraft } = useDraft(id)
  return (
    <div className="space-y-5">
      <header className="flex items-end gap-3">
        <Link
          to="/studio/tools"
          aria-label="返回工具中心"
          className="text-ink-muted hover:text-ink"
        >
          <ArrowLeft className="size-4" />
        </Link>
        <div>
          <p className="flex items-center gap-2 text-xs text-ink-faint">
            <SourceBadge source="tenant" />
            <span className="font-mono">{draft?.draft.namespace}</span>
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">
            {draft?.draft.intent ?? '技能'}
          </h1>
        </div>
        {draft?.draft.publishedVersion != null && (
          <span className="ml-auto text-xs text-ink-muted">{`已发布 ${draft.draft.publishedVersion}`}</span>
        )}
      </header>
      {error !== null && <p className="text-xs text-deny">读取草稿失败：{errorText(error)}</p>}
      {draft !== undefined && <Editor view={draft} setDraft={setDraft} />}
    </div>
  )
}
