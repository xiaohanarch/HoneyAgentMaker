import { describe, expect, test } from 'vitest'
import { identityOf, toggle, valueFromAsset } from './draft-values'

const ASSET = {
  apiVersion: 'eab.dev/v1',
  kind: 'Agent',
  metadata: {
    name: 'price-negotiation-assistant',
    namespace: 'tenant.t001.agents',
    displayName: '价格谈判助手',
    description: '帮采购经理制定降价谈判策略',
    businessProcess: ['source-to-pay'],
  },
  spec: {
    instructions: '你是价格谈判助手',
    constraints: ['PUR-R-007'],
    model: { profile: 'reasoning', temperature: 0.2 },
    tools: [
      { builtin: 'calculator' },
      { agent: 'vendor.pur/supplier-performance-analyst' },
      { documents: 'vendor.pur/procurement-policies' },
      { skill: 'vendor.pur.skills/rfq-lookup' },
      {
        api: {
          source: 'vendor.erp.tools/demo-erp-bo',
          tools: ['purchase_order_list', 'supplier_list'],
        },
      },
    ],
    semanticPacks: ['vendor.pur/procurement'],
    triggers: [{ type: 'assistant' }],
    channels: ['assistant-web', 'im'],
    routing: { description: '谈判策略', examples: ['帮我出一份谈判策略'], direct: true },
  },
}

describe('valueFromAsset', () => {
  test('各步从草稿里读出与 apply 相同形状的取值', () => {
    expect(valueFromAsset('naming', ASSET)).toEqual({
      name: 'price-negotiation-assistant',
      displayName: '价格谈判助手',
      description: '帮采购经理制定降价谈判策略',
    })
    expect(valueFromAsset('collaborators', ASSET)).toEqual({
      agents: ['vendor.pur/supplier-performance-analyst'],
    })
    expect(valueFromAsset('knowledge', ASSET)).toEqual({
      semanticPacks: ['vendor.pur/procurement'],
      knowledgeSources: ['vendor.pur/procurement-policies'],
    })
    expect(valueFromAsset('tools', ASSET)).toEqual({
      skills: ['vendor.pur.skills/rfq-lookup'],
      tools: [
        { source: 'vendor.erp.tools/demo-erp-bo', name: 'purchase_order_list' },
        { source: 'vendor.erp.tools/demo-erp-bo', name: 'supplier_list' },
      ],
    })
    expect(valueFromAsset('triggers', ASSET)).toEqual({
      triggers: [{ type: 'assistant' }],
      direct: true,
    })
    expect(valueFromAsset('instructions', ASSET)).toEqual({
      instructions: '你是价格谈判助手',
      constraints: ['PUR-R-007'],
    })
    expect(valueFromAsset('examples', ASSET)).toEqual({ examples: ['帮我出一份谈判策略'] })
  })

  test('草稿解析失败（null）或字段缺失时给出空值，不抛异常', () => {
    expect(valueFromAsset('tools', null)).toEqual({ skills: [], tools: [] })
    expect(valueFromAsset('model', null)).toEqual({ profile: 'reasoning', temperature: 0.2 })
    expect(identityOf(null)).toEqual({ name: '', displayName: '', ref: '/' })
    expect(identityOf(ASSET).ref).toBe('tenant.t001.agents/price-negotiation-assistant')
  })
})

describe('toggle', () => {
  test('有则去掉，没有则追加', () => {
    const same = (a: string, b: string): boolean => a === b
    expect(toggle(['a', 'b'], 'a', same)).toEqual(['b'])
    expect(toggle(['a'], 'b', same)).toEqual(['a', 'b'])
  })
})
