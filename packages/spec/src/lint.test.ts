import { describe, expect, it } from 'vitest'
import { lintAsset, type LintRuleId } from './lint'
import { InMemoryAssetResolver } from './resolver'
import {
  agentFixture,
  buildAsset,
  extensionFixture,
  semanticPackFixture,
  skillFixture,
  toolSourceFixture,
  workflowFixture,
} from './testing'
import type { Asset } from './assets'

function resolverWith(...assets: Asset[]): InMemoryAssetResolver {
  return new InMemoryAssetResolver(assets.map((asset) => ({ asset, status: 'published' as const })))
}

const BASE = [
  agentFixture(),
  semanticPackFixture(),
  skillFixture(),
  toolSourceFixture(),
  workflowFixture(),
]
const rulesOf = (issues: { rule: LintRuleId }[]) => issues.map((i) => i.rule)

describe('no-hardcoded-endpoint', () => {
  it('资产里写地址或凭据时报错', () => {
    const agent = agentFixture({
      spec: {
        instructions: '查询 http://10.0.0.8:8080/bo 接口',
        additionalContext: '密钥 sk-abcdefghijklmnopqrstu',
      },
    })
    const issues = lintAsset(agent, { resolver: resolverWith(...BASE) }).filter(
      (i) => i.rule === 'no-hardcoded-endpoint',
    )
    expect(issues.map((i) => i.path)).toEqual(['spec.instructions', 'spec.additionalContext'])
  })

  it('凭据字段只能写 env: 或 vault: 引用', () => {
    const skill = skillFixture({
      spec: { steps: [{ id: 's', type: 'tool', tool: 'x', with: { token: 'abc123' } }] },
    })
    expect(rulesOf(lintAsset(skill, { resolver: resolverWith() }))).toContain(
      'no-hardcoded-endpoint',
    )
    const ok = skillFixture({
      spec: { steps: [{ id: 's', type: 'tool', tool: 'x', with: { token: 'env:IM_TOKEN' } }] },
    })
    expect(rulesOf(lintAsset(ok, { resolver: resolverWith() }))).not.toContain(
      'no-hardcoded-endpoint',
    )
  })

  it('连接的环境绑定允许写地址', () => {
    const connection = buildAsset('Connection', {
      name: 'demo-erp',
      namespace: 'tenant.t001.connections',
      spec: {
        alias: 'DEMO_ERP',
        type: 'bo-api',
        bindings: { dev: { url: 'http://127.0.0.1:4100', auth: { type: 'token-exchange' } } },
      },
    })
    expect(lintAsset(connection, { resolver: resolverWith() })).toEqual([])
  })
})

describe('tool-write-level', () => {
  it('工具没有声明写级别时报错', () => {
    const source = toolSourceFixture({
      spec: { tools: [{ name: 'notify_im', description: '通知' }] },
    })
    expect(rulesOf(lintAsset(source, { resolver: resolverWith() }))).toEqual(['tool-write-level'])
  })

  it('付款类工具最高只能到 L2', () => {
    const source = toolSourceFixture({
      spec: {
        tools: [{ name: 'pay', description: '付款', writeLevel: 'L3', category: 'payment' }],
      },
    })
    const [issue] = lintAsset(source, { resolver: resolverWith() })
    expect(issue?.message).toContain('最高只能到 L2')
  })
})

describe('extension-point-declared', () => {
  it('扩展只用了已声明的扩展点时没有问题', () => {
    expect(lintAsset(extensionFixture(), { resolver: resolverWith(...BASE) })).toEqual([])
  })

  it('使用未声明的扩展点、未允许的钩子事件或模型档位时报错', () => {
    const narrowAgent = agentFixture({
      spec: {
        extensionPoints: [
          { type: 'hooks', description: '只允许 onResult', events: ['onResult'] },
          { type: 'model.select', description: '模型', allowedProfiles: ['reasoning'] },
        ],
      },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        instructions: { append: '追加' },
        hooks: { onFailure: { workflow: 'tenant.t001.wf/create-followup-task' } },
        model: { profile: 'fast' },
      },
    })
    const issues = lintAsset(ext, {
      resolver: resolverWith(narrowAgent, semanticPackFixture(), workflowFixture()),
    })
    const messages = issues
      .filter((i) => i.rule === 'extension-point-declared')
      .map((i) => i.message)
    expect(messages).toEqual([
      '目标 Agent 没有声明扩展点 instructions.append',
      '目标 Agent 的 hooks 扩展点不允许挂在 onFailure 事件上',
      '目标 Agent 只允许选择模型档位 reasoning，不能选 fast',
    ])
  })
})

describe('reference-resolvable', () => {
  it('引用不存在、版本不满足或 kind 不符时报错', () => {
    const agent = agentFixture({
      spec: {
        tools: [
          { skill: 'vendor.fin.skills/invoice-lookup@^2' },
          { agent: 'vendor.fin/finance' },
          { builtin: 'teleport' },
        ],
        semanticPacks: ['vendor.fin/finance', 'vendor.fin/missing'],
      },
    })
    const messages = lintAsset(agent, { resolver: resolverWith(...BASE) }).map((i) => i.message)
    expect(messages).toEqual([
      '引用的资产 vendor.fin.skills/invoice-lookup@^2 不存在，或者没有满足 ^2 的版本',
      '引用的资产 vendor.fin/finance 是 SemanticPack，这里需要 Agent',
      '引用的资产 vendor.fin/missing 不存在',
      '内置工具 teleport 不存在',
    ])
  })

  it('约束引用的规则编号必须在语义包里存在，扩展可以引用自己追加的语义包', () => {
    const agent = agentFixture({ spec: { constraints: ['FIN-R-001', 'FIN-R-404'] } })
    const agentIssues = lintAsset(agent, { resolver: resolverWith(...BASE) })
    expect(agentIssues.map((i) => i.path)).toEqual(['spec.constraints[1]'])

    const tenantPack = semanticPackFixture({
      name: 'finance-ext',
      namespace: 'tenant.t001.semantic',
      spec: {
        extends: 'vendor.fin/finance',
        rules: [{ id: 'FIN-R-101', severity: 'warn', remedy: '跨期冲销不算异常' }],
      },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        constraints: { append: ['FIN-R-101', 'FIN-R-001'] },
        knowledge: { add: ['tenant.t001.semantic/finance-ext'] },
      },
    })
    expect(lintAsset(ext, { resolver: resolverWith(...BASE, tenantPack) })).toEqual([])
  })
})
