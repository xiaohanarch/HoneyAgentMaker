import type { ReactNode } from 'react'
import {
  BadgeCheck,
  BarChart3,
  GitBranch,
  History,
  KeyRound,
  Lightbulb,
  ScrollText,
  Siren,
} from 'lucide-react'
import { PhaseTag } from '../shell/PhaseTag'

/** 治理中心页面清单（主提示词第 11 节）。 */
const SECTIONS = [
  {
    icon: GitBranch,
    title: '资产清单',
    description: '全部助手、Agent、扩展、远程 Agent、MCP Server，以及扩展与标品的血缘',
  },
  { icon: BadgeCheck, title: '上架准入', description: '评测门禁、写级别合规、MCP 审批、签名校验' },
  {
    icon: BarChart3,
    title: '运行指标',
    description: '技术指标、流程指标、业务结果三层，岗位助手 KPI',
  },
  {
    icon: ScrollText,
    title: '审计',
    description: '按用户、Agent、工具、时间查询，反查“谁通过哪个 Agent 做了什么”',
  },
  { icon: KeyRound, title: '委托管理', description: '受托自主运行的委托清单，可撤销' },
  { icon: History, title: '升级回归', description: '标品升级记录与各租户扩展的回归结果' },
  {
    icon: Lightbulb,
    title: '经验回流',
    description: '用户纠正生成的候选规则，审核后写入租户语义包',
  },
  { icon: Siren, title: '风险告警', description: '监督层产生的异常行为告警' },
] as const

export function HubPage(): ReactNode {
  return (
    <div className="space-y-6">
      <div>
        <p className="text-xs font-medium tracking-wide text-ink-faint">Agent Hub</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">治理中心</h1>
      </div>

      <ol className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
        {SECTIONS.map(({ icon: Icon, title, description }, index) => (
          <li key={title} className="flex items-center gap-4 px-5 py-3.5">
            <span className="w-5 font-mono text-xs text-ink-faint">
              {String(index + 1).padStart(2, '0')}
            </span>
            <Icon aria-hidden className="size-4 text-ink-muted" />
            <span className="w-24 text-sm font-medium">{title}</span>
            <span className="flex-1 text-sm text-ink-muted">{description}</span>
            <PhaseTag phase="P8" />
          </li>
        ))}
      </ol>
    </div>
  )
}
