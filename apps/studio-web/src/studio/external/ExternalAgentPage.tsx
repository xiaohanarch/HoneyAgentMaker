import { useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Globe, PlugZap, Send } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../../identity/identity-context'
import {
  getOverview,
  inspectCard,
  parseExamples,
  publishRemote,
  testConnection,
  type Inspection,
} from './api'
import { CardView } from './CardView'
import { ConfigForm, type Config } from './ConfigForm'
import { ExchangeLog } from './ExchangeLog'
import { OverviewPanel } from './OverviewPanel'

const DEFAULT_CARD_URL = 'http://127.0.0.1:4200/.well-known/agent-card.json'
const DEFAULT_TEST_MESSAGE = '百润精密最近有没有什么风险'

function configOf(inspection: Inspection): Config {
  const s = inspection.suggestion
  return {
    name: s.name,
    displayName: s.displayName,
    connection: s.connection,
    description: s.routing.description,
    examples: s.routing.examples.join('\n'),
    assistant: '',
    allowedCallers: '',
  }
}

const BUTTON =
  'inline-flex items-center gap-1.5 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 disabled:opacity-50'

/**
 * 接入外部 Agent（主提示词 10.5 节，故事 C）：输入 Agent Card 地址，平台展示技能、交互模式和认证方式；
 * 配置路由、所属岗位助手和允许的调用方，做连通性测试（展示 A2A 请求与响应原文），发布后出现在能力目录里。
 */
export function ExternalAgentPage(): ReactNode {
  const { token } = useIdentity()
  const client = useQueryClient()
  const [cardUrl, setCardUrl] = useState(DEFAULT_CARD_URL)
  const [config, setConfig] = useState<Config | undefined>()
  const [message, setMessage] = useState(DEFAULT_TEST_MESSAGE)

  const overview = useQuery({
    queryKey: ['studio', 'external', token],
    queryFn: () => getOverview(token),
    enabled: token !== undefined,
  })
  const inspect = useMutation({
    mutationFn: (url: string) => inspectCard(token, url),
    onSuccess: (inspection) => setConfig(configOf(inspection)),
  })
  const test = useMutation({ mutationFn: () => testConnection(token, cardUrl, message) })
  const publish = useMutation({
    mutationFn: (c: Config) =>
      publishRemote(token, {
        cardUrl,
        name: c.name,
        displayName: c.displayName,
        connection: c.connection,
        routing: {
          description: c.description,
          examples: parseExamples(c.examples),
          direct: c.assistant === '',
        },
        assistant: c.assistant === '' ? null : c.assistant,
        allowedCallers: c.allowedCallers
          .split(',')
          .map((s) => s.trim())
          .filter((s) => s !== ''),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['studio', 'external'] }),
  })

  const onInspect = (event: FormEvent): void => {
    event.preventDefault()
    test.reset()
    publish.reset()
    inspect.mutate(cardUrl.trim())
  }

  return (
    <div className="space-y-5">
      <header>
        <Link
          to="/studio"
          className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
        >
          <ArrowLeft aria-hidden className="size-3.5" />
          Studio
        </Link>
        <h1 className="mt-1 flex items-center gap-2 text-2xl font-semibold tracking-tight">
          <Globe aria-hidden className="size-5 text-external" />
          接入外部 Agent
        </h1>
        <p className="mt-1 text-sm text-ink-muted">
          用 LangGraph、A2A 官方 SDK 等其他框架写的 Agent，经 A2A 协议接进来，与平台原生的 Agent
          共用一个入口。
        </p>
      </header>

      <form
        onSubmit={onInspect}
        className="flex gap-2 rounded-xl border border-line bg-surface p-4"
      >
        <label className="flex-1 text-xs font-medium text-ink-muted">
          Agent Card 地址
          <input
            aria-label="Agent Card 地址"
            value={cardUrl}
            onChange={(e) => setCardUrl(e.target.value)}
            className="mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 font-mono text-sm outline-none focus:border-line-strong"
          />
        </label>
        <button
          type="submit"
          disabled={inspect.isPending || token === undefined}
          className={cn(BUTTON, 'self-end')}
        >
          <PlugZap aria-hidden className="size-3.5" />
          {inspect.isPending ? '读取中…' : '读取 Agent Card'}
        </button>
      </form>
      {inspect.error !== null && (
        <p className="rounded-md bg-deny-soft px-3 py-2 text-xs text-deny">
          {inspect.error.message}
        </p>
      )}

      {inspect.data !== undefined && config !== undefined && (
        <div className="grid grid-cols-5 gap-5">
          <div className="col-span-3 space-y-5">
            <CardView inspection={inspect.data} />
            <ConfigForm
              config={config}
              assistants={overview.data?.assistants ?? []}
              onChange={setConfig}
            />
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => publish.mutate(config)}
                disabled={publish.isPending}
                className={BUTTON}
              >
                {publish.isPending ? '发布中…' : '发布'}
              </button>
              {publish.data !== undefined && (
                <p
                  className="flex items-center gap-1.5 text-xs text-allow"
                  data-testid="publish-result"
                >
                  <CheckCircle2 aria-hidden className="size-3.5" />
                  已发布 {publish.data.ref}，已进入能力目录。
                  <Link to="/assistant" className="underline">
                    去统一入口试试
                  </Link>
                </p>
              )}
              {publish.error !== null && (
                <p className="text-xs text-deny">{publish.error.message}</p>
              )}
            </div>
          </div>

          <div className="col-span-2 space-y-4">
            <section className="space-y-3 rounded-xl border border-line bg-surface p-4">
              <h2 className="text-sm font-semibold">连通性测试</h2>
              <div className="flex gap-2">
                <input
                  aria-label="测试消息"
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  className="flex-1 rounded-md border border-line bg-canvas px-2.5 py-1.5 text-sm outline-none focus:border-line-strong"
                />
                <button
                  type="button"
                  onClick={() => test.mutate()}
                  disabled={test.isPending || message.trim() === ''}
                  className={BUTTON}
                >
                  <Send aria-hidden className="size-3.5" />
                  {test.isPending ? '测试中…' : '发送'}
                </button>
              </div>
              {test.error !== null && <p className="text-xs text-deny">{test.error.message}</p>}
              {test.data !== undefined && (
                <div
                  className="rounded-md border border-line bg-canvas px-3 py-2 text-xs"
                  data-testid="test-result"
                >
                  <p className={test.data.ok ? 'font-medium text-allow' : 'font-medium text-deny'}>
                    {test.data.ok
                      ? `连通，任务状态 ${test.data.state}`
                      : `失败：${test.data.error?.message ?? ''}`}
                  </p>
                  {test.data.text !== undefined && (
                    <p className="mt-1 whitespace-pre-wrap text-ink">{test.data.text}</p>
                  )}
                </div>
              )}
            </section>
            <ExchangeLog
              title={
                test.data === undefined ? '读取 Agent Card 的请求与响应' : '连通性测试的请求与响应'
              }
              exchanges={test.data?.exchanges ?? inspect.data.exchanges}
            />
          </div>
        </div>
      )}

      <OverviewPanel overview={overview.data} />
    </div>
  )
}
