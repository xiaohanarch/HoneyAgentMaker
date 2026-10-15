import Fastify, { type FastifyInstance, type FastifyServerOptions } from 'fastify'

export const SERVICE_NAME = 'platform'
export const SERVICE_VERSION = '0.1.0'

export interface HealthReport {
  status: 'ok'
  service: string
  version: string
  uptimeSeconds: number
}

export function buildApp(options: FastifyServerOptions = {}): FastifyInstance {
  const app = Fastify(options)
  const startedAt = Date.now()

  app.get('/health', async (): Promise<HealthReport> => ({
    status: 'ok',
    service: SERVICE_NAME,
    version: SERVICE_VERSION,
    uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
  }))

  return app
}
