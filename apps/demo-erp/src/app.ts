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
import { isBoOperation } from '@eab/demo-directory'
import type { PlatformLink } from './auth/platform-link'
import type { ErpDeps } from './bo/context'
import { registerInventoryFinanceRoutes } from './bo/inventory-finance'
import { OPERATION_SPECS } from './bo/operations'
import { registerPurchasingRoutes } from './bo/purchasing'
import type { ErpDb } from './db/client'
import { registerEventRoutes } from './events/routes'
import { registerMetaRoutes } from './meta/routes'
import { registerOpenRoutes } from './open/routes'
import { registerPageRoutes } from './pages/routes'

export const SERVICE_NAME = 'demo-erp'
export const SERVICE_VERSION = '0.2.0'

export interface HealthReport {
  status: 'ok'
  service: string
  version: string
  uptimeSeconds: number
}

export interface AppOptions {
  db: ErpDb
  link: PlatformLink
  internalSecret: string
  now?: () => Date
  fastify?: FastifyServerOptions
}

type OpenApiDoc = {
  paths?: Record<string, Record<string, { operationId?: string } & Record<string, unknown>>>
} & Record<string, unknown>

/** 给 BO API 的每个操作标上写操作级别（主提示词 6.3 节：用 OpenAPI 扩展字段声明） */
function annotateWriteLevels(doc: OpenApiDoc, onlyBo: boolean): OpenApiDoc {
  const paths = Object.fromEntries(
    Object.entries(doc.paths ?? {})
      .filter(([path]) => !onlyBo || path.startsWith('/bo/v1/'))
      .map(([path, methods]) => [
        path,
        Object.fromEntries(
          Object.entries(methods).map(([method, operation]) => {
            const id = operation.operationId
            return [
              method,
              id !== undefined && isBoOperation(id)
                ? { ...operation, 'x-write-level': OPERATION_SPECS[id].writeLevel }
                : operation,
            ]
          }),
        ),
      ]),
  )
  return { ...doc, paths }
}

export function buildApp(options: AppOptions): FastifyInstance {
  const app = Fastify(options.fastify ?? {})
  const startedAt = Date.now()
  const deps: ErpDeps = {
    db: options.db,
    link: options.link,
    now: options.now ?? (() => new Date()),
  }

  app.setValidatorCompiler(validatorCompiler)
  app.setSerializerCompiler(serializerCompiler)
  app.register(swagger, {
    openapi: {
      info: {
        title: 'DemoERP',
        version: SERVICE_VERSION,
        description:
          'BO API 只接受委托令牌（用户 + Agent），按业务权限和数据权限裁决；Open API 只接受集成账号，只做租户隔离。',
      },
    },
    transform: jsonSchemaTransform,
  })

  app.setErrorHandler((error: FastifyError, request, reply) => {
    if (error.validation !== undefined) {
      return reply
        .status(400)
        .send({
          error: {
            code: 'BAD_REQUEST',
            reason: '请求参数不合法',
            suggestion: '检查参数格式',
            details: error.validation,
          },
        })
    }
    const status = error.statusCode ?? 500
    if (status < 500)
      return reply
        .status(status)
        .send({ error: { code: error.code, reason: error.message, suggestion: '' } })
    request.log.error(error, '未处理的异常')
    return reply
      .status(500)
      .send({ error: { code: 'INTERNAL_ERROR', reason: '服务内部错误', suggestion: '稍后重试' } })
  })

  app.get('/health', { schema: { hide: true } }, async (): Promise<HealthReport> => ({
    status: 'ok',
    service: SERVICE_NAME,
    version: SERVICE_VERSION,
    uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
  }))
  app.get('/openapi.json', { schema: { hide: true } }, async () =>
    annotateWriteLevels(app.swagger() as OpenApiDoc, false),
  )
  app.get('/bo/v1/openapi.json', { schema: { hide: true } }, async () =>
    annotateWriteLevels(app.swagger() as OpenApiDoc, true),
  )

  app.register(async (instance) => {
    registerPurchasingRoutes(instance, deps)
    registerInventoryFinanceRoutes(instance, deps)
    registerOpenRoutes(instance, deps)
    registerMetaRoutes(instance)
    registerEventRoutes(instance, deps, options.internalSecret)
    registerPageRoutes(instance, deps)
  })

  app.addHook('onClose', async () => {
    options.db.$client.close()
  })
  return app
}
