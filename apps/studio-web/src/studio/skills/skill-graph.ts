/**
 * 技能步骤到流程图的布局（技能编辑器的 React Flow 预览）。步骤自上而下排列；condition 的 then、else 分两列，
 * parallel 的每个分支一列，分支结束后汇合到下一步。纯函数，便于单元测试。
 */

export interface SkillStep {
  id: string
  type: string
  name?: string
  tool?: string
  skill?: string
  expression?: string
  if?: string
  then?: SkillStep[]
  else?: SkillStep[]
  branches?: SkillStep[][]
  message?: string
  [key: string]: unknown
}

export interface GraphNode {
  id: string
  label: string
  type: string
  detail: string
  x: number
  y: number
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  label?: string
}

export const COLUMN = 240
export const ROW = 90

export function stepDetail(step: SkillStep): string {
  switch (step.type) {
    case 'tool':
      return step.tool ?? ''
    case 'skill':
      return step.skill ?? ''
    case 'transform':
      return step.expression ?? ''
    case 'condition':
      return step.if ?? ''
    case 'notify':
    case 'confirm':
      return step.message ?? ''
    default:
      return ''
  }
}

interface Layout {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** 布局一段步骤，返回这段最后的出口节点（下一步要连到它们）和占用的行数 */
function place(
  steps: readonly SkillStep[],
  column: number,
  row: number,
  entry: { ids: string[]; label?: string },
  out: Layout,
): { exits: string[]; rows: number; width: number } {
  let current = entry
  let r = row
  let width = 1
  for (const step of steps) {
    out.nodes.push({
      id: step.id,
      label: step.name ?? step.id,
      type: step.type,
      detail: stepDetail(step),
      x: column * COLUMN,
      y: r * ROW,
    })
    for (const from of current.ids) {
      out.edges.push({
        id: `${from}->${step.id}`,
        source: from,
        target: step.id,
        label: current.label,
      })
    }
    r += 1
    const branches: { steps: SkillStep[]; label?: string }[] =
      step.type === 'condition'
        ? [
            { steps: step.then ?? [], label: '是' },
            { steps: step.else ?? [], label: '否' },
          ]
        : step.type === 'parallel'
          ? (step.branches ?? []).map((b, i) => ({ steps: b, label: `分支 ${i + 1}` }))
          : []
    if (branches.length === 0) {
      current = { ids: [step.id] }
      continue
    }
    const exits: string[] = []
    let deepest = 0
    let col = column
    for (const branch of branches) {
      if (branch.steps.length === 0) {
        // 空分支（例如没有 else）直接从条件步骤出去
        exits.push(step.id)
        continue
      }
      const placed = place(branch.steps, col, r, { ids: [step.id], label: branch.label }, out)
      exits.push(...placed.exits)
      deepest = Math.max(deepest, placed.rows)
      col += placed.width
    }
    width = Math.max(width, col - column)
    r += deepest
    current = { ids: [...new Set(exits)] }
  }
  return { exits: current.ids, rows: r - row, width }
}

export function layoutSkill(steps: readonly SkillStep[]): Layout {
  const out: Layout = { nodes: [], edges: [] }
  out.nodes.push({ id: '__start', label: '入参', type: 'start', detail: '', x: 0, y: -ROW })
  const placed = place(steps, 0, 0, { ids: ['__start'] }, out)
  out.nodes.push({
    id: '__end',
    label: '结果',
    type: 'end',
    detail: '',
    x: 0,
    y: placed.rows * ROW,
  })
  for (const exit of placed.exits)
    out.edges.push({ id: `${exit}->__end`, source: exit, target: '__end' })
  return out
}

/** 步骤 id 在整棵树里是否唯一（schema 也会检查，编辑器提前提示） */
export function duplicateIds(steps: readonly SkillStep[]): string[] {
  const seen = new Set<string>()
  const dup = new Set<string>()
  const visit = (list: readonly SkillStep[]): void => {
    for (const s of list) {
      if (seen.has(s.id)) dup.add(s.id)
      seen.add(s.id)
      visit(s.then ?? [])
      visit(s.else ?? [])
      for (const b of s.branches ?? []) visit(b)
    }
  }
  visit(steps)
  return [...dup]
}
