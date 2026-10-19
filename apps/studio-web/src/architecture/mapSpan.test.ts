import { describe, expect, it } from 'vitest'
import type { Span } from '../studio/api'
import { COMPONENT_IDS, COMPONENTS } from './components'
import { delegatedPrelude, mapSpan } from './mapSpan'

/** 构造 span 的最小辅助：只填判定需要的字段 */
function span(kind: Span['kind'], name: string, attributes: Record<string, unknown> = {}): Span {
  return {
    spanId: name,
    parentSpanId: null,
    name,
    kind,
    startTime: '2026-01-01T00:00:00Z',
    endTime: '2026-01-01T00:00:01Z',
    status: 'ok',
    statusMessage: null,
    attributes,
    promptSegments: null,
  }
}

function ids(
  kind: Span['kind'],
  name: string,
  attributes: Record<string, unknown> = {},
): Set<string> {
  return new Set(mapSpan(span(kind, name, attributes)))
}

describe('mapSpan run 根 span', () => {
  it('统一入口自己回答的运行点亮入口与运行时', () => {
    expect(ids('run', '统一入口 · clarify')).toEqual(new Set(['ux-entry', 'cap-runtime']))
  })

  it('Agent 运行点亮入口、运行时，并按 Agent 归属应用层', () => {
    expect(
      ids('run', '运行 · vendor.pur/supplier-performance-analyst', {
        'eab.agent': 'vendor.pur/supplier-performance-analyst',
      }),
    ).toEqual(new Set(['ux-entry', 'cap-runtime', 'app-vendor']))
    expect(ids('run', '运行 · tenant.t001.pur/purchase-order-clerk-ext')).toEqual(
      new Set(['ux-entry', 'cap-runtime', 'app-tenant']),
    )
  })

  it('远程 Agent 命名空间归属 app-remote', () => {
    expect(
      ids('run', '运行 · tenant.t001.remote/fx-rate', {
        'eab.agent': 'tenant.t001.remote/fx-rate',
      }),
    ).toEqual(new Set(['ux-entry', 'cap-runtime', 'app-remote']))
  })

  it('外部 Agent 直达点亮 Agent 网关与远程 Agent', () => {
    expect(ids('run', '外部 Agent · 汇率查询')).toEqual(
      new Set(['ux-entry', 'cap-runtime', 'cap-agent', 'app-remote']),
    )
  })

  it('Studio 单独测试点亮开发态', () => {
    expect(ids('run', '单独测试 · search_purchase_orders')).toEqual(
      new Set(['dev-studio', 'cap-runtime']),
    )
  })
})

describe('mapSpan 编排与能力', () => {
  it('路由点亮路由器，选定助手时再点亮岗位助手', () => {
    expect(
      ids('routing', '路由 · vendor.pur/supplier-performance-analyst', {
        'eab.routing.assistant': 'assistant.purchase',
      }),
    ).toEqual(new Set(['orc-router', 'orc-assistant']))
    expect(ids('routing', '路由 · platform/router')).toEqual(new Set(['orc-router']))
  })

  it('组装按 Agent 归属应用层', () => {
    expect(ids('assemble', '组装 · vendor.pur/supplier-performance-analyst')).toEqual(
      new Set(['cap-runtime', 'app-vendor']),
    )
  })

  it('检索段点亮知识层，其余 pipeline 与模型调用点亮模型网关', () => {
    expect(ids('pipeline', 'pipeline.retrieval')).toEqual(new Set(['know-semantic', 'know-docs']))
    expect(ids('pipeline', 'pipeline.template')).toEqual(new Set(['cap-model']))
    expect(ids('model', '模型调用 · reasoning')).toEqual(new Set(['cap-model']))
  })

  it('鉴权点 2、3 的 policy span 点亮左翼鉴权', () => {
    expect(ids('policy', '鉴权点 2 · approve')).toEqual(new Set(['wing-auth']))
    expect(ids('policy', '鉴权点 3 · deny')).toEqual(new Set(['wing-auth']))
  })

  it('工作流、确认、记忆、钩子各有归属', () => {
    expect(ids('workflow', '步骤 · 查库存')).toEqual(new Set(['orc-workflow']))
    expect(ids('confirmation', '人工确认')).toEqual(new Set(['orc-confirm']))
    expect(ids('memory', '记忆')).toEqual(new Set(['cap-memory']))
    expect(ids('hook', '钩子 · vendor.pur/supplier-performance-analyst')).toEqual(
      new Set(['cap-runtime']),
    )
  })
})

describe('mapSpan 工具与守卫', () => {
  it('BO 工具点亮工具网关、DemoERP 与左翼鉴权（鉴权点 3 在工具网关、点 4 在 BO API 内裁决）', () => {
    expect(
      ids('tool', '工具 · search_purchase_orders', {
        'eab.tool.source': 'vendor.erp.tools/demo-erp-bo',
        'eab.tool.type': 'bo-api',
      }),
    ).toEqual(new Set(['cap-mcp', 'app-erp', 'wing-auth']))
  })

  it('租户工具归属租户应用，技能走运行时，IM 通知点亮 IM 渠道', () => {
    expect(
      ids('tool', '工具 · alternative_supplier_lookup', {
        'eab.tool.source': 'tenant.t001.skills/alternative-supplier-lookup',
      }),
    ).toEqual(new Set(['cap-mcp', 'app-tenant']))
    expect(
      ids('skill', '技能 · 查替代供应商', {
        'eab.tool.source': 'tenant.t001.skills/alternative-supplier-lookup',
      }),
    ).toEqual(new Set(['cap-runtime', 'app-tenant']))
    expect(
      ids('tool', '工具 · notify_im', { 'eab.tool.source': 'tenant.t001.tools/notify-im' }),
    ).toEqual(new Set(['cap-mcp', 'app-tenant', 'ux-im']))
  })

  it('守卫点亮守卫与 SoD，约束规则再加语义包', () => {
    expect(ids('guard', '守卫 · 写级别')).toEqual(new Set(['wing-guard', 'orc-sod']))
    expect(ids('guard', '约束 · pur-sod-001 触发')).toEqual(
      new Set(['wing-guard', 'orc-sod', 'know-semantic']),
    )
  })

  it('A2A 出站点亮 Agent 网关与远程 Agent，子 Agent 归属运行时和应用层', () => {
    expect(ids('agent', 'A2A · 汇率查询')).toEqual(new Set(['cap-agent', 'app-remote']))
    expect(ids('agent', '子 Agent · vendor.pur/supplier-performance-analyst')).toEqual(
      new Set(['cap-runtime', 'app-vendor']),
    )
  })
})

describe('完整性与特例', () => {
  it('每个映射结果都在组件清单内，组件 id 不重复', () => {
    const spans: Span[] = [
      span('run', '运行 · vendor.pur/supplier-performance-analyst', {
        'eab.agent': 'vendor.pur/supplier-performance-analyst',
      }),
      span('routing', '路由 · platform/router'),
      span('assemble', '组装 · tenant.t001.pur/purchase-order-clerk-ext'),
      span('pipeline', 'pipeline.retrieval'),
      span('model', '模型调用 · fast'),
      span('policy', '鉴权点 3 · approve'),
      span('tool', '工具 · search_purchase_orders', {
        'eab.tool.source': 'vendor.erp.tools/demo-erp-bo',
      }),
      span('skill', '技能 · x', {
        'eab.tool.source': 'tenant.t001.skills/alternative-supplier-lookup',
      }),
      span('workflow', '步骤 · 查库存'),
      span('agent', 'A2A · 汇率查询'),
      span('agent', '子 Agent · vendor.pur/x'),
      span('guard', '约束 · c1 触发'),
      span('hook', '钩子 · y'),
      span('confirmation', '人工确认'),
      span('memory', '记忆'),
      span('other', '未知种类'),
    ]
    for (const s of spans) {
      for (const id of mapSpan(s)) expect(COMPONENT_IDS.has(id)).toBe(true)
    }
    expect(new Set(COMPONENTS.map((c) => c.id)).size).toBe(COMPONENTS.length)
  })

  it('未知种类的 span 返回空集合，只推进时间不高亮', () => {
    expect(ids('other', 'whatever')).toEqual(new Set())
  })

  it('受托运行合成的业务事件帧点亮 DemoERP 与鉴权', () => {
    expect(delegatedPrelude()).toEqual(new Set(['app-erp', 'wing-auth']))
  })
})
