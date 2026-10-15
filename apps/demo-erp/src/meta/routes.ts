import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { META_OBJECTS, META_RULES, findMetaObject } from './catalog'

/** 元数据服务（/meta/v1）：对象、字段、枚举、规则、权限维度，带版本 */
export function registerMetaRoutes(app: FastifyInstance): void {
  app.get(
    '/meta/v1/objects',
    { schema: { tags: ['meta'], summary: '业务对象清单' } },
    async () => ({
      items: META_OBJECTS.map(({ ref, label, domain, version, permissionDimension }) => ({
        ref: `${ref}@v${version}`,
        label,
        domain,
        version,
        permissionDimension,
      })),
    }),
  )

  app.get(
    '/meta/v1/objects/:ref',
    {
      schema: {
        tags: ['meta'],
        summary: '单个对象的定义，例如 erp.pur.Supplier@v1',
        params: z.object({ ref: z.string() }),
      },
    },
    async (request, reply) => {
      const { ref } = request.params as { ref: string }
      const object = findMetaObject(ref)
      if (object === undefined) {
        return reply
          .status(404)
          .send({
            error: {
              code: 'NOT_FOUND',
              reason: `元数据对象 ${ref} 不存在或版本不符`,
              suggestion: '先查询 /meta/v1/objects',
            },
          })
      }
      return { ...object, ref: `${object.ref}@v${object.version}` }
    },
  )

  app.get('/meta/v1/rules', { schema: { tags: ['meta'], summary: '业务规则清单' } }, async () => ({
    items: META_RULES,
  }))

  app.get(
    '/meta/v1/permission-dimensions',
    { schema: { tags: ['meta'], summary: '数据权限维度' } },
    async () => ({
      items: [
        {
          name: '采购组织',
          appliesTo: META_OBJECTS.filter((o) => o.permissionDimension === '采购组织').map(
            (o) => o.ref,
          ),
        },
        {
          name: '公司主体',
          appliesTo: META_OBJECTS.filter((o) => o.permissionDimension === '公司主体').map(
            (o) => o.ref,
          ),
        },
      ],
    }),
  )
}
