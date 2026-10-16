/**
 * 治理中心图表与血缘图的纯计算：折线的坐标、刻度，血缘图的分层布局。不依赖 React，便于单元测试。
 */
import type { Edge, InventoryItem } from './types'

export interface Box {
  width: number
  height: number
  padX: number
  padY: number
}

export interface Plot {
  points: { x: number; y: number; value: number; label: string }[]
  path: string
  area: string
  min: number
  max: number
}

/** 把一组数值映射到图框内的折线坐标；值全相同时画在中线，空数组返回空路径 */
export function linePlot(values: readonly { label: string; value: number }[], box: Box): Plot {
  if (values.length === 0) return { points: [], path: '', area: '', min: 0, max: 0 }
  const nums = values.map((v) => v.value)
  const min = Math.min(...nums)
  const max = Math.max(...nums)
  const span = max - min
  const innerW = box.width - box.padX * 2
  const innerH = box.height - box.padY * 2
  const step = values.length > 1 ? innerW / (values.length - 1) : 0
  const points = values.map((v, i) => ({
    x: round(box.padX + (values.length > 1 ? i * step : innerW / 2)),
    y: round(
      span === 0 ? box.padY + innerH / 2 : box.padY + innerH - ((v.value - min) / span) * innerH,
    ),
    value: v.value,
    label: v.label,
  }))
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' ')
  const baseline = box.height - box.padY
  const first = points[0]
  const last = points[points.length - 1]
  const area =
    first === undefined || last === undefined
      ? ''
      : `${path} L${last.x} ${baseline} L${first.x} ${baseline} Z`
  return { points, path, area, min, max }
}

/** 柱状图：每根柱子的位置和高度，最大值顶满图框 */
export function bars(
  values: readonly number[],
  box: Box,
  gap = 2,
): { x: number; y: number; width: number; height: number }[] {
  if (values.length === 0) return []
  const max = Math.max(...values, 0)
  const innerW = box.width - box.padX * 2
  const innerH = box.height - box.padY * 2
  const width = Math.max(1, innerW / values.length - gap)
  return values.map((v, i) => {
    const height = max === 0 ? 0 : (Math.max(v, 0) / max) * innerH
    return {
      x: round(box.padX + i * (width + gap)),
      y: round(box.padY + innerH - height),
      width: round(width),
      height: round(height),
    }
  })
}

/** 指标相对上期的变化，按方向判断好坏（higher：越高越好；lower：越低越好） */
export function trend(
  value: number | null,
  previous: number | null,
  direction: string,
): { delta: number; good: boolean } | undefined {
  if (value === null || previous === null) return undefined
  const delta = round(value - previous)
  const good = direction === 'lower' ? delta <= 0 : delta >= 0
  return { delta, good }
}

export function round(n: number, digits = 2): number {
  const f = 10 ** digits
  return Math.round(n * f) / f
}

export function percent(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—'
  return `${round(rate * 100, 1)}%`
}

export function duration(ms: number): string {
  if (ms < 1000) return `${ms} ms`
  return `${round(ms / 1000, 1)} s`
}

// ── 血缘图 ──

export interface LineageNode {
  item: InventoryItem
  column: number
  row: number
  x: number
  y: number
}

export interface LineageLink {
  from: LineageNode
  to: LineageNode
  relation: string
}

export interface LineageLayout {
  nodes: LineageNode[]
  links: LineageLink[]
  width: number
  height: number
}

/** 血缘图里的列：助手 → Agent 与扩展 → 远程 Agent */
const COLUMN_OF_KIND: Record<string, number> = {
  Assistant: 0,
  AgentExtension: 1,
  Agent: 2,
  RemoteAgent: 3,
}

export const NODE = { width: 188, height: 44, colGap: 64, rowGap: 14 } as const

/**
 * 只画有边相连的资产：助手、扩展、Agent、远程 Agent 四列，列内按名称排序，
 * 扩展与其标品 Agent 尽量对齐在同一行附近。
 */
export function layoutLineage(
  items: readonly InventoryItem[],
  edges: readonly Edge[],
): LineageLayout {
  const byRef = new Map(items.map((i) => [i.ref, i]))
  const linked = new Set<string>()
  for (const e of edges) {
    if (byRef.has(e.from) && byRef.has(e.to)) {
      linked.add(e.from)
      linked.add(e.to)
    }
  }
  const columns: InventoryItem[][] = [[], [], [], []]
  for (const ref of [...linked].sort()) {
    const item = byRef.get(ref)
    if (item === undefined) continue
    const column = COLUMN_OF_KIND[item.kind] ?? 2
    columns[column]?.push(item)
  }
  const nodes: LineageNode[] = []
  const nodeOf = new Map<string, LineageNode>()
  const used = columns.map((c, i) => ({ c, i })).filter(({ c }) => c.length > 0)
  used.forEach(({ c }, visual) => {
    c.forEach((item, row) => {
      const node = {
        item,
        column: visual,
        row,
        x: visual * (NODE.width + NODE.colGap),
        y: row * (NODE.height + NODE.rowGap),
      }
      nodes.push(node)
      nodeOf.set(item.ref, node)
    })
  })
  const links: LineageLink[] = []
  for (const e of edges) {
    const from = nodeOf.get(e.from)
    const to = nodeOf.get(e.to)
    if (from !== undefined && to !== undefined) links.push({ from, to, relation: e.relation })
  }
  const rows = Math.max(0, ...used.map(({ c }) => c.length))
  return {
    nodes,
    links,
    width: Math.max(0, used.length * (NODE.width + NODE.colGap) - NODE.colGap),
    height: Math.max(0, rows * (NODE.height + NODE.rowGap) - NODE.rowGap),
  }
}

/** 两个节点之间的三次贝塞尔连线：从左节点右侧出，到右节点左侧入；同列时从下方绕 */
export function linkPath(from: LineageNode, to: LineageNode): string {
  const [a, b] = from.x <= to.x ? [from, to] : [to, from]
  const y1 = a.y + NODE.height / 2
  const y2 = b.y + NODE.height / 2
  if (a.column === b.column) {
    const x = a.x + NODE.width
    return `M${x} ${y1} C${x + 32} ${y1} ${x + 32} ${y2} ${x} ${y2}`
  }
  const x1 = a.x + NODE.width
  const x2 = b.x
  const mid = (x1 + x2) / 2
  return `M${x1} ${y1} C${mid} ${y1} ${mid} ${y2} ${x2} ${y2}`
}
