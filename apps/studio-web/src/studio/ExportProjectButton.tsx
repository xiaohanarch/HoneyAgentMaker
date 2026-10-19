import { useState, type ReactNode } from 'react'
import { FolderDown } from 'lucide-react'
import type { DraftView } from './api'
import { errorText, useStudioApi } from './use-studio'

/** 导出为代码工程：zip 与 pnpm eab init 的模板同构，本地放进 Git 编辑后 pnpm eab push 推回同一条草稿 */
export function ExportProjectButton({ view }: { view: DraftView }): ReactNode {
  const { api } = useStudioApi()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const download = (): void => {
    setBusy(true)
    setError(null)
    api
      .exportProject(view.draft.id)
      .then((blob) => {
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `${view.draft.name}-project.zip`
        link.click()
        URL.revokeObjectURL(url)
      })
      .catch((e: unknown) => setError(errorText(e)))
      .finally(() => setBusy(false))
  }

  return (
    <span className="flex items-center gap-2">
      {error !== null && (
        <span className="text-xs text-deny" data-testid="export-error">
          {error}
        </span>
      )}
      <button
        type="button"
        onClick={download}
        disabled={busy}
        className="inline-flex shrink-0 items-center gap-1 rounded-md border border-line px-2 py-1 text-xs text-ink-muted hover:bg-surface-sunken disabled:opacity-50"
        data-testid="export-project"
      >
        <FolderDown aria-hidden className="size-3.5" />
        导出为代码工程
      </button>
    </span>
  )
}
