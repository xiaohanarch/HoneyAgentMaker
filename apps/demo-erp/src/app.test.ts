import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { BO_OPERATIONS } from '@eab/demo-directory'
import { parseAssetYaml } from '@eab/spec'
import { loadAssetDirectory } from '@eab/spec/node'
import { findMetaObject } from './meta/catalog'
import {
  INTERNAL_SECRET,
  bearer,
  clientToken,
  createTestErp,
  delegatedToken,
  pageToken,
  userToken,
  type TestErp,
} from './test-support'

const ASSETS = resolve(dirname(fileURLToPath(import.meta.url)), '../../../assets')
const PO_OPS = [
  'purchase_order_list',
  'purchase_order_change_delivery_date',
  'purchase_order_change_approve',
  'supplier_get',
]

let erp: TestErp
beforeEach(() => {
  erp = createTestErp()
})
afterEach(() => erp.app.close())

async function get(url: string, token: string) {
  return erp.app.inject({ method: 'GET', url, headers: bearer(token) })
}

describe('数据权限：同一个问题，不同用户结果不同（故事 D 第 1 步）', () => {
  it('张敏只看到华东，李强只看到华南，结果都注明过滤范围', async () => {
    const url = '/bo/v1/purchase-orders?overdueDaysGt=30'
    const zhang = (await get(url, await delegatedToken({ user: 'zhangmin', scope: PO_OPS }))).json()
    const li = (await get(url, await delegatedToken({ user: 'liqiang', scope: PO_OPS }))).json()

    expect(zhang).toMatchObject({
      filtered: true,
      visibleScope: ['华东'],
      scopeLabel: '采购组织：华东',
    })
    expect(li).toMatchObject({
      filtered: true,
      visibleScope: ['华南'],
      scopeLabel: '采购组织：华南',
    })
    expect(
      new Set(zhang.items.map((p: { purchasingOrgName: string }) => p.purchasingOrgName)),
    ).toEqual(new Set(['华东']))
    expect(
      new Set(li.items.map((p: { purchasingOrgName: string }) => p.purchasingOrgName)),
    ).toEqual(new Set(['华南']))
    expect(zhang.items.find((p: { id: string }) => p.id === 'PO-2026-0117')).toMatchObject({
      supplierName: '百润精密',
      overdueDays: 120,
    })
  })

  it('不在数据权限内的单据返回 404，不泄露是否存在', async () => {
    const response = await get(
      '/bo/v1/suppliers/S-0001',
      await delegatedToken({ user: 'liqiang', scope: PO_OPS }),
    )
    expect(response.statusCode).toBe(404)
    expect(response.json().error).toMatchObject({ code: 'NOT_FOUND' })
    expect(response.json().error.suggestion).not.toBe('')
  })

  it('租户隔离：t002 的用户查不到 t001 的供应商', async () => {
    const response = await get(
      '/bo/v1/suppliers?name=百润',
      await delegatedToken({ user: 'zhouting', tenant: 't002', scope: ['supplier_list'] }),
    )
    expect(response.json().items).toEqual([])
  })
})

describe('Open API 对照（故事 D 第 2 步）', () => {
  it('集成账号拿到两个采购组织的全部数据，没有过滤', async () => {
    const response = (
      await get('/open/v1/purchase-orders?overdueDaysGt=30', await clientToken())
    ).json()
    expect(response.filtered).toBe(false)
    expect(
      new Set(response.items.map((p: { purchasingOrgName: string }) => p.purchasingOrgName)),
    ).toEqual(new Set(['华东', '华南']))
  })

  it('集成账号调用 BO API 被拒绝：没有人的身份', async () => {
    const response = await get('/bo/v1/purchase-orders', await clientToken())
    expect(response.statusCode).toBe(401)
    expect(response.json().error.code).toBe('AGENT_IDENTITY_REQUIRED')
  })

  it('委托令牌不能调用 Open API', async () => {
    const response = await get(
      '/open/v1/suppliers',
      await delegatedToken({ user: 'zhangmin', scope: PO_OPS }),
    )
    expect(response.statusCode).toBe(401)
  })
})

describe('鉴权点 4 的各项检查', () => {
  it('没有 Agent 身份的用户令牌被拒绝', async () => {
    const response = await get('/bo/v1/purchase-orders', await userToken('zhangmin'))
    expect(response.json().error.code).toBe('AGENT_IDENTITY_REQUIRED')
  })

  it('用户在 ERP 里没有的权限，通过 Agent 也没有', async () => {
    const response = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/delivery-date-changes/DC-2026-0001/approve',
      headers: bearer(await delegatedToken({ user: 'liqiang', scope: PO_OPS })),
    })
    expect(response.statusCode).toBe(403)
    expect(response.json().error.code).toBe('BUSINESS_PERMISSION_DENIED')
  })

  it('Agent 没有声明的操作被拒绝', async () => {
    const response = await get(
      '/bo/v1/suppliers',
      await delegatedToken({ user: 'zhangmin', scope: ['purchase_order_list'] }),
    )
    expect(response.json().error.code).toBe('AGENT_SCOPE_DENIED')
  })

  it('受托运行时委托必须有效，撤销后被拒绝', async () => {
    const token = await delegatedToken({
      user: 'sunkg',
      scope: ['inventory_balance_list'],
      agent: 'vendor.scm/inventory-risk-analyst',
      mode: 'delegated',
      delegationId: 'DLG-0001',
    })
    expect((await get('/bo/v1/inventory-balances', token)).json().error.code).toBe(
      'DELEGATION_REVOKED',
    )
    erp.activeDelegations.add('DLG-0001')
    const ok = (await get('/bo/v1/inventory-balances?belowSafetyStock=true', token)).json()
    expect(ok.items.some((b: { materialId: string }) => b.materialId === 'M-0007')).toBe(true)
  })

  it('每次裁决都推送到平台审计，带用户、Agent、数据范围', async () => {
    await get('/bo/v1/purchase-orders', await delegatedToken({ user: 'zhangmin', scope: PO_OPS }))
    await get(
      '/bo/v1/suppliers',
      await delegatedToken({ user: 'zhangmin', scope: ['purchase_order_list'] }),
    )
    const events = erp.link.published.filter((e) => e.type === 'bo.access').map((e) => e.payload)
    expect(events).toEqual([
      expect.objectContaining({
        userId: 'zhangmin',
        agent: 'vendor.pur/supplier-performance-analyst@1.0.0',
        outcome: 'allow',
        dataScope: '采购组织：华东',
      }),
      expect.objectContaining({ outcome: 'deny', code: 'AGENT_SCOPE_DENIED' }),
    ])
  })
})

describe('交期变更与职责分离（故事 D 第 3 步）', () => {
  it('张敏提交变更后不能审批自己的变更单；幂等键重放返回同一张变更单', async () => {
    const token = await delegatedToken({ user: 'zhangmin', scope: PO_OPS })
    const submit = () =>
      erp.app.inject({
        method: 'POST',
        url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
        headers: { ...bearer(token), 'idempotency-key': 'chg-1' },
        payload: { newDate: '2026-11-15', reason: '供应商产能不足' },
      })
    const first = await submit()
    expect(first.statusCode).toBe(201)
    expect(first.json().item).toMatchObject({
      id: 'DC-2026-0001',
      oldDate: '2026-06-17',
      status: 'submitted',
      requestedBy: 'zhangmin',
      requestedVia: 'agent:vendor.pur/supplier-performance-analyst',
    })
    const replay = await submit()
    expect(replay.headers['idempotent-replayed']).toBe('true')
    expect(replay.json().item.id).toBe('DC-2026-0001')

    const approve = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/delivery-date-changes/DC-2026-0001/approve',
      headers: bearer(token),
    })
    expect(approve.statusCode).toBe(403)
    expect(approve.json().error).toMatchObject({ code: 'SOD_VIOLATION' })
    expect(approve.json().error.reason).toContain('职责分离')
  })

  it('新交期必须晚于今天，错误是结构化的', async () => {
    const response = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
      headers: bearer(await delegatedToken({ user: 'zhangmin', scope: PO_OPS })),
      payload: { newDate: '2026-01-01', reason: 'x' },
    })
    expect(response.statusCode).toBe(422)
    expect(Object.keys(response.json().error).sort()).toEqual(['code', 'reason', 'suggestion'])
  })
})

describe('财务与库存', () => {
  it('王芳看到本月金额超过 50 万的高风险异常（故事 B）', async () => {
    const response = await get(
      '/bo/v1/transaction-anomalies?severity=high,critical&period=2026-10',
      await delegatedToken({
        user: 'wangfang',
        scope: ['transaction_anomaly_list'],
        agent: 'vendor.fin/transaction-anomaly-analyst',
      }),
    )
    const amounts = response.json().items.map((a: { amount: number }) => a.amount)
    expect(amounts.filter((a: number) => a > 500_000).length).toBeGreaterThanOrEqual(2)
    expect(response.json().scopeLabel).toBe('公司主体：星海制造本部')
  })

  it('资产核算本期结账检查有 3 项未通过', async () => {
    const response = await get(
      '/bo/v1/accounting-periods/current/close-checks',
      await delegatedToken({ user: 'wangfang', scope: ['period_close_checklist'] }),
    )
    const body = response.json()
    expect(body.items.filter((c: { status: string }) => c.status === 'failed')).toHaveLength(3)
    expect(body.readinessRate).toBe(50)
  })

  it('创建跟进任务，记录经由的 Agent', async () => {
    const response = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/followup-tasks',
      headers: bearer(
        await delegatedToken({
          user: 'wangfang',
          scope: ['followup_task_create'],
          agent: 'vendor.fin/transaction-anomaly-analyst',
        }),
      ),
      payload: { title: '核查重复付款', assignee: '应付组', relatedObject: 'ANO-2026-0003' },
    })
    expect(response.json().item).toMatchObject({
      id: 'TASK-2026-0001',
      createdBy: 'wangfang',
      createdVia: 'agent:vendor.fin/transaction-anomaly-analyst',
    })
  })
})

describe('接口描述与元数据', () => {
  it('BO API 的 OpenAPI 操作与工具来源的工具一一对应，写级别一致', async () => {
    const doc = (await erp.app.inject({ method: 'GET', url: '/bo/v1/openapi.json' })).json() as {
      paths: Record<string, Record<string, { operationId: string; 'x-write-level': string }>>
    }
    const operations = Object.values(doc.paths).flatMap((methods) => Object.values(methods))
    expect(operations.map((o) => o.operationId).sort()).toEqual([...BO_OPERATIONS].sort())

    const source = parseAssetYaml(readFileSync(join(ASSETS, 'vendor/erp/demo-erp-bo.yaml'), 'utf8'))
    if (!source.ok || source.asset.kind !== 'ToolSource') throw new Error('工具来源资产无效')
    for (const tool of source.asset.spec.tools) {
      expect(
        operations.find((o) => o.operationId === tool.name)?.['x-write-level'],
        tool.name,
      ).toBe(tool.writeLevel)
    }
  })

  it('三个语义包投影段引用的元数据对象都能在元数据服务里找到', async () => {
    const loaded = await loadAssetDirectory(join(ASSETS, 'vendor'))
    const refs = loaded.assets.flatMap(({ asset }) =>
      asset.kind === 'SemanticPack' ? asset.spec.objects.map((o) => o.ref) : [],
    )
    expect(refs.length).toBeGreaterThanOrEqual(15)
    expect(refs.filter((ref) => findMetaObject(ref) === undefined)).toEqual([])
    const response = await erp.app.inject({
      method: 'GET',
      url: '/meta/v1/objects/erp.pur.PurchaseOrder@v2',
    })
    expect(response.json()).toMatchObject({
      ref: 'erp.pur.PurchaseOrder@v2',
      permissionDimension: '采购组织',
    })
  })
})

describe('演示控制台与只读页面', () => {
  it('触发业务事件需要内部调用密钥，事件推送给平台', async () => {
    const denied = await erp.app.inject({
      method: 'POST',
      url: '/demo/v1/events/trigger',
      payload: { type: 'delivery.overdue' },
    })
    expect(denied.statusCode).toBe(401)
    const ok = await erp.app.inject({
      method: 'POST',
      url: '/demo/v1/events/trigger',
      headers: { 'x-eab-internal-token': INTERNAL_SECRET },
      payload: { type: 'delivery.overdue' },
    })
    expect(ok.statusCode).toBe(202)
    expect(erp.link.published.at(-1)).toMatchObject({
      type: 'delivery.overdue',
      payload: { poId: 'PO-2026-0117', overdueDays: 120 },
    })
  })

  it('只读页面需要平台签发的页面令牌，并按该用户的数据权限展示', async () => {
    const anonymous = await erp.app.inject({
      method: 'GET',
      url: '/pages/purchase-orders/PO-2026-0117?tenant=t001',
    })
    expect(anonymous.statusCode).toBe(404)
    expect(anonymous.body).not.toContain('百润精密')

    const zhang = await pageToken('zhangmin')
    const supplier = await erp.app.inject({
      method: 'GET',
      url: `/pages/suppliers/S-0001?t=${zhang}`,
    })
    expect(supplier.body).toContain('百润精密')
    const po = await erp.app.inject({
      method: 'GET',
      url: `/pages/purchase-orders/PO-2026-0117?t=${zhang}`,
    })
    expect(po.body).toContain('120')

    // 李强的数据权限是华南，看不到华东的单据；t002 的用户看不到 t001 的单据
    const li = await erp.app.inject({
      method: 'GET',
      url: `/pages/purchase-orders/PO-2026-0117?t=${await pageToken('liqiang')}`,
    })
    expect(li.statusCode).toBe(404)
    const other = await erp.app.inject({
      method: 'GET',
      url: `/pages/suppliers/S-0001?t=${await pageToken('zhouting', 't002')}`,
    })
    expect(other.body).not.toContain('百润精密')
    // 委托令牌不能当页面令牌用
    const wrong = await erp.app.inject({
      method: 'GET',
      url: `/pages/suppliers/S-0001?t=${await delegatedToken({ user: 'zhangmin', scope: PO_OPS })}`,
    })
    expect(wrong.statusCode).toBe(404)
  })

  it('健康检查', async () => {
    expect((await erp.app.inject({ method: 'GET', url: '/health' })).json()).toMatchObject({
      status: 'ok',
      service: 'demo-erp',
    })
  })
})

describe('业务处理中的拒绝也进入平台审计', () => {
  it('职责分离被拒绝时推送 bo.access 拒绝事件', async () => {
    const token = await delegatedToken({ user: 'zhangmin', scope: PO_OPS })
    await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
      headers: bearer(token),
      payload: { newDate: '2026-11-15', reason: 'x' },
    })
    await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/delivery-date-changes/DC-2026-0001/approve',
      headers: bearer(token),
    })
    const last = erp.link.published.at(-1)
    expect(last).toMatchObject({
      type: 'bo.access',
      tenantId: 't001',
      payload: { outcome: 'deny', code: 'SOD_VIOLATION', userId: 'zhangmin' },
    })
  })
})

describe('安全审查的回归测试', () => {
  it('幂等键被另一个用户复用时拒绝，不回放别人的结果', async () => {
    const zhang = await delegatedToken({ user: 'zhangmin', scope: PO_OPS })
    const first = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
      headers: { ...bearer(zhang), 'idempotency-key': 'shared-key' },
      payload: { newDate: '2026-11-15', reason: '张敏提交' },
    })
    expect(first.statusCode).toBe(201)
    const li = await delegatedToken({ user: 'liqiang', scope: PO_OPS })
    const reuse = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
      headers: { ...bearer(li), 'idempotency-key': 'shared-key' },
      payload: { newDate: '2026-11-15', reason: '张敏提交' },
    })
    expect(reuse.statusCode).toBe(422)
    expect(reuse.json().error.code).toBe('IDEMPOTENCY_KEY_REUSED')
    expect(reuse.body).not.toContain('DC-2026-0001')
  })

  it('同一个用户用同一个键提交不同内容时拒绝', async () => {
    const token = await delegatedToken({ user: 'zhangmin', scope: PO_OPS })
    const send = (newDate: string) =>
      erp.app.inject({
        method: 'POST',
        url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
        headers: { ...bearer(token), 'idempotency-key': 'k2' },
        payload: { newDate, reason: 'x' },
      })
    expect((await send('2026-11-15')).statusCode).toBe(201)
    expect((await send('2026-12-01')).json().error.code).toBe('IDEMPOTENCY_KEY_REUSED')
  })

  it('受托自主运行不能执行写操作，即使令牌里带了这个操作', async () => {
    erp.activeDelegations.add('DLG-X')
    const token = await delegatedToken({
      user: 'sunkg',
      scope: ['followup_task_create'],
      agent: 'vendor.scm/inventory-risk-analyst',
      mode: 'delegated',
      delegationId: 'DLG-X',
    })
    const response = await erp.app.inject({
      method: 'POST',
      url: '/bo/v1/followup-tasks',
      headers: bearer(token),
      payload: { title: 't', assignee: 'a' },
    })
    expect(response.statusCode).toBe(403)
    expect(response.json().error.code).toBe('DELEGATED_WRITE_FORBIDDEN')
  })

  it('同一张订单已有待审批的变更单时，不能再提交', async () => {
    const token = await delegatedToken({ user: 'zhangmin', scope: PO_OPS })
    const submit = () =>
      erp.app.inject({
        method: 'POST',
        url: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
        headers: bearer(token),
        payload: { newDate: '2026-11-15', reason: 'x' },
      })
    expect((await submit()).statusCode).toBe(201)
    const second = await submit()
    expect(second.statusCode).toBe(409)
    expect(second.json().error.code).toBe('CHANGE_PENDING')
  })

  it('审计里的目标不带查询字符串', async () => {
    await get(
      '/bo/v1/suppliers?name=secret',
      await delegatedToken({ user: 'zhangmin', scope: ['purchase_order_list'] }),
    )
    expect(erp.link.published.at(-1)?.payload.target).toBe('/bo/v1/suppliers')
  })
})
