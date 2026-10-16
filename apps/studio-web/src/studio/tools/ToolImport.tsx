import { useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Cable, Check, Download, ShieldCheck, X } from 'lucide-react'
import { WRITE_LEVELS } from '@eab/ui'
import { useIdentity } from '../../identity/identity-context'
import { errorText } from '../use-studio'
import { useExtendApi } from '../extend/use-extend'
import { Level } from './Level'

const INPUT =
  'rounded-md border border-line bg-canvas px-2 py-1 text-sm outline-none focus:border-line-strong'
const PANEL = 'rounded-xl border border-line bg-surface'
const TITLE = 'flex items-center gap-2 border-b border-line px-4 py-2.5 text-sm font-medium'

type Status = { busy: boolean; error?: string; done?: string }

/** 从 DemoERP 的 OpenAPI 描述选出 BO 操作，生成一个租户工具源 */
export function BoImport({ onImported }: { onImported: () => void }): ReactNode {
  const { api, ready } = useExtendApi()
  const client = useQueryClient()
  const { data, error } = useQuery({
    queryKey: ['studio', 'bo-operations'],
    queryFn: () => api.boOperations(),
    enabled: ready,
  })
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set())
  const [name, setName] = useState('erp-bo-extra')
  const [displayName, setDisplayName] = useState('ERP 业务对象（补充）')
  const [status, setStatus] = useState<Status>({ busy: false })

  const toggle = (op: string): void => {
    const next = new Set(picked)
    if (next.has(op)) next.delete(op)
    else next.add(op)
    setPicked(next)
  }
  const submit = (): void => {
    setStatus({ busy: true })
    api.importBo(name.trim(), displayName.trim(), [...picked]).then(
      (r) => {
        setStatus({ busy: false, done: `已生成 ${r.ref} ${r.version}，共 ${r.tools} 个工具` })
        setPicked(new Set())
        void client.invalidateQueries({ queryKey: ['studio', 'bo-operations'] })
        onImported()
      },
      (e: unknown) => setStatus({ busy: false, error: errorText(e) }),
    )
  }

  return (
    <section className={PANEL} data-testid="bo-import">
      <h2 className={TITLE}>
        <Download aria-hidden className="size-4 text-ink-muted" />从 BO API 导入
      </h2>
      <div className="space-y-3 px-4 py-3">
        {error !== null && <p className="text-xs text-deny">{errorText(error)}</p>}
        <ul className="max-h-64 space-y-1 overflow-y-auto text-xs">
          {(data ?? []).map((op) => (
            <li key={op.name}>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={picked.has(op.name)}
                  disabled={op.imported}
                  onChange={() => toggle(op.name)}
                />
                <span className="font-mono">{op.name}</span>
                <Level level={op.writeLevel} />
                <span className="ml-auto font-mono text-[10px] text-ink-faint">
                  {op.imported ? '已导入' : `${op.method} ${op.path}`}
                </span>
              </label>
            </li>
          ))}
        </ul>
        <div className="flex gap-2">
          <input
            aria-label="工具源名"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className={`${INPUT} w-36 font-mono text-xs`}
          />
          <input
            aria-label="工具源显示名"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className={`${INPUT} flex-1`}
          />
        </div>
        <button
          type="button"
          onClick={submit}
          disabled={status.busy || picked.size === 0}
          className="rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
        >
          {`生成工具（${picked.size}）`}
        </button>
        {status.done !== undefined && <p className="text-xs text-allow">{status.done}</p>}
        {status.error !== undefined && <p className="text-xs text-deny">{status.error}</p>}
      </div>
    </section>
  )
}

/** 登记 MCP Server：列出连接上的工具，逐个标写级别；登记后要租户管理员审批才能进 prod */
export function McpRegister({ onRegistered }: { onRegistered: () => void }): ReactNode {
  const { api } = useExtendApi()
  const client = useQueryClient()
  const [connection, setConnection] = useState('LOGISTICS_MCP')
  const [tools, setTools] = useState<
    { name: string; description: string; writeLevel: string; picked: boolean }[] | null
  >(null)
  const [name, setName] = useState('logistics')
  const [displayName, setDisplayName] = useState('物流查询')
  const [status, setStatus] = useState<Status>({ busy: false })

  const list = (): void => {
    setStatus({ busy: true })
    api.mcpTools(connection.trim()).then(
      (found) => {
        setTools(
          found.map((t) => ({
            name: t.name,
            description: t.description,
            writeLevel: 'L0',
            picked: true,
          })),
        )
        setStatus({ busy: false })
      },
      (e: unknown) => setStatus({ busy: false, error: errorText(e) }),
    )
  }
  const register = (): void => {
    if (tools === null) return
    setStatus({ busy: true })
    api
      .registerMcp({
        name: name.trim(),
        displayName: displayName.trim(),
        connection: connection.trim(),
        tools: tools
          .filter((t) => t.picked)
          .map((t) => ({ name: t.name, writeLevel: t.writeLevel })),
      })
      .then(
        (r) => {
          setStatus({
            busy: false,
            done: `已登记 ${r.ref} ${r.version}，审批状态：${r.approval === 'pending' ? '待租户管理员审批' : r.approval}`,
          })
          void client.invalidateQueries({ queryKey: ['studio', 'mcp-approvals'] })
          onRegistered()
        },
        (e: unknown) => setStatus({ busy: false, error: errorText(e) }),
      )
  }

  return (
    <section className={PANEL} data-testid="mcp-register">
      <h2 className={TITLE}>
        <Cable aria-hidden className="size-4 text-ink-muted" />
        登记 MCP Server
      </h2>
      <div className="space-y-3 px-4 py-3">
        <div className="flex gap-2">
          <input
            aria-label="MCP 连接别名"
            value={connection}
            onChange={(e) => setConnection(e.target.value)}
            className={`${INPUT} flex-1 font-mono text-xs`}
          />
          <button
            type="button"
            onClick={list}
            disabled={status.busy}
            className="rounded-lg border border-line px-3 py-1 text-sm text-ink-muted hover:text-ink disabled:opacity-40"
          >
            列出工具
          </button>
        </div>
        <p className="text-[11px] text-ink-faint">只填连接中心里的连接别名，地址和凭据不进资产。</p>
        {tools !== null && (
          <>
            <ul className="space-y-1 text-xs" data-testid="mcp-tools">
              {tools.map((t, i) => (
                <li key={t.name} className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    aria-label={`登记 ${t.name}`}
                    checked={t.picked}
                    onChange={() =>
                      setTools(tools.map((x, j) => (j === i ? { ...x, picked: !x.picked } : x)))
                    }
                  />
                  <span className="font-mono" title={t.description}>
                    {t.name}
                  </span>
                  <select
                    aria-label={`${t.name} 写级别`}
                    value={t.writeLevel}
                    onChange={(e) =>
                      setTools(
                        tools.map((x, j) => (j === i ? { ...x, writeLevel: e.target.value } : x)),
                      )
                    }
                    className="ml-auto rounded border border-line bg-canvas px-1 py-px text-[11px]"
                  >
                    {WRITE_LEVELS.map((l) => (
                      <option key={l} value={l}>
                        {l}
                      </option>
                    ))}
                  </select>
                </li>
              ))}
            </ul>
            <div className="flex gap-2">
              <input
                aria-label="MCP 工具源名"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className={`${INPUT} w-28 font-mono text-xs`}
              />
              <input
                aria-label="MCP 工具源显示名"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className={`${INPUT} flex-1`}
              />
            </div>
            <button
              type="button"
              onClick={register}
              disabled={status.busy || !tools.some((t) => t.picked)}
              className="rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
            >
              登记并提交审批
            </button>
          </>
        )}
        {status.done !== undefined && <p className="text-xs text-allow">{status.done}</p>}
        {status.error !== undefined && <p className="text-xs text-deny">{status.error}</p>}
      </div>
    </section>
  )
}

/** MCP 审批：租户管理员批准后，用到这些工具的资产才能晋级到 prod */
export function McpApprovals({ onDecided }: { onDecided: () => void }): ReactNode {
  const { api, ready } = useExtendApi()
  const { user } = useIdentity()
  const client = useQueryClient()
  const isAdmin = user.role === '租户管理员'
  const { data, error } = useQuery({
    queryKey: ['studio', 'mcp-approvals'],
    queryFn: () => api.approvals(),
    enabled: ready,
  })
  const [failure, setFailure] = useState<string | null>(null)
  const decide = (id: number, approved: boolean): void => {
    setFailure(null)
    api.decide(id, approved, approved ? '已核对工具清单和写级别' : '写级别标注需要复核').then(
      () => {
        void client.invalidateQueries({ queryKey: ['studio', 'mcp-approvals'] })
        onDecided()
      },
      (e: unknown) => setFailure(errorText(e)),
    )
  }

  return (
    <section className={PANEL} data-testid="mcp-approvals">
      <h2 className={TITLE}>
        <ShieldCheck aria-hidden className="size-4 text-ink-muted" />
        MCP 审批
        {!isAdmin && (
          <span className="text-xs font-normal text-ink-faint">只有租户管理员可以审批</span>
        )}
      </h2>
      <ul className="divide-y divide-line text-xs">
        {error !== null && <li className="px-4 py-2 text-deny">{errorText(error)}</li>}
        {(data ?? []).length === 0 && (
          <li className="px-4 py-2 text-ink-faint">没有登记过的 MCP Server</li>
        )}
        {(data ?? []).map((a) => (
          <li key={a.id} className="space-y-1 px-4 py-2">
            <p className="flex items-center gap-2">
              <span className="font-mono">{a.source}</span>
              <span className="ml-auto text-ink-muted">
                {a.status === 'pending' ? '待审批' : a.status === 'approved' ? '已批准' : '已拒绝'}
              </span>
            </p>
            <p className="text-ink-faint">
              {`${a.requestedBy} 提交`}
              {a.decidedBy !== null &&
                ` · ${a.decidedBy} 处理${a.note === null ? '' : `：${a.note}`}`}
            </p>
            {isAdmin && a.status === 'pending' && (
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => decide(a.id, true)}
                  className="inline-flex items-center gap-1 text-allow"
                >
                  <Check aria-hidden className="size-3.5" />
                  批准
                </button>
                <button
                  type="button"
                  onClick={() => decide(a.id, false)}
                  className="inline-flex items-center gap-1 text-deny"
                >
                  <X aria-hidden className="size-3.5" />
                  拒绝
                </button>
              </div>
            )}
          </li>
        ))}
        {failure !== null && <li className="px-4 py-2 text-deny">{failure}</li>}
      </ul>
    </section>
  )
}
