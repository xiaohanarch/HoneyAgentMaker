import type { AttributeValue, SpanRecord } from './types'

/**
 * 转成 OTLP/HTTP JSON（ExportTraceServiceRequest）。系统提示词片段不导出，只导出片段编号：
 * 外部采集端不属于任何一层资产的查看者，标品指令原文不应离开平台。
 */
interface OtlpAnyValue {
  stringValue?: string
  boolValue?: boolean
  intValue?: string
  doubleValue?: number
}

function toAnyValue(value: AttributeValue): OtlpAnyValue {
  if (typeof value === 'string') return { stringValue: value }
  if (typeof value === 'boolean') return { boolValue: value }
  if (typeof value === 'number')
    return Number.isInteger(value) ? { intValue: String(value) } : { doubleValue: value }
  return { stringValue: JSON.stringify(value) }
}

const toNanos = (iso: string): string => (BigInt(Date.parse(iso)) * 1_000_000n).toString()

/** OTLP 的 traceId 是 32 位十六进制，spanId 是 16 位 */
export function toOtlp(spans: readonly SpanRecord[], serviceName = 'eab-platform'): unknown {
  return {
    resourceSpans: [
      {
        resource: {
          attributes: [{ key: 'service.name', value: { stringValue: serviceName } }],
        },
        scopeSpans: [
          {
            scope: { name: '@eab/tracing', version: '0.1.0' },
            spans: spans.map((span) => ({
              traceId: span.traceId,
              spanId: span.spanId,
              ...(span.parentSpanId === null ? {} : { parentSpanId: span.parentSpanId }),
              name: span.name,
              kind: 1,
              startTimeUnixNano: toNanos(span.startTime),
              endTimeUnixNano: toNanos(span.endTime),
              attributes: [
                { key: 'eab.span.kind', value: { stringValue: span.kind } },
                ...(span.tenantId === undefined
                  ? []
                  : [{ key: 'eab.tenant.id', value: { stringValue: span.tenantId } }]),
                ...(span.promptSegments === undefined
                  ? []
                  : [
                      {
                        key: 'eab.prompt.segment_ids',
                        value: { stringValue: span.promptSegments.map((s) => s.id).join(',') },
                      },
                    ]),
                ...Object.entries(span.attributes).map(([key, value]) => ({
                  key,
                  value: toAnyValue(value),
                })),
              ],
              status:
                span.status === 'error'
                  ? { code: 2, message: span.statusMessage ?? '' }
                  : { code: span.status === 'ok' ? 1 : 0, message: span.statusMessage ?? '' },
            })),
          },
        ],
      },
    ],
  }
}

/** 运行结束时导出到 OTLP/HTTP 采集端，失败只返回错误，不影响运行结果 */
export async function exportOtlp(
  endpoint: string,
  spans: readonly SpanRecord[],
  fetchImpl: typeof fetch = fetch,
): Promise<{ ok: boolean; error?: string }> {
  try {
    const response = await fetchImpl(new URL('/v1/traces', endpoint), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(toOtlp(spans)),
      signal: AbortSignal.timeout(5_000),
    })
    return response.ok ? { ok: true } : { ok: false, error: `HTTP ${response.status}` }
  } catch (error: unknown) {
    return { ok: false, error: error instanceof Error ? error.message : String(error) }
  }
}
