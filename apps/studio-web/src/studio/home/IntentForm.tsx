import { useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { ArrowRight, LoaderCircle } from 'lucide-react'
import { errorText, useStudioApi } from '../use-studio'

export const EXAMPLE_INTENT = '帮采购经理制定和供应商的降价谈判策略'

/** 从意图新建：创建草稿后进入向导第一步 */
export function IntentForm({ disabled }: { disabled: boolean }): ReactNode {
  const { api } = useStudioApi()
  const navigate = useNavigate()
  const [intent, setIntent] = useState('')
  const [state, setState] = useState<{ busy: boolean; error?: string }>({ busy: false })

  const submit = (e: FormEvent): void => {
    e.preventDefault()
    const text = intent.trim() === '' ? EXAMPLE_INTENT : intent.trim()
    setState({ busy: true })
    api.createDraft(text).then(
      (view) => navigate(`/studio/drafts/${view.draft.id}/purpose`),
      (error: unknown) => setState({ busy: false, error: errorText(error) }),
    )
  }

  return (
    <form onSubmit={submit} className="mt-6 space-y-2">
      <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 focus-within:border-line-strong">
        <input
          aria-label="描述 Agent 的目的"
          value={intent}
          onChange={(e) => setIntent(e.target.value)}
          maxLength={500}
          disabled={disabled || state.busy}
          placeholder={`例如：${EXAMPLE_INTENT}`}
          className="flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint"
        />
        <button
          type="submit"
          disabled={disabled || state.busy}
          className="inline-flex items-center gap-1 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {state.busy ? (
            <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
          ) : (
            <ArrowRight aria-hidden className="size-3.5" />
          )}
          开始
        </button>
      </div>
      {state.error !== undefined && <p className="text-xs text-deny">{state.error}</p>}
    </form>
  )
}
