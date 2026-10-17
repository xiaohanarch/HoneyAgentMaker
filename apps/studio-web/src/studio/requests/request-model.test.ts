import { describe, expect, test } from 'vitest'
import type { RequestPlan } from '../api'
import {
  FIT_GAP_VERDICT_VIEWS,
  PLAN_ASSET_ACTION_VIEWS,
  PLAN_DRAFT_KIND_LABELS,
  REQUEST_STATUS_VIEWS,
  applyPlanEdits,
  emptyPlanEdits,
  formatTime,
  planHasChanges,
  requestPath,
  requestRefParts,
  scoreLabel,
} from './request-model'

const PLAN: RequestPlan = {
  decidedBy: 'model',
  generatedAt: '2026-10-16T08:00:00Z',
  clarifications: [{ question: '补货时只考虑认证备选供应商吗？', options: ['是', '否'] }],
  fitGap: {
    verdict: 'extend',
    score: 0.72,
    matched: [{ ref: 'vendor.pur/purchase-order-clerk', reason: '已有采购订单处理能力' }],
    reason: '缺少备选供应商查询，可以经扩展补齐',
  },
  summary: {
    user: '采购经理',
    scenario: '交付异常时从备选供应商补货',
    metrics: ['补货时长'],
    boundaries: '金额超过 10 万元的请购单草稿必须人工审批',
  },
  assets: [
    {
      action: 'create',
      kind: 'Skill',
      ref: 'tenant.t001.skills/alternative-supplier-lookup',
      note: '查询订单缺口与有现货的认证备选供应商',
    },
  ],
  drafts: [
    { kind: 'Skill', yaml: 'apiVersion: eab.dev/v1\nkind: Skill\n' },
    { kind: 'AgentExtension', yaml: 'apiVersion: eab.dev/v1\nkind: AgentExtension\n' },
  ],
  evalSuite: {
    name: 'purchase-clerk-ext-smoke',
    yaml: 'apiVersion: eab.dev/v1\nkind: EvalSuite\n',
  },
}

describe('requestRefParts / requestPath', () => {
  test('引用按最后一个斜杠拆成命名空间与名称，路径指向需求详情页', () => {
    expect(requestRefParts('tenant.t001.requests/req-202610161234561234')).toEqual({
      namespace: 'tenant.t001.requests',
      name: 'req-202610161234561234',
    })
    expect(requestPath('tenant.t001.requests/req-1')).toBe(
      '/studio/requests/tenant.t001.requests/req-1',
    )
  })

  test('不成形的引用返回 null，不生成链接', () => {
    expect(requestRefParts('req-1')).toBeNull()
    expect(requestRefParts('tenant.t001.requests/')).toBeNull()
    expect(requestRefParts('/req-1')).toBeNull()
    expect(requestPath('req-1')).toBeNull()
  })
})

describe('applyPlanEdits / planHasChanges', () => {
  test('只替换编辑过的资产草稿 YAML，评测集保持不动；没有修改时不可保存', () => {
    const edits = emptyPlanEdits()
    expect(planHasChanges(PLAN, edits)).toBe(false)

    const edited = { ...edits, drafts: { ...edits.drafts, 1: 'kind: AgentExtension\n# 补充\n' } }
    const merged = applyPlanEdits(PLAN, edited)
    expect(merged.drafts[0]).toEqual(PLAN.drafts[0])
    expect(merged.drafts[1]?.kind).toBe('AgentExtension')
    expect(merged.drafts[1]?.yaml).toBe('kind: AgentExtension\n# 补充\n')
    expect(merged.evalSuite).toEqual(PLAN.evalSuite)
    expect(planHasChanges(PLAN, edited)).toBe(true)
  })

  test('评测集只改 YAML、名称保留；没有评测集时编辑不生效', () => {
    const withEval = { ...emptyPlanEdits(), evalSuite: 'kind: EvalSuite\n# 补充\n' }
    expect(applyPlanEdits(PLAN, withEval).evalSuite).toEqual({
      name: 'purchase-clerk-ext-smoke',
      yaml: 'kind: EvalSuite\n# 补充\n',
    })
    expect(planHasChanges(PLAN, withEval)).toBe(true)

    const noEvalSuite: RequestPlan = { ...PLAN, evalSuite: null }
    const editWithoutEval = { ...emptyPlanEdits(), evalSuite: 'kind: EvalSuite\n' }
    expect(applyPlanEdits(noEvalSuite, editWithoutEval).evalSuite).toBeNull()
  })

  test('编辑后又改回原文不算有修改', () => {
    const reverted = { ...emptyPlanEdits(), drafts: { 0: PLAN.drafts[0]?.yaml ?? '' } }
    expect(planHasChanges(PLAN, reverted)).toBe(false)
  })
})

describe('标签映射', () => {
  test('需求状态、fit-gap 结论、资产动作、草稿种类都有中文标签', () => {
    expect(Object.values(REQUEST_STATUS_VIEWS).map((s) => s.label)).toEqual([
      '待处理',
      '处理中',
      '已交付',
      '已拒绝',
    ])
    expect(FIT_GAP_VERDICT_VIEWS.satisfied.label).toBe('标品已满足')
    expect(FIT_GAP_VERDICT_VIEWS.extend.label).toBe('扩展标品即可')
    expect(FIT_GAP_VERDICT_VIEWS.new.label).toBe('需要新建')
    expect(PLAN_ASSET_ACTION_VIEWS.reuse.label).toBe('复用')
    expect(PLAN_ASSET_ACTION_VIEWS.create.label).toBe('新建')
    expect(PLAN_DRAFT_KIND_LABELS.Skill).toBe('技能')
    expect(PLAN_DRAFT_KIND_LABELS.AgentExtension).toBe('标品扩展')
  })

  test('匹配度显示成百分比；时间解析失败时原样返回', () => {
    expect(scoreLabel(0.72)).toBe('72%')
    expect(scoreLabel(1)).toBe('100%')
    expect(scoreLabel(0)).toBe('0%')
    expect(formatTime('不是时间')).toBe('不是时间')
  })
})
