import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify'
import type { ZodTypeProvider } from 'fastify-type-provider-zod'
import { z } from 'zod'
import {
  ASSET_STATUSES,
  NameSchema,
  NamespaceSchema,
  SemverSchema,
  formatViewer,
  lintAsset,
  parseAssetYaml,
  type Viewer,
} from '@eab/spec'
import type { AuditStore } from '../audit/audit-store'
import { VIEWER_HEADER, isApiError, sendError, success, viewerOf } from '../http/envelope'
import { assembleFromRegistry } from './effective'
import type { RegistryErrorCode, RegistryService } from './service'

const STATUS_OF: Readonly<Record<RegistryErrorCode, number>> = {
  ASSET_INVALID: 422,
  REGISTRY_WRITE_FORBIDDEN: 403,
  VERSION_EXISTS: 409,
  NOT_FOUND: 404,
  INVALID_TRANSITION: 409,
  INTEGRITY_VIOLATION: 409,
  PUBLISH_BLOCKED: 422,
}

// 身份头在 handler 里校验，缺失时返回 401；这里声明为可选只为生成 OpenAPI 描述
const ViewerHeader = z.object({
  [VIEWER_HEADER]: z
    .string()
    .optional()
    .describe('platform、vendor 或 tenant:<租户编号>（P1 临时方案）'),
})
// 路径参数先按资产命名规则校验，不合法直接 400，后面的代码不会拿到非法命名空间
const AssetPath = z.object({ namespace: NamespaceSchema, name: NameSchema })
/** 只接受 true/false 字符串；z.coerce.boolean 会把 "false" 当成 true */
const BooleanQuery = z.enum(['true', 'false']).transform((v) => v === 'true')
const YAML_CONTENT_TYPES = ['application/yaml', 'application/x-yaml', 'text/yaml', 'text/plain']

/** 取查看者身份，失败时直接回复 401 并返回 undefined */
function requireViewer(request: FastifyRequest, reply: FastifyReply): Viewer | undefined {
  const viewer = viewerOf(request)
  if (isApiError(viewer)) {
    void sendError(reply, 401, viewer)
    return undefined
  }
  return viewer
}

export interface RegistryRouteDeps {
  registry: RegistryService
  audit: AuditStore
}

export function registerRegistryRoutes(app: FastifyInstance, deps: RegistryRouteDeps): void {
  const { registry, audit } = deps
  const r = app.withTypeProvider<ZodTypeProvider>()

  app.addContentTypeParser(YAML_CONTENT_TYPES, { parseAs: 'string' }, (_req, body, done) => {
    done(null, body)
  })

  r.get(
    '/registry/assets',
    {
      schema: {
        tags: ['registry'],
        summary: '列出当前身份可见的资产版本',
        headers: ViewerHeader,
        querystring: z.object({
          kind: z.string().optional(),
          namespace: z.string().optional(),
          layer: z.enum(['platform', 'vendor', 'tenant']).optional(),
          status: z.enum(ASSET_STATUSES).optional(),
        }),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      return success(registry.list(viewer, request.query))
    },
  )

  r.get(
    '/registry/assets/:namespace/:name',
    {
      schema: {
        tags: ['registry'],
        summary: '读取资产（按身份裁剪，租户看不到标品指令原文）',
        headers: ViewerHeader,
        params: AssetPath,
        querystring: z.object({ version: z.string().optional() }),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      const { namespace, name } = request.params
      const result = registry.view(viewer, namespace, name, request.query.version)
      return result.ok
        ? success(result.value)
        : sendError(reply, STATUS_OF[result.error.code], result.error)
    },
  )

  r.put(
    '/registry/assets',
    {
      schema: {
        tags: ['registry'],
        summary: '提交 YAML，新建资产的草稿版本（只能写本层命名空间）',
        headers: ViewerHeader,
        querystring: z.object({ changeNote: z.string().optional() }),
        body: z.string().min(1).describe('资产 YAML，Content-Type 用 application/yaml'),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      const result = registry.put(viewer, request.body, request.query.changeNote)
      return result.ok
        ? reply.status(201).send(success(result.value))
        : sendError(reply, STATUS_OF[result.error.code], result.error)
    },
  )

  r.patch(
    '/registry/assets/:namespace/:name/:version/status',
    {
      schema: {
        tags: ['registry'],
        summary: '变更资产状态，标品层发布时签名',
        headers: ViewerHeader,
        params: AssetPath.extend({ version: SemverSchema }),
        body: z.object({ status: z.enum(ASSET_STATUSES) }),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      const { namespace, name, version } = request.params
      const result = registry.setStatus(viewer, namespace, name, version, request.body.status)
      return result.ok
        ? success(result.value)
        : sendError(reply, STATUS_OF[result.error.code], result.error)
    },
  )

  r.post(
    '/registry/validate',
    {
      schema: {
        tags: ['registry'],
        summary: '校验资产 YAML 并运行静态检查，不写入（引用只在当前身份可见的范围内解析）',
        headers: ViewerHeader,
        body: z.string().min(1),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      const parsed = parseAssetYaml(request.body)
      if (!parsed.ok) return success({ valid: false, issues: parsed.issues, lint: [] })
      const lint = lintAsset(parsed.asset, { resolver: registry.resolverFor(viewer) })
      return success({ valid: lint.every((i) => i.severity !== 'error'), issues: [], lint })
    },
  )

  r.get(
    '/registry/agents/:namespace/:name/effective',
    {
      schema: {
        tags: ['registry'],
        summary: '有效定义预览：平台规则 + 标品 Agent + 租户扩展的组装结果（按身份裁剪）',
        headers: ViewerHeader,
        params: AssetPath,
        querystring: z.object({
          tenant: z.string().optional(),
          includeDrafts: BooleanQuery.default(false),
        }),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      // 租户只能以自己的身份组装；厂商看不到任何租户的内容，不能带 tenant；平台身份可以指定租户
      const requested = request.query.tenant
      if (viewer.type === 'tenant' && requested !== undefined && requested !== viewer.tenantId) {
        return sendError(reply, 403, {
          code: 'TENANT_MISMATCH',
          message: `身份 ${formatViewer(viewer)} 不能查看其他租户的组装结果`,
        })
      }
      if (viewer.type === 'vendor' && requested !== undefined) {
        return sendError(reply, 403, {
          code: 'TENANT_FORBIDDEN',
          message: '厂商身份不能查看租户的组装结果',
        })
      }
      const tenantId = viewer.type === 'tenant' ? viewer.tenantId : requested
      const outcome = assembleFromRegistry(registry, viewer, {
        agent: `${request.params.namespace}/${request.params.name}`,
        ...(tenantId === undefined ? {} : { tenantId }),
        ...(request.query.includeDrafts
          ? { statuses: ['published', 'testing', 'draft'] as const }
          : {}),
      })
      return outcome.ok
        ? success({ definition: outcome.view, issues: outcome.issues })
        : sendError(reply, 422, {
            code: 'ASSEMBLY_FAILED',
            message: '组装失败',
            details: outcome.issues,
          })
    },
  )

  r.get(
    '/audit/events',
    {
      schema: {
        tags: ['audit'],
        summary: '查询审计事件（P1 仅平台身份可用，P2 扩充）',
        headers: ViewerHeader,
        querystring: z.object({
          action: z.string().optional(),
          decision: z.enum(['allow', 'deny']).optional(),
          limit: z.coerce.number().int().min(1).max(500).optional(),
        }),
      },
    },
    async (request, reply) => {
      const viewer = requireViewer(request, reply)
      if (viewer === undefined) return reply
      if (viewer.type !== 'platform') {
        return sendError(reply, 403, {
          code: 'FORBIDDEN',
          message: 'P1 阶段只有平台身份可以查询审计',
        })
      }
      return success(audit.list(request.query))
    },
  )
}
