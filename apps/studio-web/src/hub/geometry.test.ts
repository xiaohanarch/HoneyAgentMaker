import { describe, expect, it } from 'vitest'
import { bars, duration, layoutLineage, linePlot, linkPath, NODE, percent, trend } from './geometry'
import type { InventoryItem } from './types'

const box = { width: 100, height: 50, padX: 0, padY: 0 }

function item(ref: string, kind: string): InventoryItem {
  return {
    ref,
    kind,
    version: '1.0.0',
    source: 'vendor',
    name: ref,
    description: null,
    status: 'published',
    signed: true,
    environments: {},
  }
}

describe('linePlot', () => {
  it('把最小值放在底部、最大值放在顶部，并生成闭合的面积路径', () => {
    const plot = linePlot(
      [
        { label: 'a', value: 1 },
        { label: 'b', value: 3 },
        { label: 'c', value: 2 },
      ],
      box,
    )
    expect(plot.points.map((p) => [p.x, p.y])).toEqual([
      [0, 50],
      [50, 0],
      [100, 25],
    ])
    expect(plot.path).toBe('M0 50 L50 0 L100 25')
    expect(plot.area.endsWith('L100 50 L0 50 Z')).toBe(true)
    expect([plot.min, plot.max]).toEqual([1, 3])
  })

  it('值全相同时画在中线，单点居中，空数组返回空路径', () => {
    expect(
      linePlot(
        [
          { label: 'a', value: 5 },
          { label: 'b', value: 5 },
        ],
        box,
      ).points.map((p) => p.y),
    ).toEqual([25, 25])
    expect(linePlot([{ label: 'a', value: 5 }], box).points[0]?.x).toBe(50)
    expect(linePlot([], box).path).toBe('')
  })
})

describe('bars', () => {
  it('最大值顶满，负值和全零按零高度处理', () => {
    const result = bars([2, 4, -1], { width: 30, height: 40, padX: 0, padY: 0 }, 0)
    expect(result.map((b) => b.height)).toEqual([20, 40, 0])
    expect(result.map((b) => b.x)).toEqual([0, 10, 20])
    expect(bars([0, 0], box).every((b) => b.height === 0)).toBe(true)
    expect(bars([], box)).toEqual([])
  })
})

describe('trend 与格式化', () => {
  it('按指标方向判断升降的好坏', () => {
    expect(trend(90, 85, 'higher')).toEqual({ delta: 5, good: true })
    expect(trend(20, 30, 'lower')).toEqual({ delta: -10, good: true })
    expect(trend(40, 30, 'lower')?.good).toBe(false)
    expect(trend(null, 30, 'higher')).toBeUndefined()
  })

  it('百分比和耗时', () => {
    expect(percent(0.9567)).toBe('95.7%')
    expect(percent(null)).toBe('—')
    expect(duration(850)).toBe('850 ms')
    expect(duration(12345)).toBe('12.3 s')
  })
})

describe('layoutLineage', () => {
  const items = [
    item('t1/assistant', 'Assistant'),
    item('vendor.fin/agent', 'Agent'),
    item('tenant.t001.finance/ext', 'AgentExtension'),
    item('ext/remote', 'RemoteAgent'),
    item('lonely/agent', 'Agent'),
  ]
  const edges = [
    { from: 't1/assistant', to: 'vendor.fin/agent', relation: '成员' },
    { from: 'tenant.t001.finance/ext', to: 'vendor.fin/agent', relation: '扩展' },
    { from: 'vendor.fin/agent', to: 'ext/remote', relation: '协作' },
    { from: 'vendor.fin/agent', to: 'missing/x', relation: '协作' },
  ]

  it('只放有边相连的资产，按助手、扩展、Agent、远程 Agent 分列', () => {
    const layout = layoutLineage(items, edges)
    const columnOf = Object.fromEntries(layout.nodes.map((n) => [n.item.ref, n.column]))
    expect(columnOf).toEqual({
      't1/assistant': 0,
      'tenant.t001.finance/ext': 1,
      'vendor.fin/agent': 2,
      'ext/remote': 3,
    })
    expect(layout.links).toHaveLength(3)
    expect(layout.width).toBe(4 * (NODE.width + NODE.colGap) - NODE.colGap)
    expect(layout.height).toBe(NODE.height)
  })

  it('空列不占位置，连线从左节点右侧画到右节点左侧', () => {
    const layout = layoutLineage(items.slice(0, 2), edges.slice(0, 1))
    expect(layout.nodes.map((n) => n.column)).toEqual([0, 1])
    const [assistant, agent] = layout.nodes
    if (assistant === undefined || agent === undefined) throw new Error('缺少节点')
    expect(linkPath(agent, assistant)).toMatch(new RegExp(`^M${NODE.width} `))
    expect(linkPath({ ...assistant }, { ...assistant, y: 100 })).toContain('C')
    expect(layoutLineage([], [])).toEqual({ nodes: [], links: [], width: 0, height: 0 })
  })
})
