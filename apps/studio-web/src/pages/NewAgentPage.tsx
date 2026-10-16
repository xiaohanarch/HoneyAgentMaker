import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ArrowLeft } from 'lucide-react'
import { useIdentity } from '../identity/identity-context'
import { IntentForm } from '../studio/home/IntentForm'

/** 从意图新建 Agent：输入一句话意图，创建草稿后进入 13 步向导 */
export function NewAgentPage(): ReactNode {
  const { token } = useIdentity()
  return (
    <div className="mx-auto max-w-2xl space-y-4 pt-10">
      <Link
        to="/studio"
        className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
      >
        <ArrowLeft aria-hidden className="size-3.5" />
        返回 Studio
      </Link>
      <h1 className="text-2xl font-semibold tracking-tight">从一句话意图新建 Agent</h1>
      <p className="text-sm text-ink-muted">
        描述这个 Agent
        要帮谁做什么。平台会在后面的每一步给出推荐：先复用已有能力，再扩展，最后才新建。
      </p>
      <IntentForm disabled={token === undefined} />
    </div>
  )
}
