import { describe, expect, test } from 'vitest'
import { retryDelay } from './identity-context'

describe('retryDelay', () => {
  test('登录重试间隔从 1 秒起逐次加倍，封顶 15 秒', () => {
    expect([0, 1, 2, 3, 4, 10].map(retryDelay)).toEqual([
      1_000, 2_000, 4_000, 8_000, 15_000, 15_000,
    ])
  })
})
