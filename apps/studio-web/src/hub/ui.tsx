import type { ReactNode } from 'react'
import { CircleAlert, LoaderCircle } from 'lucide-react'
import { SOURCE_STYLES, cn } from '@eab/ui'
import { ApiError } from '../assist/api'
import type { AssetSourceKind } from './types'

/** 分区的标题行：标题、一句说明、右侧操作 */
export function SectionHeader({
  title,
  description,
  actions,
}: {
  title: string
  description: string
  actions?: ReactNode
}): ReactNode {
  return (
    <header className="flex flex-wrap items-end gap-3 border-b border-line pb-3">
      <div className="min-w-0 flex-1">
        <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
        <p className="mt-0.5 text-xs text-ink-muted">{description}</p>
      </div>
      {actions !== undefined && <div className="flex flex-wrap gap-2">{actions}</div>}
    </header>
  )
}

export function Loading(): ReactNode {
  return (
    <p className="flex items-center gap-1.5 py-6 text-xs text-ink-faint">
      <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
      正在加载…
    </p>
  )
}

export function ErrorLine({ error }: { error: unknown }): ReactNode {
  if (error === null || error === undefined) return null
  const message = error instanceof ApiError ? `${error.message}（${error.code}）` : String(error)
  return (
    <p role="alert" className="flex items-center gap-1.5 text-xs text-deny">
      <CircleAlert aria-hidden className="size-3.5 shrink-0" />
      {message}
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }): ReactNode {
  return (
    <p className="rounded-lg border border-dashed border-line px-4 py-6 text-center text-xs text-ink-faint">
      {children}
    </p>
  )
}

type Tone = 'allow' | 'deny' | 'pending' | 'info' | 'neutral'

const TONES: Record<Tone, string> = {
  allow: 'bg-allow-soft text-allow ring-allow/25',
  deny: 'bg-deny-soft text-deny ring-deny/25',
  pending: 'bg-pending-soft text-pending ring-pending/25',
  info: 'bg-info-soft text-info ring-info/25',
  neutral: 'bg-surface-sunken text-ink-muted ring-line',
}

export function Pill({ tone, children }: { tone: Tone; children: ReactNode }): ReactNode {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset',
        TONES[tone],
      )}
    >
      {children}
    </span>
  )
}

/** 审计裁决、评测结果等状态词对应的色调 */
export function toneOf(status: string): Tone {
  if (['allow', 'passed', 'approved', 'ready', 'active'].includes(status)) return 'allow'
  if (['deny', 'failed', 'rejected', 'high', 'critical'].includes(status)) return 'deny'
  if (['confirm', 'pending', 'medium', 'warning'].includes(status)) return 'pending'
  return 'neutral'
}

/** 资产来源标签：扩展沿用租户色，其余取 @eab/ui 的来源样式 */
export function SourcePill({ source }: { source: AssetSourceKind }): ReactNode {
  const style = SOURCE_STYLES[source === 'extension' ? 'tenant' : source]
  const Icon = style.icon
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset',
        style.badgeClass,
      )}
    >
      <Icon aria-hidden className="size-3" />
      {source === 'extension' ? '扩展' : style.label}
    </span>
  )
}

export function Button({
  children,
  onClick,
  disabled,
  variant = 'secondary',
  type = 'button',
  label,
}: {
  children: ReactNode
  onClick?: () => void
  disabled?: boolean
  variant?: 'primary' | 'secondary' | 'danger'
  type?: 'button' | 'submit'
  label?: string
}): ReactNode {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:cursor-not-allowed disabled:opacity-50',
        variant === 'primary' && 'bg-ink text-surface hover:bg-ink/85',
        variant === 'secondary' &&
          'border border-line bg-surface text-ink hover:border-line-strong hover:bg-surface-sunken',
        variant === 'danger' && 'border border-deny/30 bg-deny-soft text-deny hover:border-deny/60',
      )}
    >
      {children}
    </button>
  )
}

export function shortTime(at: string | null | undefined): string {
  if (at === null || at === undefined || at === '') return '—'
  return at.replace('T', ' ').slice(0, 16)
}
