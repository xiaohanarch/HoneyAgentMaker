import { randomUUID } from 'node:crypto'
import { Role, TaskState, type Message, type Part } from '@a2a-js/sdk'
import {
  AgentEvent,
  type AgentExecutor,
  type ExecutionEventBus,
  type RequestContext,
} from '@a2a-js/sdk/server'
import { callPlatformTool } from './callback.js'

const text = (value: string): Part => ({
  content: { $case: 'text', value },
  metadata: undefined,
  filename: '',
  mediaType: 'text/plain',
})

function agentMessage(context: RequestContext, value: string): Message {
  return {
    messageId: randomUUID(),
    contextId: context.contextId,
    taskId: context.taskId,
    role: Role.ROLE_AGENT,
    parts: [text(value)],
    metadata: undefined,
    extensions: [],
    referenceTaskIds: [],
  }
}

/** 用户消息里的文字 */
export function userText(message: Message): string {
  return message.parts
    .map((p) => (p.content?.$case === 'text' ? p.content.value : ''))
    .join('')
    .trim()
}

function answer(input: string): string {
  // 业务逻辑：换成真实实现
  return `收到：${input}`
}

/** 从请求头里取平台出站调用携带的服务令牌（A2A 端点要求它，校验在 app.ts 已完成） */
function serviceToken(context: RequestContext): string | undefined {
  const headers = context.context.state?.get('headers') as
    | Record<string, string | string[] | undefined>
    | undefined
  const authorization = headers?.authorization
  const value = Array.isArray(authorization) ? authorization[0] : authorization
  return value?.startsWith('Bearer ') ? value.slice('Bearer '.length) : undefined
}

/**
 * 回查平台示例：用户问「查订单」时，用本次请求的服务令牌换受限只读令牌，调平台的 purchase_order_list。
 * 令牌主体是发起用户，只能读、只能看该用户的数据范围；换不到令牌或调用被拒都如实说明，不编造数据。
 */
async function answerWithCallback(context: RequestContext, input: string): Promise<string> {
  const token = serviceToken(context)
  if (token === undefined) return '本次请求没有携带平台服务令牌，无法回查平台。'
  try {
    const result = await callPlatformTool({
      serviceToken: token,
      tool: 'purchase_order_list',
      args: { limit: 3 },
    })
    return `平台侧查到 ${result.total} 张采购订单（${result.scopeLabel}），前 3 张：${(result.items as Array<{ id: string }>)
      .map((item) => item.id)
      .join('、')}。`
  } catch (error) {
    return `回查平台失败：${(error as Error).message}。拿不到平台数据时我不会编造结果。`
  }
}

/** 一问一答：直接回复一条消息（不建任务），演示不用任何 Agent 框架时的最小接入成本 */
export class {{namePascal}}Executor implements AgentExecutor {
  async execute(context: RequestContext, bus: ExecutionEventBus): Promise<void> {
    const input = userText(context.userMessage)
    const reply = input.includes('查订单') ? await answerWithCallback(context, input) : answer(input)
    bus.publish(AgentEvent.message(agentMessage(context, reply)))
    bus.finished()
  }

  async cancelTask(taskId: string, bus: ExecutionEventBus): Promise<void> {
    bus.publish(
      AgentEvent.statusUpdate({
        taskId,
        contextId: '',
        status: {
          state: TaskState.TASK_STATE_CANCELED,
          message: undefined,
          timestamp: new Date().toISOString(),
        },
        metadata: undefined,
      }),
    )
    bus.finished()
  }
}
