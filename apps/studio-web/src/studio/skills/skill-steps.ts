import type { SkillStep } from './skill-graph'

/** 技能里允许的步骤类型（与 eab-spec 的 StepSpecs.SKILL_STEP_TYPES 一致） */
export const SKILL_STEP_TYPES = [
  { value: 'tool', label: '工具' },
  { value: 'skill', label: '技能' },
  { value: 'transform', label: '变换' },
  { value: 'condition', label: '条件' },
  { value: 'confirm', label: '确认' },
  { value: 'notify', label: '通知' },
] as const

const FIELDS: Record<string, readonly string[]> = {
  tool: ['tool', 'source', 'with', 'output'],
  skill: ['skill', 'with', 'output'],
  transform: ['expression', 'output'],
  condition: ['if', 'then', 'else'],
  confirm: ['message'],
  notify: ['message', 'channel'],
}

/** 切换步骤类型：保留 id、名称和新类型用得到的字段，补上必填字段的初值 */
export function newStep(type: string, previous: SkillStep): SkillStep {
  const step: SkillStep = { id: previous.id, type }
  if (previous.name !== undefined && previous.name !== '') step.name = previous.name
  for (const key of FIELDS[type] ?? []) {
    if (previous[key] !== undefined) step[key] = previous[key]
  }
  if (type === 'tool' && step.tool === undefined) step.tool = ''
  if (type === 'skill' && step.skill === undefined) step.skill = ''
  if (type === 'transform' && step.expression === undefined) step.expression = 'input'
  if (type === 'condition') {
    step.if ??= 'true'
    step.then ??= [{ id: `${previous.id}-then`, type: 'transform', expression: 'input' }]
  }
  if ((type === 'confirm' || type === 'notify') && step.message === undefined) step.message = ''
  return step
}

/** 步骤入参（with）的 JSON 文本；空文本表示不传 */
export function parseWith(text: string): { value?: Record<string, unknown>; error?: string } {
  if (text.trim() === '') return {}
  try {
    const value: unknown = JSON.parse(text)
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      return { error: '入参应是 JSON 对象' }
    }
    return { value: value as Record<string, unknown> }
  } catch {
    return { error: '入参不是合法的 JSON' }
  }
}

function spec(asset: Record<string, unknown> | null | undefined): Record<string, unknown> {
  const s = asset?.spec
  return s !== null && typeof s === 'object' ? (s as Record<string, unknown>) : {}
}

export function stepsOf(asset: Record<string, unknown> | null | undefined): SkillStep[] {
  const steps = spec(asset).steps
  return Array.isArray(steps) ? (steps as SkillStep[]) : []
}

/** 按技能的 inputSchema 给出测试入参的样例：有默认值用默认值，否则按类型给空值 */
export function exampleInput(
  asset: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  const schema = spec(asset).inputSchema as
    { properties?: Record<string, { type?: string; default?: unknown }> } | undefined
  const result: Record<string, unknown> = {}
  for (const [key, prop] of Object.entries(schema?.properties ?? {})) {
    if (prop.default !== undefined) result[key] = prop.default
    else if (prop.type === 'number' || prop.type === 'integer') result[key] = 0
    else if (prop.type === 'boolean') result[key] = false
    else if (prop.type === 'array') result[key] = []
    else if (prop.type === 'object') result[key] = {}
    else result[key] = ''
  }
  return result
}
