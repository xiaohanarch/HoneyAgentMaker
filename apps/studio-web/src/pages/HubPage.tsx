import type { ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import {
  BadgeCheck,
  BarChart3,
  FlaskConical,
  GitBranch,
  History,
  KeyRound,
  Lightbulb,
  PackageCheck,
  ScrollText,
  Siren,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@eab/ui'
import { AuditSection } from '../hub/AuditSection'
import { AdmissionSection, AlertSection, DelegationSection } from '../hub/ControlSections'
import { FeedbackSection } from '../hub/FeedbackSection'
import { InventorySection } from '../hub/InventorySection'
import { EvalSection, SolutionSection, UpgradeSection } from '../hub/LifecycleSections'
import { MetricsSection } from '../hub/MetricsSection'
import { useHubRole } from '../hub/use-hub'
import { useIdentity } from '../identity/identity-context'

interface Tab {
  key: string
  title: string
  icon: LucideIcon
  group: string
  render: () => ReactNode
}

/** 治理中心的分区（主提示词第 11 节）；当前分区记在 URL 的 tab 参数里，便于分享和刷新 */
const TABS: readonly Tab[] = [
  {
    key: 'inventory',
    title: '资产清单',
    icon: GitBranch,
    group: '资产',
    render: () => <InventorySection />,
  },
  {
    key: 'admission',
    title: '上架准入',
    icon: BadgeCheck,
    group: '资产',
    render: () => <AdmissionSection />,
  },
  {
    key: 'solutions',
    title: '方案包',
    icon: PackageCheck,
    group: '资产',
    render: () => <SolutionSection />,
  },
  {
    key: 'metrics',
    title: '运行指标',
    icon: BarChart3,
    group: '运行',
    render: () => <MetricsSection />,
  },
  { key: 'evals', title: '评测', icon: FlaskConical, group: '运行', render: () => <EvalSection /> },
  {
    key: 'upgrades',
    title: '升级回归',
    icon: History,
    group: '运行',
    render: () => <UpgradeSection />,
  },
  {
    key: 'feedback',
    title: '经验回流',
    icon: Lightbulb,
    group: '运行',
    render: () => <FeedbackSection />,
  },
  { key: 'audit', title: '审计', icon: ScrollText, group: '安全', render: () => <AuditSection /> },
  {
    key: 'delegations',
    title: '委托管理',
    icon: KeyRound,
    group: '安全',
    render: () => <DelegationSection />,
  },
  { key: 'alerts', title: '风险告警', icon: Siren, group: '安全', render: () => <AlertSection /> },
]

const GROUPS = ['资产', '运行', '安全'] as const

export function HubPage(): ReactNode {
  const { user } = useIdentity()
  const { canRead, canWrite } = useHubRole()
  const [params, setParams] = useSearchParams()
  const active = TABS.find((t) => t.key === params.get('tab')) ?? TABS[0]

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-faint">Agent Hub</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">治理中心</h1>
        </div>
        <p className="ml-auto text-xs text-ink-muted">
          {user.tenantName} · {user.name}（{user.role}）{canRead && !canWrite && ' · 只读'}
        </p>
      </div>

      {!canRead ? (
        <p className="rounded-xl border border-line bg-surface px-5 py-8 text-center text-sm text-ink-muted">
          治理中心面向租户管理员、租户开发者和平台运维。请在右上角切换到赵管理员查看。
        </p>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[11rem_minmax(0,1fr)]">
          <nav aria-label="治理中心分区" className="space-y-4 lg:sticky lg:top-4 lg:self-start">
            {GROUPS.map((group) => (
              <div key={group}>
                <p className="px-2 text-[10px] font-semibold tracking-widest text-ink-faint">
                  {group}
                </p>
                <ul className="mt-1 space-y-0.5">
                  {TABS.filter((t) => t.group === group).map((t) => {
                    const Icon = t.icon
                    const current = t.key === active?.key
                    return (
                      <li key={t.key}>
                        <button
                          type="button"
                          aria-current={current ? 'page' : undefined}
                          onClick={() => setParams({ tab: t.key })}
                          className={cn(
                            'flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition-colors',
                            current
                              ? 'bg-ink text-surface'
                              : 'text-ink-muted hover:bg-surface-sunken hover:text-ink',
                          )}
                        >
                          <Icon aria-hidden className="size-4" />
                          {t.title}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </div>
            ))}
          </nav>
          <div className="min-w-0">{active?.render()}</div>
        </div>
      )}
    </div>
  )
}
