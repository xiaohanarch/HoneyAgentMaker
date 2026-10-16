import type { ReactNode } from 'react'
import { ArrowDownLeft, ArrowUpRight, FileCode2 } from 'lucide-react'
import type { Overview } from './api'

/** 外部 Agent 的接入模板，代码在仓库的 external-agents 目录下 */
const TEMPLATES = [
  {
    title: 'Python + LangGraph',
    path: 'external-agents/supplier-risk-py',
    note: 'A2A 官方 Python SDK，多轮追问、异步任务与推送回调，校验平台服务令牌',
  },
  {
    title: 'TypeScript（不用 Agent 框架）',
    path: 'external-agents/fx-rate-ts',
    note: 'A2A 官方 JS SDK，一个执行器加一张 Agent Card，演示最小接入成本',
  },
] as const

/** 已接入的外部 Agent、平台对外暴露的 A2A Agent，以及接入模板 */
export function OverviewPanel({ overview }: { overview: Overview | undefined }): ReactNode {
  return (
    <div className="grid grid-cols-3 gap-4">
      <section className="rounded-xl border border-line bg-surface p-4" data-testid="remote-list">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold">
          <ArrowDownLeft aria-hidden className="size-4 text-external" />
          已接入的外部 Agent
        </h2>
        <ul className="mt-2 space-y-1.5 text-xs">
          {(overview?.remotes ?? []).map((r) => (
            <li key={r.ref}>
              <span className="font-medium">{r.name}</span>
              <span className="ml-1.5 font-mono text-[11px] text-ink-faint">
                {r.ref}@{r.version} · {r.connection}
              </span>
            </li>
          ))}
          {overview !== undefined && overview.remotes.length === 0 && (
            <li className="text-ink-faint">还没有接入外部 Agent</li>
          )}
        </ul>
      </section>
      <section className="rounded-xl border border-line bg-surface p-4" data-testid="exposed-list">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold">
          <ArrowUpRight aria-hidden className="size-4 text-tenant" />
          对外暴露的 A2A Agent
        </h2>
        <p className="mt-1 text-[11px] text-ink-faint">
          触发器里声明了“被其他 Agent 调用”的 Agent 和岗位助手，外部调用方必须带用户令牌。
        </p>
        <ul className="mt-2 space-y-1.5 text-xs">
          {(overview?.exposed ?? []).map((e) => (
            <li key={e.ref}>
              <span className="font-medium">{e.name}</span>
              <span
                className="block truncate font-mono text-[11px] text-ink-faint"
                title={e.cardUrl}
              >
                {e.cardUrl}
              </span>
            </li>
          ))}
        </ul>
      </section>
      <section className="rounded-xl border border-line bg-surface p-4">
        <h2 className="flex items-center gap-1.5 text-sm font-semibold">
          <FileCode2 aria-hidden className="size-4 text-ink-muted" />
          接入模板
        </h2>
        <ul className="mt-2 space-y-2 text-xs">
          {TEMPLATES.map((t) => (
            <li key={t.path}>
              <span className="font-medium">{t.title}</span>
              <span className="block font-mono text-[11px] text-ink-faint">{t.path}</span>
              <span className="block text-[11px] text-ink-muted">{t.note}</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
