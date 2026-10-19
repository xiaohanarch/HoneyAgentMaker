import { randomUUID } from 'node:crypto'
import { Role, TaskState, type Message, type Part } from '@a2a-js/sdk'
import {
  AgentEvent,
  type AgentExecutor,
  type ExecutionEventBus,
  type RequestContext,
} from '@a2a-js/sdk/server'

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

/** 一问一答：直接回复一条消息（不建任务），演示不用任何 Agent 框架时的最小接入成本 */
export class {{namePascal}}Executor implements AgentExecutor {
  async execute(context: RequestContext, bus: ExecutionEventBus): Promise<void> {
    bus.publish(AgentEvent.message(agentMessage(context, answer(userText(context.userMessage)))))
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
