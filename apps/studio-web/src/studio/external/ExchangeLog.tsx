import { useState, type ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { cn } from '@eab/ui'
import { prettyBody, type Exchange } from './api'

const KIND_LABEL: Record<Exchange['kind'], string> = {
  request: '请求',
  response: '响应',
  event: '事件',
}

const KIND_CLASS: Record<Exchange['kind'], string> = {
  request: 'bg-external-soft text-external',
  response: 'bg-allow-soft text-allow',
  event: 'bg-surface-sunken text-ink-muted',
}

function ExchangeRow({ exchange, index }: { exchange: Exchange; index: number }): ReactNode {
  const [open, setOpen] = useState(index < 2)
  const failed = exchange.status !== undefined && exchange.status >= 400
  return (
    <li className="border-b border-line last:border-b-0">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs hover:bg-surface-sunken focus-visible:bg-surface-sunken"
      >
        <ChevronRight
          aria-hidden
          className={cn(
            'size-3.5 shrink-0 text-ink-faint transition-transform',
            open && 'rotate-90',
          )}
        />
        <span
          className={cn('rounded px-1.5 py-0.5 text-[10px] font-medium', KIND_CLASS[exchange.kind])}
        >
          {KIND_LABEL[exchange.kind]}
        </span>
        <span className="font-mono text-[11px] text-ink-muted">{exchange.method}</span>
        <span className="min-w-0 flex-1 truncate font-mono text-[11px]" title={exchange.url}>
          {exchange.url}
        </span>
        {exchange.status !== undefined && (
          <span className={cn('font-mono text-[11px]', failed ? 'text-deny' : 'text-allow')}>
            {exchange.status}
          </span>
        )}
      </button>
      {open && (
        <div className="space-y-2 bg-canvas px-3 pt-1 pb-3">
          {exchange.headers !== undefined && (
            <pre className="overflow-x-auto font-mono text-[11px] leading-relaxed text-ink-muted">
              {Object.entries(exchange.headers)
                .map(([k, v]) => `${k}: ${v}`)
                .join('\n')}
            </pre>
          )}
          {exchange.body !== undefined && (
            <pre className="max-h-64 overflow-auto rounded-md border border-line bg-surface p-2 font-mono text-[11px] leading-relaxed">
              {prettyBody(exchange.body)}
            </pre>
          )}
        </div>
      )}
    </li>
  )
}

/** A2A 请求与响应原文，令牌已脱敏，便于对照协议排查问题 */
export function ExchangeLog({
  exchanges,
  title,
}: {
  exchanges: Exchange[]
  title: string
}): ReactNode {
  if (exchanges.length === 0) return null
  return (
    <section
      className="overflow-hidden rounded-lg border border-line bg-surface"
      data-testid="a2a-exchanges"
    >
      <h3 className="border-b border-line bg-surface-sunken px-3 py-2 text-[11px] font-medium text-ink-muted">
        {title}（{exchanges.length} 条，令牌只显示类型）
      </h3>
      <ol>
        {exchanges.map((exchange, index) => (
          <ExchangeRow key={`${exchange.at}-${index}`} exchange={exchange} index={index} />
        ))}
      </ol>
    </section>
  )
}
