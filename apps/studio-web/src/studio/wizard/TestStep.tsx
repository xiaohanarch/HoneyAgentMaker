import { useState, type FormEvent, type ReactNode } from 'react'
import { MessagesSquare, RotateCcw, SendHorizontal, ShieldAlert } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../../identity/identity-context'
import { ConfirmationCard, ResultCard, Thoughts, ToolCards } from '../../assist/cards'
import { awaitingConfirmation, viewOf, type Turn } from '../../assist/turn'
import type { DraftView } from '../api'
import { valueFromAsset } from '../draft-values'
import { TestUserSelect } from './EvalStep'
import { TraceTimeline } from './TraceTimeline'
import { constraintsOf, useTestRuns } from './use-test-run'

/** 故事 A 的测试话术 */
const STORY_PROMPT = '查一下百润精密最近一次询价，帮我出一份谈判策略'

function TestTurn({
  turn,
  busy,
  selected,
  dataScope,
  onSelect,
  onConfirm,
}: {
  turn: Turn
  busy: boolean
  selected: boolean
  dataScope: string
  onSelect: () => void
  onConfirm: (runId: string, approved: boolean) => void
}): ReactNode {
  const view = viewOf(turn)
  const answer = view.final?.text ?? (view.text !== '' ? view.text : undefined)
  const constraints = constraintsOf(turn)
  return (
    <li className="space-y-2" data-testid="test-turn">
      <p className="ml-auto w-fit max-w-[80%] rounded-2xl rounded-br-sm bg-ink px-3.5 py-2 text-sm text-surface">
        {turn.question}
      </p>
      <div
        onClick={onSelect}
        className={cn(
          'space-y-2.5 rounded-2xl rounded-bl-sm border bg-canvas px-4 py-3',
          selected ? 'border-line-strong' : 'border-line',
        )}
      >
        <Thoughts thoughts={view.thoughts} />
        <ToolCards tools={view.tools} />
        {constraints.map((c) => (
          <p
            key={c.rule}
            data-testid="constraint-banner"
            className="flex items-start gap-1.5 rounded-lg border border-pending/40 bg-pending-soft/50 px-3 py-2 text-xs"
          >
            <ShieldAlert aria-hidden className="mt-0.5 size-3.5 shrink-0 text-pending" />
            <span>
              <span className="font-medium text-pending">{`约束 ${c.rule} 已触发`}</span>
              <span className="ml-1 text-ink-muted">{c.remedy}</span>
            </span>
          </p>
        ))}
        {view.pending !== undefined && view.runId !== undefined && (
          <ConfirmationCard
            pending={view.pending}
            state={awaitingConfirmation(view) ? 'waiting' : 'resolved'}
            disabled={busy}
            onDecide={(approved) => onConfirm(view.runId ?? '', approved)}
          />
        )}
        {answer !== undefined && answer !== '' && (
          <ResultCard
            text={answer}
            dataScope={dataScope}
            tools={view.tools}
            unverified={view.final?.unverified ?? []}
          />
        )}
        {turn.status === 'streaming' && <p className="text-xs text-ink-faint">运行中…</p>}
        {view.error !== undefined && <p className="text-xs text-deny">出错了：{view.error}</p>}
      </div>
    </li>
  )
}

/** 测试：私有环境里以选定用户运行草稿，下面是所选一轮的链路追踪 */
export function TestStep({ view }: { view: DraftView }): ReactNode {
  const { users } = useIdentity()
  const runs = useTestRuns(view.draft.id)
  const [asUser, setAsUser] = useState('zhangmin')
  const [input, setInput] = useState('')
  const [pinned, setPinned] = useState<string | undefined>()
  const examples = (valueFromAsset('examples', view.asset).examples as string[]) ?? []
  const prompts = [...new Set([STORY_PROMPT, ...examples])].slice(0, 3)
  const selected = runs.turns.find((t) => t.id === pinned) ?? runs.turns.at(-1)
  const selectedView = selected === undefined ? undefined : viewOf(selected)
  const dataScope = users.find((u) => u.id === asUser)?.dataScope ?? ''

  const send = (message: string): void => {
    if (message.trim() === '' || runs.busy) return
    setPinned(undefined)
    void runs.ask(message.trim(), asUser)
  }
  const submit = (e: FormEvent): void => {
    e.preventDefault()
    send(input)
    setInput('')
  }

  return (
    <div className="space-y-4">
      <section className="rounded-xl border border-line bg-surface" data-testid="test-panel">
        <header className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-2.5 text-sm font-medium">
          <MessagesSquare aria-hidden className="size-4 text-ink-muted" />
          测试对话
          <span className="text-xs font-normal text-ink-faint">
            私有环境 · 运行草稿，不影响已发布版本
          </span>
          <span className="ml-auto flex items-center gap-3 font-normal">
            <TestUserSelect value={asUser} onChange={setAsUser} />
            {runs.turns.length > 0 && (
              <button
                type="button"
                onClick={runs.reset}
                className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
              >
                <RotateCcw aria-hidden className="size-3.5" />
                清空
              </button>
            )}
          </span>
        </header>
        <div className="max-h-[440px] overflow-y-auto px-4 py-4">
          {runs.turns.length === 0 ? (
            <div className="flex flex-wrap gap-2">
              {prompts.map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => send(p)}
                  className="rounded-lg border border-line bg-canvas px-3 py-2 text-left text-sm transition-colors hover:border-line-strong"
                >
                  {p}
                </button>
              ))}
            </div>
          ) : (
            <ol className="space-y-4">
              {runs.turns.map((turn) => (
                <TestTurn
                  key={turn.id}
                  turn={turn}
                  busy={runs.busy}
                  selected={turn.id === selected?.id}
                  dataScope={dataScope}
                  onSelect={() => setPinned(turn.id)}
                  onConfirm={(runId, approved) => void runs.confirm(turn.id, runId, approved)}
                />
              ))}
            </ol>
          )}
        </div>
        <form onSubmit={submit} className="border-t border-line p-3">
          <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 focus-within:border-line-strong">
            <input
              aria-label="测试话术"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={2000}
              placeholder={`以 ${asUser} 的身份提问`}
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint"
            />
            <button
              type="submit"
              aria-label="发送测试话术"
              disabled={runs.busy || input.trim() === ''}
              className="disabled:opacity-40"
            >
              <SendHorizontal aria-hidden className="size-4 text-ink-muted" />
            </button>
          </div>
        </form>
      </section>
      {selected !== undefined &&
        selected.status !== 'streaming' &&
        selectedView?.traceId !== undefined && (
          <TraceTimeline traceId={selectedView.traceId} revision={selected.events.length} />
        )}
    </div>
  )
}
