import { describe, expect, it } from 'vitest'
import { InMemoryAssetResolver, type Asset } from '@eab/spec'
import {
  agentFixture,
  buildAsset,
  extensionFixture,
  semanticPackFixture,
  skillFixture,
  toolSourceFixture,
  workflowFixture,
} from '@eab/spec/testing'
import { assemble } from './assemble'
import { checkExpression, hookEnvironment } from './conditions'

const remoteAgent = buildAsset('RemoteAgent', {
  name: 'supplier-risk',
  namespace: 'tenant.t001.remote',
  spec: {
    connection: 'SUPPLIER_RISK_AGENT',
    auth: { type: 'platform-service-token' },
    routing: { description: '查询供应商外部舆情风险' },
  },
})
const knowledge = buildAsset('KnowledgeSource', {
  name: 'negotiation-playbook',
  namespace: 'tenant.t001.kb',
  spec: { documents: [{ title: '降价谈判制度', path: 'docs/playbook.md' }] },
})
const helperAgent = agentFixture({ name: 'helper-agent', namespace: 'tenant.t001.agents' })
const tenantSkill = skillFixture({
  name: 'vendor-check',
  namespace: 'tenant.t001.skills',
  spec: { writeLevel: 'L1' },
})

function resolver(...extra: Asset[]): InMemoryAssetResolver {
  return new InMemoryAssetResolver(
    [
      agentFixture(),
      semanticPackFixture(),
      skillFixture(),
      toolSourceFixture(),
      workflowFixture(),
      ...extra,
    ].map((asset) => ({ asset, status: 'published' as const })),
  )
}

describe('扩展里直接写引用时按被引用资产的 kind 归一化', () => {
  it('技能、Agent、远程 Agent、知识源、MCP 工具来源都能作为工具挂载', () => {
    const mcpSource = toolSourceFixture({
      name: 'logistics-mcp',
      spec: {
        type: 'mcp',
        connection: 'LOGISTICS_MCP',
        tools: [{ name: 'track_shipment', description: '物流在途查询', writeLevel: 'L0' }],
      },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: {
          add: [
            'tenant.t001.skills/vendor-check',
            'tenant.t001.agents/helper-agent',
            'tenant.t001.remote/supplier-risk',
            'tenant.t001.kb/negotiation-playbook',
            'tenant.t001.tools/logistics-mcp',
          ],
        },
      },
    })
    const result = assemble({
      agent: 'vendor.fin/transaction-anomaly-analyst',
      extensions: [ext],
      resolver: resolver(remoteAgent, knowledge, helperAgent, tenantSkill, mcpSource),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const tenantTools = result.definition.tools
      .filter((t) => t.layer === 'tenant')
      .map((t) => `${t.type}:${t.name}`)
    expect(tenantTools).toEqual([
      'skill:vendor-check',
      'agent:helper-agent',
      'remoteAgent:supplier-risk',
      'documents:kb_negotiation_playbook',
      'mcp:track_shipment',
    ])
  })

  it('引用的资产不是可挂载的 kind，或 kind 与条目类型不符时报错', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: {
          add: [
            'vendor.fin/finance',
            { agent: 'tenant.t001.remote/supplier-risk' },
            { builtin: 'teleport' },
          ],
        },
      },
    })
    const result = assemble({
      agent: 'vendor.fin/transaction-anomaly-analyst',
      extensions: [ext],
      resolver: resolver(remoteAgent),
    })
    expect(result.issues.map((i) => i.code)).toEqual([
      'REFERENCE_UNRESOLVED',
      'REFERENCE_UNRESOLVED',
      'REFERENCE_UNRESOLVED',
    ])
  })
})

describe('条件环境', () => {
  it('onFailure 可以引用 error，工具调用事件可以引用 tool、input、output', () => {
    expect(
      checkExpression(hookEnvironment('onFailure', undefined), "error.code == 'TIMEOUT'"),
    ).toEqual({ valid: true })
    expect(
      checkExpression(
        hookEnvironment('beforeToolCall', undefined),
        "tool == 'notify_im' && input.to != ''",
      ),
    ).toEqual({
      valid: true,
    })
    expect(
      checkExpression(hookEnvironment('afterToolCall', undefined), 'result.x == 1').valid,
    ).toBe(false)
  })

  it('没有声明输出结构时 result 按 map 处理，条件结果必须是布尔值', () => {
    const env = hookEnvironment('onResult', undefined)
    expect(checkExpression(env, "result.anything == 'x'")).toEqual({ valid: true })
    const output = { schema: { amount: { type: 'double' as const } } }
    const nonBool = checkExpression(hookEnvironment('onResult', output), 'result.amount + 1.0')
    expect(nonBool).toEqual({ valid: false, reason: '条件的结果类型是 double，应为 bool' })
  })
})
