import type { ReactNode } from 'react'
import { Pause, Play, SkipBack, SkipForward } from 'lucide-react'
import { cn } from '@eab/ui'

const SPEEDS: readonly number[] = [0.5, 1, 2]

const BTN =
  'inline-flex items-center gap-1 rounded-md border border-line bg-surface px-2.5 py-1.5 text-xs font-medium text-ink transition-colors hover:border-line-strong hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus disabled:cursor-not-allowed disabled:opacity-40'

/** 回放控制：播放暂停、单步、速度、进度条与当前步骤的字幕 */
export function ReplayControls({
  playing,
  onToggle,
  onStep,
  speed,
  onSpeed,
  progress,
  onSeek,
  caption,
  atEnd,
}: {
  playing: boolean
  onToggle: () => void
  onStep: (delta: 1 | -1) => void
  speed: number
  onSpeed: (speed: number) => void
  progress: number
  onSeek: (progress: number) => void
  caption: string
  atEnd: boolean
}): ReactNode {
  return (
    <div
      className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-xl border border-line bg-surface px-4 py-3"
      data-testid="replay-controls"
    >
      <div className="flex items-center gap-1.5">
        <button
          type="button"
          aria-label="上一步"
          data-testid="replay-prev"
          className={BTN}
          onClick={() => onStep(-1)}
        >
          <SkipBack aria-hidden className="size-3.5" />
        </button>
        <button
          type="button"
          aria-label={playing ? '暂停' : '播放'}
          data-testid="replay-play"
          className={cn(
            BTN,
            'border-ink/20 bg-ink text-surface hover:bg-ink/85 hover:text-surface',
          )}
          onClick={onToggle}
        >
          {playing ? (
            <Pause aria-hidden className="size-3.5" />
          ) : (
            <Play aria-hidden className="size-3.5" />
          )}
        </button>
        <button
          type="button"
          aria-label="下一步"
          data-testid="replay-next"
          className={BTN}
          onClick={() => onStep(1)}
          disabled={atEnd}
        >
          <SkipForward aria-hidden className="size-3.5" />
        </button>
      </div>
      <div className="flex items-center gap-1" role="group" aria-label="回放速度">
        {SPEEDS.map((s) => (
          <button
            key={s}
            type="button"
            className={cn(
              'rounded-md px-2 py-1 text-[11px] font-medium transition-colors',
              s === speed
                ? 'bg-surface-sunken text-ink ring-1 ring-line'
                : 'text-ink-muted hover:text-ink',
            )}
            aria-pressed={s === speed}
            onClick={() => onSpeed(s)}
          >
            {`${s}×`}
          </button>
        ))}
      </div>
      <input
        type="range"
        min={0}
        max={1000}
        value={Math.round(progress * 1000)}
        aria-label="回放进度"
        data-testid="replay-progress"
        onChange={(e) => onSeek(Number(e.target.value) / 1000)}
        className="h-1.5 min-w-[10rem] flex-1 accent-ink"
      />
      <p
        className="min-w-0 flex-1 basis-full truncate text-xs text-ink-muted sm:basis-auto"
        data-testid="replay-caption"
      >
        {caption}
      </p>
    </div>
  )
}
