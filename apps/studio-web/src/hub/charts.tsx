import { useId, type ReactNode } from 'react'
import { cn } from '@eab/ui'
import { bars, linePlot } from './geometry'

/** 小折线图：带面积底色和最后一个点的标记；颜色由 className 的 text-* 决定 */
export function Sparkline({
  values,
  width = 220,
  height = 56,
  className,
  label,
}: {
  values: readonly { label: string; value: number }[]
  width?: number
  height?: number
  className?: string
  label: string
}): ReactNode {
  const gradient = useId()
  const plot = linePlot(values, { width, height, padX: 3, padY: 6 })
  const last = plot.points[plot.points.length - 1]
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${width} ${height}`}
      className={cn('h-14 w-full overflow-visible', className)}
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="currentColor" stopOpacity={0.18} />
          <stop offset="100%" stopColor="currentColor" stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={plot.area} fill={`url(#${gradient})`} />
      <path
        d={plot.path}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        vectorEffect="non-scaling-stroke"
      />
      {last !== undefined && <circle cx={last.x} cy={last.y} r={2.5} fill="currentColor" />}
    </svg>
  )
}

/** 每日运行量柱状图，失败数叠在柱子底部 */
export function RunBars({
  days,
  height = 120,
}: {
  days: readonly { day: string; runs: number; failed: number }[]
  height?: number
}): ReactNode {
  const width = 600
  const box = { width, height, padX: 0, padY: 4 }
  const total = bars(
    days.map((d) => d.runs),
    box,
  )
  const max = Math.max(0, ...days.map((d) => d.runs))
  const innerH = height - 8
  return (
    <svg
      role="img"
      aria-label="每日运行次数与失败次数"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className="h-32 w-full"
    >
      {total.map((b, i) => {
        const d = days[i]
        if (d === undefined) return null
        const failedH = max === 0 ? 0 : (d.failed / max) * innerH
        return (
          <g key={d.day}>
            <rect
              x={b.x}
              y={b.y}
              width={b.width}
              height={b.height}
              rx={1.5}
              className="fill-ink/15"
            >
              <title>{`${d.day}：运行 ${d.runs} 次，失败 ${d.failed} 次`}</title>
            </rect>
            <rect
              x={b.x}
              y={height - 4 - failedH}
              width={b.width}
              height={failedH}
              rx={1.5}
              className="fill-deny/70"
            />
          </g>
        )
      })}
    </svg>
  )
}
