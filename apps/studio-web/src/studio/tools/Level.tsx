import type { ReactNode } from 'react'
import { WriteLevelBadge, WRITE_LEVELS, type WriteLevel } from '@eab/ui'

/** 写级别徽标；接口返回的不是 L0–L3 时不显示 */
export function Level({ level }: { level: string | null | undefined }): ReactNode {
  return level != null && (WRITE_LEVELS as readonly string[]).includes(level) ? (
    <WriteLevelBadge level={level as WriteLevel} />
  ) : null
}
