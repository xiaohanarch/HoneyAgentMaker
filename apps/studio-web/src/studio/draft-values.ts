import type { StepKey } from './api'

/**
 * 从草稿资产树里读出某一步的取值，与平台合并草稿的规则对应（docs/studio-api.md 的各步 value）。
 * 向导打开一步时，表单显示的是草稿里已有的内容，而不是推荐。
 */

type Tree = Record<string, unknown>

function obj(value: unknown): Tree {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Tree) : {}
}

function list(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function strings(value: unknown): string[] {
  return list(value).filter((v): v is string => typeof v === 'string')
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** 工具列表里某一类条目的引用，例如 agent、documents、skill */
function toolRefs(tools: unknown[], kind: string): string[] {
  return tools.map((t) => obj(t)[kind]).filter((v): v is string => typeof v === 'string')
}

export interface ToolRef {
  source: string
  name: string
}

function apiTools(tools: unknown[]): ToolRef[] {
  const result: ToolRef[] = []
  for (const entry of tools) {
    const api = obj(obj(entry).api)
    const source = str(api.source)
    if (source === '') continue
    for (const name of strings(api.tools)) result.push({ source, name })
  }
  return result
}

export function valueFromAsset(step: StepKey, asset: Tree | null): Record<string, unknown> {
  const metadata = obj(asset?.metadata)
  const spec = obj(asset?.spec)
  const routing = obj(spec.routing)
  const model = obj(spec.model)
  const tools = list(spec.tools)
  switch (step) {
    case 'purpose':
      return { intent: str(metadata.description) }
    case 'naming':
      return {
        name: str(metadata.name),
        displayName: str(metadata.displayName),
        description: str(metadata.description),
      }
    case 'process':
      return { businessProcess: strings(metadata.businessProcess) }
    case 'collaborators':
      return { agents: toolRefs(tools, 'agent') }
    case 'knowledge':
      return {
        semanticPacks: strings(spec.semanticPacks),
        knowledgeSources: toolRefs(tools, 'documents'),
      }
    case 'tools':
      return { skills: toolRefs(tools, 'skill'), tools: apiTools(tools) }
    case 'triggers':
      return {
        triggers: list(spec.triggers).map((t) => ({ type: str(obj(t).type) })),
        direct: routing.direct === true,
      }
    case 'channels':
      return { channels: strings(spec.channels) }
    case 'instructions':
      return { instructions: str(spec.instructions), constraints: strings(spec.constraints) }
    case 'model':
      return {
        profile: str(model.profile) || 'reasoning',
        temperature: typeof model.temperature === 'number' ? model.temperature : 0.2,
      }
    case 'examples':
      return { examples: strings(routing.examples) }
  }
}

/** 名称、显示名，以及完整引用（namespace/name） */
export function identityOf(asset: Tree | null): { name: string; displayName: string; ref: string } {
  const metadata = obj(asset?.metadata)
  const name = str(metadata.name)
  return {
    name,
    displayName: str(metadata.displayName) || name,
    ref: `${str(metadata.namespace)}/${name}`,
  }
}

/** 列表里切换一项：有则去掉，没有则加上，保持原有顺序 */
export function toggle<T>(items: readonly T[], item: T, same: (a: T, b: T) => boolean): T[] {
  return items.some((i) => same(i, item)) ? items.filter((i) => !same(i, item)) : [...items, item]
}
