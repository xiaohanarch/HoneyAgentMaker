import type { ReactNode } from 'react'
import { cn } from '@eab/ui'
import type { StepResult } from '../extend/api'
import { ROW, layoutSkill, type SkillStep } from './skill-graph'

const WIDTH = 200
const HEIGHT = 52
const PAD = 16

const TYPE_LABELS: Record<string, string> = {
  tool: '工具',
  skill: '技能',
  transform: '变换',
  condition: '条件',
  confirm: '确认',
  notify: '通知',
  parallel: '并行',
  start: '',
  end: '',
}

function statusClass(status: string | undefined): string {
  switch (status) {
    case 'ok':
      return 'fill-allow-soft stroke-allow'
    case 'error':
      return 'fill-deny-soft stroke-deny'
    case 'suspended':
      return 'fill-pending-soft stroke-pending'
    default:
      return 'fill-surface stroke-line-strong'
  }
}

/**
 * 技能流程图：步骤自上而下，条件和并行分列，汇合到结果。单独测试后按每步的状态着色。
 * 用 SVG 直接画，布局在 skill-graph.ts 里计算。
 */
export function SkillGraph({
  steps,
  results,
  selected,
  onSelect,
}: {
  steps: readonly SkillStep[]
  results?: readonly StepResult[]
  selected?: string
  onSelect?: (id: string) => void
}): ReactNode {
  const { nodes, edges } = layoutSkill(steps)
  const status = new Map((results ?? []).map((r) => [r.id, r.status]))
  const minX = Math.min(...nodes.map((n) => n.x))
  const minY = Math.min(...nodes.map((n) => n.y))
  const maxX = Math.max(...nodes.map((n) => n.x)) + WIDTH
  const maxY = Math.max(...nodes.map((n) => n.y)) + HEIGHT
  const at = new Map(nodes.map((n) => [n.id, n]))
  const width = maxX - minX + PAD * 2
  const height = maxY - minY + PAD * 2

  return (
    <svg
      role="img"
      aria-label="技能流程图"
      data-testid="skill-graph"
      viewBox={`${minX - PAD} ${minY - PAD} ${width} ${height}`}
      className="h-auto w-full"
      style={{ maxWidth: width, maxHeight: Math.max(height, ROW * 2) }}
    >
      {edges.map((e) => {
        const from = at.get(e.source)
        const to = at.get(e.target)
        if (from === undefined || to === undefined) return null
        const x1 = from.x + WIDTH / 2
        const y1 = from.y + HEIGHT
        const x2 = to.x + WIDTH / 2
        const y2 = to.y
        const mid = y1 + Math.min((y2 - y1) / 2, ROW / 3)
        return (
          <g key={e.id}>
            <path
              d={`M${x1},${y1} L${x1},${mid} L${x2},${mid} L${x2},${y2}`}
              className="fill-none stroke-line-strong"
              strokeWidth={1.25}
            />
            {e.label !== undefined && (
              <text x={x2 + 4} y={y2 - 6} className="fill-ink-faint text-[10px]">
                {e.label}
              </text>
            )}
          </g>
        )
      })}
      {nodes.map((n) => {
        const terminal = n.type === 'start' || n.type === 'end'
        return (
          <g
            key={n.id}
            transform={`translate(${n.x},${n.y})`}
            onClick={terminal ? undefined : () => onSelect?.(n.id)}
            className={terminal || onSelect === undefined ? undefined : 'cursor-pointer'}
            data-step={terminal ? undefined : n.id}
          >
            <rect
              width={WIDTH}
              height={HEIGHT}
              rx={terminal ? HEIGHT / 2 : 8}
              strokeWidth={n.id === selected ? 2 : 1}
              className={cn(
                terminal ? 'fill-surface-sunken stroke-line' : statusClass(status.get(n.id)),
              )}
            />
            <text x={12} y={20} className="fill-ink text-[12px] font-medium">
              {n.label.length > 16 ? `${n.label.slice(0, 15)}…` : n.label}
            </text>
            {!terminal && (
              <text x={WIDTH - 10} y={20} textAnchor="end" className="fill-ink-faint text-[10px]">
                {TYPE_LABELS[n.type] ?? n.type}
              </text>
            )}
            <text x={12} y={39} className="fill-ink-muted font-mono text-[10px]">
              {n.detail.length > 28 ? `${n.detail.slice(0, 27)}…` : n.detail}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
