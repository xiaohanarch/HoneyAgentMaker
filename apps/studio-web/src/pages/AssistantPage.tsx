import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import {
  Compass,
  FileSearch,
  LineChart,
  LoaderCircle,
  MessageSquareText,
  PencilLine,
  RotateCcw,
  SendHorizontal,
} from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../identity/identity-context'
import { postJson } from '../assist/api'
import {
  ClarifyCard,
  ConfirmationCard,
  GapCard,
  NavigationCard,
  ResultCard,
  Thoughts,
  ToolCards,
} from '../assist/cards'
import { NotificationArea } from '../assist/NotificationArea'
import { RoutingPanel, TracePanel } from '../assist/panels'
import { awaitingConfirmation, viewOf, type Turn } from '../assist/turn'
import { useConversation } from '../assist/use-conversation'

const ASSISTANT_NAME = import.meta.env.VITE_ASSISTANT_NAME ?? 'ERP 助手'

/** 四种交互模式各一句演示话术（主提示词 9.1.1 节） */
const MODES = [
  { icon: PencilLine, mode: '事务型', prompt: '把 PO-2026-0117 的交期改到下月 15 号' },
  { icon: Compass, mode: '导航型', prompt: '打开百润精密的供应商详情' },
  { icon: FileSearch, mode: '信息型', prompt: '我们的降价谈判制度是怎么规定的' },
  { icon: LineChart, mode: '分析型', prompt: '华东区本季度逾期交付率是多少' },
] as const

interface TurnItemProps {
  turn: Turn
  dataScope: string
  busy: boolean
  selected: boolean
  onSelect: () => void
  onConfirm: (runId: string, approved: boolean) => void
  onAsk: (question: string, target?: string) => void
  onDevRequest: (text: string, assistant: string) => Promise<string>
}

function TurnItem({
  turn,
  dataScope,
  busy,
  selected,
  onSelect,
  onConfirm,
  onAsk,
  onDevRequest,
}: TurnItemProps): ReactNode {
  const view = viewOf(turn)
  const waiting = awaitingConfirmation(view)
  const agentRun = view.final?.kind === undefined
  const answer = view.final?.text ?? (view.text !== '' ? view.text : undefined)
  return (
    <li className="space-y-3" data-testid="turn">
      <p className="ml-auto w-fit max-w-[80%] rounded-2xl rounded-br-sm bg-ink px-3.5 py-2 text-sm text-surface">
        {turn.question}
      </p>
      <div
        onClick={onSelect}
        className={cn(
          'max-w-[92%] space-y-2.5 rounded-2xl rounded-bl-sm border bg-canvas px-4 py-3 transition-colors',
          selected ? 'border-line-strong' : 'border-line hover:border-line-strong',
        )}
      >
        {view.routing?.targetName !== undefined && (
          <p className="text-[11px] font-medium text-ink-faint">
            {view.routing.assistant !== undefined ? `${view.routing.assistant.name} → ` : ''}
            {view.routing.targetName}
          </p>
        )}
        <Thoughts thoughts={view.thoughts} />
        <ToolCards tools={view.tools} />
        {view.pending !== undefined && view.runId !== undefined && (
          <ConfirmationCard
            pending={view.pending}
            state={waiting ? 'waiting' : 'resolved'}
            disabled={busy}
            onDecide={(approved) => onConfirm(view.runId ?? '', approved)}
          />
        )}
        {view.navigation !== undefined && <NavigationCard navigation={view.navigation} />}
        {view.clarify !== undefined && (
          <ClarifyCard
            clarify={view.clarify}
            disabled={busy}
            onPick={(o) => onAsk(turn.question, o.ref)}
          />
        )}
        {view.gap !== undefined && (
          <GapCard
            gap={view.gap}
            onSubmit={() =>
              onDevRequest(view.gap?.originalText ?? turn.question, view.gap?.assistant ?? '')
            }
          />
        )}
        {agentRun && answer !== undefined && answer !== '' && (
          <ResultCard
            text={answer}
            dataScope={dataScope}
            tools={view.tools}
            unverified={view.final?.unverified ?? []}
          />
        )}
        {turn.status === 'streaming' && (
          <p className="flex items-center gap-1.5 text-xs text-ink-faint">
            <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
            {view.routing === undefined ? '正在选择合适的助手…' : '处理中…'}
          </p>
        )}
        {view.error !== undefined && <p className="text-xs text-deny">出错了：{view.error}</p>}
        {!selected && turn.status !== 'streaming' && (
          <button
            type="button"
            onClick={onSelect}
            className="text-[11px] text-ink-faint hover:text-ink"
          >
            查看这一轮的路由与链路
          </button>
        )}
      </div>
    </li>
  )
}

/** 切换身份时以新的 key 重新挂载，对话、会话编号和侧栏都清空 */
export function AssistantPage(): ReactNode {
  const { user } = useIdentity()
  return <AssistantWorkspace key={user.id} />
}

function AssistantWorkspace(): ReactNode {
  const { user, token } = useIdentity()
  const conversation = useConversation(token)
  const [input, setInput] = useState('')
  // 用户点选过的轮次；没有点选时，侧栏跟随最新一轮
  const [pinnedId, setPinnedId] = useState<string | undefined>()
  const bottom = useRef<HTMLDivElement>(null)
  const { turns, busy } = conversation

  useEffect(() => {
    bottom.current?.scrollIntoView?.({ block: 'end' })
  }, [turns])

  const selected = turns.find((t) => t.id === pinnedId) ?? turns.at(-1)
  const selectedId = selected?.id
  const selectedView = selected === undefined ? undefined : viewOf(selected)

  const send = (question: string, target?: string): void => {
    if (question.trim() === '' || busy || token === undefined) return
    setPinnedId(undefined)
    void conversation.ask(question.trim(), target)
  }
  const submit = (e: FormEvent): void => {
    e.preventDefault()
    send(input)
    setInput('')
  }
  const devRequest = async (text: string, assistant: string): Promise<string> =>
    (await postJson<{ message: string }>('/dev-requests', token, { text, assistant })).message

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_340px] gap-5">
      <section className="flex h-[calc(100dvh-8.5rem)] min-h-[560px] flex-col rounded-xl border border-line bg-surface">
        <header className="flex items-center gap-2 border-b border-line px-5 py-2.5 text-xs text-ink-muted">
          <span className="font-medium text-ink">{ASSISTANT_NAME}</span>
          <span>{`${user.tenantName} · ${user.role} · ${user.dataScope}`}</span>
          <Link to="/channels/im" className="ml-auto inline-flex items-center gap-1 hover:text-ink">
            <MessageSquareText aria-hidden className="size-3.5" />
            IM 渠道
          </Link>
          {turns.length > 0 && (
            <button
              type="button"
              onClick={conversation.reset}
              className="inline-flex items-center gap-1 hover:text-ink"
            >
              <RotateCcw aria-hidden className="size-3.5" />
              新对话
            </button>
          )}
        </header>

        <div className="flex-1 overflow-y-auto px-6 py-6">
          {turns.length === 0 ? (
            <div>
              <h1 className="text-2xl font-semibold tracking-tight">
                {user.name}，今天想处理什么？
              </h1>
              <p className="mt-1.5 text-sm text-ink-muted">
                {`以 ${user.tenantName} · ${user.role} 的身份提问，回答范围受你的数据权限约束（${user.dataScope}）。`}
              </p>
              <ul className="mt-8 grid max-w-2xl grid-cols-2 gap-2.5">
                {MODES.map(({ icon: Icon, mode, prompt }) => (
                  <li key={mode}>
                    <button
                      type="button"
                      onClick={() => send(prompt)}
                      disabled={token === undefined}
                      className="w-full rounded-lg border border-line bg-canvas px-3.5 py-3 text-left transition-colors hover:border-line-strong disabled:opacity-60"
                    >
                      <span className="flex items-center gap-1.5 text-[11px] font-medium text-ink-faint">
                        <Icon aria-hidden className="size-3.5" />
                        {mode}
                      </span>
                      <span className="mt-1 block text-sm">{prompt}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <ol className="space-y-6">
              {turns.map((turn) => (
                <TurnItem
                  key={turn.id}
                  turn={turn}
                  dataScope={user.dataScope}
                  busy={busy}
                  selected={turn.id === selectedId}
                  onSelect={() => setPinnedId(turn.id)}
                  onConfirm={(runId, approved) =>
                    void conversation.confirm(turn.id, runId, approved)
                  }
                  onAsk={send}
                  onDevRequest={devRequest}
                />
              ))}
            </ol>
          )}
          <div ref={bottom} />
        </div>

        <form className="border-t border-line p-4" onSubmit={submit}>
          <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 focus-within:border-line-strong">
            <input
              aria-label="输入问题"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={2000}
              placeholder={
                token === undefined ? '正在连接平台…' : '输入问题，例如：百润精密最近表现怎么样'
              }
              disabled={token === undefined}
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint"
            />
            <button
              type="submit"
              aria-label="发送"
              disabled={busy || input.trim() === ''}
              className="disabled:opacity-40"
            >
              <SendHorizontal aria-hidden className="size-4 text-ink-muted" />
            </button>
          </div>
        </form>
      </section>

      <aside className="flex h-[calc(100dvh-8.5rem)] min-h-[560px] flex-col gap-4 overflow-y-auto">
        <RoutingPanel routing={selectedView?.routing} />
        <TracePanel
          traceId={selected?.status === 'streaming' ? undefined : selectedView?.traceId}
          token={token}
          revision={selected?.events.length ?? 0}
        />
        <NotificationArea token={token} />
      </aside>
    </div>
  )
}
