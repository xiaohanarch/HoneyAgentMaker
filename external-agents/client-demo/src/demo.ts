import { randomUUID } from 'node:crypto'
import { Role, type Message, type SendMessageResult } from '@a2a-js/sdk'
import { ClientFactory } from '@a2a-js/sdk/client'

/** 平台对外暴露的采购助手（触发器里声明了“被其他 Agent 调用”） */
export const TARGET = 'vendor.pur/procurement-assistant'

/** 本脚本模拟的外部平台，写进请求头 X-A2A-Caller，平台记进审计 */
export const CALLER = 'partner-procurement-platform'

export interface DemoOptions {
  platformUrl: string
  user: string
  question: string
  /** 不带用户令牌，演示平台拒绝没有身份的入站调用 */
  anonymous?: boolean
}

/** 演示模式下选人登录，取用户令牌（真实部署由调用方自己的身份系统完成令牌交换） */
export async function login(platformUrl: string, user: string): Promise<string> {
  const response = await fetch(`${platformUrl}/identity/v1/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId: user }),
  })
  if (!response.ok) throw new Error(`登录失败：HTTP ${response.status}`)
  const body = (await response.json()) as { data: { token: string } }
  return body.data.token
}

function userMessage(text: string): Message {
  return {
    messageId: randomUUID(),
    contextId: '',
    taskId: '',
    role: Role.ROLE_USER,
    parts: [{ content: { $case: 'text', value: text }, metadata: undefined, filename: '', mediaType: 'text/plain' }],
    metadata: undefined,
    extensions: [],
    referenceTaskIds: [],
  }
}

/** 结果里的文字：任务的产出物和状态消息，或直接回复的消息 */
export function textOf(result: SendMessageResult): string {
  const parts =
    'parts' in result
      ? result.parts
      : [...result.artifacts.flatMap((a) => a.parts), ...(result.status?.message?.parts ?? [])]
  return parts
    .map((p) => (p.content?.$case === 'text' ? p.content.value : ''))
    .filter((t) => t !== '')
    .join('\n')
}

export function stateOf(result: SendMessageResult): string {
  return 'parts' in result ? 'message' : String(result.status?.state ?? 'unknown')
}

/** 用 A2A 官方 JS SDK 取平台的 Agent Card 并发一条消息 */
export async function callAssistant(options: DemoOptions): Promise<SendMessageResult> {
  const factory = new ClientFactory()
  const client = await factory.createFromUrl(
    options.platformUrl,
    `/a2a/agents/${TARGET}/.well-known/agent-card.json`,
  )
  const headers: Record<string, string> = { 'X-A2A-Caller': CALLER }
  if (options.anonymous !== true) {
    headers.authorization = `Bearer ${await login(options.platformUrl, options.user)}`
  }
  return client.sendMessage(
    { tenant: '', message: userMessage(options.question), configuration: undefined, metadata: undefined },
    { serviceParameters: headers },
  )
}
