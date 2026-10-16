import type { EffectiveSegment, ExtensionPoint, RedactedText } from './api'

/** 扩展编辑器的分页与扩展点类型的对应（主提示词 5.3 节） */
export const SECTIONS = [
  {
    key: 'instructions',
    point: 'instructions.append',
    label: '指令追加',
    hint: '追加业务指令和约束，不能覆盖或删除标品指令',
  },
  { key: 'tools', point: 'tools.add', label: '工具', hint: '增加工具，不能删除或替换标品工具' },
  { key: 'skills', point: 'skills.add', label: '技能', hint: '增加本企业的技能' },
  { key: 'knowledge', point: 'knowledge.add', label: '知识', hint: '增加知识源或语义包' },
  {
    key: 'hooks',
    point: 'hooks',
    label: '钩子',
    hint: '在指定事件上挂工作流或技能，失败不影响标品主流程',
  },
  { key: 'approvals', point: 'approvals.add', label: '确认点', hint: '增加人工确认点，只能更严格' },
  { key: 'model', point: 'model.select', label: '模型档位', hint: '在标品允许的档位里选择' },
] as const

export type SectionKey = (typeof SECTIONS)[number]['key']

export const HOOK_EVENTS = ['beforeToolCall', 'afterToolCall', 'onResult', 'onFailure'] as const

export interface HookBinding {
  kind: 'workflow' | 'skill'
  ref: string
  when: string
}

export interface ApprovalRow {
  tool: string
  policy: 'require-confirmation' | 'require-approval'
  description: string
}

export interface SectionValues {
  instructions: { append: string; constraints: string[] }
  tools: string[]
  skills: string[]
  knowledge: string[]
  hooks: Partial<Record<(typeof HOOK_EVENTS)[number], HookBinding>>
  approvals: ApprovalRow[]
  model: string
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
}

/** 从扩展草稿（YAML 解析出的树）读出各页的当前取值 */
export function sectionValues(asset: Record<string, unknown> | null | undefined): SectionValues {
  const spec = record(record(asset).spec)
  const hooks: SectionValues['hooks'] = {}
  for (const event of HOOK_EVENTS) {
    const raw = spec.hooks === undefined ? undefined : record(spec.hooks)[event]
    const binding = Array.isArray(raw) ? record(raw[0]) : record(raw)
    const workflow = typeof binding.workflow === 'string' ? binding.workflow : undefined
    const skill = typeof binding.skill === 'string' ? binding.skill : undefined
    if (workflow === undefined && skill === undefined) continue
    hooks[event] = {
      kind: workflow !== undefined ? 'workflow' : 'skill',
      ref: workflow ?? skill ?? '',
      when: typeof binding.when === 'string' ? binding.when : '',
    }
  }
  const approvals = (
    Array.isArray(record(spec.approvals).add) ? (record(spec.approvals).add as unknown[]) : []
  )
    .map(record)
    .map((a) => ({
      tool: typeof a.tool === 'string' ? a.tool : '',
      policy:
        a.policy === 'require-approval'
          ? ('require-approval' as const)
          : ('require-confirmation' as const),
      description: typeof a.description === 'string' ? a.description : '',
    }))
  return {
    instructions: {
      append:
        typeof record(spec.instructions).append === 'string'
          ? (record(spec.instructions).append as string)
          : '',
      constraints: strings(record(spec.constraints).append),
    },
    tools: strings(record(spec.tools).add),
    skills: strings(record(spec.skills).add),
    knowledge: strings(record(spec.knowledge).add),
    hooks,
    approvals,
    model:
      typeof record(spec.model).profile === 'string' ? (record(spec.model).profile as string) : '',
  }
}

/** 一页取值转成接口要的 value */
export function sectionPayload(key: SectionKey, values: SectionValues): Record<string, unknown> {
  switch (key) {
    case 'instructions':
      return {
        append: values.instructions.append,
        constraints: values.instructions.constraints.map((c) => c.trim()).filter((c) => c !== ''),
      }
    case 'tools':
    case 'skills':
    case 'knowledge':
      return { add: values[key] }
    case 'approvals':
      return {
        add: values.approvals
          .filter((a) => a.tool.trim() !== '')
          .map((a) => ({
            tool: a.tool,
            policy: a.policy,
            ...(a.description.trim() === '' ? {} : { description: a.description }),
          })),
      }
    case 'hooks': {
      const value: Record<string, unknown> = {}
      for (const [event, binding] of Object.entries(values.hooks)) {
        if (binding === undefined || binding.ref.trim() === '') continue
        value[event] = {
          [binding.kind]: binding.ref,
          ...(binding.when.trim() === '' ? {} : { when: binding.when }),
        }
      }
      return value
    }
    case 'model':
      return { profile: values.model }
  }
}

/** 标品声明了哪些扩展点；没声明的页面只读，并说明原因 */
export function declaredPoint(
  points: readonly ExtensionPoint[] | null | undefined,
  key: SectionKey,
): ExtensionPoint | undefined {
  const section = SECTIONS.find((s) => s.key === key)
  return (points ?? []).find((p) => p.type === section?.point)
}

export function isRedacted(text: EffectiveSegment['text']): text is RedactedText {
  return typeof text === 'object' && text !== null && '$redacted' in text
}

/** 有效定义里的片段按来源分三类：锁住的标品部分、租户部分、其他（平台规则、语义包指令） */
export function segmentTone(segment: EffectiveSegment): 'locked' | 'tenant' | 'other' {
  if (isRedacted(segment.text)) return 'locked'
  if (segment.layer === 'tenant') return 'tenant'
  return 'other'
}

const KIND_LABELS: Record<string, string> = {
  'platform-rule': '平台安全规则',
  'vendor-instructions': '标品指令',
  'vendor-context': '标品补充上下文',
  'vendor-constraint': '标品约束',
  'semantic-instruction': '语义包指令',
  'tenant-instructions': '租户追加指令',
  'tenant-constraint': '租户追加约束',
}

export function segmentKindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? kind
}
