import { describe, expect, it } from 'vitest'
import { InMemoryAssetResolver } from '@eab/spec'
import {
  agentFixture,
  extensionFixture,
  platformRulesFixture,
  semanticPackFixture,
  skillFixture,
  toolSourceFixture,
} from '@eab/spec/testing'
import { assemble, type EffectiveDefinition } from '@eab/assembler'
import {
  DEFAULT_RULES,
  buildEntryInput,
  buildHandoffInput,
  buildToolInput,
  evaluate,
  loadRuleSet,
  type SubjectFacts,
} from './index'
import type { ToolContext } from './inputs'

const ZHANGMIN: SubjectFacts = {
  userId: 'zhangmin',
  tenantId: 't001',
  roles: ['purchasing-manager'],
  operations: ['supplier_list', 'purchase_order_list'],
}

function definition(): EffectiveDefinition {
  const boSource = toolSourceFixture({
    name: 'demo-erp-bo',
    namespace: 'vendor.erp.tools',
    spec: {
      type: 'bo-api',
      connection: 'DEMO_ERP',
      tools: [
        { name: 'purchase_order_list', description: '查订单', writeLevel: 'L0', category: 'query' },
        {
          name: 'purchase_order_change_approve',
          description: '审批变更',
          writeLevel: 'L2',
          category: 'write',
        },
        { name: 'auto_post', description: '自动过账', writeLevel: 'L3', category: 'posting' },
        { name: 'auto_tag', description: '打标签', writeLevel: 'L3', category: 'write' },
      ],
    },
  })
  const agent = agentFixture({
    spec: {
      writePolicy: { maxLevel: 'L3' },
      tools: [
        { api: { source: 'vendor.erp.tools/demo-erp-bo' } },
        { skill: 'vendor.fin.skills/invoice-lookup' },
      ],
      approvals: [
        {
          tool: 'invoice-lookup',
          policy: 'require-approval',
          when: 'input.amount > 100000.0',
          approverRole: '财务经理',
        },
      ],
    },
  })
  const ext = extensionFixture({
    spec: {
      target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
      tools: { add: ['tenant.t001.tools/notify-im'] },
      approvals: { add: [{ tool: 'tenant.t001.tools/notify-im', policy: 'require-confirmation' }] },
    },
  })
  const result = assemble({
    agent: 'vendor.fin/transaction-anomaly-analyst',
    extensions: [ext],
    platformRules: [platformRulesFixture()],
    resolver: new InMemoryAssetResolver(
      [agent, semanticPackFixture(), skillFixture(), toolSourceFixture(), boSource].map(
        (asset) => ({
          asset,
          status: 'published' as const,
        }),
      ),
    ),
  })
  if (!result.ok) throw new Error(result.issues.map((i) => i.message).join('；'))
  return result.definition
}

const DEF = definition()

function tool(overrides: Partial<ToolContext>) {
  return evaluate(
    DEFAULT_RULES,
    'tool',
    buildToolInput({
      subject: ZHANGMIN,
      mode: 'on-behalf',
      definition: DEF,
      toolName: 'purchase_order_list',
      tenant: { l3Enabled: false },
      ...overrides,
    }),
  )
}

describe('默认规则集', () => {
  it('加载时校验通过，规则编号唯一', () => {
    expect(DEFAULT_RULES.rules.length).toBeGreaterThanOrEqual(15)
  })

  it('条件写错的规则集拒绝加载', () => {
    expect(() =>
      loadRuleSet({
        version: 'x',
        rules: [
          {
            id: 'TOOL-999',
            checkpoint: 'tool',
            description: 'd',
            effect: 'deny',
            when: 'input.a ==',
            reason: 'r',
          },
        ],
      }),
    ).toThrow(/条件无效/)
  })
})

describe('鉴权点 1：入口', () => {
  const entry = (subject: SubjectFacts, assistant?: { ref: string; allowedRoles: string[] }) =>
    evaluate(
      DEFAULT_RULES,
      'entry',
      buildEntryInput({
        subject,
        tenantId: 't001',
        ...(assistant === undefined ? {} : { assistant }),
      }),
    )

  it('本租户有业务权限的用户允许使用', () => {
    expect(entry(ZHANGMIN).outcome).toBe('allow')
  })

  it('其他租户的用户被拒绝，原因里带用户编号', () => {
    const decision = entry({ ...ZHANGMIN, userId: 'zhouting', tenantId: 't002' })
    expect(decision).toMatchObject({
      outcome: 'deny',
      ruleId: 'ENTRY-001',
      reason: '用户 zhouting 不属于租户 t001',
    })
  })

  it('没有业务权限的用户被拒绝', () => {
    expect(
      entry({ ...ZHANGMIN, userId: 'chengong', roles: ['tenant-developer'], operations: [] })
        .ruleId,
    ).toBe('ENTRY-002')
  })

  it('岗位助手只对指定角色开放', () => {
    expect(
      entry(ZHANGMIN, { ref: 'vendor.fin/finance-assistant', allowedRoles: ['finance-manager'] })
        .ruleId,
    ).toBe('ENTRY-003')
    expect(
      entry(ZHANGMIN, {
        ref: 'vendor.pur/procurement-assistant',
        allowedRoles: ['purchasing-manager'],
      }).outcome,
    ).toBe('allow')
  })
})

describe('鉴权点 2：Agent 交接', () => {
  const handoff = (
    caller: { type: 'router' | 'assistant' | 'agent' | 'external'; ref: string; depth: number },
    target: Partial<{
      kind: 'Agent' | 'RemoteAgent'
      enabledForTenant: boolean
      allowedCallers: string[]
    }> = {},
  ) =>
    evaluate(
      DEFAULT_RULES,
      'handoff',
      buildHandoffInput({
        subject: ZHANGMIN,
        caller,
        target: {
          ref: 'tenant.t001.remote/supplier-risk',
          kind: 'RemoteAgent',
          enabledForTenant: true,
          allowedCallers: ['tenant.t001.pur/price-negotiation'],
          ...target,
        },
      }),
    )

  it('登记过的调用方可以调用远程 Agent', () => {
    expect(
      handoff({ type: 'agent', ref: 'tenant.t001.pur/price-negotiation', depth: 1 }).outcome,
    ).toBe('allow')
  })

  it('未登记的调用方、未启用的 Agent、过深的调用链、无身份的入站调用都被拒绝', () => {
    expect(
      handoff({ type: 'agent', ref: 'vendor.pur/supplier-performance-analyst', depth: 1 }).ruleId,
    ).toBe('HANDOFF-002')
    expect(
      handoff({ type: 'router', ref: 'router', depth: 0 }, { enabledForTenant: false }).ruleId,
    ).toBe('HANDOFF-001')
    expect(
      handoff({ type: 'agent', ref: 'tenant.t001.pur/price-negotiation', depth: 4 }).ruleId,
    ).toBe('HANDOFF-003')
    expect(handoff({ type: 'external', ref: '', depth: 0 }, { kind: 'Agent' }).ruleId).toBe(
      'HANDOFF-004',
    )
  })
})

describe('鉴权点 3：工具', () => {
  it('声明过的只读工具直接允许', () => {
    expect(tool({}).outcome).toBe('allow')
  })

  it('未声明的工具被拒绝', () => {
    expect(tool({ toolName: 'wire_transfer' })).toMatchObject({
      outcome: 'deny',
      ruleId: 'TOOL-001',
    })
  })

  it('集成账号模式被拒绝', () => {
    expect(tool({ mode: 'integration' }).ruleId).toBe('TOOL-002')
  })

  it('过账类工具不能 L3 自主执行；其他 L3 工具需要租户开启', () => {
    expect(tool({ toolName: 'auto_post', tenant: { l3Enabled: true } }).ruleId).toBe('TOOL-003')
    expect(tool({ toolName: 'auto_tag' }).ruleId).toBe('TOOL-004')
    expect(tool({ toolName: 'auto_tag', tenant: { l3Enabled: true } }).outcome).toBe('allow')
  })

  it('职责分离：审批自己提交的单据被拒绝，审批他人提交的需要确认', () => {
    const own = tool({
      toolName: 'purchase_order_change_approve',
      document: { id: 'DC-1', action: 'approve', submittedBy: 'zhangmin' },
    })
    expect(own).toMatchObject({ outcome: 'deny', ruleId: 'TOOL-005' })
    expect(own.reason).toContain('职责分离')
    const others = tool({
      toolName: 'purchase_order_change_approve',
      document: { id: 'DC-1', action: 'approve', submittedBy: 'liqiang' },
    })
    expect(others).toMatchObject({ outcome: 'confirm', ruleId: 'TOOL-010' })
  })

  it('受托运行只能用委托范围内的工具，且不做 L2 写', () => {
    const delegation = { id: 'DLG-1', scope: ['purchase_order_list'] }
    expect(tool({ mode: 'delegated', delegation }).outcome).toBe('allow')
    expect(
      tool({ mode: 'delegated', delegation, toolName: 'auto_tag', tenant: { l3Enabled: true } })
        .ruleId,
    ).toBe('TOOL-006')
    expect(
      tool({
        mode: 'delegated',
        delegation: { id: 'DLG-1', scope: ['purchase_order_change_approve'] },
        toolName: 'purchase_order_change_approve',
      }).ruleId,
    ).toBe('TOOL-007')
  })

  it('扩展声明的确认点生效，L2 与确认点同时命中时都列出', () => {
    const decision = tool({ toolName: 'notify_im' })
    expect(decision.outcome).toBe('confirm')
    expect(decision.requirements.map((r) => r.ruleId)).toEqual(['TOOL-010', 'TOOL-011'])
  })

  it('带条件的审批点只在条件成立时生效，审批优先于确认', () => {
    expect(tool({ toolName: 'invoice-lookup', toolInput: { amount: 5000 } }).outcome).toBe('allow')
    const big = tool({ toolName: 'invoice-lookup', toolInput: { amount: 200000 } })
    expect(big).toMatchObject({ outcome: 'approve', ruleId: 'TOOL-012' })
    expect(big.reason).toContain('财务经理')
  })

  it('规则求值出错时拒绝（失败即拒绝）', () => {
    const rules = loadRuleSet({
      version: 'x',
      rules: [
        {
          id: 'TOOL-900',
          checkpoint: 'tool',
          description: '引用不存在的事实',
          effect: 'confirm',
          when: 'input.nope.deeper == 1',
          reason: 'r',
        },
      ],
    })
    const decision = evaluate(rules, 'tool', { subject: {} })
    expect(decision).toMatchObject({ outcome: 'deny', ruleId: 'TOOL-900' })
    expect(decision.reason).toContain('失败即拒绝')
  })

  it('裁决记录带规则集版本、时间和判定依据', () => {
    const decision = tool({ toolName: 'wire_transfer' })
    expect(decision.rulesVersion).toBe(DEFAULT_RULES.version)
    expect(decision.at).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    expect(decision.facts).toMatchObject({ tool: { name: 'wire_transfer', declared: false } })
  })
})
