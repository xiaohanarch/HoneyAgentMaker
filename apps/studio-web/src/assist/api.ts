/**
 * 统一入口调用平台接口的薄封装：SSE 流式运行（POST /assist、POST /runs/{id}/confirmation）
 * 和普通 JSON 接口。令牌来自身份上下文，只保存在内存里。
 */

export interface RunEvent {
  type: string
  agent: string | null
  data: Record<string, unknown>
}

interface ApiEnvelope<T> {
  success: boolean
  data: T | null
  error: { code: string; message: string; details?: unknown } | null
}

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    /** 平台在 error.details 里附带的信息，例如门禁结果、检查问题 */
    readonly details?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** POST /dev-requests 的响应（P9）：提交后告诉提出人哪些口径被带给了开发者 */
export interface DevRequestResult {
  ref: string
  status: string
  semanticObjects: string[]
  rules: string[]
  message: string
}

/** 提交扩展需求：需求原话、答复的助手 ref 与提出人的补充说明 */
export function submitDevRequest(
  token: string | undefined,
  body: { text: string; assistant: string; supplement: string },
): Promise<DevRequestResult> {
  return postJson<DevRequestResult>('/dev-requests', token, body)
}

function headers(token: string | undefined, accept: string): HeadersInit {
  const result: Record<string, string> = { accept, 'content-type': 'application/json' }
  if (token !== undefined) result.authorization = `Bearer ${token}`
  return result
}

async function failure(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as ApiEnvelope<unknown>
    if (body.error !== null) {
      return new ApiError(body.error.code, body.error.message, body.error.details)
    }
  } catch {
    // 响应体不是 JSON 时退回到 HTTP 状态
  }
  return new ApiError(`HTTP_${response.status}`, `请求失败：HTTP ${response.status}`)
}

export async function getJson<T>(path: string, token: string | undefined): Promise<T> {
  const response = await fetch(`/api${path}`, { headers: headers(token, 'application/json') })
  if (!response.ok) throw await failure(response)
  return ((await response.json()) as ApiEnvelope<T>).data as T
}

async function sendJson<T>(
  method: 'POST' | 'PUT' | 'DELETE',
  path: string,
  token: string | undefined,
  body: unknown,
): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: headers(token, 'application/json'),
    body: method === 'DELETE' ? undefined : JSON.stringify(body ?? {}),
  })
  if (!response.ok) throw await failure(response)
  const text = await response.text()
  return (text === '' ? undefined : (JSON.parse(text) as ApiEnvelope<T>).data) as T
}

export function postJson<T>(path: string, token: string | undefined, body: unknown): Promise<T> {
  return sendJson<T>('POST', path, token, body)
}

export function putJson<T>(path: string, token: string | undefined, body: unknown): Promise<T> {
  return sendJson<T>('PUT', path, token, body)
}

export function deleteJson<T>(path: string, token: string | undefined): Promise<T> {
  return sendJson<T>('DELETE', path, token, undefined)
}

/** 把一段 SSE 文本块解析成事件；只取 data 行，事件名已在 JSON 的 type 里 */
export function parseSseBlock(block: string): RunEvent | undefined {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).replace(/^ /, ''))
    .join('\n')
  if (data === '') return undefined
  try {
    return JSON.parse(data) as RunEvent
  } catch {
    return undefined
  }
}

/** 以 SSE 方式发起运行，逐个回调事件，流结束时返回 */
export async function streamPost(
  path: string,
  token: string | undefined,
  body: unknown,
  onEvent: (event: RunEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api${path}`, {
    method: 'POST',
    headers: headers(token, 'text/event-stream'),
    body: JSON.stringify(body),
    signal,
  })
  if (!response.ok || response.body === null) throw await failure(response)
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += value
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const event = parseSseBlock(block)
      if (event !== undefined) onEvent(event)
    }
  }
  const last = parseSseBlock(buffer)
  if (last !== undefined) onEvent(last)
}
