import { describe, expect, test } from 'vitest'
import { exampleInput, newStep, parseWith, stepsOf } from './skill-steps'

describe('技能步骤编辑', () => {
  test('切换类型时保留 id、名称和用得到的字段，补上必填字段', () => {
    const tool = {
      id: 'q',
      type: 'tool',
      name: '查询',
      tool: 'rfq_list',
      with: { a: 1 },
      output: 'rfqs',
    }
    expect(newStep('skill', tool)).toEqual({
      id: 'q',
      type: 'skill',
      name: '查询',
      skill: '',
      with: { a: 1 },
      output: 'rfqs',
    })
    const condition = newStep('condition', tool)
    expect(condition.if).toBe('true')
    expect(condition.then?.[0]?.id).toBe('q-then')
    expect(newStep('notify', { id: 'n', type: 'transform', expression: 'x' })).toEqual({
      id: 'n',
      type: 'notify',
      message: '',
    })
  })

  test('入参 JSON 只接受对象；空文本表示不传', () => {
    expect(parseWith('')).toEqual({})
    expect(parseWith('{"supplierName":"input.supplierName"}').value).toEqual({
      supplierName: 'input.supplierName',
    })
    expect(parseWith('[1]').error).toBeDefined()
    expect(parseWith('{').error).toBeDefined()
  })

  test('按 inputSchema 给出测试入参样例', () => {
    const asset = {
      spec: {
        inputSchema: {
          properties: {
            supplierName: { type: 'string' },
            latestOnly: { type: 'boolean', default: true },
            limit: { type: 'integer' },
          },
        },
        steps: [{ id: 'a', type: 'transform', expression: 'input' }],
      },
    }
    expect(exampleInput(asset)).toEqual({ supplierName: '', latestOnly: true, limit: 0 })
    expect(stepsOf(asset)).toHaveLength(1)
    expect(stepsOf(null)).toEqual([])
    expect(exampleInput(undefined)).toEqual({})
  })
})
