import type { FastifyReply, FastifyRequest } from 'fastify'
import { parseViewer, type Viewer } from '@eab/spec'

/** 统一的响应信封：成功时带 data，失败时带 error */
export interface ApiError {
  code: string
  message: string
  details?: unknown
}

export type ApiResponse<T> = { success: true; data: T } | { success: false; error: ApiError }

export function success<T>(data: T): ApiResponse<T> {
  return { success: true, data }
}

export function sendError(reply: FastifyReply, status: number, error: ApiError): FastifyReply {
  return reply.status(status).send({ success: false, error } satisfies ApiResponse<never>)
}

/**
 * 查看者身份的临时来源：P1 从请求头 x-eab-viewer 读取（platform、vendor、tenant:<id>）。
 * P2 起改为从平台签发的 JWT 里取，这个头将不再被信任。
 */
export const VIEWER_HEADER = 'x-eab-viewer'

export function viewerOf(request: FastifyRequest): Viewer | ApiError {
  const raw = request.headers[VIEWER_HEADER]
  if (typeof raw !== 'string' || raw === '') {
    return { code: 'VIEWER_REQUIRED', message: `缺少请求头 ${VIEWER_HEADER}` }
  }
  try {
    return parseViewer(raw)
  } catch (error: unknown) {
    return {
      code: 'VIEWER_INVALID',
      message: error instanceof Error ? error.message : String(error),
    }
  }
}

export function isApiError(value: Viewer | ApiError): value is ApiError {
  return 'code' in value
}
