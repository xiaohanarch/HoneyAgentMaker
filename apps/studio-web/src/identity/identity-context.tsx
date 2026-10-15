import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { DEFAULT_USER_ID, findDemoUser, type DemoUser } from './demo-users'

const STORAGE_KEY = 'eab.identity'

interface IdentityContextValue {
  user: DemoUser
  switchUser: (id: string) => DemoUser
}

const IdentityContext = createContext<IdentityContextValue | null>(null)

function readSavedUserId(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? DEFAULT_USER_ID
  } catch {
    return DEFAULT_USER_ID
  }
}

export function IdentityProvider({ children }: { children: ReactNode }): ReactNode {
  const [user, setUser] = useState<DemoUser>(() => findDemoUser(readSavedUserId()))

  const switchUser = useCallback((id: string): DemoUser => {
    const next = findDemoUser(id)
    setUser(next)
    try {
      localStorage.setItem(STORAGE_KEY, next.id)
    } catch {
      // 隐私模式下无法持久化，只影响刷新后的默认身份
    }
    return next
  }, [])

  const value = useMemo(() => ({ user, switchUser }), [user, switchUser])
  return <IdentityContext.Provider value={value}>{children}</IdentityContext.Provider>
}

export function useIdentity(): IdentityContextValue {
  const ctx = useContext(IdentityContext)
  if (ctx === null) throw new Error('useIdentity 必须在 IdentityProvider 内使用')
  return ctx
}
