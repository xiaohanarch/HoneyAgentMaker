import { describe, expect, test } from 'vitest'
import type { Span } from './api'
import { avatarFor, hashName } from './avatar-shapes'
import { formatDuration, layoutTimeline } from './trace-layout'
import { STEPS, stepIndex } from './steps'

function span(
  spanId: string,
  parentSpanId: string | null,
  start: number,
  end: number | null,
  extra: Partial<Span> = {},
): Span {
  const t = (ms: number): string => new Date(Date.UTC(2026, 9, 16, 0, 0, 0, ms)).toISOString()
  return {
    spanId,
    parentSpanId,
    name: spanId,
    kind: 'tool',
    startTime: t(start),
    endTime: end === null ? null : t(end),
    status: 'ok',
    statusMessage: null,
    attributes: {},
    promptSegments: null,
    ...extra,
  }
}

describe('avatarFor', () => {
  test('同一个名称得到同一个头像，不同名称一般不同', () => {
    expect(avatarFor('price-negotiation-assistant')).toEqual(
      avatarFor('price-negotiation-assistant'),
    )
    expect(hashName('a')).not.toBe(hashName('b'))
    const shapes = new Set(
      ['a', 'b', 'c', 'd', 'e'].map((n) => JSON.stringify(avatarFor(n).shapes)),
    )
    expect(shapes.size).toBeGreaterThan(1)
  })

  test('图形在 48 的画布内，颜色取自固定的令牌类名', () => {
    for (const name of ['', '价格谈判助手', 'x'.repeat(80)]) {
      const avatar = avatarFor(name)
      expect(avatar.background).toMatch(/^fill-[a-z]+-soft$/)
      expect(avatar.shapes.length).toBeGreaterThanOrEqual(2)
      for (const s of avatar.shapes) {
        expect(s.className).toMatch(/^fill-[a-z]+$/)
        if (s.type === 'circle') expect(s.cx + s.r).toBeLessThanOrEqual(48)
        if (s.type === 'rect') expect(s.x + s.size).toBeLessThanOrEqual(48)
      }
    }
  })
})

describe('layoutTimeline', () => {
  test('按父子关系排行，条形按开始时间和时长占比定位', () => {
    const timeline = layoutTimeline([
      span('child-b', 'root', 600, 1000),
      span('root', null, 0, 1000),
      span('child-a', 'root', 0, 500),
      span('grandchild', 'child-a', 100, 200),
    ])
    expect(timeline.totalMs).toBe(1000)
    expect(timeline.rows.map((r) => [r.span.spanId, r.depth])).toEqual([
      ['root', 0],
      ['child-a', 1],
      ['grandchild', 2],
      ['child-b', 1],
    ])
    const childB = timeline.rows[3]
    expect(childB?.offset).toBeCloseTo(60)
    expect(childB?.width).toBeCloseTo(40)
    expect(childB?.durationMs).toBe(400)
  })

  test('瞬时 span 有最小宽度；没有结束时间的延续到末尾；父节点缺失的作为根', () => {
    const timeline = layoutTimeline([
      span('root', null, 0, 1000),
      span('instant', 'root', 500, 500),
      span('suspended', 'root', 800, null),
      span('orphan', 'missing', 900, 950),
    ])
    const byId = Object.fromEntries(timeline.rows.map((r) => [r.span.spanId, r]))
    expect(byId.instant?.width).toBe(0.5)
    expect(byId.suspended?.durationMs).toBeNull()
    expect((byId.suspended?.offset ?? 0) + (byId.suspended?.width ?? 0)).toBeCloseTo(100)
    expect(byId.orphan?.depth).toBe(0)
  })

  test('约束触发的守卫 span 被标出', () => {
    const timeline = layoutTimeline([
      span('g', null, 0, 1, { kind: 'guard', name: '约束 · PUR-R-007 触发' }),
      span('w', null, 0, 1, { kind: 'guard', name: '守卫 · 写级别' }),
    ])
    expect(timeline.rows.filter((r) => r.constraint).map((r) => r.span.spanId)).toEqual(['g'])
  })

  test('空列表与时长格式化', () => {
    expect(layoutTimeline([])).toEqual({ rows: [], totalMs: 0 })
    expect(formatDuration(null)).toBe('进行中')
    expect(formatDuration(42)).toBe('42 ms')
    expect(formatDuration(1500)).toBe('1.50 s')
    expect(formatDuration(25_000)).toBe('25.0 s')
  })
})

describe('STEPS', () => {
  test('向导有 13 步，推荐步骤与接口的 step 对应', () => {
    expect(STEPS).toHaveLength(13)
    expect(STEPS[0]?.label).toBe('描述目的')
    expect(STEPS[12]?.label).toBe('发布')
    expect(STEPS.filter((s) => s.recommend !== undefined).map((s) => s.recommend)).toEqual([
      'purpose',
      'naming',
      'process',
      'collaborators',
      'knowledge',
      'tools',
      'triggers',
      'channels',
      'instructions',
      'model',
    ])
    expect(stepIndex('test')).toBe(11)
    expect(stepIndex('unknown')).toBe(0)
  })
})
