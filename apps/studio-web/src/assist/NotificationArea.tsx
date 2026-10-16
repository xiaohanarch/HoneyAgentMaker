import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, Zap } from 'lucide-react'
import { cn } from '@eab/ui'
import { getJson, postJson } from './api'

interface Notification {
  id: number
  at: string
  title: string
  body: string
  source: string
  runId: string | null
  traceId: string | null
  read: boolean
}

const POLL_MILLIS = 5_000

/** 通知区：业务事件触发的受托运行结果推送到委托人这里；演示时可以在这里触发交付逾期事件 */
export function NotificationArea({ token }: { token: string | undefined }): ReactNode {
  const client = useQueryClient()
  const key = ['notifications', token]
  const { data = [] } = useQuery({
    queryKey: key,
    queryFn: () => getJson<Notification[]>('/notifications', token),
    enabled: token !== undefined,
    refetchInterval: POLL_MILLIS,
  })
  const read = useMutation({
    mutationFn: (id: number) => postJson(`/notifications/${id}/read`, token, {}),
    onSuccess: () => client.invalidateQueries({ queryKey: key }),
  })
  const trigger = useMutation({
    mutationFn: () => postJson('/demo/v1/events', token, { type: 'delivery.overdue' }),
  })
  const unread = data.filter((n) => !n.read).length

  return (
    <section className="rounded-xl border border-line bg-surface" data-testid="notification-area">
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <Bell aria-hidden className="size-4 text-ink-muted" />
        通知
        {unread > 0 && (
          <span className="rounded-full bg-pending-soft px-1.5 text-[11px] text-pending">
            {unread}
          </span>
        )}
        <button
          type="button"
          onClick={() => trigger.mutate()}
          disabled={trigger.isPending || token === undefined}
          title="演示控制台：DemoERP 推送一条交付逾期事件，库存经营分析 Agent 以孙库管的委托运行"
          className="ml-auto inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-[11px] font-normal text-ink-muted transition-colors hover:bg-surface-sunken disabled:opacity-50"
        >
          <Zap aria-hidden className="size-3" />
          触发交付逾期事件
        </button>
      </header>
      <div className="px-4 py-3 text-xs">
        {trigger.isSuccess && (
          <p className="mb-2 text-ink-muted">事件已发出，结果会推送给委托人。</p>
        )}
        {trigger.error !== null && <p className="mb-2 text-deny">{trigger.error.message}</p>}
        {data.length === 0 ? (
          <p className="text-ink-muted">
            暂无通知。业务事件触发的受托运行结果会推送到委托人的通知区。
          </p>
        ) : (
          <ul className="max-h-72 space-y-2 overflow-y-auto">
            {data.map((n) => (
              <li
                key={n.id}
                data-testid="notification"
                className={cn(
                  'rounded-md border px-2.5 py-2',
                  n.read ? 'border-line' : 'border-pending/40 bg-pending-soft/30',
                )}
              >
                <p className="flex items-center gap-2 font-medium">
                  <span className="min-w-0 flex-1 truncate">{n.title}</span>
                  {!n.read && (
                    <button
                      type="button"
                      onClick={() => read.mutate(n.id)}
                      className="shrink-0 text-[11px] font-normal text-ink-faint hover:text-ink"
                    >
                      标为已读
                    </button>
                  )}
                </p>
                <p className="mt-1 line-clamp-4 whitespace-pre-wrap text-ink-muted">{n.body}</p>
                <p className="mt-1 text-[10px] text-ink-faint">
                  {new Date(n.at).toLocaleString('zh-CN')}
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
