import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { Sparkles } from 'lucide-react'
import { cn } from '@eab/ui'
import { STEPS } from '../steps'

/** 向导左侧的步骤导航；已经走过的步骤（草稿记录的最大步骤之前）标为完成 */
export function StepNav({ draftId, reached }: { draftId: string; reached: number }): ReactNode {
  return (
    <nav aria-label="向导步骤" className="rounded-xl border border-line bg-surface p-2">
      <ol className="space-y-0.5">
        {STEPS.map((step, i) => (
          <li key={step.key}>
            <NavLink
              to={`/studio/drafts/${draftId}/${step.key}`}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-2.5 py-1.5 text-sm transition-colors',
                  isActive
                    ? 'bg-surface-sunken font-medium text-ink ring-1 ring-line'
                    : 'text-ink-muted hover:bg-surface-sunken hover:text-ink',
                )
              }
            >
              <span
                className={cn(
                  'flex size-5 shrink-0 items-center justify-center rounded-full font-mono text-[10px]',
                  i + 1 < reached ? 'bg-allow-soft text-allow' : 'bg-surface-sunken text-ink-faint',
                )}
              >
                {i + 1}
              </span>
              <span className="min-w-0 flex-1 truncate">{step.label}</span>
              {step.recommend !== undefined && (
                <Sparkles aria-label="有 AI 推荐" className="size-3 shrink-0 text-ink-faint" />
              )}
            </NavLink>
          </li>
        ))}
      </ol>
    </nav>
  )
}
