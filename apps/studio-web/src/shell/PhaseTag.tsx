import type { ReactNode } from 'react'

/** 标注某个界面区域在哪个阶段接通，骨架阶段用。 */
export function PhaseTag({ phase }: { phase: string }): ReactNode {
  return (
    <span className="rounded border border-dashed border-line-strong px-1.5 py-px font-mono text-[10px] text-ink-faint">
      {phase}
    </span>
  )
}
