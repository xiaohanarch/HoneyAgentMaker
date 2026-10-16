import type { ReactNode } from 'react'
import { Radio, ShieldCheck, Zap } from 'lucide-react'
import { SourceBadge } from '@eab/ui'
import type { Inspection } from './api'

function Fact({ label, children }: { label: string; children: ReactNode }): ReactNode {
  return (
    <div>
      <dt className="text-[11px] text-ink-faint">{label}</dt>
      <dd className="mt-0.5 text-xs">{children}</dd>
    </div>
  )
}

function Flag({
  on,
  label,
  icon: Icon,
}: {
  on: boolean
  label: string
  icon: typeof Zap
}): ReactNode {
  return (
    <span
      className={
        on
          ? 'inline-flex items-center gap-1 rounded bg-allow-soft px-1.5 py-0.5 text-[11px] text-allow'
          : 'inline-flex items-center gap-1 rounded bg-surface-sunken px-1.5 py-0.5 text-[11px] text-ink-faint line-through'
      }
    >
      <Icon aria-hidden className="size-3" />
      {label}
    </span>
  )
}

/** Agent Card 的解读：技能、交互模式和认证方式 */
export function CardView({ inspection }: { inspection: Inspection }): ReactNode {
  const { card, skills } = inspection
  return (
    <section
      className="rounded-xl border border-line border-l-4 border-l-external bg-surface p-5"
      data-testid="agent-card"
    >
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold">{card.name}</h2>
            <SourceBadge source="external" />
            <span className="font-mono text-[11px] text-ink-faint">v{card.version}</span>
          </div>
          <p className="mt-1 text-sm text-ink-muted">{card.description}</p>
        </div>
      </div>

      <dl className="mt-4 grid grid-cols-4 gap-4">
        <Fact label="交互模式">
          <span className="flex flex-wrap gap-1">
            <Flag on={card.streaming} label="流式" icon={Zap} />
            <Flag on={card.pushNotifications} label="推送回调" icon={Radio} />
          </span>
        </Fact>
        <Fact label="输入 / 输出">
          <span className="font-mono text-[11px]">
            {card.inputModes.join(', ')} → {card.outputModes.join(', ')}
          </span>
        </Fact>
        <Fact label="认证方式">
          <span className="inline-flex items-center gap-1">
            <ShieldCheck aria-hidden className="size-3.5 text-ink-muted" />
            {card.auth.length === 0 ? '未声明' : card.auth.join('、')}
          </span>
        </Fact>
        <Fact label="接口">
          {card.interfaces.map((i) => (
            <span key={i} className="block truncate font-mono text-[11px]" title={i}>
              {i}
            </span>
          ))}
        </Fact>
      </dl>

      <h3 className="mt-5 text-xs font-medium text-ink-muted">技能（{skills.length}）</h3>
      <ul className="mt-2 space-y-2">
        {skills.map((skill) => (
          <li key={skill.id} className="rounded-lg border border-line bg-canvas px-3 py-2.5">
            <p className="text-sm font-medium">
              {skill.name}
              <span className="ml-2 font-mono text-[11px] text-ink-faint">{skill.id}</span>
            </p>
            <p className="mt-0.5 text-xs text-ink-muted">{skill.description}</p>
            {skill.examples.length > 0 && (
              <p className="mt-1.5 flex flex-wrap gap-1.5">
                {skill.examples.map((e) => (
                  <span key={e} className="rounded-full bg-surface-sunken px-2 py-0.5 text-[11px]">
                    {e}
                  </span>
                ))}
              </p>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
