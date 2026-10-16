import { useCallback, useEffect, useRef, useState } from 'react'
import { streamPost, type RunEvent } from './api'
import type { Turn } from './turn'

function newId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

export interface Conversation {
  turns: readonly Turn[]
  busy: boolean
  ask: (question: string, target?: string) => Promise<void>
  confirm: (turnId: string, runId: string, approved: boolean) => Promise<void>
  reset: () => void
}

/**
 * 统一入口的对话状态：每轮问答收集 SSE 事件；确认或拒绝后，恢复运行的事件追加到同一轮。
 * 会话编号在前端生成，同一会话里的多轮问答共享短期记忆。切换身份时页面以新的 key 重新挂载，对话随之清空。
 */
export function useConversation(token: string | undefined): Conversation {
  const [turns, setTurns] = useState<readonly Turn[]>([])
  const [busy, setBusy] = useState(false)
  const sessionId = useRef(newId('ses'))
  const controller = useRef<AbortController | null>(null)

  const reset = useCallback(() => {
    controller.current?.abort()
    controller.current = null
    sessionId.current = newId('ses')
    setTurns([])
    setBusy(false)
  }, [])

  // 离开页面时中断进行中的流；运行本身在平台上照常完成
  useEffect(() => () => controller.current?.abort(), [])

  const update = useCallback((id: string, change: (turn: Turn) => Turn) => {
    setTurns((all) => all.map((t) => (t.id === id ? change(t) : t)))
  }, [])

  const stream = useCallback(
    async (id: string, path: string, body: unknown) => {
      const abort = new AbortController()
      controller.current = abort
      setBusy(true)
      update(id, (t) => ({ ...t, status: 'streaming' }))
      const onEvent = (event: RunEvent): void =>
        update(id, (t) => ({ ...t, events: [...t.events, event] }))
      try {
        await streamPost(path, token, body, onEvent, abort.signal)
        update(id, (t) => ({ ...t, status: 'done' }))
      } catch (error: unknown) {
        if (abort.signal.aborted) return
        const message = error instanceof Error ? error.message : '请求失败'
        update(id, (t) => ({ ...t, status: 'error', error: message }))
      } finally {
        if (controller.current === abort) {
          controller.current = null
          setBusy(false)
        }
      }
    },
    [token, update],
  )

  const ask = useCallback(
    async (question: string, target?: string) => {
      const id = newId('turn')
      setTurns((all) => [...all, { id, question, events: [], status: 'streaming' }])
      await stream(id, '/assist', { message: question, sessionId: sessionId.current, target })
    },
    [stream],
  )

  const confirm = useCallback(
    async (turnId: string, runId: string, approved: boolean) => {
      await stream(turnId, `/runs/${encodeURIComponent(runId)}/confirmation`, { approved })
    },
    [stream],
  )

  return { turns, busy, ask, confirm, reset }
}
