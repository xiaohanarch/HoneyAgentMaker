import type { ReactNode } from 'react'
import { cn } from '@eab/ui'
import { COMPONENTS, ZONE_LABELS, type ArchComponent, type ArchZone } from './components'

/** 各区块回放点亮时的配色（完整类名写死，不拼接）；③ 应用层在 SOURCE_ACTIVE 里按来源覆盖 */
const ZONE_ACTIVE: Record<ArchZone, string> = {
  ux: 'fill-info-soft stroke-info',
  orchestration: 'fill-pending-soft stroke-pending',
  app: 'fill-platform-soft stroke-platform',
  knowledge: 'fill-allow-soft stroke-allow',
  capability: 'fill-platform-soft stroke-platform',
  'auth-wing': 'fill-deny-soft stroke-deny',
  'ops-wing': 'fill-info-soft stroke-info',
  dev: 'fill-info-soft stroke-info',
}

/** ③ 应用层的来源色：DemoERP 与远程 Agent 是外部系统，标品、租户、共享服务各自对应 */
const SOURCE_ACTIVE: Record<string, string> = {
  'app-erp': 'fill-external-soft stroke-external',
  'app-vendor': 'fill-vendor-soft stroke-vendor',
  'app-tenant': 'fill-tenant-soft stroke-tenant',
  'app-remote': 'fill-external-soft stroke-external',
  'app-shared': 'fill-platform-soft stroke-platform',
}

const SOURCE_DOT: Record<string, string> = {
  'app-erp': 'fill-external',
  'app-vendor': 'fill-vendor',
  'app-tenant': 'fill-tenant',
  'app-remote': 'fill-external',
  'app-shared': 'fill-platform',
}

/** 五层从上到下的排布顺序 */
const LAYER_ZONES: readonly ArchZone[] = ['ux', 'orchestration', 'app', 'knowledge', 'capability']

// ── 几何常量（viewBox 坐标）：左翼列 + 五层横行 + 右翼列 + 开发态贯穿带 ──
const W = 1040
const GAP = 14
const LAYER_X = 152
const LAYER_W = 736
const LEFT_X = 8
const RIGHT_X = W - 8 - 120
const WING_W = 120
const LAYER_TOP = 16
const LAYER_STRIDE = 100
const CELL_H = 56
const LABEL_H = 20
const LAYERS_BOTTOM = LAYER_TOP + (LAYER_ZONES.length - 1) * LAYER_STRIDE + LABEL_H + CELL_H
const WING_TOP = 40
const DEV_Y = LAYERS_BOTTOM + 16
const DEV_H = 52
const H = DEV_Y + DEV_H + 8

function activeClass(c: ArchComponent): string {
  return SOURCE_ACTIVE[c.id] ?? ZONE_ACTIVE[c.zone]
}

interface Cell {
  component: ArchComponent
  x: number
  y: number
  w: number
  h: number
  dashed?: boolean
}

function layerCells(): Cell[] {
  const cells: Cell[] = []
  LAYER_ZONES.forEach((zone, i) => {
    const comps = COMPONENTS.filter((c) => c.zone === zone)
    const top = LAYER_TOP + i * LAYER_STRIDE
    const cw = (LAYER_W - GAP * (comps.length - 1)) / comps.length
    comps.forEach((c, j) =>
      cells.push({ component: c, x: LAYER_X + j * (cw + GAP), y: top + LABEL_H, w: cw, h: CELL_H }),
    )
  })
  return cells
}

function wingCells(): Cell[] {
  const cells: Cell[] = []
  const left = COMPONENTS.filter((c) => c.zone === 'auth-wing')
  const right = COMPONENTS.filter((c) => c.zone === 'ops-wing')
  const leftH = (LAYERS_BOTTOM - WING_TOP - GAP * (left.length - 1)) / left.length
  left.forEach((c, i) =>
    cells.push({ component: c, x: LEFT_X, y: WING_TOP + i * (leftH + GAP), w: WING_W, h: leftH }),
  )
  const rightH = (LAYERS_BOTTOM - WING_TOP - 12 * (right.length - 1)) / right.length
  right.forEach((c, i) =>
    cells.push({ component: c, x: RIGHT_X, y: WING_TOP + i * (rightH + 12), w: WING_W, h: rightH }),
  )
  return cells
}

const CELLS: readonly Cell[] = [
  ...layerCells(),
  ...wingCells(),
  {
    component: COMPONENTS.find((c) => c.id === 'dev-studio')!,
    x: 8,
    y: DEV_Y,
    w: W - 16,
    h: DEV_H,
    dashed: true,
  },
]

type CellState = 'active' | 'dim' | 'idle'

function CellRect({ cell, state }: { cell: Cell; state: CellState }): ReactNode {
  const { component: c, x, y, w, h } = cell
  return (
    <g
      data-testid="arch-component"
      data-id={c.id}
      data-state={state}
      className={cn('transition-opacity duration-200', state === 'dim' && 'opacity-35')}
    >
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={9}
        strokeWidth={state === 'active' ? 2 : 1}
        strokeDasharray={cell.dashed === true ? '6 4' : undefined}
        className={cn(
          'fill-surface transition-colors',
          state === 'active' ? activeClass(c) : 'stroke-line-strong',
        )}
      >
        <title>{`${c.label}：${c.description}`}</title>
      </rect>
      {SOURCE_DOT[c.id] !== undefined && (
        <circle cx={x + 13} cy={y + 21} r={3} className={SOURCE_DOT[c.id]} />
      )}
      <text
        x={x + (SOURCE_DOT[c.id] !== undefined ? 22 : 14)}
        y={y + 25}
        className="fill-ink text-[12px] font-medium"
      >
        {c.label}
      </text>
      <text x={x + 14} y={y + 43} className="fill-ink-faint text-[9.5px]">
        {c.description}
      </text>
    </g>
  )
}

/**
 * 五层两翼架构图（静态 SVG）：回放时把当前 span 映射到的组件点亮（active），
 * 其余置暗（dim）；没有进行中的高亮时全部处于 idle。链路追踪是本页回放的载体，回放期间常亮。
 */
export function Diagram({
  highlighted,
  replaying,
}: {
  highlighted: ReadonlySet<string>
  replaying: boolean
}): ReactNode {
  return (
    <figure className="overflow-x-auto rounded-xl border border-line bg-surface p-4">
      <svg
        role="img"
        aria-label="五层两翼架构图"
        viewBox={`0 0 ${W} ${H}`}
        className="block min-w-[880px]"
        data-testid="arch-diagram"
      >
        {/* 五层的层名 */}
        {LAYER_ZONES.map((zone, i) => (
          <text
            key={zone}
            x={LAYER_X}
            y={LAYER_TOP + i * LAYER_STRIDE + 13}
            className="fill-ink-muted text-[11px] font-medium"
          >
            {ZONE_LABELS[zone]}
          </text>
        ))}
        {/* 两翼的翼名 */}
        <text
          x={LEFT_X + WING_W / 2}
          y={29}
          textAnchor="middle"
          className="fill-ink-muted text-[11px] font-medium"
        >
          {ZONE_LABELS['auth-wing']}
        </text>
        <text
          x={RIGHT_X + WING_W / 2}
          y={29}
          textAnchor="middle"
          className="fill-ink-muted text-[11px] font-medium"
        >
          {ZONE_LABELS['ops-wing']}
        </text>
        {CELLS.map((cell) => {
          const active =
            highlighted.has(cell.component.id) || (cell.component.id === 'wing-trace' && replaying)
          const state: CellState = active ? 'active' : highlighted.size > 0 ? 'dim' : 'idle'
          return <CellRect key={cell.component.id} cell={cell} state={state} />
        })}
      </svg>
      <figcaption className="mt-3 flex flex-col gap-1 text-[11px] leading-relaxed text-ink-muted">
        <p>
          鉴权点 1 在入口校验用户令牌、鉴权点 4 在 BO API 内裁决，没有独立 span：前者在回放第 0
          帧标注， 后者并入 BO 工具行，BO 工具执行期间左翼鉴权同步点亮（鉴权点 3 在工具网关、点 4 在
          BO API 内）。 受托运行的开头由页面合成「业务事件」一帧。
        </p>
        <p>
          链路追踪在回放期间常亮（本页就是它的一部分）；评测、指标、生命周期、审计是治理面能力，
          不在单次运行链路里点亮。应用层按资产来源分色，回放时长为归一化的 12 秒，不是真实耗时。
        </p>
      </figcaption>
    </figure>
  )
}
