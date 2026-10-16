import type { RunEvent } from './api'

/** 一轮问答在界面上的状态：事件按到达顺序保存，界面展示的内容都由事件推导 */
export interface Turn {
  id: string
  question: string
  events: readonly RunEvent[]
  status: 'streaming' | 'done' | 'error'
  error?: string
}

export interface RoutingCandidate {
  ref: string
  name: string
  kind: string
  score: number
  matched: string
  filtered?: string
}

export interface RoutingLevel {
  level: number
  outcome: 'route' | 'clarify' | 'gap'
  candidates: RoutingCandidate[]
  decision?: { ref: string; name: string; confidence: number; reason: string; decidedBy: string }
}

export interface Routing {
  query: string
  levels: RoutingLevel[]
  assistant?: { ref: string; name: string }
  target?: string
  targetName?: string
  confidence?: number
  reason?: string
  decidedBy?: string
  outcome: string
}

export interface ToolStep {
  callId: string
  tool: string
  arguments: Record<string, unknown>
  ok?: boolean
  summary?: string
}

export interface Pending {
  key: string
  title: string
  preview: Record<string, unknown>
}

export interface Option {
  ref: string
  name: string
  description: string
}

/** 推导出的一轮内容 */
export interface TurnView {
  runId?: string
  traceId?: string
  agentName?: string
  routing?: Routing
  thoughts: string[]
  tools: ToolStep[]
  text: string
  pending?: Pending
  confirmed?: boolean
  navigation?: {
    message: string
    card?: { title: string; url: string; id: string; description: string }
  }
  clarify?: { message: string; options: Option[] }
  gap?: { message: string; suggestion: string; originalText: string; assistant: string }
  final?: { text: string; kind?: string; unverified: string[] }
  error?: string
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** 把事件序列折叠成界面需要的结构；恢复运行时同一轮会收到第二组事件，按到达顺序覆盖 */
export function viewOf(turn: Turn): TurnView {
  const view: TurnView = { thoughts: [], tools: [], text: '' }
  for (const { type, data } of turn.events) {
    switch (type) {
      case 'run_started':
        view.runId ??= str(data.runId)
        view.traceId = str(data.traceId)
        view.agentName = str(data.agent)
        break
      case 'routing':
        view.routing = data as unknown as Routing
        break
      case 'step':
        if (str(data.text) !== '') view.thoughts = [...view.thoughts, str(data.text)]
        break
      case 'tool_call': {
        // 确认后恢复运行时，同一次调用会以相同的 callId 再报一次，替换原来的卡片
        const step: ToolStep = {
          callId: str(data.callId),
          tool: str(data.tool),
          arguments: (data.arguments ?? {}) as Record<string, unknown>,
        }
        view.tools = view.tools.some((t) => t.callId === step.callId)
          ? view.tools.map((t) => (t.callId === step.callId ? step : t))
          : [...view.tools, step]
        break
      }
      case 'tool_result':
        view.tools = view.tools.map((t) =>
          t.callId === str(data.callId) && t.ok === undefined
            ? { ...t, ok: data.ok === true, summary: str(data.summary) }
            : t,
        )
        break
      case 'confirmation_required':
        view.pending = {
          key: str(data.key),
          title: str(data.title),
          preview: (data.preview ?? {}) as Record<string, unknown>,
        }
        break
      case 'text_delta':
        view.text += str(data.delta)
        break
      case 'navigation':
        view.navigation = data as unknown as TurnView['navigation']
        break
      case 'clarify':
        view.clarify = data as unknown as TurnView['clarify']
        break
      case 'gap':
        view.gap = data as unknown as TurnView['gap']
        break
      case 'final':
        view.final = {
          text: str(data.text),
          kind: typeof data.kind === 'string' ? data.kind : undefined,
          unverified: Array.isArray(data.unverified) ? (data.unverified as string[]) : [],
        }
        if (view.pending !== undefined) view.confirmed = true
        break
      case 'error':
        view.error = str(data.message) || str(data.code)
        break
      default:
        break
    }
  }
  if (turn.error !== undefined) view.error = turn.error
  return view
}

/** 正在等待用户确认：有确认请求，且之后还没有收到最终结果 */
export function awaitingConfirmation(view: TurnView): boolean {
  return view.pending !== undefined && view.final === undefined && view.error === undefined
}
