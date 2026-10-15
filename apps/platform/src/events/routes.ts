import { createHmac, timingSafeEqual } from 'node:crypto'
import type { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { findTenant } from '@eab/demo-directory'
import { sendError, success } from '../http/envelope'
import type { PlatformServices } from '../services'

export const SIGNATURE_HEADER = 'x-eab-signature'
export const TIMESTAMP_HEADER = 'x-eab-timestamp'
/** 允许的时钟偏差，超过的事件按重放拒绝 */
const MAX_SKEW_SECONDS = 300
const SEEN_TTL_MS = 2 * MAX_SKEW_SECONDS * 1000

/** 事件推送的签名：sha256=<HMAC-SHA256(内部调用密钥, "时间戳.原始请求体")> */
export function signPayload(secret: string, timestamp: string, body: string): string {
  return `sha256=${createHmac('sha256', secret).update(`${timestamp}.${body}`, 'utf8').digest('hex')}`
}

function verifySignature(
  secret: string,
  timestamp: string,
  body: string,
  header: unknown,
): boolean {
  if (typeof header !== 'string') return false
  const expected = Buffer.from(signPayload(secret, timestamp, body))
  const actual = Buffer.from(header)
  return expected.length === actual.length && timingSafeEqual(expected, actual)
}

/** DemoERP 鉴权点 4 的裁决，推送到平台审计（主提示词 7.3、7.5 节） */
const BoAccessEvent = z.object({
  id: z.string().min(1).max(64),
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
  id: z.string().min(1).max(64),
  type: z.enum([
    'delivery.overdue',
    'inventory.below_safety_stock',
    'transaction.anomaly_created',
    'period.close_check_failed',
  ]),
  tenantId: z.string(),
  payload: z.record(z.string(), z.unknown()),
})

const IngestBody = z.union([BoAccessEvent, BusinessEvent])

/**
 * 接收 DemoERP 推送的事件（webhook，HMAC 签名）。
 * bo.access 写入审计；业务事件写入事件表并在进程内广播，P4 起触发 Agent。
 */
export function registerEventRoutes(app: FastifyInstance, services: PlatformServices): void {
  // 最近见过的事件编号，带过期时间；同一编号再次出现按重放拒绝
  const seen = new Map<string, number>()
  app.register(async (scoped) => {
    // 签名基于原始请求体，这个作用域里保留 JSON 原文
    scoped.addContentTypeParser('application/json', { parseAs: 'string' }, (_req, body, done) => {
      done(null, body)
    })

    scoped.post('/events/v1/ingest', { schema: { hide: true } }, async (request, reply) => {
      const raw = typeof request.body === 'string' ? request.body : ''
      const timestamp = request.headers[TIMESTAMP_HEADER]
      const skew = Math.abs(Date.now() / 1000 - Number(timestamp))
      if (typeof timestamp !== 'string' || !Number.isFinite(skew) || skew > MAX_SKEW_SECONDS) {
        return sendError(reply, 401, {
          code: 'STALE_EVENT',
          message: '事件时间戳缺失或超出允许范围',
        })
      }
      if (
        !verifySignature(services.internalSecret, timestamp, raw, request.headers[SIGNATURE_HEADER])
      ) {
        return sendError(reply, 401, { code: 'BAD_SIGNATURE', message: '事件签名校验失败' })
      }
      let parsed: z.infer<typeof IngestBody>
      try {
        parsed = IngestBody.parse(JSON.parse(raw))
      } catch {
        return sendError(reply, 400, { code: 'BAD_EVENT', message: '事件格式不合法' })
      }
      if (findTenant(parsed.tenantId) === undefined) {
        return sendError(reply, 400, { code: 'UNKNOWN_TENANT', message: '事件的租户不存在' })
      }
      const now = Date.now()
      for (const [id, at] of seen) if (now - at > SEEN_TTL_MS) seen.delete(id)
      if (seen.has(parsed.id)) {
        return sendError(reply, 409, { code: 'REPLAYED_EVENT', message: '事件已处理过' })
      }
      seen.set(parsed.id, now)

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
      const stored = services.events.append({
        source: 'demo-erp',
        type: parsed.type,
        tenantId: parsed.tenantId,
        payload: parsed.payload,
      })
      return reply.status(202).send(success({ accepted: true, id: stored.id }))
    })
  })
}
