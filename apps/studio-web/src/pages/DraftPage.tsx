import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { cn } from '@eab/ui'
import { Avatar } from '../studio/Avatar'
import { identityOf } from '../studio/draft-values'
import { STEPS, stepAt, stepIndex } from '../studio/steps'
import { draftKey, errorText, useDraft } from '../studio/use-studio'
import { EvalStep } from '../studio/wizard/EvalStep'
import { PublishStep } from '../studio/wizard/PublishStep'
import { RecommendStep } from '../studio/wizard/RecommendStep'
import { StepNav } from '../studio/wizard/StepNav'
import { TestStep } from '../studio/wizard/TestStep'
import { YamlPanel } from '../studio/wizard/YamlPanel'

/** 新建 Agent 向导（主提示词 10.2 节）：左侧步骤导航，中间是当前一步，右侧是实时 YAML */
export function DraftPage(): ReactNode {
  const { id = '', step } = useParams()
  const client = useQueryClient()
  const { draft, error, setDraft } = useDraft(id)
  const index = stepIndex(step)
  const current = stepAt(index)

  if (error !== null) {
    return (
      <p className="text-sm text-deny" data-testid="draft-error">
        草稿读取失败：{errorText(error)}
      </p>
    )
  }
  if (draft === undefined) return <p className="text-sm text-ink-muted">读取草稿中…</p>

  const identity = identityOf(draft.asset)
  const showYaml = current.recommend !== undefined

  return (
    <div className="space-y-4">
      <header className="flex items-center gap-3">
        <Link
          to="/studio"
          aria-label="返回 Studio"
          className="rounded-md p-1 text-ink-muted hover:bg-surface-sunken hover:text-ink"
        >
          <ArrowLeft className="size-4" />
        </Link>
        <Avatar name={identity.name || draft.draft.name} className="size-10" />
        <div className="min-w-0">
          <h1 className="truncate text-lg font-semibold tracking-tight" data-testid="draft-title">
            {identity.displayName || draft.draft.intent}
          </h1>
          <p className="truncate text-xs text-ink-muted">{draft.draft.intent}</p>
        </div>
        <span className="ml-auto text-xs text-ink-faint">
          {`第 ${index + 1} / ${STEPS.length} 步 · ${current.hint}`}
        </span>
      </header>

      <div
        className={cn(
          'grid items-start gap-4',
          showYaml
            ? 'grid-cols-[13rem_minmax(0,1fr)_minmax(0,22rem)]'
            : 'grid-cols-[13rem_minmax(0,1fr)]',
        )}
      >
        <StepNav draftId={id} reached={draft.draft.step} />
        <main className="min-w-0" data-testid={`step-${current.key}`}>
          <h2 className="mb-3 text-base font-semibold">{`${index + 1}. ${current.label}`}</h2>
          {current.recommend !== undefined ? (
            <RecommendStep
              key={current.key}
              view={draft}
              step={current.recommend}
              onSaved={setDraft}
            />
          ) : current.key === 'evals' ? (
            <EvalStep view={draft} onSaved={setDraft} />
          ) : current.key === 'test' ? (
            <TestStep view={draft} />
          ) : (
            <PublishStep
              view={draft}
              onPublished={() => void client.invalidateQueries({ queryKey: draftKey(id) })}
            />
          )}
        </main>
        {showYaml && <YamlPanel view={draft} onSaved={setDraft} />}
      </div>
    </div>
  )
}
