import type { ReactNode } from 'react'
import { cn } from '@eab/ui'

/** 发布前可以调整的配置，初值来自平台对 Agent Card 的建议 */
export interface Config {
  name: string
  displayName: string
  connection: string
  description: string
  examples: string
  assistant: string
  allowedCallers: string
}

const INPUT =
  'mt-1 w-full rounded-md border border-line bg-canvas px-2.5 py-1.5 text-sm outline-none focus:border-line-strong'

function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: string
  children: ReactNode
}): ReactNode {
  return (
    <label className="block text-xs font-medium text-ink-muted">
      {label}
      {children}
      {hint !== undefined && (
        <span className="mt-1 block text-[11px] font-normal text-ink-faint">{hint}</span>
      )}
    </label>
  )
}

export function ConfigForm({
  config,
  assistants,
  onChange,
}: {
  config: Config
  assistants: { ref: string; name: string }[]
  onChange: (next: Config) => void
}): ReactNode {
  const set = (key: keyof Config) => (value: string) => onChange({ ...config, [key]: value })
  return (
    <section
      className="space-y-4 rounded-xl border border-line bg-surface p-5"
      data-testid="external-config"
    >
      <h2 className="text-sm font-semibold">接入配置</h2>
      <div className="grid grid-cols-2 gap-4">
        <Field label="资产名" hint="发布到 tenant.<租户>.remote 命名空间">
          <input
            aria-label="资产名"
            value={config.name}
            onChange={(e) => set('name')(e.target.value)}
            className={cn(INPUT, 'font-mono')}
          />
        </Field>
        <Field label="显示名">
          <input
            aria-label="显示名"
            value={config.displayName}
            onChange={(e) => set('displayName')(e.target.value)}
            className={INPUT}
          />
        </Field>
        <Field label="连接别名" hint="资产里只写别名，各环境的地址在连接中心绑定">
          <input
            aria-label="连接别名"
            value={config.connection}
            onChange={(e) => set('connection')(e.target.value)}
            className={cn(INPUT, 'font-mono')}
          />
        </Field>
        <Field label="所属岗位助手" hint="挂到岗位助手后由它的第二级路由选中；不挂时统一入口直达">
          <select
            aria-label="所属岗位助手"
            value={config.assistant}
            onChange={(e) => set('assistant')(e.target.value)}
            className={INPUT}
          >
            <option value="">不挂到岗位助手（入口直达）</option>
            {assistants.map((a) => (
              <option key={a.ref} value={a.ref}>
                {a.name}（{a.ref}）
              </option>
            ))}
          </select>
        </Field>
      </div>
      <Field label="路由说明" hint="统一入口据此判断什么问题交给这个外部 Agent">
        <input
          aria-label="路由说明"
          value={config.description}
          onChange={(e) => set('description')(e.target.value)}
          className={INPUT}
        />
      </Field>
      <Field label="示例话术" hint="每行一条，参与路由召回">
        <textarea
          aria-label="示例话术"
          value={config.examples}
          rows={3}
          onChange={(e) => set('examples')(e.target.value)}
          className={INPUT}
        />
      </Field>
      <Field
        label="允许的调用方"
        hint="平台里可以调用它的 Agent 或岗位助手，逗号分隔；留空表示不限制"
      >
        <input
          aria-label="允许的调用方"
          value={config.allowedCallers}
          placeholder="例如 tenant.t001.agents/price-negotiation-assistant"
          onChange={(e) => set('allowedCallers')(e.target.value)}
          className={cn(INPUT, 'font-mono')}
        />
      </Field>
      <p className="rounded-md bg-surface-sunken px-3 py-2 text-[11px] text-ink-muted">
        认证方式：平台服务令牌。每次调用以最终用户为主体、发起调用的平台 Agent 为 act
        签发短时令牌；身份透传失败时拒绝调用，不会退回到技术账号。
      </p>
    </section>
  )
}
