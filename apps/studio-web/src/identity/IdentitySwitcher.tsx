import type { ReactNode } from 'react'
import { DropdownMenu } from 'radix-ui'
import { Check, ChevronDown } from 'lucide-react'
import { cn } from '@eab/ui'
import type { DemoUser } from './demo-users'
import { useIdentity } from './identity-context'
import { usePasscodeLogin } from './use-passcode-login'

type Tone = 'tenant' | 'vendor' | 'platform'

const toneOf = (user: DemoUser): Tone =>
  user.tenantId === 'vendor' ? 'vendor' : user.tenantId === 'platform' ? 'platform' : 'tenant'

const TONE_CLASS: Readonly<Record<Tone, string>> = {
  tenant: 'bg-tenant-soft text-tenant',
  vendor: 'bg-vendor-soft text-vendor',
  platform: 'bg-platform-soft text-platform',
}

function Initial({ name, tone }: { name: string; tone: Tone }): ReactNode {
  return (
    <span
      aria-hidden
      className={cn(
        'grid size-7 shrink-0 place-items-center rounded-full text-xs font-semibold',
        TONE_CLASS[tone],
      )}
    >
      {name.slice(0, 1)}
    </span>
  )
}

export function IdentitySwitcher(): ReactNode {
  const { user, users, passcodeRequired } = useIdentity()
  const { loginAs, prompt } = usePasscodeLogin()

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        className="flex items-center gap-2 rounded-lg py-1 pr-2 pl-1 text-left hover:bg-surface-sunken data-[state=open]:bg-surface-sunken"
        aria-label={`切换演示身份，当前 ${user.name}，${user.tenantName} ${user.role}`}
        data-testid="identity-switcher"
      >
        <Initial name={user.name} tone={toneOf(user)} />
        <span className="leading-tight">
          <span className="block text-sm font-medium">{user.name}</span>
          <span className="block text-[11px] text-ink-muted">
            {user.tenantName} · {user.role}
          </span>
        </span>
        <ChevronDown aria-hidden className="size-4 text-ink-faint" />
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="z-50 w-80 rounded-xl border border-line bg-surface p-1.5 shadow-lg shadow-black/5"
        >
          <DropdownMenu.Label className="px-2 pt-1 pb-2 text-[11px] font-medium tracking-wide text-ink-faint">
            {passcodeRequired ? '演示身份（口令登录）' : '演示身份（选人即登录）'}
          </DropdownMenu.Label>
          {users.map((u) => (
            <DropdownMenu.Item
              key={u.id}
              onSelect={() => loginAs(u)}
              className="flex cursor-default items-center gap-2.5 rounded-lg px-2 py-1.5 outline-none data-[highlighted]:bg-surface-sunken"
            >
              <Initial name={u.name} tone={toneOf(u)} />
              <span className="min-w-0 flex-1 leading-tight">
                <span className="block text-sm">
                  {u.name}
                  <span className="ml-1.5 text-xs text-ink-muted">{u.role}</span>
                </span>
                <span className="block truncate text-[11px] text-ink-faint">{u.dataScope}</span>
              </span>
              {u.id === user.id && <Check aria-hidden className="size-4 text-focus" />}
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
      {prompt}
    </DropdownMenu.Root>
  )
}
