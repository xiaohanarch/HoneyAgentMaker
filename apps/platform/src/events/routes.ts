import { createHmac, timingSafeEqual } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { sendError, success } from '../http/envelope'
import type { PlatformServices } from '../services'

export const SIGNATURE_HEADER = 'x-eab-signature'

/** 事件推送的签名：sha256=<HMAC-SHA256(内部调用密钥, 原始请求体)> */
export function signPayload(secret: string, body: string): string {
  return `sha256=${createHmac('sha256', secret).update(body, 'utf8').digest('hex')}`
}

function verifySignature(secret: string, body: string, header: unknown): boolean {
  if (typeof header !== 'string') return false
  const expected = Buffer.from(signPayload(secret, body))
  const actual = Buffer.from(header)
  return expected.length === actual.length && timingSafeEqual(expected, actual)
}

/** DemoERP 鉴权点 4 的裁决，推送到平台审计（主提示词 7.3、7.5 节） */
const BoAccessEvent = z.object({
  type: z.literal('bo.access'),
  tenantId: z.string(),
  payload: z.object({
    at: z.string(),
    operation: z.string(),
    userId: z.string(),
    agent: z.string(),
    mode: z.string(),
    delegationId: z.string().optional(),
    outcome: z.enum(['allow', 'deny']),
    code: z.string(),
    reason: z.string(),
    dataScope: z.string(),
    target: z.string(),
    resultSummary: z.string(),
  }),
})

const BusinessEvent = z.object({
  type: z.enum(['delivery.overdue', 'inventory.below_safety_stock', 'transaction.anomaly_created', 'period.close_check_failed']),
  tenantId: z.string(),
  payload: z.record(z.string(), z.unknown()),
})

const IngestBody = z.union([BoAccessEvent, BusinessEvent])

/**
 * 接收 DemoERP 推送的事件（webhook，HMAC 签名）。
 * bo.access 写入审计；业务事件写入事件表并在进程内广播，P4 起触发 Agent。
 */
export function registerEventRoutes(app: FastifyInstance, services: PlatformServices): void {
  app.register(async (scoped) => {
    // 签名基于原始请求体，这个作用域里保留 JSON 原文
    scoped.addContentTypeParser('application/json', { parseAs: 'string' }, (_req, body, done) => {
      done(null, body)
    })

    scoped.post('/events/v1/ingest', { schema: { hide: true } }, async (request, reply) => {
      const raw = typeof request.body === 'string' ? request.body : ''
      if (!verifySignature(services.internalSecret, raw, request.headers[SIGNATURE_HEADER])) {
        return sendError(reply, 401, { code: 'BAD_SIGNATURE', message: '事件签名校验失败' })
      }
      let parsed: z.infer<typeof IngestBody>
      try {
        parsed = IngestBody.parse(JSON.parse(raw))
      } catch {
        return sendError(reply, 400, { code: 'BAD_EVENT', message: '事件格式不合法' })
      }

      if (parsed.type === 'bo.access') {
        const p = parsed.payload
        services.audit.append({
          actor: p.userId,
          action: 'bo.access',
          target: p.target,
          decision: p.outcome,
          reason: `鉴权点 4 · BO API：${p.reason}`,
          tenantId: parsed.tenantId,
          userId: p.userId,
          agent: p.agent,
          mode: p.mode,
          checkpoint: 'bo-api',
          tool: p.operation,
          dataScope: p.dataScope,
          ruleId: p.code,
          resultSummary: p.resultSummary,
          ...(p.delegationId === undefined ? {} : { detail: { delegationId: p.delegationId } }),
        })
        return reply.status(202).send(success({ accepted: true }))
      }
      const stored = services.events.append({ source: 'demo-erp', type: parsed.type, tenantId: parsed.tenantId, payload: parsed.payload })
      return reply.status(202).send(success({ accepted: true, id: stored.id }))
    })
  })
}
