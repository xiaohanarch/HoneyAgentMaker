import type { AddressInfo } from 'node:net'
import type { Server } from 'node:http'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair } from 'jose'
import { createApp } from './app.js'
import { platformVerifier } from './auth.js'
import { quote } from './rates.js'

let server: Server
let base: string
let token: string
let privateKey: Awaited<ReturnType<typeof generateKeyPair>>['privateKey']

async function sign(claims: Record<string, unknown>, audience = 'eab-a2a'): Promise<string> {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: 'EdDSA', kid: 'k1' })
    .setIssuer('eab-identity')
    .setAudience(audience)
    .setIssuedAt()
    .setExpirationTime('5m')
    .sign(privateKey)
}

beforeAll(async () => {
  const pair = await generateKeyPair('EdDSA', { extractable: true })
  privateKey = pair.privateKey
  const jwk = { ...(await exportJWK(pair.publicKey)), kid: 'k1', alg: 'EdDSA' }
  const verifier = platformVerifier(createLocalJWKSet({ keys: [jwk] }))
  server = createApp('http://127.0.0.1', verifier).listen(0, '127.0.0.1')
  await new Promise((resolve) => server.once('listening', resolve))
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  token = await sign({ kind: 'service', sub: 'zhangmin', tid: 't001', act: { sub: 'agent:x' } })
})

afterAll(() => {
  server.close()
})

function send(text: string, authorization?: string): Promise<Response> {
  const headers: Record<string, string> = { 'content-type': 'application/json', 'a2a-version': '1.0' }
  if (authorization !== undefined) headers.authorization = authorization
  return fetch(`${base}/`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: '1',
      method: 'SendMessage',
      params: { message: { messageId: 'm-1', role: 'ROLE_USER', parts: [{ text }] } },
    }),
  })
}

describe('汇率查询 Agent', () => {
  it('解析金额和币种对', () => {
    expect(quote('1000 美元折合多少人民币')).toMatchObject({ from: 'USD', to: 'CNY', converted: 7120 })
    expect(quote('欧元兑人民币汇率')).toMatchObject({ from: 'EUR', to: 'CNY', amount: 1 })
    expect(quote('今天天气怎么样')).toBeUndefined()
  })

  it('Agent Card 在标准路径和旧路径都公开', async () => {
    for (const path of ['/.well-known/agent-card.json', '/.well-known/agent.json']) {
      const card = (await (await fetch(`${base}${path}`)).json()) as { name: string; skills: { id: string }[] }
      expect(card.name).toBe('汇率查询 Agent')
      expect(card.skills[0]?.id).toBe('fx-rate')
    }
  })

  it('没有令牌或令牌受众不对时返回 401', async () => {
    expect((await send('美元兑人民币')).status).toBe(401)
    const wrong = await sign({ kind: 'service', sub: 'zhangmin', tid: 't001' }, 'eab-platform')
    expect((await send('美元兑人民币', `Bearer ${wrong}`)).status).toBe(401)
    const user = await sign({ kind: 'user', sub: 'zhangmin', tid: 't001' })
    expect((await send('美元兑人民币', `Bearer ${user}`)).status).toBe(401)
  })

  it('带服务令牌时回复参考汇率', async () => {
    const response = await send('1000 美元折合多少人民币', `Bearer ${token}`)
    expect(response.status).toBe(200)
    const body = JSON.stringify(await response.json())
    expect(body).toContain('7120')
    expect(body).toContain('参考汇率')
  })
})
