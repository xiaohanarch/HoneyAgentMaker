import rateLimit from '@fastify/rate-limit'
import swagger from '@fastify/swagger'
import Fastify, {
  type FastifyError,
  type FastifyInstance,
  type FastifyServerOptions,
} from 'fastify'
import {
  jsonSchemaTransform,
  serializerCompiler,
  validatorCompiler,
} from 'fastify-type-provider-zod'
import { registerAuditRoutes } from './audit/routes'
import { registerEventRoutes } from './events/routes'
import { createRequirePrincipal } from './http/auth'
import { registerIdentityRoutes } from './identity/routes'
import { registerPolicyRoutes } from './policy/routes'
import { registerRegistryRoutes } from './registry/routes'
import type { PlatformServices } from './services'

export const SERVICE_NAME = 'platform'
export const SERVICE_VERSION = '0.1.0'

export interface HealthReport {
  status: 'ok'
  service: string
  version: string
  uptimeSeconds: number
}

export interface AppOptions {
  services: PlatformServices
  fastify?: FastifyServerOptions
  /** 演示模式开放 /identity/v1/demo/* 接口，仅供手工验证；pnpm demo 默认开启 */
  demoMode?: boolean
}

export function buildApp(options: AppOptions): FastifyInstance {
  const app = Fastify(options.fastify ?? {})
  const startedAt = Date.now()
  const { services } = options

  app.setValidatorCompiler(validatorCompiler)
  app.setSerializerCompiler(serializerCompiler)

  // 所有对外接口的 OpenAPI 描述从 zod schema 生成（主提示词第 14 节）
  app.register(swagger, {
    openapi: {
      info: { title: 'ERP Agent Builder 平台 API', version: SERVICE_VERSION },
    },
    transform: jsonSchemaTransform,
  })

  // 限流只对声明了 config.rateLimit 的路由生效（登录、令牌端点）
  app.register(rateLimit, { global: false })

  // 未预期的异常统一返回 500，不把内部错误信息回显给调用方；参数校验错误保留 400
  app.setErrorHandler((error: FastifyError, request, reply) => {
    if (error.validation !== undefined) {
      return reply.status(400).send({
        success: false,
        error: { code: 'BAD_REQUEST', message: '请求参数不合法', details: error.validation },
      })
    }
    const status = error.statusCode ?? 500
    if (status < 500) {
      // 框架层的客户端错误（如不支持的 Content-Type）消息是安全的，原样返回
      return reply
        .status(status)
        .send({ success: false, error: { code: error.code, message: error.message } })
    }
    request.log.error(error, '未处理的异常')
    return reply.status(status).send({
      success: false,
      error: { code: 'INTERNAL_ERROR', message: '服务内部错误' },
    })
  })

  app.get('/health', { schema: { hide: true } }, async (): Promise<HealthReport> => ({
    status: 'ok',
    service: SERVICE_NAME,
    version: SERVICE_VERSION,
    uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
  }))

  app.get('/openapi.json', { schema: { hide: true } }, async () => app.swagger())

  const requirePrincipal = createRequirePrincipal(services.tokens)
  app.register(async (instance) => {
    registerRegistryRoutes(instance, { registry: services.registry, requirePrincipal })
  })
  app.register(async (instance) => {
    registerIdentityRoutes(instance, services, requirePrincipal, {
      demoMode: options.demoMode ?? false,
    })
  })
  app.register(async (instance) => {
    registerPolicyRoutes(instance, services, requirePrincipal)
    registerAuditRoutes(instance, services.audit, requirePrincipal)
  })
  registerEventRoutes(app, services)

  app.addHook('onClose', async () => {
    services.db.$client.close()
  })

  return app
}
