import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Cable, KeyRound, Lock, Pencil, X } from 'lucide-react'
import { cn } from '@eab/ui'
import { useIdentity } from '../identity/identity-context'
import { getJson, putJson } from '../assist/api'

/** 环境按晋级顺序排列；private 是开发者的私有环境 */
const ENVIRONMENTS = [
  { key: 'private', label: '私有' },
  { key: 'dev', label: 'dev' },
  { key: 'test', label: 'test' },
  { key: 'prod', label: 'prod' },
] as const

type Environment = (typeof ENVIRONMENTS)[number]['key']

const AUTH_TYPES = [
  { value: 'none', label: '无认证' },
  { value: 'token-exchange', label: '令牌交换（用户 + Agent）' },
  { value: 'platform-service-token', label: '平台服务令牌' },
  { value: 'client-credentials', label: '客户端凭据' },
] as const

const TYPE_LABELS: Record<string, string> = {
  'bo-api': 'BO API',
  mcp: 'MCP Server',
  a2a: 'A2A Agent',
  http: 'HTTP',
}

interface Binding {
  url: string
  auth: { type: string; credentialRef?: string }
}

interface Connection {
  alias: string
  type: string
  ref: string
  version: string
  bindings: Partial<Record<Environment, Binding>>
  usedBy: string[]
  editable: boolean
}

interface Editing {
  connection: Connection
  environment: Environment
}

function authLabel(type: string): string {
  return AUTH_TYPES.find((a) => a.value === type)?.label ?? type
}

function BindingCell({
  binding,
  editable,
  onEdit,
  label,
}: {
  binding: Binding | undefined
  editable: boolean
  onEdit: () => void
  label: string
}): ReactNode {
  const body =
    binding === undefined ? (
      <span className="text-ink-faint">未绑定</span>
    ) : (
      <>
        <span className="block truncate font-mono text-[11px] text-ink" title={binding.url}>
          {binding.url}
        </span>
        <span className="mt-0.5 block truncate text-[11px] text-ink-muted">
          {authLabel(binding.auth.type)}
        </span>
        {binding.auth.credentialRef !== undefined && (
          <span
            className="mt-0.5 inline-flex max-w-full items-center gap-1 truncate font-mono text-[10px] text-ink-faint"
            title={binding.auth.credentialRef}
          >
            <KeyRound aria-hidden className="size-3 shrink-0" />
            {binding.auth.credentialRef}
          </span>
        )}
      </>
    )
  if (!editable) return <div className="min-w-0 px-3 py-2.5">{body}</div>
  return (
    <button
      type="button"
      onClick={onEdit}
      aria-label={`修改 ${label} 环境的绑定`}
      className="group relative block w-full min-w-0 px-3 py-2.5 text-left transition-colors hover:bg-surface-sunken focus-visible:bg-surface-sunken"
    >
      {body}
      <Pencil
        aria-hidden
        className="absolute top-2.5 right-2 size-3 text-ink-faint opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
      />
    </button>
  )
}

function EditDialog({
  editing,
  token,
  onClose,
}: {
  editing: Editing
  token: string | undefined
  onClose: () => void
}): ReactNode {
  const client = useQueryClient()
  const current = editing.connection.bindings[editing.environment]
  const [url, setUrl] = useState(current?.url ?? '')
  const [authType, setAuthType] = useState(current?.auth.type ?? 'none')
  const [credentialRef, setCredentialRef] = useState(current?.auth.credentialRef ?? '')
  const save = useMutation({
    mutationFn: () =>
      putJson<Connection>(
        `/studio/connections/${encodeURIComponent(editing.connection.alias)}/bindings/${editing.environment}`,
        token,
        { url: url.trim(), authType, credentialRef: credentialRef.trim() || undefined },
      ),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['studio', 'connections'] })
      onClose()
    },
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const submit = (e: FormEvent): void => {
    e.preventDefault()
    save.mutate()
  }
  const envLabel = ENVIRONMENTS.find((x) => x.key === editing.environment)?.label

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4">
      <form
        role="dialog"
        aria-modal="true"
        aria-labelledby="binding-dialog-title"
        onSubmit={submit}
        className="w-full max-w-md rounded-xl border border-line bg-surface shadow-lg"
      >
        <header className="flex items-center gap-2 border-b border-line px-5 py-3">
          <h2 id="binding-dialog-title" className="text-sm font-medium">
            {`修改 ${editing.connection.alias} · ${envLabel} 环境的绑定`}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="ml-auto text-ink-faint hover:text-ink"
          >
            <X className="size-4" />
          </button>
        </header>
        <div className="space-y-3 px-5 py-4 text-sm">
          <label className="block">
            <span className="text-xs text-ink-muted">地址</span>
            <input
              required
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://erp.example.test"
              className="mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 font-mono text-xs outline-none focus:border-line-strong"
            />
          </label>
          <label className="block">
            <span className="text-xs text-ink-muted">认证方式</span>
            <select
              value={authType}
              onChange={(e) => setAuthType(e.target.value)}
              className="mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 text-xs outline-none focus:border-line-strong"
            >
              {AUTH_TYPES.map((a) => (
                <option key={a.value} value={a.value}>
                  {a.label}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-xs text-ink-muted">凭据引用（可选）</span>
            <input
              value={credentialRef}
              onChange={(e) => setCredentialRef(e.target.value)}
              placeholder="vault:erp/client 或 env:ERP_CLIENT_SECRET"
              className="mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 font-mono text-xs outline-none focus:border-line-strong"
            />
            <span className="mt-1 block text-[11px] text-ink-faint">
              只能写凭据的引用，凭据本身保存在密钥管理里，不进入资产和制品。
            </span>
          </label>
          {save.error !== null && <p className="text-xs text-deny">{save.error.message}</p>}
        </div>
        <footer className="flex justify-end gap-2 border-t border-line px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-line-strong px-3 py-1.5 text-xs hover:bg-surface-sunken"
          >
            取消
          </button>
          <button
            type="submit"
            disabled={save.isPending}
            className="rounded-md bg-ink px-3 py-1.5 text-xs font-medium text-surface hover:opacity-90 disabled:opacity-50"
          >
            {save.isPending ? '保存中…' : '保存并发布新版本'}
          </button>
        </footer>
      </form>
    </div>
  )
}

/**
 * 连接中心（主提示词 10.8.2 节）：资产里只引用连接别名，地址、认证方式和凭据引用按环境绑定在这里。
 * 租户管理员可以修改绑定，修改会写入 Connection 资产的新版本；其他角色只读。
 */
export function ConnectionCenterPage(): ReactNode {
  const { token } = useIdentity()
  const [editing, setEditing] = useState<Editing | undefined>()
  const { data, error, isLoading } = useQuery({
    queryKey: ['studio', 'connections', token],
    queryFn: () => getJson<Connection[]>('/studio/connections', token),
    enabled: token !== undefined,
  })
  const editable = data?.some((c) => c.editable) ?? false

  return (
    <div className="space-y-5">
      <header className="flex items-end gap-4">
        <div>
          <Link
            to="/studio"
            className="inline-flex items-center gap-1 text-xs text-ink-muted hover:text-ink"
          >
            <ArrowLeft aria-hidden className="size-3.5" />
            Studio
          </Link>
          <h1 className="mt-1 flex items-center gap-2 text-2xl font-semibold tracking-tight">
            <Cable aria-hidden className="size-5 text-ink-muted" />
            连接中心
          </h1>
          <p className="mt-1 text-sm text-ink-muted">
            资产里只写连接别名，每个环境的地址、认证方式和凭据引用在这里绑定，部署到哪个环境就用哪份绑定。
          </p>
        </div>
        {data !== undefined && !editable && (
          <p className="ml-auto flex items-center gap-1.5 rounded-md bg-surface-sunken px-3 py-1.5 text-xs text-ink-muted">
            <Lock aria-hidden className="size-3.5" />
            只读：只有租户管理员可以修改绑定
          </p>
        )}
      </header>

      <section className="overflow-hidden rounded-xl border border-line bg-surface">
        <table className="w-full table-fixed text-sm" data-testid="connection-table">
          <colgroup>
            <col className="w-[200px]" />
            {ENVIRONMENTS.map((env) => (
              <col key={env.key} />
            ))}
            <col className="w-[220px]" />
          </colgroup>
          <thead className="border-b border-line bg-surface-sunken text-left text-[11px] font-medium text-ink-muted">
            <tr>
              <th className="px-4 py-2">别名</th>
              {ENVIRONMENTS.map((env) => (
                <th key={env.key} className="px-3 py-2 font-mono">
                  {env.label}
                </th>
              ))}
              <th className="px-3 py-2">使用方</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {isLoading && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-xs text-ink-muted">
                  读取中…
                </td>
              </tr>
            )}
            {error !== null && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-xs text-deny">
                  {error.message}
                </td>
              </tr>
            )}
            {data?.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-xs text-ink-muted">
                  本租户还没有连接别名。
                </td>
              </tr>
            )}
            {data?.map((c) => (
              <tr key={c.alias} data-testid="connection-row" className="align-top">
                <td className="px-4 py-2.5">
                  <span className="block font-mono text-xs font-medium">{c.alias}</span>
                  <span
                    className={cn(
                      'mt-1 inline-block rounded px-1.5 py-0.5 text-[10px]',
                      c.type === 'a2a'
                        ? 'bg-external-soft text-external'
                        : 'bg-surface-sunken text-ink-muted',
                    )}
                  >
                    {TYPE_LABELS[c.type] ?? c.type}
                  </span>
                  <span className="mt-1 block font-mono text-[10px] text-ink-faint">{`v${c.version}`}</span>
                </td>
                {ENVIRONMENTS.map((env) => (
                  <td key={env.key} className="border-l border-line p-0">
                    <BindingCell
                      binding={c.bindings[env.key]}
                      editable={c.editable}
                      label={env.label}
                      onEdit={() => setEditing({ connection: c, environment: env.key })}
                    />
                  </td>
                ))}
                <td className="border-l border-line px-3 py-2.5">
                  {c.usedBy.length === 0 ? (
                    <span className="text-xs text-ink-faint">暂无</span>
                  ) : (
                    <ul className="space-y-0.5">
                      {c.usedBy.map((ref) => (
                        <li
                          key={ref}
                          className="truncate font-mono text-[11px] text-ink-muted"
                          title={ref}
                        >
                          {ref}
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {editing !== undefined && (
        <EditDialog editing={editing} token={token} onClose={() => setEditing(undefined)} />
      )}
    </div>
  )
}
