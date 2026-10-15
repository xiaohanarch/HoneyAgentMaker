import { stringify } from 'yaml'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { FastifyInstance } from 'fastify'
import type { Asset, Viewer } from '@eab/spec'
import { agentFixture, platformRulesFixture, semanticPackFixture, skillFixture } from '@eab/spec/testing'
import { buildApp } from '../app'
import { SIGNATURE_HEADER, signPayload } from '../events/routes'
import { DEMO_DELEGATION_ID, ensureDemoDefaults, type PlatformServices } from '../services'
import { TEST_INTERNAL_SECRET, createTestServices, loginAll } from '../test-support'
import { INTERNAL_HEADER } from './routes'

let services: PlatformServices
let app: FastifyInstance
let as: Awaited<ReturnType<typeof loginAll>>

function publish(viewer: Viewer, asset: Asset): void {
  const result = services.registry.publishAsset(viewer, asset, stringify(asset))
  if (!result.ok) throw new Error(result.error.message)
}

beforeEach(async () => {
  services = createTestServices()
  ensureDemoDefaults(services)
  app = buildApp({ services, demoMode: true })
  as = await loginAll(services)
  publish({ type: 'platform' }, platformRulesFixture())
  publish({ type: 'vendor' }, semanticPackFixture())
  publish({ type: 'vendor' }, skillFixture())
  publish({ type: 'vendor' }, agentFixture())
})

afterEach(() => app.close())

describe('身份接口', () => {
  it('用户列表与选人登录', async () => {
    const users = await app.inject({ method: 'GET', url: '/identity/v1/users' })
    expect(users.json().data.map((u: { id: string }) => u.id)).toContain('zhangmin')
    const login = await app.inject({ method: 'POST', url: '/identity/v1/login', payload: { userId: 'zhangmin' } })
    expect(login.json().data.user).toMatchObject({ name: '张敏', role: '采购经理', dataScope: '采购组织：华东' })
  })

  it('JWKS 公开 EdDSA 公钥', async () => {
    const jwks = await app.inject({ method: 'GET', url: '/identity/v1/jwks.json' })
    expect(jwks.json().keys[0]).toMatchObject({ kty: 'OKP', crv: 'Ed25519', alg: 'EdDSA' })
  })

  it('令牌端点接受表单编码的客户端凭证，失败时返回 RFC 6749 格式错误', async () => {
    const secret = services.clients.secretOf('t002-integration') ?? ''
    const ok = await app.inject({
      method: 'POST',
      url: '/identity/v1/token',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      payload: `grant_type=client_credentials&client_id=t002-integration&client_secret=${secret}`,
    })
    expect(ok.json()).toMatchObject({ token_type: 'Bearer', expires_in: 3600 })
    const bad = await app.inject({
      method: 'POST',
      url: '/identity/v1/token',
      payload: { grant_type: 'client_credentials', client_id: 't002-integration', client_secret: 'x' },
    })
    expect(bad.statusCode).toBe(400)
    expect(bad.json()).toMatchObject({ error: 'invalid_client' })
  })

  it('演示模式下平台运维为 Agent 签发令牌，其 scope 来自有效定义里的 BO 操作', async () => {
    const forbidden = await app.inject({
      method: 'POST',
      url: '/identity/v1/demo/agent-token',
      headers: as('zhangmin'),
      payload: { namespace: 'vendor.fin', name: 'transaction-anomaly-analyst', tenantId: 't001' },
    })
    expect(forbidden.statusCode).toBe(403)
    const response = await app.inject({
      method: 'POST',
      url: '/identity/v1/demo/agent-token',
      headers: as('platform'),
      payload: { namespace: 'vendor.fin', name: 'transaction-anomaly-analyst', tenantId: 't001' },
    })
    // 测试夹具里的技能调用 invoice_list，不是 bo-api 来源，所以没有 BO 操作
    expect(response.json().data.operations).toEqual([])
  })

  it('非演示模式不暴露代签接口', async () => {
    const plain = buildApp({ services })
    const response = await plain.inject({
      method: 'POST',
      url: '/identity/v1/demo/agent-token',
      headers: as('platform'),
      payload: { namespace: 'vendor.fin', name: 'transaction-anomaly-analyst', tenantId: 't001' },
    })
    expect(response.statusCode).toBe(404)
  })
})

describe('委托管理', () => {
  it('只有租户管理员能登记和撤销，撤销后状态即时变为不可用', async () => {
    const byUser = await app.inject({
      method: 'POST',
      url: '/identity/v1/delegations',
      headers: as('zhangmin'),
      payload: { delegatorUserId: 'zhangmin', agent: 'vendor.pur/supplier-performance-analyst', scope: ['purchase_order_list'] },
    })
    expect(byUser.statusCode).toBe(403)

    const created = await app.inject({
      method: 'POST',
      url: '/identity/v1/delegations',
      headers: as('zhaoadmin'),
      payload: { delegatorUserId: 'zhangmin', agent: 'vendor.pur/supplier-performance-analyst', scope: ['purchase_order_list'] },
    })
    expect(created.statusCode).toBe(201)

    const revoke = await app.inject({ method: 'DELETE', url: `/identity/v1/delegations/${DEMO_DELEGATION_ID}`, headers: as('zhaoadmin') })
    expect(revoke.json().data.revokedBy).toBe('zhaoadmin')

    const status = await app.inject({
      method: 'GET',
      url: `/identity/v1/delegations/${DEMO_DELEGATION_ID}/status`,
      headers: { [INTERNAL_HEADER]: TEST_INTERNAL_SECRET },
    })
    expect(status.json().data).toEqual({ active: false, reason: 'revoked' })

    const audit = await app.inject({ method: 'GET', url: '/audit/events?action=identity.delegation.revoke', headers: as('zhaoadmin') })
    expect(audit.json().data[0]).toMatchObject({ target: DEMO_DELEGATION_ID, agent: 'vendor.scm/inventory-risk-analyst' })
  })

  it('委托状态接口只接受内部调用', async () => {
    const response = await app.inject({ method: 'GET', url: `/identity/v1/delegations/${DEMO_DELEGATION_ID}/status` })
    expect(response.statusCode).toBe(401)
  })

  it('登记未知的 BO 操作返回 422', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/identity/v1/delegations',
      headers: as('zhaoadmin'),
      payload: { delegatorUserId: 'sunkg', agent: 'vendor.scm/inventory-risk-analyst', scope: ['drop_database'] },
    })
    expect(response.statusCode).toBe(422)
  })
})

describe('事件推送', () => {
  const event = JSON.stringify({ type: 'delivery.overdue', tenantId: 't001', payload: { poId: 'PO-2026-0117', overdueDays: 120 } })

  it('签名正确的业务事件入库并广播', async () => {
    const received: string[] = []
    services.events.subscribe((e) => received.push(e.type))
    const response = await app.inject({
      method: 'POST',
      url: '/events/v1/ingest',
      headers: { 'content-type': 'application/json', [SIGNATURE_HEADER]: signPayload(TEST_INTERNAL_SECRET, event) },
      payload: event,
    })
    expect(response.statusCode).toBe(202)
    expect(received).toEqual(['delivery.overdue'])
    expect(services.events.list('t001')[0]?.payload).toMatchObject({ poId: 'PO-2026-0117' })
  })

  it('签名不对的事件被拒绝', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/events/v1/ingest',
      headers: { 'content-type': 'application/json', [SIGNATURE_HEADER]: 'sha256=00' },
      payload: event,
    })
    expect(response.statusCode).toBe(401)
  })

  it('BO API 的裁决写入审计，可以按单据反查', async () => {
    const access = JSON.stringify({
      type: 'bo.access',
      tenantId: 't001',
      payload: {
        at: new Date().toISOString(),
        operation: 'purchase_order_change_delivery_date',
        userId: 'zhangmin',
        agent: 'vendor.pur/supplier-performance-analyst@1.0.0',
        mode: 'on-behalf',
        outcome: 'allow',
        code: 'OK',
        reason: '允许',
        dataScope: '采购组织：华东',
        target: 'PO-2026-0117',
        resultSummary: '生成交期变更单 DC-0001',
      },
    })
    await app.inject({
      method: 'POST',
      url: '/events/v1/ingest',
      headers: { 'content-type': 'application/json', [SIGNATURE_HEADER]: signPayload(TEST_INTERNAL_SECRET, access) },
      payload: access,
    })
    const audit = await app.inject({ method: 'GET', url: '/audit/events?target=PO-2026-0117', headers: as('zhaoadmin') })
    expect(audit.json().data[0]).toMatchObject({
      userId: 'zhangmin',
      agent: 'vendor.pur/supplier-performance-analyst@1.0.0',
      checkpoint: 'bo-api',
      tool: 'purchase_order_change_delivery_date',
    })
  })
})

describe('策略试算', () => {
  it('入口与工具裁决写入审计；普通用户只能查到自己的审计记录', async () => {
    const entry = await app.inject({
      method: 'POST',
      url: '/policy/v1/evaluate',
      headers: as('zhangmin'),
      payload: { checkpoint: 'entry', assistant: 'vendor.fin/finance-assistant' },
    })
    expect(entry.json().data).toMatchObject({ outcome: 'deny', ruleId: 'ENTRY-003' })

    const tool = await app.inject({
      method: 'POST',
      url: '/policy/v1/evaluate',
      headers: as('zhangmin'),
      payload: { checkpoint: 'tool', agent: 'vendor.fin/transaction-anomaly-analyst', tool: 'wire_transfer' },
    })
    expect(tool.json().data).toMatchObject({ outcome: 'deny', ruleId: 'TOOL-001' })

    const own = await app.inject({ method: 'GET', url: '/audit/events?checkpoint=entry', headers: as('zhangmin') })
    expect(own.json().data).toHaveLength(1)
    const others = await app.inject({ method: 'GET', url: '/audit/events?checkpoint=entry', headers: as('liqiang') })
    expect(others.json().data).toHaveLength(0)
  })

  it('规则清单公开可查', async () => {
    const rules = await app.inject({ method: 'GET', url: '/policy/v1/rules' })
    expect(rules.json().data.rules.some((r: { id: string }) => r.id === 'TOOL-005')).toBe(true)
  })
})
