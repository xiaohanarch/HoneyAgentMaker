import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  ArrowLeft,
  CircleCheck,
  CircleX,
  FolderPlus,
  LoaderCircle,
  RefreshCw,
  Save,
  Wand2,
} from 'lucide-react'
import { ToneBadge } from '@eab/ui'
import type { AdoptedDraft, RequestPlan } from '../api'
import { errorText, useStudioApi } from '../use-studio'
import { draftPath } from '../extend/use-extend'
import { PlanCards } from './PlanCards'
import { RequestHeader } from './RequestHeader'
import {
  PLAN_DECIDED_BY_LABELS,
  PLAN_DRAFT_KIND_LABELS,
  applyPlanEdits,
  emptyPlanEdits,
  formatTime,
  planHasChanges,
  type PlanEdits,
} from './request-model'

const ACTION_BUTTON =
  'inline-flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 text-sm text-ink-muted hover:text-ink disabled:opacity-40'
const PRIMARY_BUTTON =
  'inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm font-medium text-surface disabled:opacity-40'

interface ActionButtonProps {
  /** 这个按钮对应的动作名，与 busy 对上时显示进度图标 */
  step: string
  /** 正在执行的动作；有任意动作在执行时所有按钮禁用 */
  busy: string | null
  icon: typeof Wand2
  primary?: boolean
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}

function ActionButton({
  step,
  busy,
  icon: Icon,
  primary,
  disabled,
  onClick,
  children,
}: ActionButtonProps): ReactNode {
  const running = busy === step
  return (
    <button
      type="button"
      className={primary === true ? PRIMARY_BUTTON : ACTION_BUTTON}
      disabled={disabled === true || busy !== null}
      onClick={onClick}
    >
      {running ? (
        <LoaderCircle aria-hidden className="size-4 animate-spin" />
      ) : (
        <Icon aria-hidden className="size-4" />
      )}
      {children}
    </button>
  )
}

function BackLink(): ReactNode {
  return (
    <Link
      to="/studio"
      aria-label="返回 Studio 首页"
      className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
    >
      <ArrowLeft aria-hidden className="size-3.5" />
      Studio 首页
    </Link>
  )
}

/** 采纳方案后生成的工作区草稿，链接到对应的编辑页 */
function AdoptedList({ drafts }: { drafts: readonly AdoptedDraft[] }): ReactNode {
  return (
    <section
      className="rounded-xl border border-allow/30 bg-allow-soft/40 px-4 py-3"
      data-testid="adopted-drafts"
    >
      <p className="text-sm font-medium text-allow">方案已采纳，工作区里生成了草稿：</p>
      <ul className="mt-2 space-y-1.5">
        {drafts.map((draft) => (
          <li key={draft.id} className="flex flex-wrap items-center gap-2 text-sm">
            <ToneBadge tone="neutral">{PLAN_DRAFT_KIND_LABELS[draft.kind]}</ToneBadge>
            <Link to={draftPath(draft)} className="font-medium hover:underline">
              {draft.name}
            </Link>
            <span className="min-w-0 break-all font-mono text-xs text-ink-faint">{draft.ref}</span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-xs text-ink-muted">
        在编辑页里完善并发布，完成后回到这里点“标记已交付”，会通知提出人重新提问。
      </p>
    </section>
  )
}

/** 扩展需求详情：需求头、意图生成的六步方案卡，以及生成、保存、采纳、交付、拒绝动作 */
export function RequestDetailPage(): ReactNode {
  const { namespace = '', name = '' } = useParams()
  const { api, ready } = useStudioApi()
  const query = useQuery({
    queryKey: ['studio', 'request', namespace, name],
    queryFn: () => api.request(namespace, name),
    enabled: ready && namespace !== '' && name !== '',
  })
  const [edits, setEdits] = useState<PlanEdits>(emptyPlanEdits)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adopted, setAdopted] = useState<AdoptedDraft[] | null>(null)
  const [summary, setSummary] = useState('')
  const [reason, setReason] = useState('')

  const view = query.data
  const readOnly = view !== undefined && (view.status === 'delivered' || view.status === 'rejected')

  const act = async <T,>(
    step: string,
    work: () => Promise<T>,
    done: (value: T) => void,
  ): Promise<void> => {
    setBusy(step)
    setError(null)
    try {
      done(await work())
      await query.refetch()
    } catch (e: unknown) {
      setError(errorText(e))
    } finally {
      setBusy(null)
    }
  }

  const generate = (): void => {
    void act(
      'plan',
      () => api.planRequest(namespace, name),
      () => {
        setEdits(emptyPlanEdits())
        setAdopted(null)
      },
    )
  }
  const regenerate = (): void => {
    if (!window.confirm('重新生成会覆盖现有的六张方案卡，确定继续吗？')) return
    generate()
  }
  const save = (): void => {
    if (view?.plan == null) return
    const plan: RequestPlan = applyPlanEdits(view.plan, edits)
    void act(
      'save',
      () => api.putRequestPlan(namespace, name, plan),
      () => setEdits(emptyPlanEdits()),
    )
  }
  const adopt = (): void => {
    void act(
      'adopt',
      () => api.adoptRequest(namespace, name),
      (value) => setAdopted(value.drafts),
    )
  }
  const deliver = (): void => {
    const text = summary.trim()
    void act(
      'deliver',
      () => api.deliverRequest(namespace, name, text === '' ? undefined : text),
      () => {
        setSummary('')
        setAdopted(null)
      },
    )
  }
  const reject = (): void => {
    if (!window.confirm('拒绝后会通知提出人，确定拒绝这个需求吗？')) return
    const text = reason.trim()
    void act(
      'reject',
      () => api.rejectRequest(namespace, name, text === '' ? undefined : text),
      () => setReason(''),
    )
  }

  if (query.error !== null) {
    return (
      <div className="space-y-3">
        <BackLink />
        <p className="text-xs text-deny">读取需求失败：{errorText(query.error)}</p>
      </div>
    )
  }
  if (view === undefined) {
    return (
      <div className="space-y-3">
        <BackLink />
        <p className="text-xs text-ink-muted">读取中…</p>
      </div>
    )
  }

  // 后端序列化忽略 null 字段：还没有方案时 plan 字段整个不出现（undefined），宽松判断两种都算没有
  const plan = view.plan ?? null
  const hasPlan = plan !== null

  return (
    <div className="space-y-5">
      <BackLink />
      <RequestHeader view={view} />

      {!hasPlan ? (
        <section
          className="rounded-xl border border-dashed border-line-strong bg-surface px-4 py-8 text-center"
          data-testid="plan-empty"
        >
          <p className="mx-auto max-w-xl text-sm leading-relaxed text-ink-muted">
            还没有生成方案。点“生成方案”后，平台按需求原话与附带的口径给出六张卡：
            意图澄清、意图分析与 fit-gap、需求摘要、技术规格、资产草稿和评测集草稿。
          </p>
          {!readOnly && (
            <ActionButton step="plan" busy={busy} icon={Wand2} primary onClick={generate}>
              生成方案
            </ActionButton>
          )}
        </section>
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-ink-faint" data-testid="plan-meta" data-generated={plan.generatedAt}>
            {`${PLAN_DECIDED_BY_LABELS[plan.decidedBy]} · 生成于 ${formatTime(plan.generatedAt)}`}
          </p>
          <PlanCards plan={plan} edits={edits} readOnly={readOnly} onEdit={setEdits} />
        </div>
      )}

      {adopted !== null && <AdoptedList drafts={adopted} />}

      {!readOnly && (
        <section
          className="space-y-3 rounded-xl border border-line bg-surface p-4"
          data-testid="request-actions"
        >
          {hasPlan && (
            <div className="flex flex-wrap items-center gap-2">
              <ActionButton step="plan" busy={busy} icon={RefreshCw} onClick={regenerate}>
                重新生成
              </ActionButton>
              <ActionButton
                step="save"
                busy={busy}
                icon={Save}
                disabled={!planHasChanges(plan, edits)}
                onClick={save}
              >
                保存修改
              </ActionButton>
              <ActionButton step="adopt" busy={busy} icon={FolderPlus} primary onClick={adopt}>
                采纳方案，创建草稿
              </ActionButton>
            </div>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <textarea
                aria-label="交付说明"
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                rows={2}
                placeholder="交付说明（可选），会随通知发给提出人"
                className="block w-full rounded-lg border border-line bg-canvas px-3 py-2 text-xs leading-relaxed outline-none focus:border-line-strong"
              />
              <ActionButton step="deliver" busy={busy} icon={CircleCheck} onClick={deliver}>
                标记已交付
              </ActionButton>
            </div>
            <div className="space-y-1.5">
              <textarea
                aria-label="拒绝原因"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={2}
                placeholder="拒绝原因（可选），会随通知发给提出人"
                className="block w-full rounded-lg border border-line bg-canvas px-3 py-2 text-xs leading-relaxed outline-none focus:border-line-strong"
              />
              <ActionButton step="reject" busy={busy} icon={CircleX} onClick={reject}>
                拒绝
              </ActionButton>
            </div>
          </div>
          <p className="text-[11px] text-ink-faint">
            标记已交付或拒绝后需求转为只读，平台会通知提出人。
          </p>
        </section>
      )}

      {error !== null && (
        <p
          className="rounded-lg border border-deny/30 bg-deny-soft/40 px-3 py-2 text-xs text-deny"
          role="alert"
        >
          {error}
        </p>
      )}
    </div>
  )
}
