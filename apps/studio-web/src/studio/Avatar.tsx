import type { ReactNode } from 'react'
import { cn } from '@eab/ui'
import { avatarFor } from './avatar-shapes'

/** 由名称生成的几何头像 */
export function Avatar({ name, className }: { name: string; className?: string }): ReactNode {
  const avatar = avatarFor(name)
  return (
    <svg
      viewBox="0 0 48 48"
      role="img"
      aria-label={`${name || '新建 Agent'} 的头像`}
      data-testid="agent-avatar"
      className={cn('size-12 shrink-0 rounded-xl', className)}
    >
      <rect width="48" height="48" rx="10" className={avatar.background} />
      {avatar.shapes.map((s, i) => {
        if (s.type === 'circle') {
          return (
            <circle key={i} cx={s.cx} cy={s.cy} r={s.r} className={s.className} opacity={0.9} />
          )
        }
        if (s.type === 'rect') {
          const c = s.x + s.size / 2
          const m = s.y + s.size / 2
          return (
            <rect
              key={i}
              x={s.x}
              y={s.y}
              width={s.size}
              height={s.size}
              rx={2}
              transform={`rotate(${s.rotate} ${c} ${m})`}
              className={s.className}
              opacity={0.85}
            />
          )
        }
        return <polygon key={i} points={s.points} className={s.className} opacity={0.85} />
      })}
    </svg>
  )
}
