import { describe, expect, test } from 'vitest'
import { declaredPoint, sectionPayload, sectionValues, segmentTone } from './extension-values'
import { duplicateIds, layoutSkill } from '../skills/skill-graph'

const DRAFT = {
  apiVersion: 'eab.dev/v1',
  kind: 'AgentExtension',
  spec: {
    target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
    instructions: { append: '单笔超过 50 万元须给出责任部门' },
    tools: { add: ['tenant.t001.tools/notify-im'] },
    hooks: {
      onResult: {
        workflow: 'tenant.t001.wf/create-followup-task',
        when: "result.severity in ['high']",
      },
    },
    approvals: { add: [{ tool: 'tenant.t001.tools/notify-im', policy: 'require-confirmation' }] },
  },
}

describe('扩展草稿的分页取值', () => {
  test('从 YAML 树读出各页取值，再转回接口要的 value', () => {
    const values = sectionValues(DRAFT)
    expect(values.instructions.append).toContain('责任部门')
    expect(values.tools).toEqual(['tenant.t001.tools/notify-im'])
    expect(values.hooks.onResult).toEqual({
      kind: 'workflow',
      ref: 'tenant.t001.wf/create-followup-task',
      when: "result.severity in ['high']",
    })
    expect(sectionPayload('hooks', values)).toEqual({
      onResult: {
        workflow: 'tenant.t001.wf/create-followup-task',
        when: "result.severity in ['high']",
      },
    })
    expect(sectionPayload('approvals', values)).toEqual({
      add: [{ tool: 'tenant.t001.tools/notify-im', policy: 'require-confirmation' }],
    })
    expect(sectionValues(null).tools).toEqual([])
  })

  test('没填工作流或技能的钩子、没填工具的确认点不提交', () => {
    const values = sectionValues(DRAFT)
    values.hooks.onFailure = { kind: 'skill', ref: '  ', when: '' }
    values.approvals.push({ tool: '', policy: 'require-approval', description: '' })
    expect(Object.keys(sectionPayload('hooks', values))).toEqual(['onResult'])
    expect((sectionPayload('approvals', values).add as unknown[]).length).toBe(1)
  })

  test('只认标品声明过的扩展点；片段按来源分为锁住、租户、其他', () => {
    const points = [{ type: 'hooks', description: '', events: ['onResult'] }]
    expect(declaredPoint(points, 'hooks')?.events).toEqual(['onResult'])
    expect(declaredPoint(points, 'tools')).toBeUndefined()
    const base = { id: 's', kind: 'k', source: 'x', field: 'f', label: null, confidential: false }
    expect(
      segmentTone({ ...base, layer: 'vendor', text: { $redacted: true, label: 'l', digest: 'd' } }),
    ).toBe('locked')
    expect(segmentTone({ ...base, layer: 'tenant', text: '追加' })).toBe('tenant')
    expect(segmentTone({ ...base, layer: 'vendor', text: '语义包指令' })).toBe('other')
  })
})

describe('技能流程图布局', () => {
  test('顺序步骤自上而下；条件的是、否分两列，汇合到结果', () => {
    const { nodes, edges } = layoutSkill([
      { id: 'query', type: 'tool', tool: 'rfq_list' },
      {
        id: 'latest',
        type: 'condition',
        if: 'input.latestOnly',
        then: [{ id: 'first', type: 'transform', expression: 'x' }],
        else: [{ id: 'all', type: 'transform', expression: 'y' }],
      },
    ])
    const at = (id: string) => nodes.find((n) => n.id === id)
    expect(at('query')?.y).toBeLessThan(at('latest')?.y ?? 0)
    expect(at('first')?.x).toBeLessThan(at('all')?.x ?? 0)
    expect(edges.map((e) => e.id)).toEqual(
      expect.arrayContaining([
        '__start->query',
        'query->latest',
        'latest->first',
        'latest->all',
        'first->__end',
        'all->__end',
      ]),
    )
    expect(edges.find((e) => e.id === 'latest->first')?.label).toBe('是')
  })

  test('没有 else 的条件直接从条件步骤连到下一步；重复的步骤 id 被找出来', () => {
    const { edges } = layoutSkill([
      { id: 'c', type: 'condition', if: 'true', then: [{ id: 't', type: 'transform' }] },
      { id: 'n', type: 'notify', message: 'm' },
    ])
    expect(edges.map((e) => e.id)).toEqual(expect.arrayContaining(['t->n', 'c->n']))
    expect(
      duplicateIds([
        { id: 'a', type: 'tool' },
        {
          id: 'b',
          type: 'parallel',
          branches: [[{ id: 'a', type: 'tool' }], [{ id: 'c', type: 'tool' }]],
        },
      ]),
    ).toEqual(['a'])
  })
})
