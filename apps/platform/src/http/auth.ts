import type { FastifyReply, FastifyRequest } from 'fastify'
import { z } from 'zod'
import { findUser, permittedOperations, type DemoUser } from '@eab/demo-directory'
import type { Viewer } from '@eab/spec'
import type { SubjectFacts } from '@eab/policy'
import type { TokenService } from '../identity/tokens'
import { sendError } from './envelope'

/** 已认证的调用者：演示用户及其对应的资产查看者身份 */
export interface Principal {
  user: DemoUser
  viewer: Viewer
  /** 原始用户令牌，令牌交换时作为 subject_token */
  token: string
}

/** 厂商研发对应厂商身份，平台运维对应平台身份，其余按租户 */
export function viewerOfUser(user: DemoUser): Viewer {
  if (user.tenantId === 'vendor') return { type: 'vendor' }
  if (user.tenantId === 'platform') return { type: 'platform' }
  return { type: 'tenant', tenantId: user.tenantId }
}

export function subjectFacts(user: DemoUser): SubjectFacts {
  return {
    userId: user.id,
    tenantId: user.tenantId,
    roles: [...user.roles],
    operations: permittedOperations(user),
  }
}

/** OpenAPI 描述用：Authorization 头在 handler 里校验，缺失时返回 401 */
export const AuthHeader = z.object({
  authorization: z
    .string()
    .optional()
    .describe('Bearer <用户令牌>，由 POST /identity/v1/login 获得'),
})

export type RequirePrincipal = (
  request: FastifyRequest,
  reply: FastifyReply,
) => Promise<Principal | undefined>

/**
 * 从 Bearer 用户令牌得到调用者。失败时回复 401 并返回 undefined。
 * P1 的 x-eab-viewer 请求头已删除，身份只来自平台签发的令牌。
 */
export function createRequirePrincipal(tokens: TokenService): RequirePrincipal {
  return async (request, reply) => {
    const header = request.headers.authorization
    const token =
      typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : undefined
    if (token === undefined) {
      void sendError(reply, 401, {
        code: 'UNAUTHENTICATED',
        message: '缺少 Authorization: Bearer <用户令牌>',
      })
      return undefined
    }
    try {
      const claims = await tokens.verifyUser(token)
      const user = findUser(claims.sub)
      if (user === undefined) throw new Error('令牌中的用户不存在')
      return { user, viewer: viewerOfUser(user), token }
    } catch (error: unknown) {
      void sendError(reply, 401, {
        code: 'INVALID_TOKEN',
        message: error instanceof Error ? error.message : String(error),
      })
      return undefined
    }
  }
}
