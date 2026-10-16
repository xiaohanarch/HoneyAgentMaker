/**
 * “接入外部 Agent”页面调用的平台接口：解读 Agent Card、连通性测试、发布 RemoteAgent 资产（主提示词 10.5 节）。
 */
import { getJson, postJson } from '../../assist/api'

/** A2A 请求与响应的原文，令牌只显示类型 */
export interface Exchange {
  at: string
  kind: 'request' | 'response' | 'event'
  method: string
  url: string
  headers?: Record<string, string>
  body?: string
  status?: number
}

export interface CardSummary {
  name: string
  description: string
  version: string
  streaming: boolean
  pushNotifications: boolean
  inputModes: string[]
  outputModes: string[]
  auth: string[]
  interfaces: string[]
}

export interface Skill {
  id: string
  name: string
  description: string
  tags: string[]
  examples: string[]
}

export interface Routing {
  description: string
  examples: string[]
  direct: boolean
}

export interface Suggestion {
  name: string
  displayName: string
  connection: string
  agentCardPath: string
  routing: Routing
  existing?: string
}

export interface Inspection {
  card: CardSummary
  skills: Skill[]
  raw: string
  exchanges: Exchange[]
  suggestion: Suggestion
}

export interface TestResult {
  ok: boolean
  state: string
  text?: string
  taskId?: string
  error?: { code: string; message: string }
  exchanges: Exchange[]
}

export interface PublishBody {
  cardUrl: string
  name: string
  displayName: string
  connection: string
  routing: Routing
  assistant: string | null
  allowedCallers: string[]
}

export interface Overview {
  remotes: {
    ref: string
    version: string
    name: string
    connection: string
    assistant: string | null
  }[]
  exposed: { ref: string; name: string; kind: string; cardUrl: string }[]
  assistants: { ref: string; name: string }[]
}

export const inspectCard = (token: string | undefined, cardUrl: string): Promise<Inspection> =>
  postJson<Inspection>('/studio/external/inspect', token, { cardUrl })

export const testConnection = (
  token: string | undefined,
  cardUrl: string,
  message: string,
): Promise<TestResult> => postJson<TestResult>('/studio/external/test', token, { cardUrl, message })

export const publishRemote = (
  token: string | undefined,
  body: PublishBody,
): Promise<{ ref: string; published: string[] }> =>
  postJson<{ ref: string; published: string[] }>('/studio/external/publish', token, body)

export const getOverview = (token: string | undefined): Promise<Overview> =>
  getJson<Overview>('/studio/external', token)

/** 每行一条示例话术，去掉空行 */
export function parseExamples(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}

/** 请求体如果是 JSON 就缩进显示，否则原样 */
export function prettyBody(body: string | undefined): string {
  if (body === undefined) return ''
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}
