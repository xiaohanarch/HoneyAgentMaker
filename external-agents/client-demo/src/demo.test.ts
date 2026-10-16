import { describe, expect, it } from 'vitest'
import { Role, TaskState, type Task } from '@a2a-js/sdk'
import { stateOf, textOf } from './demo.js'

const part = (value: string) => ({
  content: { $case: 'text' as const, value },
  metadata: undefined,
  filename: '',
  mediaType: 'text/plain',
})

describe('client-demo 结果解析', () => {
  it('任务取产出物文字和状态', () => {
    const task: Task = {
      id: 't',
      contextId: 'c',
      status: { state: TaskState.TASK_STATE_COMPLETED, message: undefined, timestamp: undefined },
      artifacts: [
        {
          artifactId: 'a',
          name: '回复',
          description: '',
          parts: [part('逾期率 12%')],
          metadata: undefined,
          extensions: [],
        },
      ],
      history: [],
      metadata: undefined,
    }
    expect(textOf(task)).toBe('逾期率 12%')
    expect(stateOf(task)).toBe('TASK_STATE_COMPLETED')
  })

  it('消息直接取文字', () => {
    const message = {
      messageId: 'm',
      contextId: '',
      taskId: '',
      role: Role.ROLE_AGENT,
      parts: [part('你好')],
      metadata: undefined,
      extensions: [],
      referenceTaskIds: [],
    }
    expect(textOf(message)).toBe('你好')
    expect(stateOf(message)).toBe('message')
  })
})
