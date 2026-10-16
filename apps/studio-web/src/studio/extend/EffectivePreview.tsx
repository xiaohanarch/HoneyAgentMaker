import type { ReactNode } from 'react'
import { Lock } from 'lucide-react'
import { SourceBadge, cn, type AssetSource } from '@eab/ui'
import { Level } from '../tools/Level'
import type { EffectiveSegment, EffectiveView } from './api'
import { isRedacted, segmentKindLabel, segmentTone } from './extension-values'

function layerSource(layer: string): AssetSource {
  return layer === 'vendor' || layer === 'tenant' ? layer : 'platform'
}

/** 有效指令的各个片段：标品部分锁住只显示占位，租户追加的部分高亮，每段标出来源 */
export function EffectiveSegments({
  segments,
}: {
  segments: readonly EffectiveSegment[]
}): ReactNode {
  return (
    <ol className="space-y-2" data-testid="effective-segments">
      {segments.map((s) => {
        const tone = segmentTone(s)
        return (
          <li
            key={s.id}
            data-tone={tone}
            className={cn(
              'rounded-lg border border-l-4 px-3 py-2 text-xs',
              tone === 'locked' && 'border-line border-l-vendor bg-surface-sunken text-ink-muted',
              tone === 'tenant' && 'border-tenant/30 border-l-tenant bg-tenant-soft/40',
              tone === 'other' && 'border-line border-l-platform bg-canvas',
            )}
          >
            <p className="flex items-center gap-2 text-[11px] text-ink-faint">
              {tone === 'locked' && <Lock aria-hidden className="size-3 text-vendor" />}
              <span className="font-medium text-ink-muted">{segmentKindLabel(s.kind)}</span>
              <span className="font-mono">{s.source}</span>
            </p>
            {isRedacted(s.text) ? (
              <p className="mt-1">
                {s.text.label}
                <span className="ml-2 font-mono text-[10px] text-ink-faint">
                  {s.text.digest.slice(0, 12)}
                </span>
              </p>
            ) : (
              <p className="mt-1 whitespace-pre-wrap leading-relaxed">{s.text}</p>
            )}
          </li>
        )
      })}
    </ol>
  )
}

/** 扩展草稿叠加到标品上之后的有效定义：指令片段、工具、确认点、钩子、模型 */
export function EffectivePanel({ view }: { view: EffectiveView }): ReactNode {
  return (
    <div className="space-y-5" data-testid="effective-preview">
      <EffectiveSegments segments={view.segments} />
      <section>
        <h4 className="mb-1.5 text-xs font-medium text-ink-muted">工具</h4>
        <ul className="divide-y divide-line rounded-lg border border-line text-xs">
          {view.tools.map((t) => (
            <li
              key={`${t.source}:${t.name}`}
              className={cn(
                'flex items-center gap-2 px-3 py-1.5',
                t.layer === 'tenant' && 'bg-tenant-soft/40',
              )}
            >
              <span className="font-mono">{t.name}</span>
              <Level level={t.writeLevel} />
              <span className="ml-auto">
                <SourceBadge source={layerSource(t.layer)} />
              </span>
            </li>
          ))}
        </ul>
      </section>
      {view.approvals.length > 0 && (
        <section>
          <h4 className="mb-1.5 text-xs font-medium text-ink-muted">确认点</h4>
          <ul className="space-y-1 text-xs">
            {view.approvals.map((a, i) => (
              <li key={i} className="flex items-center gap-2">
                <span className="font-mono">{a.tool}</span>
                <span className="text-ink-faint">
                  {a.policy === 'require-approval' ? '需要审批' : '需要确认'}
                </span>
                {a.when !== null && <span className="font-mono text-ink-faint">{a.when}</span>}
                <span className="ml-auto">
                  <SourceBadge source={layerSource(a.layer)} />
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {view.hooks.length > 0 && (
        <section>
          <h4 className="mb-1.5 text-xs font-medium text-ink-muted">钩子</h4>
          <ul className="space-y-1 text-xs">
            {view.hooks.map((h, i) => (
              <li key={i} className="flex items-center gap-2">
                <span className="w-28 font-mono">{h.event}</span>
                <span className="font-mono">{h.workflow ?? h.skill}</span>
                {h.when !== null && (
                  <span className="font-mono text-ink-faint">{`当 ${h.when}`}</span>
                )}
                <span className="ml-auto text-ink-faint">
                  {h.failurePolicy === 'ignore' ? '失败不影响主流程' : h.failurePolicy}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {view.model !== null && (
        <p className="text-xs text-ink-muted">
          模型档位 <span className="font-mono text-ink">{view.model.profile}</span>
          <span className="ml-2">
            <SourceBadge source={layerSource(view.model.layer)} />
          </span>
        </p>
      )}
    </div>
  )
}
