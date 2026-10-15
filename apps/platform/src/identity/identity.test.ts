import { decodeJwt } from 'jose'
import { beforeEach, describe, expect, it } from 'vitest'
import { ephemeralIdentityKeys } from './keys'
import { TOKEN_TYPE } from './service'
import { AUDIENCE, TokenError, TokenService } from './tokens'
import type { PlatformServices } from '../services'
import { DEMO_DELEGATION_ID, ensureDemoDefaults } from '../services'
import { createTestServices } from '../test-support'

const PURCHASING_AGENT = 'vendor.pur/supplier-performance-analyst'
const INVENTORY_AGENT = 'vendor.scm/inventory-risk-analyst'

let services: PlatformServices

beforeEach(() => {
  services = createTestServices()
  ensureDemoDefaults(services)
})

async function agentToken(agent: string, operations: string[], tenantId = 't001'): Promise<string> {
  return services.identity.issueAgentToken({ agent, version: '1.0.0', tenantId, operations })
}

async function exchangeAs(userId: string, agent: string, operations: string[]) {
  const { token } = await services.identity.login(userId)
  return services.identity.exchange({
    subjectToken: token,
    subjectTokenType: TOKEN_TYPE.jwt,
    actorToken: await agentToken(agent, operations),
    audience: AUDIENCE.bo,
  })
}

describe('登录与令牌字段', () => {
  it('用户令牌带用户、租户、角色，受众是平台', async () => {
    const { token } = await services.identity.login('zhangmin')
    expect(decodeJwt(token)).toMatchObject({
      iss: 'eab-identity',
      aud: AUDIENCE.platform,
      sub: 'zhangmin',
      tid: 't001',
      roles: ['purchasing-manager'],
      kind: 'user',
    })
  })

  it('未知用户不能登录', async () => {
    await expect(services.identity.login('nobody')).rejects.toThrow(TokenError)
  })

  it('集成账号换到的令牌受众是 Open API，没有人的身份', async () => {
    const secret = services.clients.secretOf('t001-integration') ?? ''
    const token = await services.identity.clientCredentials('t001-integration', secret)
    expect(decodeJwt(token)).toMatchObject({
      aud: AUDIENCE.open,
      sub: 'client:t001-integration',
      kind: 'client',
    })
    await expect(services.identity.clientCredentials('t001-integration', 'wrong')).rejects.toThrow(
      /密钥错误/,
    )
  })
})

describe('令牌交换（RFC 8693）', () => {
  it('代用户操作：sub 是用户，act.sub 是 Agent，scope 取用户权限与 Agent 声明的交集', async () => {
    const result = await exchangeAs('liqiang', PURCHASING_AGENT, [
      'purchase_order_list',
      'purchase_order_change_approve',
      'transaction_anomaly_list',
    ])
    // 李强是采购员：有订单查询权限，没有审批和财务权限
    expect(result.scope).toEqual(['purchase_order_list'])
    expect(decodeJwt(result.accessToken)).toMatchObject({
      aud: AUDIENCE.bo,
      sub: 'liqiang',
      act: { sub: `agent:${PURCHASING_AGENT}` },
      tid: 't001',
      scope: 'purchase_order_list',
      mode: 'on-behalf',
      kind: 'delegated',
    })
    expect(result.expiresIn).toBe(300)
  })

  it('受托运行：用委托编号换令牌，sub 是委托人，带 delegation_id，范围再与委托取交集', async () => {
    const result = await services.identity.exchange({
      subjectToken: `delegation:${DEMO_DELEGATION_ID}`,
      subjectTokenType: TOKEN_TYPE.delegation,
      actorToken: await agentToken(INVENTORY_AGENT, [
        'inventory_balance_list',
        'followup_task_create',
      ]),
      audience: AUDIENCE.bo,
    })
    expect(result.scope).toEqual(['inventory_balance_list'])
    expect(result.claims).toMatchObject({
      sub: 'sunkg',
      mode: 'delegated',
      delegation_id: DEMO_DELEGATION_ID,
    })
  })

  it('撤销委托后交换失败，并写入拒绝审计', async () => {
    services.delegations.revoke(DEMO_DELEGATION_ID, 'zhaoadmin')
    await expect(
      services.identity.exchange({
        subjectToken: `delegation:${DEMO_DELEGATION_ID}`,
        subjectTokenType: TOKEN_TYPE.delegation,
        actorToken: await agentToken(INVENTORY_AGENT, ['inventory_balance_list']),
        audience: AUDIENCE.bo,
      }),
    ).rejects.toMatchObject({ code: 'DELEGATION_INACTIVE' })
    const [event] = services.audit.list({ action: 'identity.token-exchange', decision: 'deny' })
    expect(event?.reason).toContain('revoked')
    // 拒绝记录带上 Agent 令牌里已验证的租户和 Agent，以及委托人，租户管理员能查到
    expect(event).toMatchObject({
      tenantId: 't001',
      userId: 'sunkg',
      agent: `${INVENTORY_AGENT}@1.0.0`,
      mode: 'delegated',
    })
    expect(event?.resultSummary).toContain('未退回任何技术账号')
  })

  it.each([
    [
      '委托授权的是另一个 Agent',
      async () =>
        services.identity.exchange({
          subjectToken: `delegation:${DEMO_DELEGATION_ID}`,
          subjectTokenType: TOKEN_TYPE.delegation,
          actorToken: await agentToken(PURCHASING_AGENT, ['purchase_order_list']),
          audience: AUDIENCE.bo,
        }),
      'DELEGATION_AGENT_MISMATCH',
    ],
    [
      '用户与 Agent 不属于同一租户',
      async () =>
        services.identity.exchange({
          subjectToken: (await services.identity.login('zhouting')).token,
          subjectTokenType: TOKEN_TYPE.jwt,
          actorToken: await agentToken(PURCHASING_AGENT, ['purchase_order_list']),
          audience: AUDIENCE.bo,
        }),
      'TENANT_MISMATCH',
    ],
    [
      '权限交集为空',
      async () => exchangeAs('chengong', PURCHASING_AGENT, ['purchase_order_list']),
      'EMPTY_SCOPE',
    ],
    [
      '申请的受众不是 BO API',
      async () =>
        services.identity.exchange({
          subjectToken: (await services.identity.login('zhangmin')).token,
          subjectTokenType: TOKEN_TYPE.jwt,
          actorToken: await agentToken(PURCHASING_AGENT, ['purchase_order_list']),
          audience: AUDIENCE.open,
        }),
      'INVALID_TARGET',
    ],
    [
      '用用户令牌冒充 Agent 令牌',
      async () =>
        services.identity.exchange({
          subjectToken: (await services.identity.login('zhangmin')).token,
          subjectTokenType: TOKEN_TYPE.jwt,
          actorToken: (await services.identity.login('zhangmin')).token,
          audience: AUDIENCE.bo,
        }),
      'INVALID_TOKEN',
    ],
    [
      '不支持的主体令牌类型',
      async () =>
        services.identity.exchange({
          subjectToken: 'x',
          subjectTokenType: 'urn:example:unknown',
          actorToken: await agentToken(PURCHASING_AGENT, ['purchase_order_list']),
          audience: AUDIENCE.bo,
        }),
      'UNSUPPORTED_SUBJECT',
    ],
  ] as const)('%s时拒绝签发', async (_label, run, code) => {
    await expect(run()).rejects.toMatchObject({ code })
  })

  it('其他密钥签的令牌、过期令牌都校验失败', async () => {
    const otherKeys = new TokenService(await ephemeralIdentityKeys('other'))
    const forged = await otherKeys.issueUser({
      sub: 'zhangmin',
      tid: 't001',
      roles: ['purchasing-manager'],
    })
    await expect(services.tokens.verifyUser(forged)).rejects.toMatchObject({
      code: 'INVALID_TOKEN',
    })

    // 同一把密钥：2020 年签发，按现在的时间校验
    const keys = await ephemeralIdentityKeys('x')
    const past = new TokenService(keys, () => new Date('2020-01-01T00:00:00Z'))
    const old = await past.issueUser({ sub: 'zhangmin', tid: 't001', roles: [] })
    await expect(new TokenService(keys).verifyUser(old)).rejects.toMatchObject({
      code: 'INVALID_TOKEN',
    })
  })
})

describe('委托存储', () => {
  it('未生效、已过期的委托不可用', () => {
    const future = services.delegations.create({
      tenantId: 't001',
      delegatorUserId: 'sunkg',
      agent: INVENTORY_AGENT,
      scope: ['inventory_balance_list'],
      validFrom: '2999-01-01T00:00:00.000Z',
      validUntil: '2999-12-31T00:00:00.000Z',
      createdBy: 'zhaoadmin',
    })
    expect(services.delegations.status(future.id)).toMatchObject({
      active: false,
      reason: 'not-yet-valid',
    })
    const expired = services.delegations.create({
      tenantId: 't001',
      delegatorUserId: 'sunkg',
      agent: INVENTORY_AGENT,
      scope: ['inventory_balance_list'],
      validFrom: '2000-01-01T00:00:00.000Z',
      validUntil: '2000-12-31T00:00:00.000Z',
      createdBy: 'zhaoadmin',
    })
    expect(services.delegations.status(expired.id)).toMatchObject({
      active: false,
      reason: 'expired',
    })
    expect(services.delegations.status('DLG-NOPE')).toMatchObject({
      active: false,
      reason: 'not-found',
    })
  })
})
