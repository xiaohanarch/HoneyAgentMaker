import { describe, expect, it } from 'vitest'
import { InMemoryAssetResolver, isRedacted, type Asset, type AssetStatus } from '@eab/spec'
import {
  VENDOR_ANOMALY_INSTRUCTIONS,
  agentFixture,
  buildAsset,
  extensionFixture,
  platformRulesFixture,
  semanticPackFixture,
  skillFixture,
  toolSourceFixture,
  workflowFixture,
} from '@eab/spec/testing'
import { assemble, type AssembleInput } from './assemble'
import type { AssembleResult, EffectiveDefinition } from './types'
import { renderInstructions, viewEffectiveDefinition } from './view'

const TENANT = { type: 'tenant', tenantId: 't001' } as const

function resolverOf(...entries: (Asset | [Asset, AssetStatus])[]): InMemoryAssetResolver {
  return new InMemoryAssetResolver(
    entries.map((e) =>
      Array.isArray(e) ? { asset: e[0], status: e[1] } : { asset: e, status: 'published' },
    ),
  )
}

const BASE: Asset[] = [
  agentFixture({ version: '1.2.0' }),
  semanticPackFixture(),
  skillFixture(),
  toolSourceFixture(),
  workflowFixture(),
]
const PLATFORM_RULES = [platformRulesFixture()]

function run(
  overrides: Partial<AssembleInput> & { assets?: (Asset | [Asset, AssetStatus])[] } = {},
): AssembleResult {
  const { assets = BASE, ...rest } = overrides
  return assemble({
    agent: 'vendor.fin/transaction-anomaly-analyst',
    tenantId: 't001',
    platformRules: PLATFORM_RULES,
    resolver: resolverOf(...assets),
    ...rest,
  })
}

function definitionOf(result: AssembleResult): EffectiveDefinition {
  if (!result.ok) throw new Error(`组装失败：${result.issues.map((i) => i.message).join('；')}`)
  return result.definition
}

function codesOf(result: AssembleResult): string[] {
  return result.issues.map((i) => i.code)
}

describe('规则 1：指令按固定顺序拼接，每段带来源', () => {
  it('平台规则 → 标品指令 → 标品约束 → 语义包指令 → 租户追加指令 → 租户追加约束', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        instructions: { append: '单笔超 50 万须给出责任部门' },
        constraints: { append: ['结论里不写客户姓名'] },
      },
    })
    const definition = definitionOf(run({ extensions: [ext] }))

    expect(definition.segments.map((s) => s.kind)).toEqual([
      'platform-rule',
      'vendor-instructions',
      'vendor-constraint',
      'vendor-constraint',
      'semantic-instruction',
      'tenant-instructions',
      'tenant-constraint',
    ])
    expect(definition.segments.map((s) => s.layer)).toEqual([
      'platform',
      'vendor',
      'vendor',
      'vendor',
      'vendor',
      'tenant',
      'tenant',
    ])
    expect(definition.segments[1]?.source).toBe('vendor.fin/transaction-anomaly-analyst@1.2.0')
    expect(definition.segments[5]?.source).toBe('tenant.t001.finance/anomaly-analyst-ext@0.3.0')
  })

  it('规则编号约束展开为语义包里的处置建议，并且对租户可见', () => {
    const definition = definitionOf(run())
    const ruleSegment = definition.segments.find((s) => s.label === 'FIN-R-001')
    expect(ruleSegment?.text).toBe('[FIN-R-001] 金额必须带币种')
    expect(ruleSegment?.confidential).toBe(false)
  })

  it('约束引用的规则在语义包里找不到时组装失败', () => {
    const agent = agentFixture({ version: '1.2.0', spec: { constraints: ['FIN-R-999'] } })
    const result = run({ assets: [agent, ...BASE.slice(1)] })
    expect(result.ok).toBe(false)
    expect(codesOf(result)).toContain('REFERENCE_UNRESOLVED')
  })

  it('renderInstructions 输出带来源标记的系统提示词', () => {
    const text = renderInstructions(definitionOf(run()))
    expect(text.indexOf('【平台机密】')).toBeLessThan(text.indexOf(VENDOR_ANOMALY_INSTRUCTIONS))
    expect(text).toContain(
      '<!-- vendor-instructions vendor.fin/transaction-anomaly-analyst@1.2.0 spec.instructions -->',
    )
  })
})

describe('规则 2：工具取并集，租户工具名冲突时拒绝', () => {
  it('标品工具、语义查询工具、租户新增工具合并', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: { add: ['tenant.t001.tools/notify-im'] },
      },
    })
    const definition = definitionOf(run({ extensions: [ext] }))
    const names = definition.tools.map((t) => `${t.layer}:${t.name}`)
    expect(names).toContain('vendor:invoice-lookup')
    expect(names).toContain('platform:semantic.describe_object')
    expect(names).toContain('tenant:notify_im')
    expect(definition.tools.find((t) => t.name === 'notify_im')?.writeLevel).toBe('L2')
  })

  it('租户新增的工具与标品工具重名时报 TOOL_NAME_CONFLICT', () => {
    const tenantSkill = skillFixture({ namespace: 'tenant.t001.skills' })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        skills: { add: ['tenant.t001.skills/invoice-lookup'] },
      },
    })
    const result = run({ extensions: [ext], assets: [...BASE, tenantSkill] })
    expect(result.ok).toBe(false)
    expect(codesOf(result)).toEqual(['TOOL_NAME_CONFLICT'])
  })

  it('引用不存在的工具来源或工具时报错', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: {
          add: [
            { api: { source: 'tenant.t001.tools/notify-im', tools: ['notify_sms'] } },
            'tenant.t001.tools/missing',
          ],
        },
      },
    })
    expect(codesOf(run({ extensions: [ext] }))).toEqual([
      'REFERENCE_UNRESOLVED',
      'REFERENCE_UNRESOLVED',
    ])
  })
})

describe('规则 3：人工确认策略取最严', () => {
  const vendorAgent = agentFixture({
    version: '1.2.0',
    spec: { approvals: [{ tool: 'invoice-lookup', policy: 'require-confirmation' }] },
  })
  const assets = [vendorAgent, ...BASE.slice(1)]

  it('标品和租户的确认点都保留，租户可以加更严格的确认', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: { add: ['tenant.t001.tools/notify-im'] },
        approvals: {
          add: [
            { tool: 'tenant.t001.tools/notify-im', policy: 'require-confirmation' },
            {
              tool: 'invoice-lookup',
              policy: 'require-approval',
              when: 'input.amount > 100000',
              approverRole: '财务经理',
            },
          ],
        },
      },
    })
    const definition = definitionOf(run({ extensions: [ext], assets }))
    expect(definition.approvals.map((a) => `${a.layer}:${a.tool}:${a.policy}`)).toEqual([
      'vendor:invoice-lookup:require-confirmation',
      'tenant:tenant.t001.tools/notify-im:require-confirmation',
      'tenant:invoice-lookup:require-approval',
    ])
  })

  it('租户放宽标品已有的确认要求时报 APPROVAL_RELAXED', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        approvals: { add: [{ tool: 'invoice-lookup', policy: 'auto' }] },
      },
    })
    expect(codesOf(run({ extensions: [ext], assets }))).toEqual(['APPROVAL_RELAXED'])
  })

  it('确认点指向不存在的工具或条件写错时报错', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        approvals: {
          add: [{ tool: 'wire_transfer', policy: 'require-approval', when: 'input.amount >' }],
        },
      },
    })
    expect(codesOf(run({ extensions: [ext], assets }))).toEqual([
      'REFERENCE_UNRESOLVED',
      'APPROVAL_CONDITION_INVALID',
    ])
  })
})

describe('规则 4：钩子按标品 → 租户顺序，租户钩子失败隔离', () => {
  it('同一事件先标品钩子后租户钩子，租户钩子标注 isolate', () => {
    const agent = agentFixture({
      version: '1.2.0',
      spec: { hooks: { onResult: { skill: 'vendor.fin.skills/invoice-lookup' } } },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        hooks: {
          onResult: {
            workflow: 'tenant.t001.wf/create-followup-task',
            when: "result.severity in ['high','critical']",
          },
        },
      },
    })
    const definition = definitionOf(run({ extensions: [ext], assets: [agent, ...BASE.slice(1)] }))
    expect(definition.hooks.map((h) => `${h.layer}:${h.failurePolicy}`)).toEqual([
      'vendor:propagate',
      'tenant:isolate',
    ])
  })
})

describe('钩子条件按标品输出结构做类型检查（故事 F）', () => {
  const ext = extensionFixture({
    spec: {
      target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
      hooks: {
        onResult: {
          workflow: 'tenant.t001.wf/create-followup-task',
          when: "result.severity in ['high','critical']",
        },
      },
    },
  })

  it('标品升级把 severity 改名为 riskLevel 后，租户钩子停用并给出警告', () => {
    const upgraded = agentFixture({
      version: '1.3.0',
      spec: { output: { schema: { riskLevel: { type: 'string' }, amount: { type: 'double' } } } },
    })
    const result = run({ extensions: [ext], assets: [...BASE, upgraded] })
    const definition = definitionOf(result)
    expect(definition.provenance.agent).toBe('vendor.fin/transaction-anomaly-analyst@1.3.0')
    expect(definition.hooks).toEqual([])
    expect(result.issues).toEqual([
      expect.objectContaining({
        code: 'HOOK_CONDITION_INVALID',
        severity: 'warning',
        source: 'tenant.t001.finance/anomaly-analyst-ext@0.3.0',
      }),
    ])
    expect(result.issues[0]?.message).toContain('No such key: severity')
  })

  it('标品钩子条件无效时组装失败', () => {
    const agent = agentFixture({
      version: '1.2.0',
      spec: {
        hooks: {
          onResult: { skill: 'vendor.fin.skills/invoice-lookup', when: 'result.missing == 1' },
        },
      },
    })
    const result = run({ assets: [agent, ...BASE.slice(1)] })
    expect(result.ok).toBe(false)
    expect(result.issues[0]?.severity).toBe('error')
  })
})

describe('规则 5：模型档位只能在标品允许的范围内选择', () => {
  it('允许范围内的档位生效', () => {
    const ext = extensionFixture({
      spec: { target: 'vendor.fin/transaction-anomaly-analyst@^1.0', model: { profile: 'fast' } },
    })
    expect(definitionOf(run({ extensions: [ext] })).model).toEqual({
      profile: 'fast',
      layer: 'tenant',
    })
  })

  it('超出范围时报错', () => {
    const ext = extensionFixture({
      spec: { target: 'vendor.fin/transaction-anomaly-analyst@^1.0', model: { profile: 'judge' } },
    })
    const result = run({ extensions: [ext] })
    expect(codesOf(result)).toEqual(['MODEL_NOT_ALLOWED'])
  })

  it('没有扩展时使用标品配置', () => {
    expect(definitionOf(run()).model).toEqual({ profile: 'reasoning', layer: 'vendor' })
  })
})

describe('规则 6：扩展的 target 版本范围解析', () => {
  const versions: (Asset | [Asset, AssetStatus])[] = [
    agentFixture({ version: '1.2.0' }),
    agentFixture({ version: '1.4.0' }),
    [agentFixture({ version: '1.5.0' }), 'draft'],
    agentFixture({ version: '2.0.0' }),
    ...BASE.slice(1),
  ]

  it('解析到当前生效的标品版本，范围覆盖它的扩展生效并记录在 provenance', () => {
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^2.0',
        instructions: { append: '追加' },
      },
    })
    const definition = definitionOf(run({ extensions: [ext], assets: versions }))
    expect(definition.provenance.agent).toMatch(/@2\.0\.0$/)
    expect(definition.provenance.resolvedTarget).toEqual({ ranges: ['^2.0'], version: '2.0.0' })
    expect(definition.provenance.extensions).toEqual([
      'tenant.t001.finance/anomaly-analyst-ext@0.3.0',
    ])
  })

  it('请求可以带版本范围；私有环境测试可以带上草稿', () => {
    const pinned = run({ agent: 'vendor.fin/transaction-anomaly-analyst@^1.0', assets: versions })
    expect(definitionOf(pinned).provenance.agent).toMatch(/@1\.4\.0$/)
    const withDraft = run({
      agent: 'vendor.fin/transaction-anomaly-analyst@^1.0',
      extensions: [extensionFixture()],
      assets: versions,
      statuses: ['published', 'draft'],
    })
    expect(definitionOf(withDraft).provenance.agent).toMatch(/@1\.5\.0$/)
  })

  it('范围不覆盖当前版本的扩展本次不生效，给出警告而不是让整个 Agent 不可用', () => {
    const result = run({ extensions: [extensionFixture()], assets: versions })
    const definition = definitionOf(result)
    expect(definition.provenance.agent).toMatch(/@2\.0\.0$/)
    expect(definition.provenance.extensions).toEqual([])
    expect(result.issues).toEqual([
      expect.objectContaining({
        code: 'TARGET_UNRESOLVED',
        severity: 'warning',
        path: 'spec.target',
      }),
    ])
  })

  it('请求的 Agent 引用不合法时返回问题而不是抛异常', () => {
    expect(codesOf(run({ agent: 'not a ref' }))).toEqual(['AGENT_NOT_FOUND'])
  })

  it('只应用 target 指向本 Agent 的扩展；Agent 不存在时报 AGENT_NOT_FOUND', () => {
    const other = extensionFixture({
      name: 'other',
      spec: { target: 'vendor.pur/supplier-performance-analyst@^1.0' },
    })
    expect(definitionOf(run({ extensions: [other] })).provenance.extensions).toEqual([])
    expect(codesOf(run({ agent: 'vendor.fin/missing' }))).toEqual(['AGENT_NOT_FOUND'])
  })

  it('扩展使用未声明的扩展点时报 EXTENSION_POINT_UNDECLARED', () => {
    const narrow = agentFixture({ version: '1.2.0', spec: { extensionPoints: [] } })
    expect(
      codesOf(run({ extensions: [extensionFixture()], assets: [narrow, ...BASE.slice(1)] })),
    ).toEqual(['EXTENSION_POINT_UNDECLARED'])
  })
})

describe('规则 7：有效定义按查看者脱敏', () => {
  const definition = definitionOf(run({ extensions: [extensionFixture()] }))

  it('租户视角下标品指令和平台规则原文是占位块，序列化后找不到原文', () => {
    const view = viewEffectiveDefinition(definition, TENANT)
    const serialized = JSON.stringify(view)
    expect(serialized).not.toContain(VENDOR_ANOMALY_INSTRUCTIONS)
    expect(serialized).not.toContain('【标品机密】')
    expect(serialized).not.toContain('【平台机密】')

    const byKind = (kind: string) => view.segments.find((s) => s.kind === kind)
    expect(isRedacted(byKind('vendor-instructions')?.text)).toBe(true)
    expect(byKind('platform-rule')?.label).toBe('租户数据隔离')
    expect(byKind('tenant-instructions')?.text).toBe(
      '单笔金额超过 50 万元的异常，结论中必须给出责任部门。',
    )
    expect(byKind('semantic-instruction')?.text).toBe('引用金额时必须带币种')
  })

  it('厂商视角看到全文', () => {
    const view = viewEffectiveDefinition(definition, { type: 'vendor' })
    expect(JSON.stringify(view)).toContain(VENDOR_ANOMALY_INSTRUCTIONS)
  })
})

describe('知识与语义包', () => {
  it('扩展追加的语义包及其父包进入有效定义，知识源成为文档检索工具', () => {
    const tenantPack = semanticPackFixture({
      name: 'finance-ext',
      namespace: 'tenant.t001.semantic',
      spec: {
        extends: 'vendor.fin/finance',
        rules: [{ id: 'FIN-R-101', severity: 'warn', remedy: '跨期冲销不算异常' }],
        agent_instructions: ['跨期冲销按正常业务处理'],
      },
    })
    const kb = buildAsset('KnowledgeSource', {
      name: 'anomaly-playbook',
      namespace: 'tenant.t001.kb',
      spec: { documents: [{ title: '异常处理手册', path: 'docs/anomaly.md' }] },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        knowledge: { add: ['tenant.t001.semantic/finance-ext', 'tenant.t001.kb/anomaly-playbook'] },
        constraints: { append: ['FIN-R-101'] },
      },
    })
    const definition = definitionOf(run({ extensions: [ext], assets: [...BASE, tenantPack, kb] }))
    expect(definition.semanticPacks).toEqual([
      'vendor.fin/finance@1.0.0',
      'tenant.t001.semantic/finance-ext@1.0.0',
    ])
    expect(
      definition.segments.filter((s) => s.kind === 'semantic-instruction').map((s) => s.layer),
    ).toEqual(['vendor', 'tenant'])
    expect(definition.segments.at(-1)?.text).toBe('[FIN-R-101] 跨期冲销不算异常')
    expect(
      definition.tools.some((t) => t.name === 'kb_anomaly_playbook' && t.type === 'documents'),
    ).toBe(true)
  })
})

describe('评审补充：可见性、确认点、写级别', () => {
  it('租户自己的 Agent 的指令对本租户不保密', () => {
    const tenantAgent = agentFixture({
      namespace: 'tenant.t001.agents',
      spec: { semanticPacks: [], constraints: ['自定义约束'] },
    })
    const definition = definitionOf(
      assemble({
        agent: 'tenant.t001.agents/transaction-anomaly-analyst',
        resolver: resolverOf(tenantAgent, skillFixture()),
      }),
    )
    expect(definition.segments.filter((s) => s.confidential)).toEqual([])
  })

  it('租户不能声明 auto，也不能通过资产引用的写法放宽标品确认', () => {
    const agent = agentFixture({
      version: '1.2.0',
      spec: { approvals: [{ tool: 'invoice-lookup', policy: 'require-approval' }] },
    })
    const assets = [agent, ...BASE.slice(1)]
    const auto = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        approvals: { add: [{ tool: 'invoice-lookup', policy: 'auto', when: 'input.x == 1' }] },
      },
    })
    expect(codesOf(run({ extensions: [auto], assets }))).toEqual(['APPROVAL_RELAXED'])
    const byRef = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        approvals: {
          add: [{ tool: 'vendor.fin.skills/invoice-lookup', policy: 'require-confirmation' }],
        },
      },
    })
    expect(codesOf(run({ extensions: [byRef], assets }))).toEqual(['APPROVAL_RELAXED'])
  })

  it('strictestPolicy 在匹配的确认点里取最严', async () => {
    const { matchingApprovals, strictestPolicy } = await import('./capabilities')
    const agent = agentFixture({
      version: '1.2.0',
      spec: { approvals: [{ tool: 'invoice-lookup', policy: 'require-confirmation' }] },
    })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        approvals: {
          add: [
            {
              tool: 'vendor.fin.skills/invoice-lookup',
              policy: 'require-approval',
              when: 'input.amount > 100000.0',
            },
          ],
        },
      },
    })
    const definition = definitionOf(run({ extensions: [ext], assets: [agent, ...BASE.slice(1)] }))
    const tool = definition.tools.find((t) => t.name === 'invoice-lookup')!
    const matched = matchingApprovals(definition.approvals, tool)
    expect(matched).toHaveLength(2)
    expect(strictestPolicy(matched)).toBe('require-approval')
    expect(strictestPolicy([])).toBe('auto')
  })

  it('租户新增工具的写级别不能超过 Agent 上限', () => {
    const agent = agentFixture({ version: '1.2.0', spec: { writePolicy: { maxLevel: 'L1' } } })
    const ext = extensionFixture({
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        tools: { add: ['tenant.t001.tools/notify-im'] },
      },
    })
    expect(codesOf(run({ extensions: [ext], assets: [agent, ...BASE.slice(1)] }))).toEqual([
      'TOOL_WRITE_LEVEL_EXCEEDED',
    ])
  })
})
