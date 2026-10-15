import { stringify } from 'yaml'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { FastifyInstance } from 'fastify'
import type { Asset, Viewer } from '@eab/spec'
import {
  VENDOR_ANOMALY_INSTRUCTIONS,
  agentFixture,
  extensionFixture,
  platformRulesFixture,
  semanticPackFixture,
  skillFixture,
} from '@eab/spec/testing'
import { buildApp } from '../app'
import type { PlatformServices } from '../services'
import { createTestServices, loginAll } from '../test-support'

let services: PlatformServices
let app: FastifyInstance

const YAML = { 'content-type': 'application/yaml' }
let as: Awaited<ReturnType<typeof loginAll>>

function publish(viewer: Viewer, asset: Asset): void {
  const result = services.registry.publishAsset(viewer, asset, stringify(asset))
  if (!result.ok) throw new Error(result.error.message)
}

beforeEach(async () => {
  services = createTestServices()
  app = buildApp({ services })
  as = await loginAll(services)
  publish({ type: 'platform' }, platformRulesFixture())
  // 发布门禁会检查引用，被引用的资产先发布
  publish({ type: 'vendor' }, semanticPackFixture())
  publish({ type: 'vendor' }, skillFixture())
  publish({ type: 'vendor' }, agentFixture({ version: '1.2.0' }))
})

afterEach(() => app.close())

describe('registry API', () => {
  it('缺少身份头时返回 401', async () => {
    const response = await app.inject({ method: 'GET', url: '/registry/assets' })
    expect(response.statusCode).toBe(401)
    expect(response.json()).toMatchObject({ success: false, error: { code: 'UNAUTHENTICATED' } })
  })

  it('租户以 YAML 写入自己的扩展，返回 201 草稿', async () => {
    const response = await app.inject({
      method: 'PUT',
      url: '/registry/assets?changeNote=首版',
      headers: { ...YAML, ...as('tenant:t001') },
      payload: stringify(extensionFixture()),
    })
    expect(response.statusCode).toBe(201)
    expect(response.json()).toMatchObject({
      success: true,
      data: { status: 'draft', changeNote: '首版' },
    })
  })

  it('租户写标品资产返回 403，审计里能查到', async () => {
    const response = await app.inject({
      method: 'PUT',
      url: '/registry/assets',
      headers: { ...YAML, ...as('tenant:t001') },
      payload: stringify(agentFixture({ version: '9.9.9' })),
    })
    expect(response.statusCode).toBe(403)
    expect(response.json().error.code).toBe('REGISTRY_WRITE_FORBIDDEN')

    const audit = await app.inject({
      method: 'GET',
      url: '/audit/events?decision=deny',
      headers: as('platform'),
    })
    expect(audit.json().data[0]).toMatchObject({
      actor: 'tenant:t001',
      action: 'registry.write',
      decision: 'deny',
    })

    // 厂商身份看不到租户审计；租户管理员能看到本租户的越权记录
    const vendor = await app.inject({ method: 'GET', url: '/audit/events', headers: as('vendor') })
    expect(vendor.statusCode).toBe(403)
    const admin = await app.inject({
      method: 'GET',
      url: '/audit/events?action=registry.write&decision=deny',
      headers: as('zhaoadmin'),
    })
    expect(admin.json().data.map((e: { tenantId: string }) => e.tenantId)).toEqual(['t001'])
  })

  it('不合法的 YAML 返回 422 和带行号的问题', async () => {
    const response = await app.inject({
      method: 'PUT',
      url: '/registry/assets',
      headers: { ...YAML, ...as('tenant:t001') },
      payload:
        'apiVersion: eab.dev/v1\nkind: AgentExtension\nmetadata: {name: x, namespace: tenant.t001, version: 1.0.0}\nspec: {target: nope}\n',
    })
    expect(response.statusCode).toBe(422)
    expect(response.json().error.issues[0]).toMatchObject({ path: 'spec.target', line: 4 })
  })

  it('读取标品 Agent：租户看到占位，厂商看到全文', async () => {
    const url = '/registry/assets/vendor.fin/transaction-anomaly-analyst'
    const tenant = await app.inject({ method: 'GET', url, headers: as('tenant:t001') })
    expect(tenant.body).not.toContain(VENDOR_ANOMALY_INSTRUCTIONS)
    expect(tenant.json().data.asset.spec.instructions.$redacted).toBe(true)

    const vendor = await app.inject({ method: 'GET', url, headers: as('vendor') })
    expect(vendor.body).toContain(VENDOR_ANOMALY_INSTRUCTIONS)
  })

  it('状态变更接口：发布后可以组装出有效定义', async () => {
    await app.inject({
      method: 'PUT',
      url: '/registry/assets',
      headers: { ...YAML, ...as('tenant:t001') },
      payload: stringify(extensionFixture()),
    })
    const publish = await app.inject({
      method: 'PATCH',
      url: '/registry/assets/tenant.t001.finance/anomaly-analyst-ext/0.3.0/status',
      headers: as('tenant:t001'),
      payload: { status: 'published' },
    })
    expect(publish.json().data).toMatchObject({ status: 'published', signed: false })

    const effective = await app.inject({
      method: 'GET',
      url: '/registry/agents/vendor.fin/transaction-anomaly-analyst/effective',
      headers: as('tenant:t001'),
    })
    expect(effective.statusCode).toBe(200)
    const definition = effective.json().data.definition
    expect(definition.provenance.extensions).toEqual([
      'tenant.t001.finance/anomaly-analyst-ext@0.3.0',
    ])
    expect(effective.body).not.toContain(VENDOR_ANOMALY_INSTRUCTIONS)
    expect(effective.body).not.toContain('【平台机密】')
  })

  it('租户不能查看其他租户的组装结果', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/registry/agents/vendor.fin/transaction-anomaly-analyst/effective?tenant=t002',
      headers: as('tenant:t001'),
    })
    expect(response.statusCode).toBe(403)
  })

  it('组装失败返回 422 和问题列表', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/registry/agents/vendor.fin/missing/effective',
      headers: as('vendor'),
    })
    expect(response.statusCode).toBe(422)
    expect(response.json().error.details[0].code).toBe('AGENT_NOT_FOUND')
  })

  it('validate 接口返回 schema 问题和静态检查结果', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/registry/validate',
      headers: { ...YAML, ...as('tenant:t001') },
      payload: stringify(
        extensionFixture({
          spec: {
            target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
            model: { profile: 'judge' },
          },
        }),
      ),
    })
    const data = response.json().data
    expect(data.valid).toBe(false)
    expect(data.lint[0].rule).toBe('extension-point-declared')
  })
})
