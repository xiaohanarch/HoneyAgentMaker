import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { lintAsset, type Asset, type Viewer } from '@eab/spec'
import { assembleFromRegistry } from '../registry/effective'
import type { PlatformServices } from '../services'
import type { SeedReport } from '../registry/seed'
import { loadRepositoryRegistry } from './repo-registry'

// 仓库里 assets/ 下的真实资产：全部合法、静态检查干净、可组装，且租户视角不泄露标品指令

let services: PlatformServices
let seed: SeedReport
let assets: Asset[]

beforeAll(async () => {
  ;({ services, seed } = await loadRepositoryRegistry())
  assets = services.registry.latestPublished({}).assets
})

afterAll(() => services.db.$client.close())

const T001: Viewer = { type: 'tenant', tenantId: 't001' }

describe('仓库资产', () => {
  it('全部资产通过 schema 校验并签名入库', () => {
    expect(seed.failed).toEqual([])
    expect(assets.length).toBeGreaterThanOrEqual(25)
  })

  it('静态检查没有问题', () => {
    const issues = assets.flatMap((asset) =>
      lintAsset(asset, { resolver: services.registry.resolverFor({ type: 'platform' }) }).map(
        (i) => `${asset.metadata.name} ${i.path} ${i.message}`,
      ),
    )
    expect(issues).toEqual([])
  })

  it('三个语义包各有至少 5 个对象、3 条规则、2 个指标、5 条已验证任务（主提示词 13.1 节）', () => {
    const packs = assets.filter(
      (a) => a.kind === 'SemanticPack' && a.metadata.namespace.startsWith('vendor.'),
    )
    expect(packs).toHaveLength(3)
    for (const pack of packs) {
      if (pack.kind !== 'SemanticPack') continue
      expect(pack.spec.objects.length, pack.metadata.name).toBeGreaterThanOrEqual(5)
      expect(pack.spec.rules.length, pack.metadata.name).toBeGreaterThanOrEqual(3)
      expect(pack.spec.metrics.length, pack.metadata.name).toBeGreaterThanOrEqual(2)
      expect(pack.spec.verified_tasks.length, pack.metadata.name).toBeGreaterThanOrEqual(5)
    }
  })

  it('四个标品 Agent 都能组装，并且都带公开版指令', () => {
    const agents = assets.filter((a) => a.kind === 'Agent')
    expect(agents.map((a) => a.metadata.name).sort()).toEqual([
      'inventory-risk-analyst',
      'period-close-checker',
      'supplier-performance-analyst',
      'transaction-anomaly-analyst',
    ])
    for (const agent of agents) {
      if (agent.kind !== 'Agent') continue
      expect(agent.spec.publicInstructions, agent.metadata.name).toBeDefined()
      const outcome = assembleFromRegistry(
        services.registry,
        { type: 'vendor' },
        {
          agent: `${agent.metadata.namespace}/${agent.metadata.name}`,
        },
      )
      expect(outcome.issues, agent.metadata.name).toEqual([])
    }
  })
})

describe('验收：交易异常解析 Agent + t001 扩展', () => {
  it('组装成功，扩展生效，版本解析记录在 provenance', () => {
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: 'vendor.fin/transaction-anomaly-analyst',
      tenantId: 't001',
    })
    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.issues).toEqual([])
    const { definition } = outcome
    expect(definition.provenance.resolvedTarget).toEqual({ ranges: ['^1.0'], version: '1.2.0' })
    expect(definition.tools.find((t) => t.name === 'notify_im')).toMatchObject({
      layer: 'tenant',
      writeLevel: 'L2',
    })
    expect(definition.hooks).toEqual([
      expect.objectContaining({ event: 'onResult', layer: 'tenant', failurePolicy: 'isolate' }),
    ])
  })

  it('租户视角的有效定义里找不到任何一段标品指令或平台规则原文', () => {
    const outcome = assembleFromRegistry(services.registry, T001, {
      agent: 'vendor.fin/transaction-anomaly-analyst',
      tenantId: 't001',
    })
    if (!outcome.ok) throw new Error('组装失败')
    const serialized = JSON.stringify(outcome.view)
    const secrets = outcome.definition.segments.filter((s) => s.confidential).map((s) => s.text)
    expect(secrets.length).toBeGreaterThanOrEqual(7)
    for (const secret of secrets) {
      // 逐行比对，避免只因换行差异而漏检
      for (const line of secret
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l.length >= 8)) {
        expect(serialized).not.toContain(line)
      }
    }
  })
})
