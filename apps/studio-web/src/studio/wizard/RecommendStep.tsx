import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Check, LoaderCircle, RefreshCw, Sparkles } from 'lucide-react'
import type { CatalogRule, DraftView, Recommendation, StepKey } from '../api'
import { valueFromAsset } from '../draft-values'
import { STEPS, stepIndex } from '../steps'
import { errorText, useCatalog, useStudioApi } from '../use-studio'
import { StepEditor } from './editors'

function Badge({ decidedBy }: { decidedBy: Recommendation['decidedBy'] }): ReactNode {
  return decidedBy === 'model' ? (
    <span className="rounded bg-external-soft px-1.5 text-[10px] text-external">模型推荐</span>
  ) : (
    <span className="rounded bg-surface-sunken px-1.5 text-[10px] text-ink-muted">检索推荐</span>
  )
}

/** 推荐取值的简短摘要，展示在 AI 推荐区 */
export function summarize(value: Record<string, unknown>): string[] {
  const lines: string[] = []
  for (const [key, v] of Object.entries(value)) {
    if (key === 'similar') continue
    if (Array.isArray(v)) {
      const items = v.map((i) =>
        typeof i === 'string'
          ? i
          : typeof i === 'object' && i !== null
            ? String(
                (i as Record<string, unknown>).name ?? (i as Record<string, unknown>).type ?? '',
              )
            : String(i),
      )
      lines.push(`${key}：${items.length === 0 ? '（无）' : items.join('、')}`)
    } else if (typeof v === 'string') {
      lines.push(`${key}：${v.length > 120 ? `${v.slice(0, 120)}…` : v}`)
    } else if (v !== undefined && v !== null) {
      lines.push(`${key}：${String(v)}`)
    }
  }
  return lines
}

/**
 * 有“AI 推荐”的一步：上方是推荐区（理由、推荐方式、一键采纳），下方是可以改的表单。
 * 表单初值取自草稿里已有的内容；保存（含一键采纳）后按保存后的草稿刷新表单。
 */
export function RecommendStep({
  view,
  step,
  onSaved,
}: {
  view: DraftView
  step: StepKey
  onSaved: (view: DraftView) => void
}): ReactNode {
  const { api, ready } = useStudioApi()
  const { data: catalog } = useCatalog()
  const [value, setValue] = useState<Record<string, unknown>>(() =>
    valueFromAsset(step, view.asset),
  )
  const [state, setState] = useState<{ busy: boolean; error?: string; saved?: boolean }>({
    busy: false,
  })
  const rec = useQuery({
    queryKey: ['studio', 'recommend', view.draft.id, step],
    queryFn: () => api.recommend(view.draft.id, step),
    enabled: ready,
    staleTime: Infinity,
    retry: false,
  })

  const save = (next: Record<string, unknown>): void => {
    setState({ busy: true })
    api.apply(view.draft.id, step, next).then(
      (saved) => {
        setValue(valueFromAsset(step, saved.asset))
        setState({ busy: false, saved: true })
        onSaved(saved)
      },
      (error: unknown) => setState({ busy: false, error: errorText(error) }),
    )
  }

  // 指令一步的约束候选来自已选语义包的规则；推荐不可用时从能力清单里取
  const selectedPacks = (valueFromAsset('knowledge', view.asset).semanticPacks as string[]) ?? []
  const fallbackRules: CatalogRule[] = (catalog?.semanticPacks ?? [])
    .filter((p) => selectedPacks.includes(p.ref))
    .flatMap((p) => p.rules)
  const candidates =
    rec.data?.candidates ?? (step === 'instructions' ? { rules: fallbackRules } : undefined)
  const next = STEPS[stepIndex(STEPS.find((s) => s.recommend === step)?.key) + 1]

  return (
    <div className="space-y-4">
      <section
        className="rounded-xl border border-external/30 bg-external-soft/30 px-4 py-3"
        data-testid="ai-recommendation"
      >
        <header className="flex items-center gap-2 text-sm font-medium">
          <Sparkles aria-hidden className="size-4 text-external" />
          AI 推荐
          {rec.data !== undefined && <Badge decidedBy={rec.data.decidedBy} />}
          <button
            type="button"
            onClick={() => void rec.refetch()}
            disabled={rec.isFetching}
            className="ml-auto inline-flex items-center gap-1 text-xs font-normal text-ink-muted hover:text-ink disabled:opacity-50"
          >
            <RefreshCw aria-hidden className="size-3" />
            重新推荐
          </button>
        </header>
        {rec.isFetching ? (
          <p className="mt-2 flex items-center gap-1.5 text-xs text-ink-muted">
            <LoaderCircle aria-hidden className="size-3.5 animate-spin" />
            正在生成推荐…
          </p>
        ) : rec.error !== null ? (
          <p className="mt-2 text-xs text-deny">推荐失败：{errorText(rec.error)}</p>
        ) : rec.data !== undefined ? (
          <div className="mt-2 space-y-2 text-xs">
            <p className="text-ink-muted">{rec.data.reason}</p>
            <ul className="space-y-0.5 font-mono text-[11px] text-ink">
              {summarize(rec.data.value).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
            <button
              type="button"
              disabled={state.busy}
              onClick={() => save(rec.data.value)}
              className="inline-flex items-center gap-1 rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              <Check aria-hidden className="size-3.5" />
              一键采纳
            </button>
          </div>
        ) : null}
      </section>

      <section className="rounded-xl border border-line bg-surface px-4 py-4">
        <StepEditor
          step={step}
          value={value}
          onChange={setValue}
          candidates={candidates}
          catalog={catalog}
        />
        <div className="mt-4 flex items-center gap-3 border-t border-line pt-3">
          <button
            type="button"
            disabled={state.busy}
            onClick={() => save(value)}
            className="rounded-md border border-line-strong px-3 py-1.5 text-xs font-medium transition-colors hover:bg-surface-sunken disabled:opacity-50"
          >
            保存这一步
          </button>
          {state.saved === true && <span className="text-xs text-allow">已保存</span>}
          {state.error !== undefined && <span className="text-xs text-deny">{state.error}</span>}
          {next !== undefined && (
            <Link
              to={`/studio/drafts/${view.draft.id}/${next.key}`}
              className="ml-auto inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
            >
              下一步：{next.label}
              <ArrowRight aria-hidden className="size-3.5" />
            </Link>
          )}
        </div>
      </section>
    </div>
  )
}
