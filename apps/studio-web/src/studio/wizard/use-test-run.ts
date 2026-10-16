import { useCallback, useEffect, useRef, useState } from 'react'
import type { RunEvent } from '../../assist/api'
import type { Turn } from '../../assist/turn'
import { useStudioApi } from '../use-studio'

export interface TestRuns {
  turns: readonly Turn[]
  busy: boolean
  ask: (message: string, asUser: string) => Promise<void>
  confirm: (turnId: string, runId: string, approved: boolean) => Promise<void>
  reset: () => void
}

function newId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

/**
 * 测试对话：以选定的测试用户运行草稿（私有环境），事件按 SSE 推送；确认或拒绝后恢复运行的事件追加到同一轮。
 * 切换测试用户时新开会话，避免不同用户的问答混在同一段会话记忆里。
 */
export function useTestRuns(draftId: string): TestRuns {
  const { api } = useStudioApi()
  const [turns, setTurns] = useState<readonly Turn[]>([])
  const [busy, setBusy] = useState(false)
  const session = useRef<{ id: string; user: string } | null>(null)
  const controller = useRef<AbortController | null>(null)

  useEffect(() => () => controller.current?.abort(), [])

  const update = useCallback((id: string, change: (turn: Turn) => Turn) => {
    setTurns((all) => all.map((t) => (t.id === id ? change(t) : t)))
  }, [])

  const stream = useCallback(
    async (
      id: string,
      run: (onEvent: (e: RunEvent) => void, signal: AbortSignal) => Promise<void>,
    ) => {
      const abort = new AbortController()
      controller.current = abort
      setBusy(true)
      update(id, (t) => ({ ...t, status: 'streaming' }))
      try {
        await run(
          (event) => update(id, (t) => ({ ...t, events: [...t.events, event] })),
          abort.signal,
        )
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
    [update],
  )

  const ask = useCallback(
    async (message: string, asUser: string) => {
      if (session.current === null || session.current.user !== asUser) {
        session.current = { id: newId('ses'), user: asUser }
      }
      const sessionId = session.current.id
      const id = newId('turn')
      setTurns((all) => [...all, { id, question: message, events: [], status: 'streaming' }])
      await stream(id, (onEvent, signal) =>
        api.test(draftId, { message, asUser, sessionId }, onEvent, signal),
      )
    },
    [api, draftId, stream],
  )

  const confirm = useCallback(
    async (turnId: string, runId: string, approved: boolean) => {
      await stream(turnId, (onEvent, signal) => api.confirm(runId, approved, onEvent, signal))
    },
    [api, stream],
  )

  const reset = useCallback(() => {
    controller.current?.abort()
    controller.current = null
    session.current = null
    setTurns([])
    setBusy(false)
  }, [])

  return { turns, busy, ask, confirm, reset }
}

/** 一轮里触发的平台约束（constraint 事件）；子 Agent 声明了同一条约束时也会触发，按规则去重 */
export function constraintsOf(turn: Turn): { rule: string; remedy: string; severity: string }[] {
  const seen = new Set<string>()
  return turn.events
    .filter((e) => e.type === 'constraint')
    .map((e) => ({
      rule: String(e.data.rule ?? ''),
      remedy: String(e.data.remedy ?? ''),
      severity: String(e.data.severity ?? ''),
    }))
    .filter((c) => {
      if (seen.has(c.rule)) return false
      seen.add(c.rule)
      return true
    })
}
