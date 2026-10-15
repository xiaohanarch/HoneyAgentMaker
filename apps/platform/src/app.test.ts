import { afterAll, describe, expect, it } from 'vitest'
import { buildApp } from './app'
import { createTestServices } from './test-support'

describe('platform /health', () => {
  const app = buildApp({
    services: createTestServices(),
  })
  afterAll(() => app.close())

  it('返回服务名、版本和状态', async () => {
    const response = await app.inject({ method: 'GET', url: '/health' })

    expect(response.statusCode).toBe(200)
    expect(response.json()).toMatchObject({ status: 'ok', service: 'platform', version: '0.1.0' })
  })

  it('提供从 zod schema 生成的 OpenAPI 描述', async () => {
    const response = await app.inject({ method: 'GET', url: '/openapi.json' })
    const doc = response.json() as { openapi: string; paths: Record<string, unknown> }
    expect(doc.openapi).toMatch(/^3\./)
    expect(Object.keys(doc.paths)).toContain('/registry/assets/{namespace}/{name}')
  })
})
