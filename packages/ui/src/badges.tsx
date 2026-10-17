import type { ReactNode } from 'react'
import { Check, CircleDashed, Hand, X } from 'lucide-react'
import { cn } from './cn'
import { SOURCE_STYLES, type AssetSource } from './sources'

const BASE =
  'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium ring-1 ring-inset whitespace-nowrap'

interface SourceBadgeProps {
  source: AssetSource
  className?: string
}

export function SourceBadge({ source, className }: SourceBadgeProps): ReactNode {
  const style = SOURCE_STYLES[source]
  const Icon = style.icon
  return (
    <span className={cn(BASE, style.badgeClass, className)} title={style.description}>
      <Icon aria-hidden className="size-3" strokeWidth={2.25} />
      {style.label}
    </span>
  )
}

/** 写操作级别（主提示词 7.4 节）。 */
export const WRITE_LEVELS = ['L0', 'L1', 'L2', 'L3'] as const
export type WriteLevel = (typeof WRITE_LEVELS)[number]

const WRITE_LEVEL_STYLES: Readonly<Record<WriteLevel, { label: string; className: string }>> = {
  L0: { label: '只读', className: 'bg-surface-sunken text-ink-muted ring-line-strong' },
  L1: { label: '出建议', className: 'bg-info-soft text-info ring-info/25' },
  L2: { label: '需确认的写', className: 'bg-pending-soft text-pending ring-pending/30' },
  L3: { label: '自主写', className: 'bg-deny-soft text-deny ring-deny/25' },
}

interface WriteLevelBadgeProps {
  level: WriteLevel
  className?: string
}

export function WriteLevelBadge({ level, className }: WriteLevelBadgeProps): ReactNode {
  const style = WRITE_LEVEL_STYLES[level]
  return (
    <span className={cn(BASE, style.className, className)}>
      <span className="font-mono">{level}</span>
      <span className="opacity-80">{style.label}</span>
    </span>
  )
}

/** 通用状态标签的色调：沿用各专用标签的颜色令牌，页面里不另写颜色。 */
export type BadgeTone = 'neutral' | 'info' | 'pending' | 'allow' | 'deny'

const TONE_STYLES: Readonly<Record<BadgeTone, string>> = {
  neutral: 'bg-surface-sunken text-ink-muted ring-line-strong',
  info: 'bg-info-soft text-info ring-info/25',
  pending: 'bg-pending-soft text-pending ring-pending/30',
  allow: 'bg-allow-soft text-allow ring-allow/25',
  deny: 'bg-deny-soft text-deny ring-deny/25',
}

interface ToneBadgeProps {
  tone: BadgeTone
  className?: string
  children: ReactNode
}

/** 带色调的通用标签：给状态、结论档位这类领域叫法用，文本由调用方给出 */
export function ToneBadge({ tone, className, children }: ToneBadgeProps): ReactNode {
  return <span className={cn(BASE, TONE_STYLES[tone], className)}>{children}</span>
}

/** 鉴权与守卫的裁决结果。 */
export type DecisionOutcome = 'allow' | 'deny' | 'confirm' | 'skipped'

const DECISION_STYLES: Readonly<
  Record<DecisionOutcome, { label: string; icon: typeof Check; className: string }>
> = {
  allow: { label: '允许', icon: Check, className: 'bg-allow-soft text-allow ring-allow/25' },
  deny: { label: '拒绝', icon: X, className: 'bg-deny-soft text-deny ring-deny/25' },
  confirm: {
    label: '待确认',
    icon: Hand,
    className: 'bg-pending-soft text-pending ring-pending/30',
  },
  skipped: {
    label: '未检查',
    icon: CircleDashed,
    className: 'bg-surface-sunken text-ink-faint ring-line-strong',
  },
}

interface DecisionBadgeProps {
  outcome: DecisionOutcome
  /** 鉴权点或守卫名称，例如“鉴权点 3” */
  checkpoint?: string
  className?: string
}

export function DecisionBadge({ outcome, checkpoint, className }: DecisionBadgeProps): ReactNode {
  const style = DECISION_STYLES[outcome]
  const Icon = style.icon
  return (
    <span className={cn(BASE, style.className, className)}>
      <Icon aria-hidden className="size-3" strokeWidth={2.5} />
      {checkpoint !== undefined && <span className="opacity-80">{checkpoint}</span>}
      {style.label}
    </span>
  )
}
