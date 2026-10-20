import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { X } from 'lucide-react'
import type { DemoUser } from './demo-users'
import { LoginError, useIdentity } from './identity-context'

/**
 * 切换身份的共用交互：平台未设口令时直接切换；设置了口令时先弹口令框，通过后才登录并跳到
 * 新身份的首页。顶栏的身份切换器和演示控制台里的切换入口共用这一份逻辑。
 */
export function usePasscodeLogin(): { loginAs: (user: DemoUser, onSuccess?: () => void) => void; prompt: ReactNode } {
  const { switchUser, passcodeRequired } = useIdentity()
  const navigate = useNavigate()
  const [target, setTarget] = useState<DemoUser>()
  const [onSuccess, setOnSuccess] = useState<(() => void) | undefined>()
  const [passcode, setPasscode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const loginAs = (user: DemoUser, after?: () => void): void => {
    const openDialog = (): void => {
      setTarget(user)
      setOnSuccess(() => after)
      setPasscode('')
      setError(undefined)
    }
    if (!passcodeRequired) {
      void switchUser(user.id).then(
        (next) => {
          after?.()
          navigate(`/${next.home}`)
        },
        (err: unknown) => {
          // login-config 尚未加载到而平台已设口令时，把 401 转入口令框而不是无声失败；其他错误保持原样
          if (!(err instanceof LoginError)) throw err
          if (err.code === 'PASSCODE_REQUIRED') openDialog()
          else if (err.code === 'LOGIN_DISABLED') {
            openDialog()
            setError('该身份未配置公网登录口令')
          } else throw err
        },
      )
      return
    }
    openDialog()
  }

  const close = (): void => {
    setTarget(undefined)
    setOnSuccess(undefined)
  }

  useEffect(() => {
    if (target === undefined) return
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [target])

  const submit = (e: FormEvent<HTMLFormElement>): void => {
    e.preventDefault()
    if (target === undefined || busy) return
    setBusy(true)
    setError(undefined)
    switchUser(target.id, passcode)
      .then((next) => {
        close()
        onSuccess?.()
        navigate(`/${next.home}`)
      })
      .catch((err: unknown) => {
        if (err instanceof LoginError && err.code === 'INVALID_PASSCODE') setError('口令不正确，请重试')
        else if (err instanceof LoginError && err.code === 'LOGIN_DISABLED') setError('该身份未配置公网登录口令')
        else setError('登录失败，请稍后重试')
      })
      .finally(() => setBusy(false))
  }

  const prompt =
    target === undefined ? null : (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4">
        <form
          role="dialog"
          aria-modal="true"
          aria-labelledby="passcode-dialog-title"
          onSubmit={submit}
          className="w-full max-w-sm rounded-xl border border-line bg-surface shadow-lg"
        >
          <header className="flex items-center gap-2 border-b border-line px-5 py-3">
            <h2 id="passcode-dialog-title" className="text-sm font-medium">
              {`以 ${target.name} 登录`}
            </h2>
            <button
              type="button"
              onClick={close}
              aria-label="取消"
              className="ml-auto text-ink-faint hover:text-ink"
            >
              <X className="size-4" />
            </button>
          </header>
          <div className="space-y-3 px-5 py-4 text-sm">
            <p className="text-xs text-ink-muted">
              {`${target.tenantName} · ${target.role}；公网访问需要输入该身份的口令`}
            </p>
            <label className="block">
              <span className="text-xs text-ink-muted">口令</span>
              <input
                type="password"
                value={passcode}
                onChange={(e) => setPasscode(e.target.value)}
                autoFocus
                autoComplete="off"
                data-testid="passcode-input"
                className="mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 text-xs outline-none focus:border-line-strong"
              />
            </label>
            {error !== undefined && <p className="text-xs text-deny">{error}</p>}
          </div>
          <footer className="flex justify-end gap-2 border-t border-line px-5 py-3">
            <button
              type="button"
              onClick={close}
              className="rounded-md border border-line-strong px-3 py-1.5 text-xs hover:bg-surface-sunken"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={busy || passcode.length === 0}
              data-testid="passcode-submit"
              className="rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface hover:opacity-90 disabled:opacity-50"
            >
              {busy ? '登录中…' : '登录'}
            </button>
          </footer>
        </form>
      </div>
    )

  return { loginAs, prompt }
}
