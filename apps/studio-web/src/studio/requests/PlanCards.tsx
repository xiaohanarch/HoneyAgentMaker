import { useState, type ReactNode } from 'react'
import { Eye, PencilLine } from 'lucide-react'
import { ToneBadge, cn } from '@eab/ui'
import type { RequestPlan } from '../api'
import {
  FIT_GAP_VERDICT_VIEWS,
  PLAN_ASSET_ACTION_VIEWS,
  PLAN_DRAFT_KIND_LABELS,
  scoreLabel,
  type PlanEdits,
} from './request-model'

function PlanCard({
  index,
  title,
  testId,
  className,
  children,
}: {
  index: number
  title: string
  testId: string
  className?: string
  children: ReactNode
}): ReactNode {
  return (
    <section
      className={cn('min-w-0 rounded-xl border border-line bg-surface', className)}
      data-testid={testId}
    >
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <span className="flex size-5 shrink-0 items-center justify-center rounded-md bg-surface-sunken font-mono text-[11px] text-ink-muted">
          {index}
        </span>
        {title}
      </header>
      <div className="px-4 py-3">{children}</div>
    </section>
  )
}

/**
 * YAML 草稿的只读预览与编辑切换。修改保存在父组件的 edits 里，统一由“保存修改”提交；
 * 重新生成后用 key 换新实例，回到只读预览。
 */
function YamlText({
  value,
  edited,
  readOnly,
  onChange,
}: {
  value: string
  edited: boolean
  readOnly: boolean
  onChange: (next: string) => void
}): ReactNode {
  const [editing, setEditing] = useState(false)
  const active = !readOnly && editing
  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-2 px-3 py-1 text-[11px]">
        {edited && <span className="text-pending">已修改，点“保存修改”后生效</span>}
        {!readOnly && (
          <button
            type="button"
            onClick={() => setEditing(!active)}
            className="ml-auto inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-ink-muted hover:bg-surface-sunken"
          >
            {active ? (
              <Eye aria-hidden className="size-3" />
            ) : (
              <PencilLine aria-hidden className="size-3" />
            )}
            {active ? '预览' : '编辑'}
          </button>
        )}
      </div>
      {active ? (
        <textarea
          aria-label="编辑 YAML"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          spellCheck={false}
          className="min-h-40 block w-full resize-y rounded-b-lg bg-canvas px-3 py-2 font-mono text-[11px] leading-relaxed outline-none"
        />
      ) : (
        <pre className="max-h-80 overflow-auto px-3 pb-2 font-mono text-[11px] leading-relaxed text-ink">
          {value}
        </pre>
      )}
    </div>
  )
}

interface PlanCardsProps {
  plan: RequestPlan
  /** 用户编辑过的 YAML；没有编辑过的草稿不在其中 */
  edits: PlanEdits
  /** 已交付或已拒绝的需求只读 */
  readOnly: boolean
  onEdit: (edits: PlanEdits) => void
}

/** 六步方案卡：意图澄清、意图分析与 fit-gap、需求摘要、技术规格、资产草稿、评测集草稿 */
export function PlanCards({ plan, edits, readOnly, onEdit }: PlanCardsProps): ReactNode {
  const verdict = FIT_GAP_VERDICT_VIEWS[plan.fitGap.verdict]
  return (
    <div className="grid items-start gap-4 lg:grid-cols-2">
      <PlanCard index={1} title="意图澄清" testId="plan-clarifications">
        {plan.clarifications.length === 0 ? (
          <p className="text-xs text-ink-muted">没有需要澄清的问题。</p>
        ) : (
          <ul className="space-y-2.5">
            {plan.clarifications.map((item, i) => (
              <li key={i}>
                <p className="text-sm leading-relaxed">{item.question}</p>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {item.options.map((option, j) => (
                    <span
                      key={j}
                      className="rounded-md border border-line bg-canvas px-2 py-0.5 text-xs text-ink-muted"
                    >
                      {option}
                    </span>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )}
      </PlanCard>

      <PlanCard index={2} title="意图分析与 fit-gap" testId="plan-fit-gap">
        <p className="flex flex-wrap items-center gap-2">
          <ToneBadge tone={verdict.tone}>{verdict.label}</ToneBadge>
          <span className="text-xs text-ink-muted">匹配度 {scoreLabel(plan.fitGap.score)}</span>
        </p>
        {plan.fitGap.matched.length > 0 && (
          <ul className="mt-2.5 space-y-1.5">
            {plan.fitGap.matched.map((match, i) => (
              <li key={i} className="text-xs leading-relaxed">
                <span className="break-all font-mono text-ink-muted">{match.ref}</span>
                <span className="ml-1.5">{match.reason}</span>
              </li>
            ))}
          </ul>
        )}
        <p className="mt-2.5 text-xs leading-relaxed text-ink-muted">{plan.fitGap.reason}</p>
      </PlanCard>

      <PlanCard index={3} title="需求摘要" testId="plan-summary">
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-2">
          <dt className="text-xs text-ink-faint">用户</dt>
          <dd className="text-sm leading-relaxed">{plan.summary.user}</dd>
          <dt className="text-xs text-ink-faint">场景</dt>
          <dd className="text-sm leading-relaxed">{plan.summary.scenario}</dd>
          <dt className="text-xs text-ink-faint">指标</dt>
          <dd className="text-sm leading-relaxed">
            {plan.summary.metrics.length === 0 ? '无' : plan.summary.metrics.join('；')}
          </dd>
          <dt className="text-xs text-ink-faint">边界</dt>
          <dd className="text-sm leading-relaxed">{plan.summary.boundaries}</dd>
        </dl>
      </PlanCard>

      <PlanCard index={4} title="技术规格" testId="plan-assets">
        {plan.assets.length === 0 ? (
          <p className="text-xs text-ink-muted">没有列出的资产。</p>
        ) : (
          <ul className="divide-y divide-line">
            {plan.assets.map((asset, i) => {
              const action = PLAN_ASSET_ACTION_VIEWS[asset.action]
              return (
                <li key={i} className="py-2 first:pt-0 last:pb-0">
                  <p className="flex flex-wrap items-center gap-2">
                    <ToneBadge tone={action.tone}>{action.label}</ToneBadge>
                    <span className="font-mono text-xs text-ink-muted">{asset.kind}</span>
                    <span className="min-w-0 flex-1 break-all font-mono text-xs">{asset.ref}</span>
                  </p>
                  {asset.note !== '' && (
                    <p className="mt-1 text-xs leading-relaxed text-ink-muted">{asset.note}</p>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </PlanCard>

      <PlanCard index={5} title="资产草稿" testId="plan-drafts" className="lg:col-span-2">
        {plan.drafts.length === 0 ? (
          <p className="text-xs text-ink-muted">没有资产草稿。</p>
        ) : (
          <ul className="space-y-3">
            {plan.drafts.map((draft, i) => {
              const value = edits.drafts[i] ?? draft.yaml
              const edited = edits.drafts[i] !== undefined && edits.drafts[i] !== draft.yaml
              return (
                <li
                  key={i}
                  className="min-w-0 rounded-lg border border-line bg-canvas"
                  data-testid="plan-draft"
                >
                  <p className="flex items-center gap-2 border-b border-line px-3 py-1.5 text-xs">
                    <ToneBadge tone="neutral">{PLAN_DRAFT_KIND_LABELS[draft.kind]}</ToneBadge>
                    <span className="text-ink-faint">草稿 {i + 1}</span>
                  </p>
                  <YamlText
                    key={`${plan.generatedAt}-${i}`}
                    value={value}
                    edited={edited}
                    readOnly={readOnly}
                    onChange={(next) =>
                      onEdit({ ...edits, drafts: { ...edits.drafts, [i]: next } })
                    }
                  />
                </li>
              )
            })}
          </ul>
        )}
      </PlanCard>

      <PlanCard index={6} title="评测集草稿" testId="plan-eval-suite" className="lg:col-span-2">
        {plan.evalSuite === null ? (
          <p className="text-xs text-ink-muted">无</p>
        ) : (
          <div className="min-w-0 rounded-lg border border-line bg-canvas">
            <p className="border-b border-line px-3 py-1.5 font-mono text-xs text-ink-muted">
              {plan.evalSuite.name}
            </p>
            <YamlText
              key={plan.generatedAt}
              value={edits.evalSuite ?? plan.evalSuite.yaml}
              edited={edits.evalSuite !== null && edits.evalSuite !== plan.evalSuite.yaml}
              readOnly={readOnly}
              onChange={(next) => onEdit({ ...edits, evalSuite: next })}
            />
          </div>
        )}
      </PlanCard>
    </div>
  )
}
