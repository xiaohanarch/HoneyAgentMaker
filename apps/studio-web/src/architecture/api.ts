/**
 * 架构视图调用的平台接口：运行列表（GET /runs）与追踪（复用 studioApi.trace）。
 * 运行列表对普通用户只返回本人，租户管理员与开发者返回本租户全部，服务端裁剪。
 */
import { getJson } from '../assist/api'

export interface RunSummary {
  runId: string
  traceId: string
  sessionId: string
  userId: string
  agent: string
  mode: string
  status: string
  utterance: string
  createdAt: string
  updatedAt: string
}

export function listRuns(token: string | undefined, limit = 50): Promise<RunSummary[]> {
  return getJson<RunSummary[]>(`/runs?limit=${limit}`, token)
}
