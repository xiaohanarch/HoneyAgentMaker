import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from 'jose'

/** 调用方：平台签发的服务令牌里的最终用户、租户和发起调用的平台 Agent */
export interface Caller {
  user: string
  tenant: string
  agent: string
}

export class AuthError extends Error {}

export type Verifier = (authorization: string | undefined) => Promise<Caller>

/**
 * 校验平台签发的服务令牌：EdDSA 签名（公钥取平台的 JWKS）、签发者 eab-identity、受众 eab-a2a、令牌种类 service。
 * 任何一项不符都拒绝，没有令牌也拒绝。
 */
export function platformVerifier(keys: JWTVerifyGetKey): Verifier {
  return async (authorization) => {
    if (authorization === undefined || !authorization.startsWith('Bearer ')) {
      throw new AuthError('缺少平台签发的服务令牌')
    }
    try {
      const { payload } = await jwtVerify(authorization.slice('Bearer '.length), keys, {
        issuer: 'eab-identity',
        audience: 'eab-a2a',
        algorithms: ['EdDSA'],
      })
      if (payload.kind !== 'service') throw new AuthError('令牌种类不是 service')
      const act = payload.act as { sub?: unknown } | undefined
      return {
        user: String(payload.sub),
        tenant: String(payload.tid),
        agent: typeof act?.sub === 'string' ? act.sub : '',
      }
    } catch (error) {
      if (error instanceof AuthError) throw error
      throw new AuthError(`服务令牌校验失败：${(error as Error).message}`)
    }
  }
}

export function remoteJwks(platformUrl: string): JWTVerifyGetKey {
  return createRemoteJWKSet(new URL('/identity/v1/jwks.json', platformUrl))
}
