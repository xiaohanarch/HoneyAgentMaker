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

/** 登录失败时带上平台错误信封的 code（PASSCODE_REQUIRED、INVALID_PASSCODE、LOGIN_DISABLED 等） */
export class LoginError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'LoginError'
    this.code = code
  }
}

interface IdentityContextValue {
  user: DemoUser
  users: readonly DemoUser[]
  /** 平台签发的用户令牌，调用平台接口时放在 Authorization 头里；只保存在内存中 */
  token: string | undefined
  /** 平台设置了口令表时为 true：切换身份需要先输口令（公网部署） */
  passcodeRequired: boolean
  switchUser: (id: string, passcode?: string) => Promise<DemoUser>
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

async function fetchLoginConfig(): Promise<boolean> {
  const response = await fetch('/api/identity/v1/login-config')
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return ((await response.json()) as { data: { passcodeRequired: boolean } }).data.passcodeRequired
}

type Session = { token: string; user: DemoUser }

/** 进行中的登录请求：开发模式下 StrictMode 会把启动 effect 执行两次，同一用户只发一次请求 */
const inflight = new Map<string, Promise<Session>>()

/** 从错误响应体里取信封的 error.code，取不到时退回 HTTP 状态码 */
export function loginErrorCode(status: number, body: unknown): string {
  const code = (body as { error?: { code?: string } } | null)?.error?.code
  return code ?? `HTTP_${status}`
}

/** 登录失败的可读信息：优先用信封里的 error.message */
function loginErrorMessage(status: number, body: unknown): string {
  const message = (body as { error?: { message?: string } } | null)?.error?.message
  return message ?? `登录失败：HTTP ${status}`
}

/** 选人登录（演示用）；平台设置了口令表时必须带 passcode */
function login(userId: string, passcode?: string): Promise<Session> {
  // 启动时的无口令登录按用户去重；带口令的登录由用户主动触发，不去重
  const key = passcode === undefined ? userId : undefined
  const pending = key !== undefined ? inflight.get(key) : undefined
  if (pending !== undefined) return pending
  const request = fetch('/api/identity/v1/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(passcode === undefined ? { userId } : { userId, passcode }),
  })
    .then(async (response) => {
      const body = await response.json().catch(() => null)
      if (!response.ok) throw new LoginError(loginErrorCode(response.status, body), loginErrorMessage(response.status, body))
      return (body as { data: Session }).data
    })
    .finally(() => {
      if (key !== undefined) inflight.delete(key)
    })
  if (key !== undefined) inflight.set(key, request)
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
  // 口令开关随部署配置固定，加载一次即可；加载失败时先按无口令处理，错误路径由登录响应兜底
  const { data: loginConfig } = useQuery({
    queryKey: ['identity', 'login-config'],
    queryFn: fetchLoginConfig,
    retry: 1,
    staleTime: Infinity,
  })
  const [userId, setUserId] = useState(readSavedUserId)
  const [session, setSession] = useState<Session | undefined>()
  const [attempt, setAttempt] = useState(0)

  const switchUser = useCallback(async (id: string, passcode?: string): Promise<DemoUser> => {
    const next = await login(id, passcode)
    setSession(next)
    setUserId(next.user.id)
    try {
      localStorage.setItem(STORAGE_KEY, next.user.id)
    } catch {
      // 隐私模式下无法持久化，只影响刷新后的默认身份
    }
    return next.user
  }, [])

  // 启动时以上次选择的身份登录；失败时保留占位身份，不阻塞界面。需要口令或身份被禁用是确定性
  // 错误，不再重试，等用户在身份切换器里输口令；其他失败（平台未就绪、限流）按退避间隔重试
  useEffect(() => {
    if (session !== undefined) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    login(userId).then(
      (next) => {
        if (!cancelled) setSession(next)
      },
      (error: unknown) => {
        if (cancelled) return
        if (error instanceof LoginError && (error.code === 'PASSCODE_REQUIRED' || error.code === 'LOGIN_DISABLED')) return
        timer = setTimeout(() => setAttempt((n) => n + 1), retryDelay(attempt))
      },
    )
    return () => {
      cancelled = true
      if (timer !== undefined) clearTimeout(timer)
    }
  }, [session, userId, attempt])

  const user = session?.user ?? users.find((u) => u.id === userId) ?? OFFLINE_USER
  const value = useMemo(
    () => ({ user, users, token: session?.token, passcodeRequired: loginConfig === true, switchUser }),
    [user, users, session, loginConfig, switchUser],
  )
  return <IdentityContext.Provider value={value}>{children}</IdentityContext.Provider>
}

export function useIdentity(): IdentityContextValue {
  const ctx = useContext(IdentityContext)
  if (ctx === null) throw new Error('useIdentity 必须在 IdentityProvider 内使用')
  return ctx
}
