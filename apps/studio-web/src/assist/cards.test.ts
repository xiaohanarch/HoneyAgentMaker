import { describe, expect, test } from 'vitest'
import { attachedSummary } from './cards'

describe('attachedSummary', () => {
  test('业务对象与规则都有时合成一行', () => {
    expect(attachedSummary(['vendor.pur/supplier', 'vendor.pur/po'], ['PUR-R-008'])).toBe(
      '已附带给开发者：2 个业务对象、1 条规则',
    )
  })

  test('只有一类时只报一类，都为空时不显示这一行', () => {
    expect(attachedSummary([], ['PUR-R-008', 'PUR-R-009'])).toBe('已附带给开发者：2 条规则')
    expect(attachedSummary(['vendor.pur/supplier'], [])).toBe('已附带给开发者：1 个业务对象')
    expect(attachedSummary([], [])).toBeNull()
  })
})
