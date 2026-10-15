import { afterAll, describe, expect, it } from 'vitest'
import { buildApp } from './app'

describe('platform /health', () => {
  const app = buildApp()
  afterAll(() => app.close())

  it('返回服务名、版本和状态', async () => {
    const response = await app.inject({ method: 'GET', url: '/health' })

    expect(response.statusCode).toBe(200)
    expect(response.json()).toMatchObject({ status: 'ok', service: 'platform', version: '0.1.0' })
  })
})
