import { useState, type ReactNode } from 'react'
import { Check, Lightbulb, X } from 'lucide-react'
import { Button, Empty, ErrorLine, Loading, Pill, SectionHeader, shortTime, toneOf } from './ui'
import { useHubAction, useHubQuery, useHubRole } from './use-hub'
import type { Feedback } from './types'

const STATUS_LABELS: Record<Feedback['status'], string> = {
  pending: '待审核',
  approved: '已写入语义包',
  rejected: '已驳回',
}

/** 候选规则是 JSON 文本（规则说明、检查工具、条件），解析失败时原样展示 */
function candidateOf(f: Feedback): Record<string, unknown> | undefined {
  if (f.candidate === null) return undefined
  try {
    const parsed: unknown = JSON.parse(f.candidate)
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : undefined
  } catch {
    return undefined
  }
}

/** 经验回流：用户在结果卡片上点“结论有误”，平台生成候选规则，管理员审核后写入租户语义包 */
export function FeedbackSection(): ReactNode {
  const list = useHubQuery<Feedback[]>('/hub/feedback')
  const { canWrite } = useHubRole()
  const [note, setNote] = useState('')
  const approve = useHubAction<Feedback, string>(
    'POST',
    (id) => `/hub/feedback/${id}/approve`,
    () => ({ note: note.trim() || undefined }),
  )
  const reject = useHubAction<Feedback, string>(
    'POST',
    (id) => `/hub/feedback/${id}/reject`,
    () => ({ note: note.trim() || undefined }),
  )
  const busy = approve.isPending || reject.isPending
  return (
    <section className="space-y-5" aria-label="经验回流">
      <SectionHeader
        title="经验回流"
        description="审核通过的规则写入租户语义包扩展，挂载基础语义包的 Agent 下次运行即按新规则判断"
      />
      <ErrorLine error={list.error ?? approve.error ?? reject.error} />
      {approve.data?.packRef != null && (
        <p className="text-xs text-allow">已写入 {approve.data.packRef}</p>
      )}
      {list.isPending ? (
        <Loading />
      ) : (list.data ?? []).length === 0 ? (
        <Empty>还没有用户反馈。业务用户可以在统一入口的结果卡片上点“结论有误”。</Empty>
      ) : (
        <ul className="space-y-3">
          {(list.data ?? []).map((f) => {
            const c = candidateOf(f)
            return (
              <li
                key={f.id}
                className="rounded-xl border border-line bg-surface p-4"
                data-testid="feedback-item"
              >
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <Lightbulb aria-hidden className="size-4 text-pending" />
                  <span className="font-medium">{f.userId}</span>
                  <span className="text-ink-muted">认为结论有误</span>
                  <Pill tone={toneOf(f.status)}>{STATUS_LABELS[f.status]}</Pill>
                  <span className="ml-auto font-mono text-[10px] text-ink-faint">
                    {shortTime(f.createdAt)}
                  </span>
                </div>
                {f.utterance !== null && (
                  <p className="mt-2 text-xs text-ink-muted">提问：{f.utterance}</p>
                )}
                <blockquote className="mt-2 border-l-2 border-pending/50 pl-3 text-sm">
                  {f.reason}
                </blockquote>
                <div className="mt-3 rounded-md bg-canvas p-3 text-xs">
                  <p className="text-[11px] text-ink-faint">
                    候选规则{f.decidedBy === 'model' ? '（模型生成）' : '（按原文）'}
                  </p>
                  {c === undefined ? (
                    <p className="mt-1">{f.candidate ?? '—'}</p>
                  ) : (
                    <dl className="mt-1 grid grid-cols-[5rem_minmax(0,1fr)] gap-x-3 gap-y-1">
                      {Object.entries(c).map(([k, v]) => (
                        <div key={k} className="contents">
                          <dt className="text-ink-faint">{k}</dt>
                          <dd className="break-all font-mono text-[11px]">
                            {typeof v === 'string' ? v : JSON.stringify(v)}
                          </dd>
                        </div>
                      ))}
                    </dl>
                  )}
                </div>
                {f.status !== 'pending' && f.reviewNote !== null && (
                  <p className="mt-2 text-xs text-ink-muted">
                    审核意见（{f.reviewer}）：{f.reviewNote}
                  </p>
                )}
                {f.status === 'pending' && canWrite && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <input
                      aria-label="审核意见"
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="审核意见（可选）"
                      className="min-w-0 flex-1 rounded-md border border-line bg-canvas px-2.5 py-1.5 text-xs outline-none focus:border-line-strong"
                    />
                    <Button variant="danger" disabled={busy} onClick={() => reject.mutate(f.id)}>
                      <X aria-hidden className="size-3.5" />
                      驳回
                    </Button>
                    <Button variant="primary" disabled={busy} onClick={() => approve.mutate(f.id)}>
                      <Check aria-hidden className="size-3.5" />
                      通过并写入语义包
                    </Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
