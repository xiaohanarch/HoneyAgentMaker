/**
 * P2 验收的端到端测试：平台与 DemoERP 各自监听随机端口，ERP 通过 HTTP 读取平台的 JWKS、核实委托、
 * 推送审计事件，链路与 pnpm demo 下完全相同。
 */
import type { AddressInfo } from 'node:net'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import type { FastifyInstance } from 'fastify'
import { buildApp as buildErpApp } from '@eab/demo-erp/app'
import { openErpDb } from '@eab/demo-erp/db'
import { createHttpPlatformLink } from '@eab/demo-erp/link'
import { seedErp } from '@eab/demo-erp/seed'
import { buildApp } from '../app'
import { DEMO_DELEGATION_ID, ensureDemoDefaults, type PlatformServices } from '../services'
import { TEST_INTERNAL_SECRET, createTestServices } from '../test-support'
import { DelegationError, callBoApi, type BoCall } from './delegated-client'

const PURCHASING = 'vendor.pur/supplier-performance-analyst'
const INVENTORY = 'vendor.scm/inventory-risk-analyst'
const PO_OPS = [
  'purchase_order_list',
  'purchase_order_change_delivery_date',
  'purchase_order_change_approve',
  'supplier_list',
]

let services: PlatformServices
let platform: FastifyInstance
let erp: FastifyInstance
let erpUrl: string

const urlOf = (app: FastifyInstance): string =>
  `http://127.0.0.1:${(app.server.address() as AddressInfo).port}`

beforeAll(async () => {
  services = createTestServices()
  ensureDemoDefaults(services)
  platform = buildApp({ services })
  await platform.listen({ port: 0, host: '127.0.0.1' })
  const db = openErpDb(':memory:')
  seedErp(db)
  erp = buildErpApp({
    db,
    internalSecret: TEST_INTERNAL_SECRET,
    // 测试里不缓存委托状态，撤销后立即可见
    link: createHttpPlatformLink({
      platformUrl: urlOf(platform),
      internalSecret: TEST_INTERNAL_SECRET,
      delegationCacheMs: 0,
    }),
  })
  await erp.listen({ port: 0, host: '127.0.0.1' })
  erpUrl = urlOf(erp)
})

afterAll(async () => {
  await erp.close()
  await platform.close()
})

async function agentToken(agent: string, operations: string[]): Promise<string> {
  return services.identity.issueAgentToken({
    agent,
    version: '1.0.0',
    tenantId: 't001',
    operations,
  })
}

async function asUser(
  userId: string,
  call: Omit<BoCall, 'subject' | 'agentToken'>,
  agent = PURCHASING,
  ops = PO_OPS,
) {
  const { token } = await services.identity.login(userId)
  return callBoApi(
    { identity: services.identity, erpUrl },
    { ...call, subject: { userToken: token }, agentToken: await agentToken(agent, ops) },
  )
}

/** ERP 的审计推送是异步的，等它落到平台审计里 */
async function waitForAudit(filter: Parameters<PlatformServices['audit']['list']>[0], count = 1) {
  for (let i = 0; i < 50; i += 1) {
    const events = services.audit.list(filter)
    if (events.length >= count) return events
    await new Promise((r) => setTimeout(r, 50))
  }
  return services.audit.list(filter)
}

describe('P2 验收', () => {
  it('张敏、李强用委托令牌调同一个 BO API，结果不同且带过滤说明', async () => {
    const query = { overdueDaysGt: '30' }
    const zhang = await asUser('zhangmin', {
      operation: 'purchase_order_list',
      method: 'GET',
      path: '/bo/v1/purchase-orders',
      query,
    })
    const li = await asUser('liqiang', {
      operation: 'purchase_order_list',
      method: 'GET',
      path: '/bo/v1/purchase-orders',
      query,
    })
    expect(zhang.body).toMatchObject({ filtered: true, visibleScope: ['华东'] })
    expect(li.body).toMatchObject({ filtered: true, visibleScope: ['华南'] })
    const zhangIds = (zhang.body as { items: { id: string }[] }).items.map((p) => p.id)
    const liIds = (li.body as { items: { id: string }[] }).items.map((p) => p.id)
    expect(zhangIds).toContain('PO-2026-0117')
    expect(zhangIds.filter((id) => liIds.includes(id))).toEqual([])
  })

  it('集成账号调 Open API 返回全部数据；调 BO API 被拒绝', async () => {
    const secret = services.clients.secretOf('t001-integration') ?? ''
    const token = await services.identity.clientCredentials('t001-integration', secret)
    const open = await fetch(`${erpUrl}/open/v1/purchase-orders?overdueDaysGt=30`, {
      headers: { authorization: `Bearer ${token}` },
    })
    const body = (await open.json()) as {
      filtered: boolean
      items: { purchasingOrgName: string }[]
    }
    expect(body.filtered).toBe(false)
    expect(new Set(body.items.map((p) => p.purchasingOrgName))).toEqual(new Set(['华东', '华南']))

    const bo = await fetch(`${erpUrl}/bo/v1/purchase-orders`, {
      headers: { authorization: `Bearer ${token}` },
    })
    expect(bo.status).toBe(401)
    expect(((await bo.json()) as { error: { code: string } }).error.code).toBe(
      'AGENT_IDENTITY_REQUIRED',
    )
  })

  it('SoD：张敏提交交期变更后审批自己的变更单被拒绝，审计可以反查', async () => {
    const submit = await asUser('zhangmin', {
      operation: 'purchase_order_change_delivery_date',
      method: 'POST',
      path: '/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes',
      body: { newDate: '2099-11-15', reason: '供应商产能不足' },
    })
    expect(submit.status).toBe(201)
    const changeId = (submit.body as { item: { id: string } }).item.id
    const approve = await asUser('zhangmin', {
      operation: 'purchase_order_change_approve',
      method: 'POST',
      path: `/bo/v1/delivery-date-changes/${changeId}/approve`,
    })
    expect(approve.status).toBe(403)
    expect(approve.body).toMatchObject({ error: { code: 'SOD_VIOLATION' } })

    // 反查“PO-2026-0117 的交期是谁通过哪个 Agent 改的”
    const [record] = await waitForAudit({
      target: 'PO-2026-0117',
      decision: 'allow',
      tool: 'purchase_order_change_delivery_date',
    })
    expect(record).toMatchObject({
      userId: 'zhangmin',
      agent: `${PURCHASING}@1.0.0`,
      checkpoint: 'bo-api',
      mode: 'on-behalf',
    })
    const denials = await waitForAudit({
      checkpoint: 'bo-api',
      decision: 'deny',
      tool: 'purchase_order_change_approve',
    })
    expect(denials[0]).toMatchObject({ ruleId: 'SOD_VIOLATION', userId: 'zhangmin' })
  })

  it('受托运行：委托有效时可以调用；赵管理员撤销后，交换失败，已签发的令牌也被 ERP 拒绝', async () => {
    const actor = await agentToken(INVENTORY, ['inventory_balance_list'])
    const call: BoCall = {
      subject: { delegationId: DEMO_DELEGATION_ID },
      agentToken: actor,
      operation: 'inventory_balance_list',
      method: 'GET',
      path: '/bo/v1/inventory-balances',
    }
    const ok = await callBoApi({ identity: services.identity, erpUrl }, call)
    expect(ok.status).toBe(200)

    // 撤销前先签好一张令牌，模拟运行中的 Agent 手里还握着令牌
    const held = await services.identity.exchange({
      subjectToken: `delegation:${DEMO_DELEGATION_ID}`,
      subjectTokenType: 'urn:eab:params:oauth:token-type:delegation',
      actorToken: actor,
      audience: 'demo-erp-bo',
    })
    const admin = await services.identity.login('zhaoadmin')
    const revoke = await platform.inject({
      method: 'DELETE',
      url: `/identity/v1/delegations/${DEMO_DELEGATION_ID}`,
      headers: { authorization: `Bearer ${admin.token}` },
    })
    expect(revoke.statusCode).toBe(200)

    await expect(callBoApi({ identity: services.identity, erpUrl }, call)).rejects.toMatchObject({
      code: 'DELEGATION_INACTIVE',
    })
    const stale = await fetch(`${erpUrl}/bo/v1/inventory-balances`, {
      headers: { authorization: `Bearer ${held.accessToken}` },
    })
    expect(stale.status).toBe(403)
    expect(((await stale.json()) as { error: { code: string } }).error.code).toBe(
      'DELEGATION_REVOKED',
    )
    const denied = await waitForAudit({
      checkpoint: 'bo-api',
      decision: 'deny',
      tool: 'inventory_balance_list',
    })
    expect(denied[0]).toMatchObject({ mode: 'delegated', ruleId: 'DELEGATION_REVOKED' })
  })
})

describe('失败即拒绝，不退回技术账号', () => {
  it('BO 路径不合法时拒绝，委托令牌不会发往配置之外的地址', async () => {
    const fetchSpy = vi.fn<typeof fetch>()
    const { token } = await services.identity.login('zhangmin')
    for (const path of [
      '//evil.example/bo/v1/x',
      'https://evil.example/bo/v1/x',
      '/bo/v1/../open/v1/suppliers',
      '/open/v1/suppliers',
    ]) {
      await expect(
        callBoApi(
          { identity: services.identity, erpUrl, fetch: fetchSpy },
          {
            subject: { userToken: token },
            agentToken: await agentToken(PURCHASING, PO_OPS),
            operation: 'purchase_order_list',
            method: 'GET',
            path,
          },
        ),
      ).rejects.toMatchObject({ code: 'INVALID_PATH' })
    }
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('令牌交换失败时不会发出 BO 调用', async () => {
    const fetchSpy = vi.fn<typeof fetch>()
    const { token } = await services.identity.login('chengong') // 没有业务权限，交集为空
    await expect(
      callBoApi(
        { identity: services.identity, erpUrl, fetch: fetchSpy },
        {
          subject: { userToken: token },
          agentToken: await agentToken(PURCHASING, PO_OPS),
          operation: 'purchase_order_list',
          method: 'GET',
          path: '/bo/v1/purchase-orders',
        },
      ),
    ).rejects.toBeInstanceOf(DelegationError)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('没有配置 ERP 连接时直接拒绝', async () => {
    const fetchSpy = vi.fn<typeof fetch>()
    const { token } = await services.identity.login('zhangmin')
    await expect(
      callBoApi(
        { identity: services.identity, erpUrl: undefined, fetch: fetchSpy },
        {
          subject: { userToken: token },
          agentToken: await agentToken(PURCHASING, PO_OPS),
          operation: 'purchase_order_list',
          method: 'GET',
          path: '/bo/v1/purchase-orders',
        },
      ),
    ).rejects.toMatchObject({ code: 'CONNECTION_MISSING' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('Agent 令牌伪造或过期时交换失败，不发出调用', async () => {
    const fetchSpy = vi.fn<typeof fetch>()
    const { token } = await services.identity.login('zhangmin')
    await expect(
      callBoApi(
        { identity: services.identity, erpUrl, fetch: fetchSpy },
        {
          subject: { userToken: token },
          agentToken: 'not-a-token',
          operation: 'purchase_order_list',
          method: 'GET',
          path: '/bo/v1/purchase-orders',
        },
      ),
    ).rejects.toMatchObject({ code: 'INVALID_TOKEN' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
