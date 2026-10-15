import type { FastifyReply } from 'fastify'

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
