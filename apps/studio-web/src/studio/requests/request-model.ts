import type { BadgeTone } from '@eab/ui'
import type {
  FitGapVerdict,
  PlanAssetAction,
  PlanDraftKind,
  RequestPlan,
  RequestStatus,
} from '../api'

/** 需求状态的中文标签与色调：提交后的处理进度 */
export const REQUEST_STATUS_VIEWS: Readonly<
  Record<RequestStatus, { label: string; tone: BadgeTone }>
> = {
  submitted: { label: '待处理', tone: 'pending' },
  'in-progress': { label: '处理中', tone: 'info' },
  delivered: { label: '已交付', tone: 'allow' },
  rejected: { label: '已拒绝', tone: 'deny' },
}

/** fit-gap 三档结论的中文标签 */
export const FIT_GAP_VERDICT_VIEWS: Readonly<
  Record<FitGapVerdict, { label: string; tone: BadgeTone }>
> = {
  satisfied: { label: '标品已满足', tone: 'allow' },
  extend: { label: '扩展标品即可', tone: 'info' },
  new: { label: '需要新建', tone: 'pending' },
}

/** 技术规格里资产动作的中文标签 */
export const PLAN_ASSET_ACTION_VIEWS: Readonly<
  Record<PlanAssetAction, { label: string; tone: BadgeTone }>
> = {
  reuse: { label: '复用', tone: 'neutral' },
  create: { label: '新建', tone: 'info' },
}

/** 方案里资产草稿的种类 */
export const PLAN_DRAFT_KIND_LABELS: Readonly<Record<PlanDraftKind, string>> = {
  Skill: '技能',
  AgentExtension: '标品扩展',
}

/** 方案的生成方式 */
export const PLAN_DECIDED_BY_LABELS: Readonly<Record<'model' | 'retrieval', string>> = {
  model: '模型生成',
  retrieval: '检索兜底',
}

/** 引用按最后一个斜杠拆成命名空间与名称：tenant.t001.requests/req-x → tenant.t001.requests + req-x */
export function requestRefParts(ref: string): { namespace: string; name: string } | null {
  const at = ref.lastIndexOf('/')
  if (at <= 0 || at === ref.length - 1) return null
  return { namespace: ref.slice(0, at), name: ref.slice(at + 1) }
}

/** 需求详情页的路由；引用不成形时返回 null，列表里退化为纯文本 */
export function requestPath(ref: string): string | null {
  const parts = requestRefParts(ref)
  if (parts === null) return null
  return `/studio/requests/${encodeURIComponent(parts.namespace)}/${encodeURIComponent(parts.name)}`
}

/** 匹配度 0~1 显示成百分比 */
export function scoreLabel(score: number): string {
  return `${Math.round(score * 100)}%`
}

/** 时间显示；解析失败时原样返回 */
export function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

/** 用户对方案卡的修改：YAML 文本可以直接编辑，drafts 的键是草稿下标 */
export interface PlanEdits {
  drafts: Record<number, string>
  evalSuite: string | null
}

export function emptyPlanEdits(): PlanEdits {
  return { drafts: {}, evalSuite: null }
}

/** 合并用户修改后的方案：只替换编辑过的 YAML，其余保持服务端的取值 */
export function applyPlanEdits(plan: RequestPlan, edits: PlanEdits): RequestPlan {
  return {
    ...plan,
    drafts: plan.drafts.map((draft, index) => {
      const yaml = edits.drafts[index]
      return yaml === undefined ? draft : { ...draft, yaml }
    }),
    evalSuite:
      plan.evalSuite !== null && edits.evalSuite !== null
        ? { ...plan.evalSuite, yaml: edits.evalSuite }
        : plan.evalSuite,
  }
}

/** 是否有未保存的修改；编辑后又改回原文的不算 */
export function planHasChanges(plan: RequestPlan, edits: PlanEdits): boolean {
  const draftChanged = plan.drafts.some(
    (draft, index) => edits.drafts[index] !== undefined && edits.drafts[index] !== draft.yaml,
  )
  const evalChanged =
    plan.evalSuite !== null && edits.evalSuite !== null && edits.evalSuite !== plan.evalSuite.yaml
  return draftChanged || evalChanged
}
