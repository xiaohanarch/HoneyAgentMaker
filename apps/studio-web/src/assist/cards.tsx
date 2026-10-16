import { useState, type ReactNode } from 'react'
import {
  ArrowUpRight,
  Check,
  CircleAlert,
  ExternalLink,
  Hand,
  LoaderCircle,
  Lightbulb,
  ShieldCheck,
  Wrench,
  X,
} from 'lucide-react'
import { WRITE_LEVELS, WriteLevelBadge, cn, type WriteLevel } from '@eab/ui'
import type { Option, Pending, ToolStep, TurnView } from './turn'

function isWriteLevel(value: unknown): value is WriteLevel {
  return typeof value === 'string' && (WRITE_LEVELS as readonly string[]).includes(value)
}

function formatArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`)
    .join('  ')
}

/** 流式的思考步骤：模型每一轮说明它接下来要做什么 */
export function Thoughts({ thoughts }: { thoughts: readonly string[] }): ReactNode {
  if (thoughts.length === 0) return null
  return (
    <ol className="space-y-1 border-l-2 border-line pl-3 text-xs text-ink-muted">
      {thoughts.map((t, i) => (
        <li key={i} className="flex gap-1.5">
          <Lightbulb aria-hidden className="mt-0.5 size-3 shrink-0 text-ink-faint" />
          <span>{t}</span>
        </li>
      ))}
    </ol>
  )
}

/** 工具卡片：调用了什么、参数、结果摘要；失败时显示原因 */
export function ToolCards({ tools }: { tools: readonly ToolStep[] }): ReactNode {
  if (tools.length === 0) return null
  return (
    <ul className="flex flex-col gap-1.5" aria-label="工具调用">
      {tools.map((t) => (
        <li
          key={t.callId}
          data-testid="tool-card"
          className="flex items-start gap-2 rounded-md border border-line bg-canvas px-2.5 py-1.5 text-xs"
        >
          {t.ok === undefined ? (
            <LoaderCircle
              aria-label="执行中"
              className="mt-0.5 size-3.5 shrink-0 animate-spin text-ink-faint"
            />
          ) : t.ok ? (
            <Wrench aria-label="成功" className="mt-0.5 size-3.5 shrink-0 text-allow" />
          ) : (
            <CircleAlert aria-label="失败" className="mt-0.5 size-3.5 shrink-0 text-deny" />
          )}
          <span className="min-w-0 flex-1">
            <span className="font-mono font-medium text-ink">{t.tool}</span>
            <span className="ml-2 break-all font-mono text-ink-faint">
              {formatArgs(t.arguments)}
            </span>
            {t.summary !== undefined && t.summary !== '' && (
              <span className={cn('block', t.ok === false ? 'text-deny' : 'text-ink-muted')}>
                {t.summary}
              </span>
            )}
          </span>
        </li>
      ))}
    </ul>
  )
}

interface ConfirmationCardProps {
  pending: Pending
  state: 'waiting' | 'resolved'
  disabled: boolean
  onDecide: (approved: boolean) => void
}

/** L2 写操作的确认卡片：写入前展示将要提交的内容，由用户本人确认或拒绝 */
export function ConfirmationCard({
  pending,
  state,
  disabled,
  onDecide,
}: ConfirmationCardProps): ReactNode {
  const level = pending.preview.writeLevel
  const args = (pending.preview.arguments ?? {}) as Record<string, unknown>
  const reason = typeof pending.preview.reason === 'string' ? pending.preview.reason : ''
  return (
    <section
      data-testid="confirmation-card"
      className="rounded-lg border border-pending/40 bg-pending-soft/40 px-3.5 py-3"
    >
      <header className="flex items-center gap-2 text-sm font-medium">
        <Hand aria-hidden className="size-4 text-pending" />
        {pending.title}
        {isWriteLevel(level) && <WriteLevelBadge level={level} className="ml-auto" />}
      </header>
      <dl className="mt-2 grid grid-cols-[max-content_1fr] gap-x-3 gap-y-1 text-xs">
        {Object.entries(args).map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="font-mono text-ink-faint">{k}</dt>
            <dd className="break-all">{typeof v === 'string' ? v : JSON.stringify(v)}</dd>
          </div>
        ))}
      </dl>
      {reason !== '' && <p className="mt-2 text-xs text-ink-muted">{reason}</p>}
      {state === 'waiting' ? (
        <div className="mt-3 flex gap-2">
          <button
            type="button"
            disabled={disabled}
            onClick={() => onDecide(true)}
            className="inline-flex items-center gap-1 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            <Check aria-hidden className="size-3.5" />
            确认执行
          </button>
          <button
            type="button"
            disabled={disabled}
            onClick={() => onDecide(false)}
            className="inline-flex items-center gap-1 rounded-md border border-line-strong px-3 py-1.5 text-xs transition-colors hover:bg-surface-sunken disabled:opacity-50"
          >
            <X aria-hidden className="size-3.5" />
            拒绝
          </button>
        </div>
      ) : (
        <p className="mt-2 text-xs text-ink-faint">已处理</p>
      )}
    </section>
  )
}

/** 导航型交互：DemoERP 只读页面的深链接 */
export function NavigationCard({
  navigation,
}: {
  navigation: NonNullable<TurnView['navigation']>
}): ReactNode {
  const card = navigation.card
  return (
    <div className="space-y-2">
      <p className="text-sm">{navigation.message}</p>
      {card !== undefined && (
        <a
          data-testid="navigation-card"
          href={card.url}
          target="_blank"
          rel="noopener noreferrer"
          className="group flex items-center gap-3 rounded-lg border border-line bg-canvas px-3.5 py-2.5 transition-colors hover:border-line-strong"
        >
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium">{card.title}</span>
            <span className="block text-xs text-ink-muted">{card.description}</span>
          </span>
          <ExternalLink aria-hidden className="size-4 text-ink-faint group-hover:text-ink" />
        </a>
      )}
    </div>
  )
}

interface ClarifyCardProps {
  clarify: NonNullable<TurnView['clarify']>
  disabled: boolean
  onPick: (option: Option) => void
}

/** 路由置信度低时请用户在候选里选一个 */
export function ClarifyCard({ clarify, disabled, onPick }: ClarifyCardProps): ReactNode {
  return (
    <div className="space-y-2" data-testid="clarify-card">
      <p className="text-sm">{clarify.message}</p>
      <div className="flex flex-wrap gap-2">
        {clarify.options.map((o) => (
          <button
            key={o.ref}
            type="button"
            disabled={disabled}
            onClick={() => onPick(o)}
            title={o.description}
            className="inline-flex items-center gap-1 rounded-md border border-line-strong bg-canvas px-3 py-1.5 text-xs transition-colors hover:bg-surface-sunken disabled:opacity-50"
          >
            {o.name}
            <ArrowUpRight aria-hidden className="size-3" />
          </button>
        ))}
      </div>
    </div>
  )
}

interface GapCardProps {
  gap: NonNullable<TurnView['gap']>
  onSubmit: () => Promise<string>
}

/** 能力缺口：说明原因，提供“提交扩展需求” */
export function GapCard({ gap, onSubmit }: GapCardProps): ReactNode {
  const [state, setState] = useState<{
    status: 'idle' | 'sending' | 'sent' | 'error'
    message?: string
  }>({
    status: 'idle',
  })
  const submit = (): void => {
    setState({ status: 'sending' })
    onSubmit().then(
      (message) => setState({ status: 'sent', message }),
      (error: unknown) =>
        setState({ status: 'error', message: error instanceof Error ? error.message : '提交失败' }),
    )
  }
  return (
    <div className="space-y-2" data-testid="gap-card">
      <p className="text-sm">{gap.message}</p>
      <p className="text-xs text-ink-muted">{gap.suggestion}</p>
      {state.status === 'sent' ? (
        <p className="text-xs text-allow">{state.message}</p>
      ) : (
        <button
          type="button"
          disabled={state.status === 'sending'}
          onClick={submit}
          className="rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          提交扩展需求
        </button>
      )}
      {state.status === 'error' && <p className="text-xs text-deny">{state.message}</p>}
    </div>
  )
}

interface ResultCardProps {
  text: string
  dataScope: string
  tools: readonly ToolStep[]
  unverified: readonly string[]
}

/** Agent 的结果卡片：回答正文，下面列出数据范围和依据（调用过的数据来源） */
export function ResultCard({ text, dataScope, tools, unverified }: ResultCardProps): ReactNode {
  const sources = tools.filter((t) => t.ok === true)
  return (
    <div className="space-y-2.5" data-testid="result-card">
      <p className="whitespace-pre-wrap text-sm leading-relaxed">{text}</p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line pt-2 text-[11px] text-ink-muted">
        <span className="inline-flex items-center gap-1">
          <ShieldCheck aria-hidden className="size-3.5 text-allow" />
          数据范围：{dataScope}
        </span>
        {sources.length > 0 && (
          <span>
            依据：
            {sources.map((s) => (
              <span key={s.callId} className="ml-1 font-mono">
                {s.tool}
              </span>
            ))}
          </span>
        )}
      </div>
      {unverified.length > 0 && (
        <p className="text-[11px] text-pending">未核实的数字：{unverified.join('、')}</p>
      )}
    </div>
  )
}
