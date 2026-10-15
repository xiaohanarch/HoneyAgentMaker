import { describe, expect, test } from 'vitest'
import {
  MemoryTraceStore,
  Tracer,
  buildTree,
  canViewTrace,
  toOtlp,
  viewTrace,
  type SpanRecord,
} from './index'

const VENDOR_TEXT = '你是供应商绩效分析专家。这是标品指令原文'

function tracerWith(store = new MemoryTraceStore()) {
  return { store, tracer: new Tracer({ store, tenantId: 't001', traceId: 'a'.repeat(32) }) }
}

describe('Tracer', () => {
  test('按显式父节点组织成树，并记录种类、状态和属性', async () => {
    const { store, tracer } = tracerWith()
    const root = tracer.start('运行', { kind: 'run', key: 'run', parent: null })
    await tracer.within(
      '模型调用',
      { kind: 'model', key: 'turn:1/model', parent: root },
      async () => 'ok',
    )
    await expect(
      tracer.within('工具调用', { kind: 'tool', key: 'turn:1/tool:c1', parent: root }, async () => {
        throw new Error('接口超时')
      }),
    ).rejects.toThrow('接口超时')
    root.end({ attributes: { 'eab.agent': 'vendor.pur/x' } })

    const tree = buildTree(store.list(tracer.traceId))
    expect(tree).toHaveLength(1)
    expect(tree[0]?.span.kind).toBe('run')
    expect(tree[0]?.children.map((c) => c.span.kind)).toEqual(['model', 'tool'])
    const tool = tree[0]?.children[1]?.span
    expect(tool?.status).toBe('error')
    expect(tool?.statusMessage).toBe('接口超时')
    expect(tree[0]?.span.attributes['eab.agent']).toBe('vendor.pur/x')
  })

  test('同一路径得到同一个 spanId；挂起的 span 恢复后被最终状态覆盖，开始时间保留', async () => {
    const store = new MemoryTraceStore()
    let clock = new Date('2026-10-15T08:00:00Z')
    const now = () => clock
    const first = new Tracer({ store, traceId: 'b'.repeat(32), now })
    const root = first.start('运行', { kind: 'run', key: 'run', parent: null })
    first.start('查询', { kind: 'tool', key: 'turn:1/tool:c1', parent: root }).end()
    const suspend = new Error('等待确认')
    suspend.name = 'SuspendSignal'
    await expect(
      first.within(
        '确认',
        { kind: 'confirmation', key: 'turn:2/confirm', parent: root },
        async () => {
          throw suspend
        },
      ),
    ).rejects.toBe(suspend)
    first.suspendOpenSpans()
    expect(
      store
        .list(first.traceId)
        .map((s) => s.status)
        .sort(),
    ).toEqual(['ok', 'suspended', 'suspended'])

    clock = new Date('2026-10-15T08:05:00Z')
    const resumed = new Tracer({ store, traceId: 'b'.repeat(32), now })
    const root2 = resumed.start('运行', { kind: 'run', key: 'run', parent: null })
    expect(root2.spanId).toBe(root.spanId)
    // 重放时已完成的 span 再写一次也不会改变内容
    resumed
      .start('查询', { kind: 'tool', key: 'turn:1/tool:c1', parent: root2 })
      .end({ status: 'error' })
    resumed.start('确认', { kind: 'confirmation', key: 'turn:2/confirm', parent: root2 }).end()
    root2.end()

    const spans = store.list(first.traceId)
    expect(spans).toHaveLength(3)
    expect(spans.every((s) => s.status === 'ok')).toBe(true)
    const run = spans.find((s) => s.kind === 'run')
    expect(run?.startTime).toBe('2026-10-15T08:00:00.000Z')
    expect(run?.endTime).toBe('2026-10-15T08:05:00.000Z')
  })
})

function modelSpan(): SpanRecord {
  return {
    traceId: 'c'.repeat(32),
    spanId: '1'.repeat(16),
    parentSpanId: null,
    name: '模型调用',
    kind: 'model',
    startTime: '2026-10-15T08:00:00.000Z',
    endTime: '2026-10-15T08:00:01.000Z',
    status: 'ok',
    attributes: { 'gen_ai.request.model': 'scripted' },
    tenantId: 't001',
    promptSegments: [
      {
        id: 'p1',
        kind: 'platform-rule',
        source: 'platform/safety-rules@1.0.0',
        confidential: true,
        text: '平台规则原文',
      },
      {
        id: 'v1',
        kind: 'vendor-instructions',
        source: 'vendor.pur/x@1.0.0',
        confidential: true,
        text: VENDOR_TEXT,
      },
      {
        id: 't1',
        kind: 'tenant-instructions',
        source: 'tenant.t001.x/y@0.1.0',
        confidential: false,
        text: '租户追加',
      },
    ],
  }
}

describe('按查看者裁剪', () => {
  test('租户视角里找不到标品指令和平台规则原文，租户自己的片段照常显示', () => {
    const view = viewTrace([modelSpan()], { type: 'tenant', tenantId: 't001' })
    const json = JSON.stringify(view)
    expect(json).not.toContain(VENDOR_TEXT)
    expect(json).not.toContain('平台规则原文')
    expect(json).toContain('租户追加')
    expect(json).toContain('标品指令（不可见，运行时注入）')
  })

  test('平台视角看到原文；厂商看不到租户的运行；租户看不到别的租户', () => {
    expect(JSON.stringify(viewTrace([modelSpan()], { type: 'platform' }))).toContain(VENDOR_TEXT)
    expect(canViewTrace({ type: 'vendor' }, [modelSpan()])).toBe(false)
    expect(canViewTrace({ type: 'tenant', tenantId: 't002' }, [modelSpan()])).toBe(false)
    expect(canViewTrace({ type: 'tenant', tenantId: 't001' }, [modelSpan()])).toBe(true)
  })
})

describe('OTLP 转换', () => {
  test('输出 ExportTraceServiceRequest 结构，不带片段原文', () => {
    const otlp = toOtlp([modelSpan()]) as {
      resourceSpans: { scopeSpans: { spans: Record<string, unknown>[] }[] }[]
    }
    const span = otlp.resourceSpans[0]?.scopeSpans[0]?.spans[0]
    expect(span?.traceId).toBe('c'.repeat(32))
    expect(span?.startTimeUnixNano).toBe('1792051200000000000')
    expect(JSON.stringify(otlp)).not.toContain(VENDOR_TEXT)
    expect(JSON.stringify(otlp)).toContain('p1,v1,t1')
  })
})
