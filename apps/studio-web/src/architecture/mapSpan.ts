/**
 * span 到架构组件的映射（纯函数，供架构视图回放高亮使用）。
 * 判定依据是运行时实际写入的 span 命名与 eab.* 属性（AgentRuntime、ToolGateway、ModelGateway 等），
 * 每条规则在 mapSpan.test.ts 里有对应用例。映射不到任何组件的 span 返回空集合，回放时只推进时间不高亮。
 */
import type { Span } from '../studio/api'

/** Agent 引用（namespace/name）到应用层组件的归属；远程 Agent 的命名空间以 .remote 结尾 */
function agentLayer(ref: string): string | null {
  const namespace = ref.split('/')[0] ?? ''
  if (namespace.endsWith('.remote')) return 'app-remote'
  if (namespace.startsWith('vendor.')) return 'app-vendor'
  if (namespace.startsWith('tenant.')) return 'app-tenant'
  return null
}

function attr(span: Span, key: string): string | null {
  const value = span.attributes?.[key]
  return typeof value === 'string' && value !== '' ? value : null
}

/** 去掉「前缀 · 」取主体（Agent 引用或名字） */
function subject(span: Span, prefix: string): string {
  return span.name.startsWith(`${prefix} · `) ? span.name.slice(prefix.length + 3) : span.name
}

/** run 根 span：按命名前缀区分统一入口、Agent 运行、外部 Agent 直达、Studio 单独测试 */
function mapRun(span: Span): ReadonlySet<string> {
  if (span.name.startsWith('统一入口')) return new Set(['ux-entry', 'cap-runtime'])
  if (span.name.startsWith('外部 Agent')) {
    return new Set(['ux-entry', 'cap-runtime', 'cap-agent', 'app-remote'])
  }
  if (span.name.startsWith('单独测试')) return new Set(['dev-studio', 'cap-runtime'])
  if (span.name.startsWith('平台工具调用')) return new Set(['dev-studio', 'cap-mcp'])
  if (span.name.startsWith('运行')) {
    const agent = attr(span, 'eab.agent') ?? subject(span, '运行')
    const layer = agentLayer(agent)
    return layer === null
      ? new Set(['ux-entry', 'cap-runtime'])
      : new Set(['ux-entry', 'cap-runtime', layer])
  }
  return new Set(['cap-runtime'])
}

/** 工具与技能：按 eab.tool.source 归属应用层；BO 工具经过鉴权点 3（工具网关）与鉴权点 4（BO API 内裁决），执行期间左翼保持点亮 */
function mapTool(span: Span): ReadonlySet<string> {
  const ids = new Set<string>([span.kind === 'skill' ? 'cap-runtime' : 'cap-mcp'])
  const source = attr(span, 'eab.tool.source')
  if (source !== null) {
    if (source.includes('demo-erp-bo')) {
      ids.add('app-erp')
      ids.add('wing-auth')
    }
    if (source.includes('notify-im')) ids.add('ux-im')
    if (source.startsWith('tenant.')) ids.add('app-tenant')
  }
  return ids
}

/**
 * 一个 span 点亮的架构组件。鉴权点 2、3 是 policy span；鉴权点 1 在入口处、没有对应 span，
 * 由页面在第 0 帧标注；鉴权点 4 在 BO API 内部，并入 BO 工具行并在图例说明。
 */
export function mapSpan(span: Span): ReadonlySet<string> {
  switch (span.kind) {
    case 'run':
      return mapRun(span)
    case 'routing': {
      const ids = new Set(['orc-router'])
      if (attr(span, 'eab.routing.assistant') !== null) ids.add('orc-assistant')
      return ids
    }
    case 'assemble': {
      const layer = agentLayer(subject(span, '组装'))
      return layer === null ? new Set(['cap-runtime']) : new Set(['cap-runtime', layer])
    }
    case 'pipeline':
      return span.name === 'pipeline.retrieval'
        ? new Set(['know-semantic', 'know-docs'])
        : new Set(['cap-model'])
    case 'model':
      return new Set(['cap-model'])
    case 'policy':
      return new Set(['wing-auth'])
    case 'tool':
    case 'skill':
      return mapTool(span)
    case 'workflow':
      return new Set(['orc-workflow'])
    case 'agent': {
      if (span.name.startsWith('A2A')) return new Set(['cap-agent', 'app-remote'])
      const layer = agentLayer(subject(span, '子 Agent'))
      return layer === null ? new Set(['cap-runtime']) : new Set(['cap-runtime', layer])
    }
    case 'guard':
      return span.name.startsWith('约束')
        ? new Set(['wing-guard', 'orc-sod', 'know-semantic'])
        : new Set(['wing-guard', 'orc-sod'])
    case 'hook':
      return new Set(['cap-runtime'])
    case 'confirmation':
      return new Set(['orc-confirm'])
    case 'memory':
      return new Set(['cap-memory'])
    default:
      return new Set()
  }
}

/**
 * 受托运行（业务事件触发，run.mode 为 delegated）没有用户提问的那一步：
 * 事件由 DemoERP 推送、平台按委托鉴权后启动 Agent，前端在回放开头合成这一帧，不伪造后端数据。
 */
export function delegatedPrelude(): ReadonlySet<string> {
  return new Set(['app-erp', 'wing-auth'])
}
