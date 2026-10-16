import { describe, expect, it } from 'vitest'
import { parseExamples, prettyBody } from './api'

describe('接入外部 Agent 的辅助函数', () => {
  it('示例话术按行拆分并去掉空行', () => {
    expect(parseExamples('百润精密最近有没有什么风险\n\n  查一下华远电子的舆情  \n')).toEqual([
      '百润精密最近有没有什么风险',
      '查一下华远电子的舆情',
    ])
  })

  it('JSON 请求体缩进显示，非 JSON 原样返回', () => {
    expect(prettyBody('{"a":1}')).toBe('{\n  "a": 1\n}')
    expect(prettyBody('data: x')).toBe('data: x')
    expect(prettyBody(undefined)).toBe('')
  })
})
