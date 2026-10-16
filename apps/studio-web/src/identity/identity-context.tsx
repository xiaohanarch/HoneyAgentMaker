import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { useQuery } from '@tanstack/react-query'
import { DEFAULT_USER_ID, OFFLINE_USER, type DemoUser } from './demo-users'

const STORAGE_KEY = 'eab.identity'

interface IdentityContextValue {
  user: DemoUser
  users: readonly DemoUser[]
  /** 平台签发的用户令牌，调用平台接口时放在 Authorization 头里；只保存在内存中 */
  token: string | undefined
  switchUser: (id: string) => Promise<DemoUser>
}

const IdentityContext = createContext<IdentityContextValue | null>(null)

function readSavedUserId(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? DEFAULT_USER_ID
  } catch {
    return DEFAULT_USER_ID
  }
}

async function fetchUsers(): Promise<DemoUser[]> {
  const response = await fetch('/api/identity/v1/users')
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return ((await response.json()) as { data: DemoUser[] }).data
}

type Session = { token: string; user: DemoUser }

/** 进行中的登录请求：开发模式下 StrictMode 会把启动 effect 执行两次，同一用户只发一次请求 */
const inflight = new Map<string, Promise<Session>>()

/** 选人即登录（演示用，没有密码），返回平台签发的用户令牌 */
function login(userId: string): Promise<Session> {
  const pending = inflight.get(userId)
  if (pending !== undefined) return pending
  const request = fetch('/api/identity/v1/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId }),
  })
    .then(async (response) => {
      if (!response.ok) throw new Error(`登录失败：HTTP ${response.status}`)
      return ((await response.json()) as { data: Session }).data
    })
    .finally(() => inflight.delete(userId))
  inflight.set(userId, request)
  return request
}

/** 启动登录失败（平台未就绪或登录限流）后的重试间隔，逐次加长，封顶 15 秒 */
export function retryDelay(attempt: number): number {
  return Math.min(1_000 * 2 ** attempt, 15_000)
}

export function IdentityProvider({ children }: { children: ReactNode }): ReactNode {
  const { data: users = [] } = useQuery({
    queryKey: ['identity', 'users'],
    queryFn: fetchUsers,
    retry: 1,
  })
  const [userId, setUserId] = useState(readSavedUserId)
  const [session, setSession] = useState<Session | undefined>()
  const [attempt, setAttempt] = useState(0)

  const switchUser = useCallback(async (id: string): Promise<DemoUser> => {
    const next = await login(id)
    setSession(next)
    setUserId(next.user.id)
    try {
      localStorage.setItem(STORAGE_KEY, next.user.id)
    } catch {
      // 隐私模式下无法持久化，只影响刷新后的默认身份
    }
    return next.user
  }, [])

  // 启动时以上次选择的身份登录；失败时保留占位身份，不阻塞界面，并按退避间隔重试
  useEffect(() => {
    if (session !== undefined) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    login(userId).then(
      (next) => {
        if (!cancelled) setSession(next)
      },
      () => {
        if (!cancelled) timer = setTimeout(() => setAttempt((n) => n + 1), retryDelay(attempt))
      },
    )
    return () => {
      cancelled = true
      if (timer !== undefined) clearTimeout(timer)
    }
  }, [session, userId, attempt])

  const user = session?.user ?? users.find((u) => u.id === userId) ?? OFFLINE_USER
  const value = useMemo(
    () => ({ user, users, token: session?.token, switchUser }),
    [user, users, session, switchUser],
  )
  return <IdentityContext.Provider value={value}>{children}</IdentityContext.Provider>
}

export function useIdentity(): IdentityContextValue {
  const ctx = useContext(IdentityContext)
  if (ctx === null) throw new Error('useIdentity 必须在 IdentityProvider 内使用')
  return ctx
}
