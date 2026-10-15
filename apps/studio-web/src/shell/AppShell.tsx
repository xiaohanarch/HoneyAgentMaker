import type { ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router'
import { ASSET_SOURCES, SourceBadge, cn } from '@eab/ui'
import { IdentitySwitcher } from '../identity/IdentitySwitcher'
import { ThemeToggle } from '../theme/ThemeToggle'
import { ServiceStatus } from './ServiceStatus'

const WORKSPACES = [
  { to: '/assistant', label: '统一入口', hint: '最终用户' },
  { to: '/studio', label: 'Studio', hint: '开发态' },
  { to: '/hub', label: '治理中心', hint: '管理者' },
] as const

function Mark(): ReactNode {
  return (
    <svg viewBox="0 0 32 32" className="size-7" aria-hidden>
      <rect width="32" height="32" rx="7" className="fill-ink" />
      <path
        d="M8 22V10h8M8 16h6"
        className="stroke-vendor-soft"
        strokeWidth="3"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="22" cy="20" r="4" className="fill-tenant" />
      <circle cx="22" cy="11" r="2.5" className="fill-external" />
    </svg>
  )
}

export function AppShell(): ReactNode {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-40 border-b border-line bg-surface/90 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-[1440px] items-center gap-6 px-5">
          <div className="flex items-center gap-2.5">
            <Mark />
            <span className="text-[15px] font-semibold tracking-tight">ERP Agent Builder</span>
          </div>

          <nav aria-label="工作区" className="flex rounded-lg bg-surface-sunken p-0.5">
            {WORKSPACES.map((w) => (
              <NavLink
                key={w.to}
                to={w.to}
                className={({ isActive }) =>
                  cn(
                    'rounded-md px-3.5 py-1.5 text-sm transition-colors',
                    isActive
                      ? 'bg-surface font-medium text-ink shadow-sm ring-1 ring-line'
                      : 'text-ink-muted hover:text-ink',
                  )
                }
              >
                {w.label}
                <span className="ml-1.5 hidden text-[11px] text-ink-faint xl:inline">{w.hint}</span>
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-2">
            <ThemeToggle />
            <span aria-hidden className="h-6 w-px bg-line" />
            <IdentitySwitcher />
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-[1440px] flex-1 px-5 py-6">
        <Outlet />
      </main>

      <footer className="border-t border-line bg-surface">
        <div className="mx-auto flex h-9 max-w-[1440px] items-center gap-6 px-5 text-xs text-ink-muted">
          <ServiceStatus />
          <div className="ml-auto flex items-center gap-1.5">
            <span className="mr-1 text-ink-faint">资产来源</span>
            {ASSET_SOURCES.map((s) => (
              <SourceBadge key={s} source={s} />
            ))}
          </div>
        </div>
      </footer>
    </div>
  )
}
