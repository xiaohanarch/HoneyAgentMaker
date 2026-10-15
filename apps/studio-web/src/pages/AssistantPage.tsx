import type { ReactNode } from 'react'
import {
  Compass,
  FileSearch,
  LineChart,
  PencilLine,
  Route,
  SendHorizontal,
  Waypoints,
} from 'lucide-react'
import { useIdentity } from '../identity/identity-context'
import { PhaseTag } from '../shell/PhaseTag'

const ASSISTANT_NAME = import.meta.env.VITE_ASSISTANT_NAME ?? 'ERP 助手'

/** 四种交互模式各一句演示话术（主提示词 9.1.1 节）。 */
const MODES = [
  { icon: PencilLine, mode: '事务型', prompt: '把 PO-2026-0117 的交期改到下月 15 号' },
  { icon: Compass, mode: '导航型', prompt: '打开百润精密的供应商详情' },
  { icon: FileSearch, mode: '信息型', prompt: '我们的降价谈判制度是怎么规定的' },
  { icon: LineChart, mode: '分析型', prompt: '华东区本季度逾期交付率是多少' },
] as const

function SidePanel({
  icon: Icon,
  title,
  children,
}: {
  icon: typeof Route
  title: string
  children: ReactNode
}): ReactNode {
  return (
    <section className="rounded-xl border border-line bg-surface">
      <header className="flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium">
        <Icon aria-hidden className="size-4 text-ink-muted" />
        {title}
        <span className="ml-auto">
          <PhaseTag phase="P4" />
        </span>
      </header>
      <div className="px-4 py-3 text-xs leading-relaxed text-ink-muted">{children}</div>
    </section>
  )
}

export function AssistantPage(): ReactNode {
  const { user } = useIdentity()

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-5">
      <section className="flex min-h-[560px] flex-col rounded-xl border border-line bg-surface">
        <div className="flex-1 px-8 py-8">
          <p className="text-xs font-medium tracking-wide text-ink-faint">{ASSISTANT_NAME}</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">
            {user.name}，今天想处理什么？
          </h1>
          <p className="mt-1.5 text-sm text-ink-muted">
            {`以 ${user.tenantName} · ${user.role} 的身份提问，回答范围受你的数据权限约束（${user.dataScope}）。`}
          </p>

          <ul className="mt-8 grid max-w-2xl grid-cols-2 gap-2.5">
            {MODES.map(({ icon: Icon, mode, prompt }) => (
              <li
                key={mode}
                className="group rounded-lg border border-line bg-canvas px-3.5 py-3 transition-colors hover:border-line-strong"
              >
                <span className="flex items-center gap-1.5 text-[11px] font-medium text-ink-faint">
                  <Icon aria-hidden className="size-3.5" />
                  {mode}
                </span>
                <span className="mt-1 block text-sm">{prompt}</span>
              </li>
            ))}
          </ul>
        </div>

        <form className="border-t border-line p-4" onSubmit={(e) => e.preventDefault()}>
          <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2">
            <input
              disabled
              aria-label="输入问题"
              placeholder="对话将在阶段 P4 接通"
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint disabled:cursor-not-allowed"
            />
            <SendHorizontal aria-hidden className="size-4 text-ink-faint" />
          </div>
        </form>
      </section>

      <aside className="flex flex-col gap-4">
        <SidePanel icon={Route} title="路由说明">
          每次提问后在这里展开：召回的候选岗位助手和
          Agent、相似度分数、模型给出的理由与置信度，以及入口鉴权的裁决。
        </SidePanel>
        <SidePanel icon={Waypoints} title="本次链路">
          从入口鉴权到 BO API 返回的完整 span
          树：组装、模型调用、工具调用、四处鉴权、守卫、钩子和人工确认。
        </SidePanel>
      </aside>
    </div>
  )
}
