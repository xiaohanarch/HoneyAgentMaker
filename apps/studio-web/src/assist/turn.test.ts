import { describe, expect, test } from 'vitest'
import { parseSseBlock, type RunEvent } from './api'
import { awaitingConfirmation, viewOf, type Turn } from './turn'
import { replyText } from '../pages/ImChannelPage'

function ev(type: string, data: Record<string, unknown> = {}): RunEvent {
  return { type, agent: 'vendor.pur/purchase-order-clerk', data }
}

function turn(events: RunEvent[]): Turn {
  return { id: 't1', question: '把 PO-2026-0117 的交期改到下月 15 号', events, status: 'done' }
}

describe('parseSseBlock', () => {
  test('取出 data 行的 JSON，忽略事件名和注释', () => {
    const block = 'event:routing\ndata:{"type":"routing","agent":null,"data":{"outcome":"route"}}'
    expect(parseSseBlock(block)).toEqual({
      type: 'routing',
      agent: null,
      data: { outcome: 'route' },
    })
  })

  test('没有 data 行或 JSON 不完整时返回 undefined', () => {
    expect(parseSseBlock(': keep-alive')).toBeUndefined()
    expect(parseSseBlock('data:{"type":')).toBeUndefined()
  })
})

describe('viewOf', () => {
  test('工具调用与结果按 callId 配对，文本增量拼接成回答', () => {
    const view = viewOf(
      turn([
        ev('run_started', { runId: 'run-1', traceId: 'tr-1', agent: '供应商绩效分析' }),
        ev('step', { text: '先取口径' }),
        ev('tool_call', { callId: 'c1', tool: 'delivery_list', arguments: { limit: 200 } }),
        ev('tool_result', { callId: 'c1', tool: 'delivery_list', ok: true, summary: '返回 12 条' }),
        ev('text_delta', { delta: '逾期交付率' }),
        ev('text_delta', { delta: ' 25%' }),
        ev('final', { text: '逾期交付率 25%', traceId: 'tr-1', unverified: [] }),
      ]),
    )
    expect(view.runId).toBe('run-1')
    expect(view.thoughts).toEqual(['先取口径'])
    expect(view.tools).toEqual([
      {
        agent: 'vendor.pur/purchase-order-clerk',
        callId: 'c1',
        tool: 'delivery_list',
        arguments: { limit: 200 },
        ok: true,
        summary: '返回 12 条',
      },
    ])
    expect(view.text).toBe('逾期交付率 25%')
    expect(view.final?.kind).toBeUndefined()
  })

  test('确认请求之后没有最终结果时在等待确认；恢复运行收到 final 后不再等待，运行编号保持第一次的', () => {
    const suspended = [
      ev('run_started', { runId: 'run-1', traceId: 'tr-1' }),
      ev('confirmation_required', { key: 'k', title: '确认执行 x', preview: { writeLevel: 'L2' } }),
    ]
    expect(awaitingConfirmation(viewOf(turn(suspended)))).toBe(true)
    const resumed = viewOf(
      turn([
        ...suspended,
        ev('run_started', { runId: 'run-1', traceId: 'tr-1' }),
        ev('final', { text: '已提交' }),
      ]),
    )
    expect(awaitingConfirmation(resumed)).toBe(false)
    expect(resumed.confirmed).toBe(true)
    expect(resumed.runId).toBe('run-1')
  })

  test('父 Agent 与子 Agent 的调用编号相同时，各自保留一张工具卡片', () => {
    const parent = ev('tool_call', {
      callId: 'tool:call_1_1',
      tool: 'purchase_order_list',
      arguments: {},
    })
    const child = {
      type: 'tool_call',
      agent: 'vendor.pur/supplier-performance-analyst',
      data: { callId: 'tool:call_1_1', tool: 'supplier-lookup', arguments: {} },
    }
    expect(viewOf(turn([parent, child])).tools.map((t) => t.tool)).toEqual([
      'purchase_order_list',
      'supplier-lookup',
    ])
  })

  test('恢复运行时同一个 callId 再次出现，替换原来的工具卡片而不是重复', () => {
    const call = ev('tool_call', { callId: 'tool:c1', tool: 'x', arguments: {} })
    const view = viewOf(
      turn([call, call, ev('tool_result', { callId: 'tool:c1', ok: true, summary: '完成' })]),
    )
    expect(view.tools).toHaveLength(1)
    expect(view.tools[0]?.summary).toBe('完成')
  })

  test('统一入口的导航、澄清、缺口事件各自落到对应字段，请求失败时带错误', () => {
    const nav = viewOf(
      turn([
        ev('navigation', {
          message: '已为你找到',
          card: { title: '供应商 · 百润精密', url: '/erp/pages/suppliers/S-0001?t=x' },
        }),
        ev('final', { text: '已为你找到', kind: 'navigation' }),
      ]),
    )
    expect(nav.navigation?.card?.title).toBe('供应商 · 百润精密')
    expect(nav.final?.kind).toBe('navigation')
    expect(viewOf(turn([ev('clarify', { message: '请选择', options: [] })])).clarify?.message).toBe(
      '请选择',
    )
    expect(viewOf(turn([ev('gap', { message: '处理不了' })])).gap?.message).toBe('处理不了')
    expect(viewOf({ ...turn([]), status: 'error', error: '请求过于频繁' }).error).toBe(
      '请求过于频繁',
    )
  })
})

describe('replyText', () => {
  test('IM 渠道里卡片降级为文字：导航给链接，确认引导到统一入口', () => {
    const nav = replyText({
      kind: 'navigation',
      runId: 'r',
      payload: { card: { title: '供应商 · 百润精密', url: '/erp/pages/suppliers/S-0001?t=x' } },
      events: [{ type: 'final', data: { text: '已为你找到 供应商 · 百润精密，点击打开：' } }],
    })
    expect(nav).toEqual({
      text: '已为你找到 供应商 · 百润精密，点击打开：',
      link: '/erp/pages/suppliers/S-0001?t=x',
    })
    const confirm = replyText({
      kind: 'run',
      runId: 'r',
      payload: {},
      events: [{ type: 'confirmation_required', data: {} }],
    })
    expect(confirm.text).toContain('统一入口')
  })
})
