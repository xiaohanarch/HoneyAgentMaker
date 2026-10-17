import type { ReactNode } from 'react'
import { ToneBadge } from '@eab/ui'
import type { RequestView } from '../api'
import { REQUEST_STATUS_VIEWS, formatTime } from './request-model'

/** 附带的业务对象与规则用等宽小徽章展示 */
function RefChip({ children }: { children: ReactNode }): ReactNode {
  return (
    <span className="rounded-md border border-line bg-canvas px-1.5 py-0.5 font-mono text-[11px] text-ink-muted">
      {children}
    </span>
  )
}

/** 需求头：提出人的原话与补充说明、状态、提交人，以及自动附带的业务上下文 */
export function RequestHeader({ view }: { view: RequestView }): ReactNode {
  const status = REQUEST_STATUS_VIEWS[view.status]
  const { context } = view
  return (
    <header
      className="space-y-3 rounded-xl border border-line bg-surface p-5"
      data-testid="request-header"
    >
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-xs text-ink-faint">
            扩展需求 <span className="font-mono">{view.ref}</span>
          </p>
          <h1 className="mt-1 text-xl font-semibold tracking-tight">{`${view.requesterName} 提出的扩展需求`}</h1>
        </div>
        <ToneBadge tone={status.tone} className="mt-1">
          {status.label}
        </ToneBadge>
      </div>
      <blockquote className="border-l-2 border-line-strong pl-3 text-sm leading-relaxed">
        {view.originalText}
      </blockquote>
      {view.supplements.length > 0 && (
        <section aria-label="补充说明">
          <p className="text-xs font-medium text-ink-faint">补充说明</p>
          <ul className="mt-1 space-y-1 text-sm leading-relaxed">
            {view.supplements.map((supplement, i) => (
              <li key={i}>{supplement}</li>
            ))}
          </ul>
        </section>
      )}
      <p className="text-xs text-ink-faint">
        {`${view.requesterName}（${view.requester}）提交于 ${formatTime(view.createdAt)}`}
      </p>
      <section className="rounded-lg bg-surface-sunken px-3 py-2.5" aria-label="自动附带的口径">
        <p className="text-xs font-medium text-ink-faint">自动附带给开发者</p>
        <div className="mt-1.5 space-y-1.5 text-xs">
          {context.assistant !== null && context.assistant !== '' && (
            <p className="flex flex-wrap items-center gap-1.5">
              <span className="text-ink-muted">答复助手</span>
              <span className="break-all font-mono">{context.assistant}</span>
            </p>
          )}
          {context.semanticObjects.length > 0 && (
            <p className="flex flex-wrap items-center gap-1.5">
              <span className="text-ink-muted">业务对象</span>
              {context.semanticObjects.map((ref) => (
                <RefChip key={ref}>{ref}</RefChip>
              ))}
            </p>
          )}
          {context.rules.length > 0 && (
            <p className="flex flex-wrap items-center gap-1.5">
              <span className="text-ink-muted">规则</span>
              {context.rules.map((id) => (
                <RefChip key={id}>{id}</RefChip>
              ))}
            </p>
          )}
          {context.semanticObjects.length === 0 && context.rules.length === 0 && (
            <p className="text-ink-muted">没有检索到相关的业务对象或规则。</p>
          )}
        </div>
      </section>
    </header>
  )
}
