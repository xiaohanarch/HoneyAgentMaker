import { useState, type FormEvent, type ReactNode } from 'react'
import { MessageSquareWarning } from 'lucide-react'
import { ApiError, postJson } from '../assist/api'
import { useIdentity } from '../identity/identity-context'

type State =
  | { status: 'idle' }
  | { status: 'editing' }
  | { status: 'sending' }
  | { status: 'sent' }
  | { status: 'error'; message: string }

const MAX_REASON = 500

/** 结果卡片上的“结论有误”：说明理由后提交，管理员在治理中心的经验回流里审核 */
export function ConclusionFeedback({ runId }: { runId: string }): ReactNode {
  const { token } = useIdentity()
  const [state, setState] = useState<State>({ status: 'idle' })
  const [reason, setReason] = useState('')

  if (state.status === 'sent') {
    return <p className="text-[11px] text-ink-muted">已提交反馈，管理员审核后会更新规则</p>
  }
  if (state.status === 'idle') {
    return (
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation()
          setState({ status: 'editing' })
        }}
        className="inline-flex items-center gap-1 text-[11px] text-ink-faint transition-colors hover:text-pending"
      >
        <MessageSquareWarning aria-hidden className="size-3.5" />
        结论有误
      </button>
    )
  }

  const submit = async (e: FormEvent): Promise<void> => {
    e.preventDefault()
    if (reason.trim() === '') return
    setState({ status: 'sending' })
    try {
      await postJson('/feedback', token, { runId, reason: reason.trim() })
      setState({ status: 'sent' })
    } catch (error) {
      setState({
        status: 'error',
        message: error instanceof ApiError ? error.message : '提交失败，请稍后再试',
      })
    }
  }

  return (
    <form
      onSubmit={(e) => void submit(e)}
      onClick={(e) => e.stopPropagation()}
      className="space-y-1.5"
      aria-label="反馈结论有误"
    >
      <textarea
        autoFocus
        rows={2}
        maxLength={MAX_REASON}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="哪里不对？例如：这类跨期冲销不算异常"
        className="w-full resize-none rounded-md border border-line bg-surface px-2.5 py-1.5 text-xs outline-none focus:border-line-strong"
      />
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={state.status === 'sending' || reason.trim() === ''}
          className="rounded-md bg-ink px-2.5 py-1 text-[11px] font-medium text-surface disabled:opacity-50"
        >
          {state.status === 'sending' ? '提交中…' : '提交反馈'}
        </button>
        <button
          type="button"
          onClick={() => setState({ status: 'idle' })}
          className="text-[11px] text-ink-faint hover:text-ink"
        >
          取消
        </button>
        {state.status === 'error' && <span className="text-[11px] text-deny">{state.message}</span>}
      </div>
    </form>
  )
}
