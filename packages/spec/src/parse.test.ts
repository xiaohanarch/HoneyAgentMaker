import { describe, expect, it } from 'vitest'
import { formatIssue, parseAssetObject, parseAssetYaml } from './parse'

const AGENT = `apiVersion: eab.dev/v1
kind: Agent
metadata:
  name: transaction-anomaly-analyst
  namespace: vendor.fin
  version: 1.2.0
  displayName: 交易异常解析 Agent
spec:
  expertise: 分析交易异常的根因
  instructions: |
    先判断异常类型，再定位根因。
  constraints: [FIN-R-001, 不对未核实的数据下结论]
  model: {profile: reasoning, temperature: 0.2}
  tools:
    - skill: vendor.fin.skills/invoice-lookup
    - api: {source: vendor.erp.tools/demo-erp-bo, tools: [anomaly_list]}
  semanticPacks: [vendor.fin/finance]
  writePolicy: {maxLevel: L1}
  routing:
    description: 分析交易异常的根因
  extensionPoints:
    - type: instructions.append
      description: 追加本企业的分析口径
    - type: hooks
      description: 结果产出后触发后续流程
      events: [onResult]
`

describe('parseAssetYaml', () => {
  it('解析合法的 Agent 并填充默认值', () => {
    const result = parseAssetYaml(AGENT)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.asset.kind).toBe('Agent')
    if (result.asset.kind !== 'Agent') return
    expect(result.asset.spec.channels).toEqual(['assistant-web'])
    expect(result.asset.spec.triggers).toEqual([{ type: 'assistant' }])
    expect(result.asset.spec.routing.direct).toBe(false)
  })

  it('缺少必填字段时定位到所在的父对象并给出中文说明', () => {
    const result = parseAssetYaml(AGENT.replace('  writePolicy: {maxLevel: L1}\n', ''))
    expect(result.ok).toBe(false)
    if (result.ok) return
    const issue = result.issues.find((i) => i.path === 'spec.writePolicy')
    expect(issue).toBeDefined()
    expect(issue?.line).toBe(9)
    expect(issue?.message).toMatch(/[一-龥]/)
  })

  it('嵌套字段出错时报告准确的行号和路径', () => {
    const result = parseAssetYaml(
      AGENT.replace('anomaly_list]}', 'anomaly_list]}\n    - mcp: {source: not-a-ref}'),
    )
    expect(result.ok).toBe(false)
    if (result.ok) return
    const issue = result.issues.find((i) => i.path === 'spec.tools[2].mcp.source')
    expect(issue?.line).toBe(17)
    expect(formatIssue(issue!, 'agent.yaml')).toMatch(
      /^agent\.yaml 第 17 行第 \d+ 列 spec\.tools\[2\]\.mcp\.source：/,
    )
  })

  it('工具条目同时写两种来源时报错', () => {
    const result = parseAssetYaml(
      AGENT.replace(
        '- skill: vendor.fin.skills/invoice-lookup',
        '- {skill: vendor.fin.skills/a, agent: vendor.fin/b}',
      ),
    )
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues[0]?.message).toContain('只能写一种来源')
  })

  it('拒绝拼错的字段名', () => {
    const result = parseAssetYaml(AGENT.replace('expertise:', 'expertize:'))
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues.some((i) => i.message.includes('expertize'))).toBe(true)
  })

  it('YAML 语法错误带行号', () => {
    const result = parseAssetYaml('apiVersion: eab.dev/v1\nkind: Agent\nmetadata: {name: [\n')
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues[0]?.code).toBe('YAML_SYNTAX')
    expect(result.issues[0]?.line).toBeGreaterThan(0)
  })

  it.each([
    ['', 'EMPTY_DOCUMENT'],
    ['a: 1\n---\nb: 2\n', 'MULTIPLE_DOCUMENTS'],
    ['- 1\n', 'NOT_AN_OBJECT'],
    ['apiVersion: v0\nkind: Agent\n', 'UNSUPPORTED_API_VERSION'],
    ['apiVersion: eab.dev/v1\nkind: Robot\n', 'UNKNOWN_KIND'],
  ])('非法文档 %j 报 %s', (text, code) => {
    const result = parseAssetYaml(text)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues[0]?.code).toBe(code)
  })

  it('扩展只能放在租户命名空间', () => {
    const result = parseAssetObject({
      apiVersion: 'eab.dev/v1',
      kind: 'AgentExtension',
      metadata: { name: 'ext', namespace: 'vendor.fin', version: '0.1.0' },
      spec: { target: 'vendor.fin/transaction-anomaly-analyst@^1.0' },
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues[0]).toMatchObject({
      code: 'KIND_LAYER_MISMATCH',
      path: 'metadata.namespace',
    })
  })

  it('扩展的 target 必须带版本范围，钩子可以写单个对象', () => {
    const base = {
      apiVersion: 'eab.dev/v1',
      kind: 'AgentExtension',
      metadata: { name: 'ext', namespace: 'tenant.t001.finance', version: '0.1.0' },
    }
    const noRange = parseAssetObject({
      ...base,
      spec: { target: 'vendor.fin/transaction-anomaly-analyst' },
    })
    expect(noRange.ok).toBe(false)

    const ok = parseAssetObject({
      ...base,
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        hooks: {
          onResult: {
            workflow: 'tenant.t001.wf/create-followup-task',
            when: "result.severity == 'high'",
          },
        },
      },
    })
    expect(ok.ok).toBe(true)
    if (!ok.ok || ok.asset.kind !== 'AgentExtension') return
    expect(ok.asset.spec.hooks?.onResult).toHaveLength(1)
  })
})

describe('parseAssetObject 的健壮性', () => {
  it('扩展 target 不是合法引用时报 schema 问题而不是抛异常', () => {
    const result = parseAssetObject({
      apiVersion: 'eab.dev/v1',
      kind: 'AgentExtension',
      metadata: { name: 'x', namespace: 'tenant.t001', version: '1.0.0' },
      spec: { target: 'nope' },
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.issues[0]?.path).toBe('spec.target')
  })
})
