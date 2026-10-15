import type { ReactNode } from 'react'
import { ArrowRight, Blocks, Cable, Globe, Sparkles, Workflow } from 'lucide-react'
import { SOURCE_STYLES, SourceBadge, cn, type AssetSource } from '@eab/ui'
import { useIdentity } from '../identity/identity-context'
import { PhaseTag } from '../shell/PhaseTag'

interface Entry {
  icon: typeof Sparkles
  title: string
  description: string
  source: AssetSource
  phase: string
}

/** Studio 首页的入口卡片（主提示词 10.1 节）。 */
const ENTRIES: readonly Entry[] = [
  {
    icon: Blocks,
    title: '扩展标品 Agent',
    description: '在标品声明的扩展点上追加指令、工具、知识、钩子和确认点',
    source: 'vendor',
    phase: 'P6',
  },
  {
    icon: Globe,
    title: '接入外部 Agent',
    description: '输入 Agent Card 地址，把其他框架写的 Agent 经 A2A 接进来',
    source: 'external',
    phase: 'P7',
  },
  {
    icon: Workflow,
    title: '新建技能',
    description: '确定性的步骤流程，不依赖大模型推理',
    source: 'tenant',
    phase: 'P6',
  },
  {
    icon: Cable,
    title: '导入工具',
    description: '从 BO API 的 OpenAPI 描述生成工具，或登记 MCP Server',
    source: 'platform',
    phase: 'P6',
  },
]

const DEVELOPER_ROLES = new Set(['租户开发者', '标品开发者'])

export function StudioPage(): ReactNode {
  const { user } = useIdentity()
  const isDeveloper = DEVELOPER_ROLES.has(user.role)

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between">
        <div>
          <p className="text-xs font-medium tracking-wide text-ink-faint">
            ERP Agent Builder Studio
          </p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">
            设计、测试、发布、运行、观测
          </h1>
        </div>
        {!isDeveloper && (
          <p className="rounded-lg bg-pending-soft px-3 py-1.5 text-xs text-pending">
            当前身份 {user.name}（{user.role}）没有 Studio 权限，演示时请切换到陈工或厂商研发
          </p>
        )}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <article className="row-span-2 flex flex-col justify-between rounded-xl border border-line border-l-4 border-l-tenant bg-surface p-6">
          <div>
            <div className="flex items-center gap-2">
              <Sparkles aria-hidden className="size-5 text-tenant" />
              <SourceBadge source="tenant" />
              <span className="ml-auto">
                <PhaseTag phase="P5" />
              </span>
            </div>
            <h2 className="mt-4 text-lg font-semibold">从一句话意图新建 Agent</h2>
            <p className="mt-1.5 text-sm leading-relaxed text-ink-muted">
              描述目的后，平台在 13 个步骤里逐项给出推荐：名称与头像、业务流程归属、协作
              Agent、语义包与知识、技能与工具、触发器、渠道、指令与约束、模型档位、评测集，最后测试并发布。
            </p>
          </div>
          <div className="mt-6 flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2.5 text-sm text-ink-faint">
            例如：帮采购经理制定和供应商的降价谈判策略
            <ArrowRight aria-hidden className="ml-auto size-4" />
          </div>
        </article>

        {ENTRIES.map(({ icon: Icon, title, description, source, phase }) => (
          <article
            key={title}
            className={cn(
              'rounded-xl border border-line border-l-4 bg-surface p-5',
              SOURCE_STYLES[source].accentClass,
            )}
          >
            <div className="flex items-center gap-2">
              <Icon aria-hidden className="size-4 text-ink-muted" />
              <h2 className="text-sm font-semibold">{title}</h2>
              <span className="ml-auto">
                <PhaseTag phase={phase} />
              </span>
            </div>
            <p className="mt-2 text-xs leading-relaxed text-ink-muted">{description}</p>
            <div className="mt-3">
              <SourceBadge source={source} />
            </div>
          </article>
        ))}
      </div>
    </div>
  )
}
