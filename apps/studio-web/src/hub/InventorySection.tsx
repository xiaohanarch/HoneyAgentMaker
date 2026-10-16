import { useState, type ReactNode } from 'react'
import { BadgeCheck, EyeOff } from 'lucide-react'
import { cn } from '@eab/ui'
import { LineageGraph } from './LineageGraph'
import { Empty, ErrorLine, Loading, SectionHeader, SourcePill } from './ui'
import { useHubQuery } from './use-hub'
import type { Change, Diff, Inventory, InventoryItem, VersionEntry } from './types'

const KIND_LABELS: Record<string, string> = {
  Assistant: '助手',
  Agent: 'Agent',
  AgentExtension: '扩展',
  RemoteAgent: '远程 Agent',
  ToolSource: '工具源',
  Skill: '技能',
}

const FILTERS = ['全部', '助手', 'Agent', '扩展', '远程 Agent', '工具源'] as const

export function InventorySection(): ReactNode {
  const inventory = useHubQuery<Inventory>('/hub/inventory')
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('全部')
  const [selected, setSelected] = useState<string>()

  const items = inventory.data?.items ?? []
  const shown = items.filter((i) => filter === '全部' || KIND_LABELS[i.kind] === filter)
  const current = items.find((i) => i.ref === selected)

  return (
    <section className="space-y-5" aria-label="资产清单">
      <SectionHeader
        title="资产清单与血缘"
        description="本租户可见的助手、Agent、扩展、远程 Agent 与工具源；点选节点查看版本差异"
      />
      <ErrorLine error={inventory.error} />
      {inventory.isPending ? (
        <Loading />
      ) : (
        <>
          <LineageGraph
            items={items}
            edges={inventory.data?.edges ?? []}
            selected={selected}
            onSelect={(ref) => setSelected(ref === selected ? undefined : ref)}
          />
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
            <div className="space-y-3">
              <div role="tablist" aria-label="按种类过滤" className="flex flex-wrap gap-1">
                {FILTERS.map((f) => (
                  <button
                    key={f}
                    role="tab"
                    aria-selected={filter === f}
                    onClick={() => setFilter(f)}
                    className={cn(
                      'rounded-full px-2.5 py-1 text-xs transition-colors',
                      filter === f
                        ? 'bg-ink text-surface'
                        : 'text-ink-muted hover:bg-surface-sunken hover:text-ink',
                    )}
                  >
                    {f}
                  </button>
                ))}
              </div>
              <InventoryTable items={shown} selected={selected} onSelect={setSelected} />
            </div>
            <DiffPanel item={current} />
          </div>
          {inventory.data !== undefined && Object.keys(inventory.data.modelProfiles).length > 0 && (
            <p className="text-[11px] text-ink-faint">
              模型档位：
              {Object.entries(inventory.data.modelProfiles)
                .map(([tier, model]) => `${tier} → ${model}`)
                .join('；')}
            </p>
          )}
        </>
      )}
    </section>
  )
}

function InventoryTable({
  items,
  selected,
  onSelect,
}: {
  items: readonly InventoryItem[]
  selected: string | undefined
  onSelect: (ref: string) => void
}): ReactNode {
  if (items.length === 0) return <Empty>没有符合条件的资产</Empty>
  return (
    <div className="overflow-x-auto rounded-xl border border-line bg-surface">
      <table className="w-full text-left text-xs">
        <thead className="border-b border-line text-[11px] text-ink-faint">
          <tr>
            <th className="px-3 py-2 font-medium">名称</th>
            <th className="px-3 py-2 font-medium">来源</th>
            <th className="px-3 py-2 font-medium">版本</th>
            <th className="px-3 py-2 font-medium">环境</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {items.map((i) => (
            <tr
              key={i.ref}
              onClick={() => onSelect(i.ref)}
              aria-selected={selected === i.ref}
              className={cn(
                'cursor-pointer transition-colors hover:bg-surface-sunken',
                selected === i.ref && 'bg-surface-sunken',
              )}
            >
              <td className="px-3 py-2">
                <span className="block font-medium text-ink">{i.name}</span>
                <span className="block font-mono text-[10px] text-ink-faint">{i.ref}</span>
              </td>
              <td className="px-3 py-2">
                <span className="flex items-center gap-1.5">
                  <SourcePill source={i.source} />
                  {i.signed && <BadgeCheck aria-label="已签名" className="size-3.5 text-allow" />}
                </span>
              </td>
              <td className="px-3 py-2 font-mono">{i.version}</td>
              <td className="px-3 py-2 text-ink-muted">
                {Object.keys(i.environments).length === 0
                  ? '—'
                  : Object.entries(i.environments)
                      .map(([env, v]) => `${env} ${v}`)
                      .join(' · ')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DiffPanel({ item }: { item: InventoryItem | undefined }): ReactNode {
  const ref = item?.ref
  const versions = useHubQuery<VersionEntry[]>(
    `/hub/assets/versions?ref=${encodeURIComponent(ref ?? '')}`,
    ref !== undefined,
  )
  const list = versions.data ?? []
  const [pair, setPair] = useState<{ ref: string; from: string; to: string }>()
  const latest = list[list.length - 1]?.version
  const previous = list[list.length - 2]?.version
  const chosen = pair !== undefined && pair.ref === ref ? pair : undefined
  const from = chosen?.from ?? previous
  const to = chosen?.to ?? latest
  const ready = ref !== undefined && from !== undefined && to !== undefined && from !== to
  const diff = useHubQuery<Diff>(
    `/hub/assets/diff?ref=${encodeURIComponent(ref ?? '')}&from=${from ?? ''}&to=${to ?? ''}`,
    ready,
  )

  if (item === undefined) {
    return <Empty>在血缘图或列表里选中一个资产，查看两个版本之间的差异</Empty>
  }
  const select = (key: 'from' | 'to', value: string): void => {
    setPair({ ref: item.ref, from: from ?? '', to: to ?? '', [key]: value })
  }
  return (
    <aside className="space-y-3 rounded-xl border border-line bg-surface p-4" aria-label="版本差异">
      <div>
        <p className="text-sm font-medium">{item.name}</p>
        <p className="font-mono text-[10px] text-ink-faint">{item.ref}</p>
      </div>
      {list.length < 2 ? (
        <p className="text-xs text-ink-faint">只有一个版本，没有可比较的差异</p>
      ) : (
        <div className="flex items-center gap-2 text-xs">
          <VersionSelect
            label="从"
            value={from}
            versions={list}
            onChange={(v) => select('from', v)}
          />
          <span className="text-ink-faint">→</span>
          <VersionSelect label="到" value={to} versions={list} onChange={(v) => select('to', v)} />
        </div>
      )}
      <ErrorLine error={diff.error} />
      {ready && diff.data !== undefined && <ChangeList changes={diff.data.changes} />}
    </aside>
  )
}

function VersionSelect({
  label,
  value,
  versions,
  onChange,
}: {
  label: string
  value: string | undefined
  versions: readonly VersionEntry[]
  onChange: (v: string) => void
}): ReactNode {
  return (
    <label className="flex items-center gap-1">
      <span className="text-ink-faint">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-md border border-line bg-canvas px-1.5 py-1 font-mono text-xs"
      >
        {versions.map((v) => (
          <option key={v.version} value={v.version}>
            {v.version}
          </option>
        ))}
      </select>
    </label>
  )
}

const CHANGE_MARK: Record<Change['change'], { sign: string; className: string }> = {
  added: { sign: '+', className: 'text-allow' },
  removed: { sign: '−', className: 'text-deny' },
  changed: { sign: '~', className: 'text-pending' },
}

function ChangeList({ changes }: { changes: readonly Change[] }): ReactNode {
  if (changes.length === 0) return <p className="text-xs text-ink-faint">两个版本内容一致</p>
  return (
    <ul className="max-h-96 space-y-2 overflow-y-auto text-xs" data-testid="diff-changes">
      {changes.map((c) => (
        <li key={c.path} className="rounded-md bg-canvas px-2.5 py-2">
          <p className="flex items-center gap-1.5 font-mono text-[11px]">
            <span className={cn('font-semibold', CHANGE_MARK[c.change].className)}>
              {CHANGE_MARK[c.change].sign}
            </span>
            {c.path}
          </p>
          {c.redacted ? (
            <p className="mt-1 flex items-center gap-1 text-vendor">
              <EyeOff aria-hidden className="size-3.5" />
              {c.after}
            </p>
          ) : (
            <p className="mt-1 break-all text-ink-muted">
              {c.before !== null && <span className="text-deny line-through">{c.before}</span>}
              {c.before !== null && c.after !== null && ' → '}
              {c.after !== null && <span className="text-ink">{c.after}</span>}
            </p>
          )}
        </li>
      ))}
    </ul>
  )
}
