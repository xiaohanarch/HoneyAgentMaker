import type { ReactNode } from 'react'
import { cn } from '@eab/ui'

export interface CheckItem {
  id: string
  title: string
  subtitle?: string | null
  /** 不可选的候选，例如 P7 前的远程 Agent */
  disabled?: boolean
  note?: string | null
  badge?: ReactNode
}

/** 候选多选列表：向导里协作 Agent、语义包、技能、工具、渠道、约束等步骤共用 */
export function CheckList({
  label,
  items,
  selected,
  onToggle,
  single = false,
}: {
  label: string
  items: readonly CheckItem[]
  selected: readonly string[]
  onToggle: (id: string) => void
  single?: boolean
}): ReactNode {
  if (items.length === 0) {
    return <p className="text-xs text-ink-muted">{`${label}：没有候选`}</p>
  }
  return (
    <fieldset className="space-y-1.5">
      <legend className="mb-1 text-xs font-medium text-ink-muted">{label}</legend>
      {items.map((item) => {
        const checked = selected.includes(item.id)
        return (
          <label
            key={item.id}
            className={cn(
              'flex items-start gap-2.5 rounded-lg border px-3 py-2 text-sm transition-colors',
              item.disabled === true
                ? 'cursor-not-allowed border-dashed border-line opacity-60'
                : 'cursor-pointer',
              item.disabled !== true && checked && 'border-tenant/40 bg-tenant-soft/40',
              item.disabled !== true && !checked && 'border-line hover:border-line-strong',
            )}
          >
            <input
              type={single ? 'radio' : 'checkbox'}
              checked={checked}
              disabled={item.disabled === true}
              onChange={() => onToggle(item.id)}
              className="mt-0.5"
            />
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2">
                <span className="font-medium">{item.title}</span>
                {item.badge}
              </span>
              {item.subtitle !== undefined && item.subtitle !== null && item.subtitle !== '' && (
                <span className="block text-xs text-ink-muted">{item.subtitle}</span>
              )}
              {item.note !== undefined && item.note !== null && (
                <span className="block text-[11px] text-pending">{item.note}</span>
              )}
            </span>
          </label>
        )
      })}
    </fieldset>
  )
}
