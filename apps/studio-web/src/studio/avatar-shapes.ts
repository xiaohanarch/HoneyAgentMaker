/**
 * 由 Agent 名称确定性地生成几何头像（主提示词 10.2 节：用 SVG 生成，不调用图像模型）。
 * 同一个名称永远得到同一个头像；颜色只取设计令牌里的完整类名，不拼接类名。
 */

export type AvatarShape =
  | { type: 'circle'; cx: number; cy: number; r: number; className: string }
  | { type: 'rect'; x: number; y: number; size: number; rotate: number; className: string }
  | { type: 'triangle'; points: string; className: string }

export interface Avatar {
  background: string
  shapes: AvatarShape[]
}

/** 背景色与图形色成对出现，保证对比度 */
const PALETTES: readonly { background: string; shapes: readonly string[] }[] = [
  { background: 'fill-tenant-soft', shapes: ['fill-tenant', 'fill-vendor', 'fill-ink'] },
  { background: 'fill-vendor-soft', shapes: ['fill-vendor', 'fill-tenant', 'fill-ink'] },
  { background: 'fill-external-soft', shapes: ['fill-external', 'fill-platform', 'fill-ink'] },
  { background: 'fill-platform-soft', shapes: ['fill-platform', 'fill-external', 'fill-ink'] },
  { background: 'fill-info-soft', shapes: ['fill-info', 'fill-tenant', 'fill-ink'] },
]

const PALETTES_FALLBACK = { background: 'fill-tenant-soft', shapes: ['fill-tenant'] } as const

/** FNV-1a 32 位哈希 */
export function hashName(name: string): number {
  let hash = 0x811c9dc5
  for (const char of name) {
    hash ^= char.codePointAt(0) ?? 0
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash >>> 0
}

/** 从哈希里依次取出若干位，作为确定性的伪随机数 */
function bits(seed: number): (count: number) => number {
  let state = seed || 1
  return (count) => {
    // xorshift32
    state ^= state << 13
    state >>>= 0
    state ^= state >>> 17
    state ^= state << 5
    state >>>= 0
    return state % count
  }
}

export function avatarFor(name: string): Avatar {
  const next = bits(hashName(name.trim() === '' ? '?' : name))
  const palette = PALETTES[next(PALETTES.length)] ?? PALETTES_FALLBACK
  const shapes: AvatarShape[] = []
  const count = 2 + next(2)
  for (let i = 0; i < count; i++) {
    const className = palette.shapes[i % palette.shapes.length] ?? 'fill-ink'
    const kind = next(3)
    const x = 8 + next(5) * 4
    const y = 8 + next(5) * 4
    if (kind === 0) {
      shapes.push({ type: 'circle', cx: x + 6, cy: y + 6, r: 5 + next(4), className })
    } else if (kind === 1) {
      shapes.push({ type: 'rect', x, y, size: 10 + next(6), rotate: next(4) * 15, className })
    } else {
      const s = 12 + next(6)
      shapes.push({
        type: 'triangle',
        points: `${x},${y + s} ${x + s / 2},${y} ${x + s},${y + s}`,
        className,
      })
    }
  }
  return { background: palette.background, shapes }
}
