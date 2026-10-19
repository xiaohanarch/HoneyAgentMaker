import { useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { Popover } from 'radix-ui'
import { ArrowRightLeft, SlidersHorizontal, Zap } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../identity/identity-context'
import { ErrorLine } from '../hub/ui'
import { useHubRole } from '../hub/use-hub'
import type { IntegrationCompare } from '../hub/types'
import { completeDemo, fetchCompare, resetDemo, storyStart, triggerEvent, upgradeVendor } from './api'

/** 演示故事的起点（主提示词 17 节）：A–E 纯重置，F 重置后发布完成态快照 */
const STORIES: readonly { id: string; label: string }[] = [
  { id: 'A', label: '新建助手' },
  { id: 'B', label: '增强标品' },
  { id: 'C', label: '外部互通' },
  { id: 'D', label: '权限治理' },
  { id: 'E', label: '需求闭环' },
  { id: 'F', label: '升级治理' },
]

const EVENTS: readonly { type: string; label: string }[] = [
  { type: 'delivery.overdue', label: '交付逾期' },
  { type: 'inventory.below_safety_stock', label: '库存低于安全线' },
  { type: 'transaction.anomaly_created', label: '交易异常' },
  { type: 'period.close_check_failed', label: '结账检查失败' },
]

const BTN =
  'inline-flex items-center gap-1 rounded-md border border-line px-2 py-1 text-xs text-ink transition-colors hover:border-line-strong hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:cursor-not-allowed disabled:opacity-40'

const BTN_PRIMARY =
  'inline-flex items-center gap-1 rounded-md bg-ink px-2.5 py-1 text-xs font-medium text-surface transition-colors hover:bg-ink/85 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:cursor-not-allowed disabled:opacity-40'

function Section({ title, children }: { title: string; children: ReactNode }): ReactNode {
  return (
    <section className="space-y-2 border-t border-line px-3 py-2.5 first:border-t-0">
      <h3 className="text-[11px] font-medium tracking-wide text-ink-faint">{title}</h3>
      {children}
    </section>
  )
}

/** 一次演示状态操作：先点一次出现确认，再点执行（避免误触清空演示数据） */
function ConfirmButton({
  action,
  confirming,
  label,
  confirmLabel,
  pending,
  onArm,
  onRun,
}: {
  action: string
  confirming: string | null
  label: string
  confirmLabel: string
  pending: boolean
  onArm: (action: string) => void
  onRun: (action: string) => void
}): ReactNode {
  const armed = confirming === action
  return (
    <button
      type="button"
      data-testid={`console-${action}`}
      className={cn(BTN, armed && 'border-deny/40 bg-deny-soft/50 text-deny')}
      disabled={pending}
      onClick={() => (armed ? onRun(action) : onArm(action))}
    >
      {pending ? '执行中…' : armed ? confirmLabel : label}
    </button>
  )
}

/**
 * 演示控制台（主提示词 17 节）：Studio 右上角的隐藏菜单，演示者在这里重置数据、
 * 跳到各故事起点、触发业务事件、模拟标品升级、切换演示身份。演示状态操作只对租户管理员开放。
 */
export function DemoConsole(): ReactNode {
  const { token, user, users, switchUser } = useIdentity()
  const { canWrite } = useHubRole()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [compareUser, setCompareUser] = useState('zhangmin')
  const [compare, setCompare] = useState<IntegrationCompare | null>(null)

  // 演示状态操作（重置、故事起点、完成态）：成功后全量失效查询缓存，
  // 运行、通知、委托、治理中心的数据全部按新状态重取
  const stateAction = useMutation({
    mutationFn: (run: () => Promise<unknown>) => run(),
    onSuccess: (result) => {
      setConfirming(null)
      setMessage(
        resultMessage(
          result as {
            erpReset?: boolean
            story?: string
            published?: string[]
            history?: { runs: number; metricPoints: number }
          },
        ),
      )
      void client.invalidateQueries()
      setCompare(null)
    },
  })

  const event = useMutation({
    mutationFn: (type: string) => triggerEvent(token, type),
    onSuccess: () => setMessage('事件已推送给平台，匹配的 Agent 会以受托方式运行，结果出现在委托人的通知区。'),
    onError: () => setMessage(null),
  })

  const upgrade = useMutation({
    mutationFn: () => upgradeVendor(token),
    onSuccess: (regressions) => {
      const failed = regressions.filter((r) => r.status === 'failed').length
      setMessage(
        failed > 0
          ? `标品升级回归未通过：${failed} 项扩展的评测集出现回归，租户管理员已收到通知。`
          : '标品升级回归全部通过。',
      )
      void client.invalidateQueries({ queryKey: ['hub'] })
    },
    onError: () => setMessage(null),
  })

  const compareQuery = useMutation({
    mutationFn: () => fetchCompare(token, compareUser),
    onSuccess: (data) => setCompare(data),
  })

  // 集成账号对照用业务用户（同租户、入口在统一入口的用户），管理员与开发者没有数据范围，对照没有意义
  const compareUsers = users.filter((u) => u.tenantId === user.tenantId && u.home === 'assistant')

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        className="grid size-8 place-items-center rounded-lg text-ink-muted transition-colors hover:bg-surface-sunken hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus data-[state=open]:bg-surface-sunken"
        aria-label="演示控制台"
        data-testid="demo-console-trigger"
        title="演示控制台：重置数据、跳故事起点、触发事件、切换身份"
      >
        <SlidersHorizontal aria-hidden className="size-4" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          align="end"
          sideOffset={8}
          className="z-50 w-[24rem] rounded-xl border border-line bg-surface shadow-lg shadow-black/5"
          data-testid="demo-console"
        >
          <Section title="演示状态（租户管理员）">
            {!canWrite ? (
              <p className="text-xs text-ink-muted">
                演示状态操作只对租户管理员开放，当前身份只读。切换用户一节可换到赵管理员。
              </p>
            ) : (
              <>
                <div className="flex flex-wrap gap-1.5">
                  <ConfirmButton
                    action="reset"
                    confirming={confirming}
                    label="重置演示数据"
                    confirmLabel="确认重置？"
                    pending={stateAction.isPending}
                    onArm={setConfirming}
                    onRun={() => stateAction.mutate(() => resetDemo(token))}
                  />
                  <ConfirmButton
                    action="complete"
                    confirming={confirming}
                    label="一键完成态"
                    confirmLabel="确认生成？"
                    pending={stateAction.isPending}
                    onArm={setConfirming}
                    onRun={() => stateAction.mutate(() => completeDemo(token))}
                  />
                </div>
                <div className="flex flex-wrap gap-1">
                  {STORIES.map((s) => (
                    <ConfirmButton
                      key={s.id}
                      action={`story-${s.id}`}
                      confirming={confirming}
                      label={`故事 ${s.id} · ${s.label}`}
                      confirmLabel="确认跳转？"
                      pending={stateAction.isPending}
                      onArm={setConfirming}
                      onRun={() => stateAction.mutate(() => storyStart(token, s.id))}
                    />
                  ))}
                </div>
                <p className="text-[11px] leading-relaxed text-ink-faint">
                  重置会清空运行数据并撤回演示中发布的租户资产（扩展、技能、评测集、语义包），连接、工具来源、
                  工作流、远程 Agent 与标品资产保留。故事 F 与完成态在此基础上按 tenants 快照重建完成态。
                </p>
              </>
            )}
            <ErrorLine error={stateAction.error} />
          </Section>

          <Section title="触发业务事件">
            <div className="flex flex-wrap gap-1.5">
              {EVENTS.map((e) => (
                <button
                  key={e.type}
                  type="button"
                  data-testid={`console-event-${e.type}`}
                  className={BTN}
                  disabled={event.isPending || token === undefined}
                  onClick={() => event.mutate(e.type)}
                >
                  <Zap aria-hidden className="size-3" />
                  {e.label}
                </button>
              ))}
            </div>
            <ErrorLine error={event.error} />
          </Section>

          <Section title="发布标品新版本（租户管理员）">
            {canWrite ? (
              <>
                <button
                  type="button"
                  data-testid="console-upgrade"
                  className={BTN_PRIMARY}
                  disabled={upgrade.isPending}
                  onClick={() => upgrade.mutate()}
                >
                  模拟发布 1.3.0 并跑租户扩展回归
                </button>
                {upgrade.error !== null && (
                  <p className="text-[11px] text-ink-muted">
                    已经升过版本时会报错：先在演示状态里跳到「故事 F 起点」再升级。
                  </p>
                )}
              </>
            ) : (
              <p className="text-xs text-ink-muted">只对租户管理员开放。</p>
            )}
            <ErrorLine error={upgrade.error} />
          </Section>

          <Section title="切换演示身份">
            <div className="flex flex-wrap gap-1.5">
              {users.map((u) => (
                <button
                  key={u.id}
                  type="button"
                  data-testid={`console-user-${u.id}`}
                  className={cn(
                    BTN,
                    u.id === user.id && 'border-line-strong bg-surface-sunken font-medium',
                  )}
                  onClick={() => {
                    // 切换身份即离开控制台：收起面板并跳到新身份的首页
                    void switchUser(u.id).then((next) => {
                      setOpen(false)
                      navigate(`/${next.home}`)
                    })
                  }}
                >
                  {u.name}
                </button>
              ))}
            </div>
          </Section>

          <Section title="集成账号对照">
            <div className="flex items-center gap-1.5">
              <select
                aria-label="对照用户"
                value={compareUser}
                onChange={(e) => {
                  setCompareUser(e.target.value)
                  setCompare(null)
                }}
                className="rounded-md border border-line bg-surface px-2 py-1 text-xs text-ink"
              >
                {compareUsers.map((u) => (
                  <option key={u.id} value={u.id}>{`${u.name} · ${u.dataScope}`}</option>
                ))}
              </select>
              <button
                type="button"
                data-testid="console-compare"
                className={BTN}
                disabled={compareQuery.isPending || token === undefined}
                onClick={() => compareQuery.mutate()}
              >
                <ArrowRightLeft aria-hidden className="size-3" />
                对照
              </button>
            </div>
            <ErrorLine error={compareQuery.error} />
            {compare !== null && (
              <div className="space-y-1.5 rounded-lg border border-line bg-surface-sunken/40 p-2 text-[11px]">
                <p className="flex justify-between">
                  <span className="text-ink-muted">BO API · 用户身份</span>
                  <span className="font-mono text-allow">{`${compare.bo.total} 张（${compare.bo.scope}）`}</span>
                </p>
                <p className="flex justify-between">
                  <span className="text-ink-muted">Open API · 集成账号</span>
                  <span className="font-mono text-deny">{`${compare.open.total} 张（未按用户过滤）`}</span>
                </p>
                <a className="block text-focus underline-offset-2 hover:underline" href="/hub?tab=audit">
                  治理中心看完整对照
                </a>
              </div>
            )}
          </Section>

          {message !== null && (
            <p
              className="border-t border-line px-3 py-2 text-[11px] text-ink-muted"
              data-testid="console-message"
            >
              {message}
            </p>
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** 把三类演示状态操作的结果换成人话 */
function resultMessage(result: {
  erpReset?: boolean
  story?: string
  published?: string[]
  history?: { runs: number; metricPoints: number }
}): string {
  if (result.story !== undefined) {
    const n = result.published?.length ?? 0
    return result.story === 'F'
      ? `已跳到故事 F 起点：演示数据已重置，完成态快照发布 ${n} 项资产，升级回归的对象就绪。`
      : `已跳到故事 ${result.story} 起点：演示数据已重置，演示委托已重建。`
  }
  if (result.history !== undefined) {
    return `完成态已生成：发布 ${result.published?.length ?? 0} 项资产，模拟 ${result.history.runs} 次运行、${result.history.metricPoints} 个指标点，治理中心可看 30 天趋势。`
  }
  if (result.erpReset === true) {
    return '演示数据已重置：运行、追踪、通知、扩展需求已清空，演示委托已重建。'
  }
  return '操作完成。'
}
