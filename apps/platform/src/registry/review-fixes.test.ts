/**
 * P1 代码审查发现的问题的回归测试：跨租户、跨层、完整性、发布门禁。
 * 每个用例都对应一个具体的越权或静默失败场景。
 */
import { stringify } from 'yaml'
import { eq } from 'drizzle-orm'
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
  toolSourceFixture,
  workflowFixture,
} from '@eab/spec/testing'
import { buildApp } from '../app'
import { assets as assetsTable } from '../db/schema'
import type { PlatformServices } from '../services'
import { createTestServices, loginAll } from '../test-support'
import { assembleFromRegistry } from './effective'

const PLATFORM: Viewer = { type: 'platform' }
const VENDOR: Viewer = { type: 'vendor' }
const T001: Viewer = { type: 'tenant', tenantId: 't001' }
const T002: Viewer = { type: 'tenant', tenantId: 't002' }
const AGENT = 'vendor.fin/transaction-anomaly-analyst'
let as: Awaited<ReturnType<typeof loginAll>>
const YAML = { 'content-type': 'application/yaml' }

let services: PlatformServices
let app: FastifyInstance

function publish(viewer: Viewer, asset: Asset): void {
  const result = services.registry.publishAsset(viewer, asset, stringify(asset))
  if (!result.ok)
    throw new Error(`${result.error.message} ${JSON.stringify(result.error.issues ?? [])}`)
}

/** 使用真实的发布门禁，按依赖顺序发布一套最小可组装的资产 */
beforeEach(async () => {
  services = createTestServices()
  app = buildApp({ services })
  as = await loginAll(services)
  publish(PLATFORM, platformRulesFixture())
  publish(VENDOR, semanticPackFixture())
  publish(VENDOR, skillFixture())
  publish(VENDOR, agentFixture({ version: '1.2.0' }))
})

afterEach(() => app.close())

describe('跨租户与跨层可见性', () => {
  it('租户组装不到其他租户的 Agent', () => {
    const t002Agent = agentFixture({
      namespace: 'tenant.t002.agents',
      spec: { semanticPacks: [], constraints: [], instructions: 't002 的机密指令' },
    })
    publish(T002, t002Agent)
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: 'tenant.t002.agents/transaction-anomaly-analyst',
    })
    expect(outcome.ok).toBe(false)
    expect(outcome.issues.map((i) => i.code)).toEqual(['AGENT_NOT_FOUND'])
    expect(JSON.stringify(outcome)).not.toContain('t002 的机密指令')
  })

  it('扩展不能引用其他租户的资产，写入时就被拒绝', () => {
    const ext = extensionFixture({
      spec: { target: `${AGENT}@^1.0`, knowledge: { add: ['tenant.t002.packs/secret'] } },
    })
    const result = services.registry.put(T001, stringify(ext))
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.issues?.[0]).toMatchObject({
      code: 'REFERENCE_OUT_OF_SCOPE',
      path: 'spec.knowledge.add[0]',
    })
  })

  it('厂商身份带 tenant 参数读组装结果返回 403', async () => {
    const response = await app.inject({
      method: 'GET',
      url: `/registry/agents/${AGENT}/effective?tenant=t001`,
      headers: as('vendor'),
    })
    expect(response.statusCode).toBe(403)
  })

  it('includeDrafts=false 不会打开草稿；租户带 includeDrafts=true 也看不到厂商草稿', async () => {
    services.registry.put(VENDOR, stringify(agentFixture({ version: '1.3.0' })))
    const versionFor = async (query: string, viewer: string): Promise<string> => {
      const response = await app.inject({
        method: 'GET',
        url: `/registry/agents/${AGENT}/effective${query}`,
        headers: as(viewer),
      })
      return response.json().data.definition.provenance.agent as string
    }
    expect(await versionFor('?includeDrafts=false', 'vendor')).toMatch(/@1\.2\.0$/)
    expect(await versionFor('?includeDrafts=true', 'vendor')).toMatch(/@1\.3\.0$/)
    expect(await versionFor('?includeDrafts=true', 'tenant:t001')).toMatch(/@1\.2\.0$/)

    const bad = await app.inject({
      method: 'GET',
      url: `/registry/agents/${AGENT}/effective?includeDrafts=yes`,
      headers: as('vendor'),
    })
    expect(bad.statusCode).toBe(400)
  })

  it('不带版本读取时返回该身份可见的最新版本，而不是 404', () => {
    services.registry.put(VENDOR, stringify(agentFixture({ version: '1.3.0' })))
    const result = services.registry.view(T001, 'vendor.fin', 'transaction-anomaly-analyst')
    expect(result.ok && result.value.summary.version).toBe('1.2.0')
  })

  it('validate 接口要求身份，引用越界时报 reference-scope', async () => {
    const payload = stringify(
      extensionFixture({
        spec: { target: `${AGENT}@^1.0`, skills: { add: ['tenant.t002.skills/x'] } },
      }),
    )
    const anonymous = await app.inject({
      method: 'POST',
      url: '/registry/validate',
      headers: YAML,
      payload,
    })
    expect(anonymous.statusCode).toBe(401)

    const response = await app.inject({
      method: 'POST',
      url: '/registry/validate',
      headers: { ...YAML, ...as('tenant:t001') },
      payload,
    })
    const rules = (response.json().data.lint as { rule: string }[]).map((i) => i.rule)
    expect(rules).toEqual(['reference-scope'])
  })

  it('非法的命名空间路径参数返回 400，而不是 500', async () => {
    const effective = await app.inject({
      method: 'GET',
      url: '/registry/agents/foo/bar/effective',
      headers: as('vendor'),
    })
    expect(effective.statusCode).toBe(400)
    const status = await app.inject({
      method: 'PATCH',
      url: '/registry/assets/foo/n/1.0.0/status',
      headers: as('tenant:t001'),
      payload: { status: 'published' },
    })
    expect(status.statusCode).toBe(400)
  })

  it('有效定义里的占位摘要带密钥，与不带密钥的 FNV 摘要不同', () => {
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: AGENT,
      tenantId: 't001',
    })
    if (!outcome.ok) throw new Error('组装失败')
    const segment = outcome.view.segments.find((s) => s.kind === 'vendor-instructions')
    const text = segment?.text as { digest: string }
    expect(text.digest).toMatch(/^[0-9a-f]{16}$/)
    expect(JSON.stringify(outcome.view)).not.toContain(VENDOR_ANOMALY_INSTRUCTIONS)
  })
})

describe('完整性：不能静默丢掉平台规则或扩展', () => {
  it('平台规则被篡改时组装失败，而不是少一段规则继续', () => {
    services.db
      .update(assetsTable)
      .set({ contentHash: `sha256:${'0'.repeat(64)}` })
      .where(eq(assetsTable.name, 'safety-rules'))
      .run()
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: AGENT,
      tenantId: 't001',
    })
    expect(outcome.ok).toBe(false)
    expect(outcome.issues[0]?.code).toBe('INTEGRITY_VIOLATION')
  })

  it('没有平台规则时组装失败', () => {
    services.db.delete(assetsTable).where(eq(assetsTable.name, 'safety-rules')).run()
    const outcome = assembleFromRegistry(services.registry, VENDOR, { agent: AGENT })
    expect(outcome.issues.map((i) => i.code)).toEqual(['PLATFORM_RULES_MISSING'])
  })

  it('下线时作废签名，在库里把状态改回已发布也通不过验签', () => {
    services.registry.setStatus(
      VENDOR,
      'vendor.fin',
      'transaction-anomaly-analyst',
      '1.2.0',
      'retired',
    )
    services.db
      .update(assetsTable)
      .set({ status: 'published' })
      .where(eq(assetsTable.name, 'transaction-anomaly-analyst'))
      .run()
    const outcome = assembleFromRegistry(services.registry, VENDOR, { agent: AGENT })
    expect(outcome.issues[0]?.code).toBe('INTEGRITY_VIOLATION')
  })

  it('把一个版本的签名挪到另一个版本上验签失败', () => {
    publish(VENDOR, agentFixture({ version: '1.3.0', spec: { instructions: '另一版指令' } }))
    const rows = services.db
      .select()
      .from(assetsTable)
      .where(eq(assetsTable.name, 'transaction-anomaly-analyst'))
      .all()
    const v12 = rows.find((r) => r.version === '1.2.0')!
    const v13 = rows.find((r) => r.version === '1.3.0')!
    // 把 1.2.0 的内容、哈希、签名整体复制到 1.3.0 这一行
    services.db
      .update(assetsTable)
      .set({ contentJson: v12.contentJson, contentHash: v12.contentHash, signature: v12.signature })
      .where(eq(assetsTable.id, v13.id))
      .run()
    const resolver = services.registry.resolverFor(VENDOR)
    expect(resolver.get('vendor.fin', 'transaction-anomaly-analyst', '1.3.0')).toBeUndefined()
    expect(resolver.integrityFailures()).toEqual(['vendor.fin/transaction-anomaly-analyst@1.3.0'])
  })

  it('同一条完整性失败只写一次审计', () => {
    services.db
      .update(assetsTable)
      .set({ contentHash: `sha256:${'0'.repeat(64)}` })
      .where(eq(assetsTable.name, 'transaction-anomaly-analyst'))
      .run()
    for (let i = 0; i < 5; i += 1)
      services.registry.view(VENDOR, 'vendor.fin', 'transaction-anomaly-analyst')
    expect(services.audit.list({ action: 'registry.integrity' })).toHaveLength(1)
  })
})

describe('发布门禁', () => {
  beforeEach(() => {
    publish(T001, toolSourceFixture())
    publish(T001, workflowFixture())
  })

  it('合规的扩展可以发布', () => {
    const ext = extensionFixture({
      spec: { target: `${AGENT}@^1.0`, tools: { add: ['tenant.t001.tools/notify-im'] } },
    })
    services.registry.put(T001, stringify(ext))
    const result = services.registry.setStatus(
      T001,
      'tenant.t001.finance',
      'anomaly-analyst-ext',
      '0.3.0',
      'published',
    )
    expect(result.ok).toBe(true)
  })

  it('租户工具与标品工具重名时拒绝发布', () => {
    publish(T001, skillFixture({ namespace: 'tenant.t001.skills' }))
    const ext = extensionFixture({
      spec: { target: `${AGENT}@^1.0`, skills: { add: ['tenant.t001.skills/invoice-lookup'] } },
    })
    services.registry.put(T001, stringify(ext))
    const result = services.registry.setStatus(
      T001,
      'tenant.t001.finance',
      'anomaly-analyst-ext',
      '0.3.0',
      'published',
    )
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.code).toBe('PUBLISH_BLOCKED')
    expect(result.error.issues?.map((i) => i.code)).toEqual(['ASSEMBLY_TOOL_NAME_CONFLICT'])
    expect(services.audit.list({ action: 'registry.publish', decision: 'deny' })).toHaveLength(1)
  })

  it('本扩展的钩子条件无效时拒绝发布（警告也拦）', () => {
    const ext = extensionFixture({
      spec: {
        target: `${AGENT}@^1.0`,
        hooks: {
          onResult: {
            workflow: 'tenant.t001.wf/create-followup-task',
            when: 'result.riskLevel == "high"',
          },
        },
      },
    })
    services.registry.put(T001, stringify(ext))
    const result = services.registry.setStatus(
      T001,
      'tenant.t001.finance',
      'anomaly-analyst-ext',
      '0.3.0',
      'published',
    )
    expect(!result.ok && result.error.issues?.map((i) => i.code)).toEqual([
      'ASSEMBLY_HOOK_CONDITION_INVALID',
    ])
  })

  it('静态检查不通过的资产不能发布', () => {
    const source = toolSourceFixture({
      name: 'no-level',
      spec: { tools: [{ name: 'x_tool', description: '无写级别' }] },
    })
    services.registry.put(T001, stringify(source))
    const result = services.registry.setStatus(
      T001,
      'tenant.t001.tools',
      'no-level',
      '1.0.0',
      'published',
    )
    expect(!result.ok && result.error.issues?.map((i) => i.code)).toEqual(['LINT_tool-write-level'])
  })
})

describe('租户自己的 Agent', () => {
  it('租户读自己 Agent 的有效定义能看到指令原文', () => {
    publish(
      T001,
      agentFixture({
        namespace: 'tenant.t001.agents',
        spec: { semanticPacks: [], constraints: [], instructions: '我们自己的指令' },
      }),
    )
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: 'tenant.t001.agents/transaction-anomaly-analyst',
      tenantId: 't001',
    })
    expect(outcome.ok).toBe(true)
    expect(JSON.stringify(outcome.ok && outcome.view)).toContain('我们自己的指令')
  })
})
