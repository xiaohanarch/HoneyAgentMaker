import { useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Hash, SendHorizontal } from 'lucide-react'
import { useIdentity } from '../identity/identity-context'
import { getJson, postJson } from '../assist/api'

interface ImMessage {
  id: number
  at: string
  channel: string
  recipient: string
  message: string
  runId: string | null
}

interface AssistReply {
  kind: string
  runId: string
  payload: Record<string, unknown>
  events: { type: string; data: Record<string, unknown> }[]
}

interface ChatLine {
  id: string
  from: 'user' | 'bot'
  text: string
  link?: string
}

const POLL_MILLIS = 5_000

/** IM 渠道只能显示纯文本：卡片降级为文字，需要确认的写操作引导到统一入口 */
export function replyText(reply: AssistReply): { text: string; link?: string } {
  const final = reply.events.find((e) => e.type === 'final')
  const text = typeof final?.data.text === 'string' ? final.data.text : ''
  if (reply.events.some((e) => e.type === 'confirmation_required') && final === undefined) {
    return { text: '这个操作需要你确认后才能执行，IM 渠道不支持确认，请到统一入口完成。' }
  }
  if (reply.kind === 'navigation') {
    const card = reply.payload.card as { title: string; url: string } | undefined
    // 平台给的说明里已经带了标题，IM 里只追加链接
    return { text, link: card?.url }
  }
  if (reply.kind === 'clarify') {
    const options = (reply.payload.options as { name: string }[] | undefined) ?? []
    return { text: `${text}${options.map((o) => o.name).join(' / ')}（请在统一入口点选）` }
  }
  return { text: text !== '' ? text : '没有得到结果。' }
}

/**
 * 模拟 IM 渠道（主提示词 9.3 节）：左侧是 Agent 推送到本租户 IM 群的消息，右侧是以 IM 方式向助手提问。
 * 只用于演示同一套能力可以通过不同渠道触达。
 */
export function ImChannelPage(): ReactNode {
  const { user, token } = useIdentity()
  const [lines, setLines] = useState<ChatLine[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const { data: pushed = [] } = useQuery({
    queryKey: ['im', token],
    queryFn: () => getJson<ImMessage[]>('/channels/im/messages', token),
    enabled: token !== undefined,
    refetchInterval: POLL_MILLIS,
  })

  const submit = async (e: FormEvent): Promise<void> => {
    e.preventDefault()
    const question = input.trim()
    if (question === '' || token === undefined || sending) return
    setInput('')
    setSending(true)
    setLines((all) => [...all, { id: crypto.randomUUID(), from: 'user', text: question }])
    try {
      const reply = await postJson<AssistReply>('/assist', token, { message: question })
      setLines((all) => [...all, { id: crypto.randomUUID(), from: 'bot', ...replyText(reply) }])
    } catch (error: unknown) {
      const text = error instanceof Error ? error.message : '发送失败'
      setLines((all) => [...all, { id: crypto.randomUUID(), from: 'bot', text }])
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-5">
      <section className="rounded-xl border border-line bg-surface" data-testid="im-pushed">
        <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
          <Link to="/assistant" aria-label="返回统一入口" className="text-ink-muted hover:text-ink">
            <ArrowLeft className="size-4" />
          </Link>
          <Hash aria-hidden className="size-4 text-ink-muted" />
          {`${user.tenantName} · 风险提醒群`}
        </header>
        <ul className="max-h-[calc(100dvh-12rem)] space-y-3 overflow-y-auto px-4 py-4 text-sm">
          {pushed.length === 0 ? (
            <li className="text-xs text-ink-muted">
              还没有消息。Agent 的风险提醒钩子会把消息推送到这里。
            </li>
          ) : (
            pushed.map((m) => (
              <li key={m.id} className="rounded-lg bg-canvas px-3 py-2">
                <p className="text-[11px] text-ink-faint">
                  {`@${m.recipient} · ${new Date(m.at).toLocaleString('zh-CN')}`}
                </p>
                <p className="mt-0.5 whitespace-pre-wrap">{m.message}</p>
              </li>
            ))
          )}
        </ul>
      </section>

      <section
        className="flex flex-col rounded-xl border border-line bg-surface"
        data-testid="im-chat"
      >
        <header className="border-b border-line px-4 py-2.5 text-sm font-medium">
          与 ERP 助手的私聊
        </header>
        <ul className="flex-1 space-y-2 overflow-y-auto px-4 py-4 text-sm">
          {lines.map((l) => (
            <li
              key={l.id}
              className={
                l.from === 'user'
                  ? 'ml-auto w-fit max-w-[85%] rounded-lg bg-ink px-3 py-1.5 text-surface'
                  : 'w-fit max-w-[85%] whitespace-pre-wrap rounded-lg bg-canvas px-3 py-1.5'
              }
            >
              {l.text}
              {l.link !== undefined && (
                <a
                  href={l.link}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="ml-1 underline"
                >
                  打开
                </a>
              )}
            </li>
          ))}
          {sending && <li className="text-xs text-ink-faint">助手正在输入…</li>}
        </ul>
        <form className="border-t border-line p-3" onSubmit={(e) => void submit(e)}>
          <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2">
            <input
              aria-label="IM 消息"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={2000}
              placeholder="发消息给 ERP 助手"
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint"
            />
            <button
              type="submit"
              aria-label="发送"
              disabled={sending || input.trim() === ''}
              className="disabled:opacity-40"
            >
              <SendHorizontal aria-hidden className="size-4 text-ink-muted" />
            </button>
          </div>
        </form>
      </section>
    </div>
  )
}
