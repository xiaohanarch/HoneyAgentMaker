import type { ReactNode } from 'react'
import { SOURCE_STYLES, cn } from '@eab/ui'
import { layoutLineage, linkPath, NODE } from './geometry'
import type { Edge, InventoryItem } from './types'

/** 连线样式：扩展用虚线，协作用点线，成员用实线 */
const RELATION_DASH: Record<string, string | undefined> = {
  扩展: '5 4',
  协作: '1.5 4',
}

/** 资产血缘图：助手 → 扩展 → Agent → 远程 Agent，手绘 SVG，颜色取资产来源的设计令牌 */
export function LineageGraph({
  items,
  edges,
  selected,
  onSelect,
}: {
  items: readonly InventoryItem[]
  edges: readonly Edge[]
  selected: string | undefined
  onSelect: (ref: string) => void
}): ReactNode {
  const layout = layoutLineage(items, edges)
  if (layout.nodes.length === 0) return null
  const pad = 8
  const highlighted = new Set<string>()
  if (selected !== undefined) {
    highlighted.add(selected)
    for (const l of layout.links) {
      if (l.from.item.ref === selected) highlighted.add(l.to.item.ref)
      if (l.to.item.ref === selected) highlighted.add(l.from.item.ref)
    }
  }
  return (
    <figure className="overflow-x-auto rounded-xl border border-line bg-surface p-4">
      <svg
        role="img"
        aria-label="资产血缘图"
        width={layout.width + pad * 2}
        height={layout.height + pad * 2}
        viewBox={`${-pad} ${-pad} ${layout.width + pad * 2} ${layout.height + pad * 2}`}
        className="block"
      >
        {layout.links.map((l) => {
          const active =
            selected === undefined || l.from.item.ref === selected || l.to.item.ref === selected
          return (
            <path
              key={`${l.from.item.ref}>${l.to.item.ref}:${l.relation}`}
              d={linkPath(l.from, l.to)}
              fill="none"
              strokeWidth={active ? 1.5 : 1}
              strokeDasharray={RELATION_DASH[l.relation]}
              className={cn(
                'transition-opacity',
                l.relation === '扩展' ? 'stroke-tenant' : 'stroke-line-strong',
                active ? 'opacity-100' : 'opacity-30',
              )}
            >
              <title>{`${l.from.item.name} —${l.relation}→ ${l.to.item.name}`}</title>
            </path>
          )
        })}
        {layout.nodes.map((n) => {
          const source = n.item.source === 'extension' ? 'tenant' : n.item.source
          const style = SOURCE_STYLES[source]
          const dim = selected !== undefined && !highlighted.has(n.item.ref)
          return (
            <g
              key={n.item.ref}
              transform={`translate(${n.x} ${n.y})`}
              role="button"
              tabIndex={0}
              aria-label={`${n.item.name}（${n.item.kind}）`}
              onClick={() => onSelect(n.item.ref)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') onSelect(n.item.ref)
              }}
              className={cn(
                'cursor-pointer outline-none transition-opacity focus-visible:[&>rect]:stroke-focus',
                dim && 'opacity-35',
              )}
            >
              <rect
                width={NODE.width}
                height={NODE.height}
                rx={8}
                strokeWidth={selected === n.item.ref ? 2 : 1}
                className={cn(
                  'fill-surface',
                  selected === n.item.ref ? 'stroke-ink' : 'stroke-line-strong',
                )}
              />
              <rect
                width={4}
                height={NODE.height - 16}
                x={0}
                y={8}
                rx={2}
                className={cn(style.textClass, 'fill-current')}
              />
              <text x={14} y={19} className="fill-ink text-[12px] font-medium">
                {n.item.name.length > 14 ? `${n.item.name.slice(0, 13)}…` : n.item.name}
              </text>
              <text x={14} y={34} className="fill-ink-faint font-mono text-[10px]">
                {`${n.item.kind} · ${n.item.version}`}
              </text>
            </g>
          )
        })}
      </svg>
      <figcaption className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-[11px] text-ink-muted">
        <span>实线：助手成员</span>
        <span className="text-tenant">虚线：扩展自标品</span>
        <span>点线：委派协作</span>
      </figcaption>
    </figure>
  )
}
