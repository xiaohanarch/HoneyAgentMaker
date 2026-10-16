import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Lock } from 'lucide-react'
import { SourceBadge, cn } from '@eab/ui'
import type { DraftView } from '../api'
import { errorText, useDraft } from '../use-studio'
import { TestStep } from '../wizard/TestStep'
import { YamlPanel } from '../wizard/YamlPanel'
import type { ExtendApi } from './api'
import { EffectivePanel } from './EffectivePreview'
import { ExtensionRelease } from './ExtensionRelease'
import { SECTIONS, declaredPoint, sectionValues, type SectionKey } from './extension-values'
import { SectionEditor } from './SectionEditor'
import { bareRef, useExtendApi } from './use-extend'

type Tab = SectionKey | 'effective' | 'test' | 'release'

/** 故事 B 的测试话术 */
const STORY_B_PROMPT = '本月有哪些高风险交易异常'

function effectiveKey(id: string): readonly unknown[] {
  return ['studio', 'extension-effective', id]
}

/** 候选引用：工具中心里本租户可用的工具源和技能 */
function useSuggestions(api: ExtendApi, ready: boolean) {
  const { data } = useQuery({
    queryKey: ['studio', 'tools'],
    queryFn: () => api.tools(),
    enabled: ready,
    staleTime: 30_000,
  })
  const tools = (data?.sources ?? []).filter((s) => s.layer === 'tenant').map((s) => s.ref)
  const skills = (data?.skills ?? []).map((s) => s.ref)
  return { tools, skills, knowledge: [] as string[], hooks: skills }
}

function Effective({ id }: { id: string }): ReactNode {
  const { api, ready } = useExtendApi()
  const { data, error } = useQuery({
    queryKey: effectiveKey(id),
    queryFn: () => api.effective(id),
    enabled: ready,
  })
  if (error !== null) return <p className="text-xs text-deny">{errorText(error)}</p>
  if (data === undefined) return <p className="text-xs text-ink-muted">组装中…</p>
  return (
    <div className="space-y-3">
      {data.issues.length > 0 && (
        <ul className="space-y-1 text-xs text-deny" data-testid="assembly-issues">
          {data.issues.map((i, n) => (
            <li key={n}>
              <span className="font-mono">{i.code} </span>
              {i.message}
            </li>
          ))}
        </ul>
      )}
      {data.view !== undefined && <EffectivePanel view={data.view} />}
    </div>
  )
}

function Editor({
  view,
  setDraft,
}: {
  view: DraftView
  setDraft: (v: DraftView) => void
}): ReactNode {
  const { api, ready } = useExtendApi()
  const client = useQueryClient()
  const id = view.draft.id
  const target = bareRef(
    String((view.asset?.spec as { target?: string } | undefined)?.target ?? ''),
  )
  const [tab, setTab] = useState<Tab>('instructions')
  const vendor = useQuery({
    queryKey: ['studio', 'vendor-agent', target],
    queryFn: () => api.vendorAgent(target),
    enabled: ready && target !== '',
  })
  const suggestions = useSuggestions(api, ready)
  const points = vendor.data?.summary.extensionPoints
  const values = sectionValues(view.asset)

  const changed = (next: DraftView): void => {
    setDraft(next)
    void client.invalidateQueries({ queryKey: effectiveKey(id) })
  }
  const tabs: { key: Tab; label: string; disabled?: boolean }[] = [
    ...SECTIONS.map((s) => ({
      key: s.key,
      label: s.label,
      disabled: declaredPoint(points, s.key) === undefined,
    })),
    { key: 'effective', label: '有效定义' },
    { key: 'test', label: '测试' },
    { key: 'release', label: '评测与发布' },
  ]
  const section = SECTIONS.find((s) => s.key === tab)
  const point = section === undefined ? undefined : declaredPoint(points, section.key)

  return (
    <div className="grid grid-cols-[1fr_26rem] gap-5">
      <div className="min-w-0 space-y-4">
        <nav className="flex flex-wrap gap-1 border-b border-line" aria-label="扩展编辑">
          {tabs.map((t) => (
            <button
              key={t.key}
              type="button"
              onClick={() => setTab(t.key)}
              title={t.disabled === true ? '标品没有声明这个扩展点' : undefined}
              className={cn(
                '-mb-px border-b-2 px-3 py-2 text-sm',
                tab === t.key
                  ? 'border-ink font-medium'
                  : 'border-transparent text-ink-muted hover:text-ink',
                t.disabled === true && 'text-ink-faint',
              )}
            >
              {t.disabled === true && <Lock aria-hidden className="mr-1 inline size-3" />}
              {t.label}
            </button>
          ))}
        </nav>
        <section className="rounded-xl border border-line bg-surface p-5">
          {section !== undefined && (
            <>
              <p className="mb-4 text-xs text-ink-muted">{point?.description ?? section.hint}</p>
              {point === undefined ? (
                <p className="text-sm text-ink-muted" data-testid="point-undeclared">
                  {vendor.isLoading
                    ? '读取扩展点…'
                    : '标品没有声明这个扩展点，不能在这里扩展。需要时可以复制为租户 Agent。'}
                </p>
              ) : (
                <SectionEditor
                  key={`${section.key}-${view.draft.updatedAt}`}
                  section={section.key}
                  initial={values}
                  point={point}
                  suggestions={suggestions}
                  onSave={(value) => api.applySection(id, section.key, value).then(changed)}
                />
              )}
            </>
          )}
          {tab === 'effective' && <Effective id={id} />}
          {tab === 'test' && (
            <TestStep
              view={view}
              start={api.test}
              prompts={[STORY_B_PROMPT]}
              defaultUser="wangfang"
            />
          )}
          {tab === 'release' && <ExtensionRelease view={view} onChange={changed} />}
        </section>
      </div>
      <aside className="sticky top-4 self-start">
        <YamlPanel view={view} onSaved={changed} />
      </aside>
    </div>
  )
}

/** 扩展编辑器：按扩展点分页编辑，预览叠加后的有效定义，测试、评测并发布 */
export function ExtensionPage(): ReactNode {
  const { id = '' } = useParams()
  const { draft, error, setDraft } = useDraft(id)
  const target = String((draft?.asset?.spec as { target?: string } | undefined)?.target ?? '')

  return (
    <div className="space-y-5">
      <header className="flex items-end gap-3">
        <Link
          to={`/studio/vendor-agents?ref=${encodeURIComponent(bareRef(target))}`}
          aria-label="返回标品 Agent"
          className="text-ink-muted hover:text-ink"
        >
          <ArrowLeft className="size-4" />
        </Link>
        <div className="min-w-0">
          <p className="flex items-center gap-2 text-xs text-ink-faint">
            <SourceBadge source="tenant" />
            <span className="font-mono">{`扩展 → ${target}`}</span>
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">
            {draft?.draft.intent ?? '扩展标品 Agent'}
          </h1>
        </div>
        {draft?.draft.publishedVersion != null && (
          <span className="ml-auto text-xs text-ink-muted">{`已发布 ${draft.draft.publishedVersion}`}</span>
        )}
      </header>
      {error !== null && <p className="text-xs text-deny">读取草稿失败：{errorText(error)}</p>}
      {draft !== undefined && <Editor view={draft} setDraft={setDraft} />}
    </div>
  )
}
