import { describe, expect, test } from 'vitest'
import { loginErrorCode, retryDelay } from './identity-context'

describe('retryDelay', () => {
  test('登录重试间隔从 1 秒起逐次加倍，封顶 15 秒', () => {
    expect([0, 1, 2, 3, 4, 10].map(retryDelay)).toEqual([
      1_000, 2_000, 4_000, 8_000, 15_000, 15_000,
    ])
  })
})

describe('loginErrorCode', () => {
  test('优先取响应信封里的 error.code', () => {
    expect(loginErrorCode(401, { error: { code: 'PASSCODE_REQUIRED' } })).toBe('PASSCODE_REQUIRED')
    expect(loginErrorCode(401, { error: { code: 'INVALID_PASSCODE', message: '口令不正确' } })).toBe(
      'INVALID_PASSCODE',
    )
  })

  test('没有信封时退回 HTTP 状态码，响应体为空或非对象也不抛错', () => {
    expect(loginErrorCode(503, null)).toBe('HTTP_503')
    expect(loginErrorCode(500, 'gateway timeout')).toBe('HTTP_500')
    expect(loginErrorCode(404, {})).toBe('HTTP_404')
  })
})
