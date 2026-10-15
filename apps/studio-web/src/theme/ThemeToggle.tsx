import { useState, type ReactNode } from 'react'
import { Moon, Sun } from 'lucide-react'

const STORAGE_KEY = 'eab.theme'

export function ThemeToggle(): ReactNode {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))

  function toggle(): void {
    const next = !dark
    document.documentElement.classList.toggle('dark', next)
    setDark(next)
    try {
      localStorage.setItem(STORAGE_KEY, next ? 'dark' : 'light')
    } catch {
      // 无法持久化时只影响刷新后的主题
    }
  }

  const Icon = dark ? Sun : Moon
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={dark ? '切换到浅色模式' : '切换到深色模式'}
      className="grid size-8 place-items-center rounded-lg text-ink-muted hover:bg-surface-sunken hover:text-ink"
    >
      <Icon aria-hidden className="size-4" />
    </button>
  )
}
