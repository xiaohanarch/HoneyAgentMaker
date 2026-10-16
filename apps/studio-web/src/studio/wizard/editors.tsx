import type { ReactNode } from 'react'
import { WRITE_LEVELS, WriteLevelBadge, type WriteLevel } from '@eab/ui'
import type {
  CatalogItem,
  CatalogOption,
  CatalogProcess,
  CatalogRule,
  CatalogTool,
  StepKey,
  StudioCatalog,
} from '../api'
import { Avatar } from '../Avatar'
import { toggle, type ToolRef } from '../draft-values'
import { CheckList, type CheckItem } from './CheckList'

type Value = Record<string, unknown>
type Candidates = Record<string, unknown[]>

const TRIGGERS: readonly CatalogOption[] = [
  { value: 'assistant', label: '统一入口对话', description: '用户在统一入口提问时由路由选中' },
  { value: 'event', label: '业务事件', description: '需要登记委托才能自主运行' },
  { value: 'schedule', label: '定时', description: '按计划自主运行，同样需要委托' },
]

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []
}

function isWriteLevel(value: unknown): value is WriteLevel {
  return typeof value === 'string' && (WRITE_LEVELS as readonly string[]).includes(value)
}

function levelBadge(level: string | null): ReactNode {
  return isWriteLevel(level) ? <WriteLevelBadge level={level} /> : null
}

/** 推荐接口给出的候选优先，缺失时用能力清单 */
function pick<T>(candidates: Candidates | undefined, key: string, fallback: readonly T[]): T[] {
  const list = candidates?.[key]
  return Array.isArray(list) ? (list as T[]) : [...fallback]
}

const itemOf = (a: CatalogItem): CheckItem => ({
  id: a.ref,
  title: a.name,
  subtitle: a.description,
  disabled: !a.available,
  note: a.note,
  badge: levelBadge(a.writeLevel),
})

const inputClass =
  'w-full rounded-lg border border-line bg-canvas px-3 py-2 text-sm outline-none focus:border-line-strong'

function Field({ label, children }: { label: string; children: ReactNode }): ReactNode {
  return (
    <label className="block space-y-1">
      <span className="text-xs font-medium text-ink-muted">{label}</span>
      {children}
    </label>
  )
}

export interface EditorProps {
  step: StepKey
  value: Value
  onChange: (value: Value) => void
  candidates: Candidates | undefined
  catalog: StudioCatalog | undefined
}

/** 一步的取值编辑表单；字段与平台 apply 接受的 value 一致 */
export function StepEditor({ step, value, onChange, candidates, catalog }: EditorProps): ReactNode {
  const set = (patch: Value): void => onChange({ ...value, ...patch })
  const toggleIn = (key: string, id: string, single = false): void =>
    set({ [key]: single ? [id] : toggle(strings(value[key]), id, (a, b) => a === b) })

  switch (step) {
    case 'purpose': {
      const similar = Array.isArray(value.similar)
        ? (value.similar as { ref: string; name: string; score: number }[])
        : []
      return (
        <div className="space-y-3">
          <Field label="一句话意图">
            <textarea
              value={String(value.intent ?? '')}
              onChange={(e) => set({ intent: e.target.value })}
              rows={3}
              className={inputClass}
            />
          </Field>
          {similar.length > 0 && (
            <div className="rounded-lg border border-info/30 bg-info-soft/40 px-3 py-2 text-xs">
              <p className="font-medium text-info">相近的已有能力（先复用、再扩展、最后新建）</p>
              <ul className="mt-1 space-y-0.5 text-ink-muted">
                {similar.map((s) => (
                  <li key={s.ref}>
                    {s.name} <span className="font-mono text-ink-faint">{s.score}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )
    }
    case 'naming':
      return (
        <div className="flex gap-4">
          <Avatar name={String(value.name ?? '')} className="size-16" />
          <div className="flex-1 space-y-3">
            <Field label="标识（小写字母、数字和连字符）">
              <input
                value={String(value.name ?? '')}
                onChange={(e) => set({ name: e.target.value })}
                className={`${inputClass} font-mono`}
              />
            </Field>
            <Field label="显示名">
              <input
                value={String(value.displayName ?? '')}
                onChange={(e) => set({ displayName: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="描述">
              <textarea
                value={String(value.description ?? '')}
                onChange={(e) => set({ description: e.target.value })}
                rows={2}
                className={inputClass}
              />
            </Field>
          </div>
        </div>
      )
    case 'process':
      return (
        <CheckList
          label="业务流程"
          items={pick<CatalogProcess>(candidates, 'processes', catalog?.processes ?? []).map(
            (p) => ({ id: p.code, title: p.name, subtitle: p.description }),
          )}
          selected={strings(value.businessProcess)}
          onToggle={(id) => toggleIn('businessProcess', id)}
        />
      )
    case 'collaborators':
      return (
        <CheckList
          label="可调用的 Agent"
          items={pick<CatalogItem>(candidates, 'agents', catalog?.agents ?? []).map(itemOf)}
          selected={strings(value.agents)}
          onToggle={(id) => toggleIn('agents', id)}
        />
      )
    case 'knowledge':
      return (
        <div className="space-y-4">
          <CheckList
            label="语义包"
            items={pick<{ ref: string; name: string; description: string | null }>(
              candidates,
              'semanticPacks',
              catalog?.semanticPacks ?? [],
            ).map((p) => ({ id: p.ref, title: p.name, subtitle: p.description }))}
            selected={strings(value.semanticPacks)}
            onToggle={(id) => toggleIn('semanticPacks', id)}
          />
          <CheckList
            label="知识源"
            items={pick<CatalogItem>(candidates, 'knowledgeSources', catalog?.knowledge ?? []).map(
              itemOf,
            )}
            selected={strings(value.knowledgeSources)}
            onToggle={(id) => toggleIn('knowledgeSources', id)}
          />
        </div>
      )
    case 'tools': {
      const tools = Array.isArray(value.tools) ? (value.tools as ToolRef[]) : []
      const key = (t: ToolRef): string => `${t.source}#${t.name}`
      const all = pick<CatalogTool>(candidates, 'tools', catalog?.tools ?? [])
      return (
        <div className="space-y-4">
          <CheckList
            label="技能"
            items={pick<CatalogItem>(candidates, 'skills', catalog?.skills ?? []).map(itemOf)}
            selected={strings(value.skills)}
            onToggle={(id) => toggleIn('skills', id)}
          />
          <CheckList
            label="工具"
            items={all.map((t) => ({
              id: key(t),
              title: t.name,
              subtitle: t.description,
              badge: levelBadge(t.writeLevel),
            }))}
            selected={tools.map(key)}
            onToggle={(id) => {
              const tool = all.find((t) => key(t) === id)
              if (tool === undefined) return
              set({
                tools: toggle(
                  tools,
                  { source: tool.source, name: tool.name },
                  (a, b) => key(a) === key(b),
                ),
              })
            }}
          />
        </div>
      )
    }
    case 'triggers': {
      const triggers = Array.isArray(value.triggers) ? (value.triggers as { type: string }[]) : []
      return (
        <div className="space-y-3">
          <CheckList
            label="触发方式"
            items={TRIGGERS.map((t) => ({ id: t.value, title: t.label, subtitle: t.description }))}
            selected={triggers.map((t) => t.type)}
            onToggle={(id) =>
              set({ triggers: toggle(triggers, { type: id }, (a, b) => a.type === b.type) })
            }
          />
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={value.direct === true}
              onChange={(e) => set({ direct: e.target.checked })}
            />
            统一入口可以直接路由到它（不经过岗位助手）
          </label>
        </div>
      )
    }
    case 'channels':
      return (
        <CheckList
          label="渠道"
          items={pick<CatalogOption>(candidates, 'channels', catalog?.channels ?? []).map((c) => ({
            id: c.value,
            title: c.label,
            subtitle: c.description,
          }))}
          selected={strings(value.channels)}
          onToggle={(id) => toggleIn('channels', id)}
        />
      )
    case 'instructions':
      return (
        <div className="space-y-4">
          <Field label="指令">
            <textarea
              value={String(value.instructions ?? '')}
              onChange={(e) => set({ instructions: e.target.value })}
              rows={8}
              className={inputClass}
            />
          </Field>
          <CheckList
            label="约束（语义包规则）"
            items={pick<CatalogRule>(candidates, 'rules', []).map((r) => ({
              id: r.id,
              title: `${r.id} · ${r.description ?? ''}`,
              subtitle: r.remedy,
              badge: r.checkable ? (
                <span className="rounded bg-allow-soft px-1.5 text-[10px] text-allow">
                  守卫检查
                </span>
              ) : null,
            }))}
            selected={strings(value.constraints)}
            onToggle={(id) => toggleIn('constraints', id)}
          />
        </div>
      )
    case 'model':
      return (
        <div className="space-y-3">
          <CheckList
            label="模型档位"
            single
            items={pick<CatalogOption>(candidates, 'profiles', catalog?.profiles ?? [])
              .filter((p) => p.value !== 'judge')
              .map((p) => ({ id: p.value, title: p.label, subtitle: p.description }))}
            selected={[String(value.profile ?? '')]}
            onToggle={(id) => set({ profile: id })}
          />
          <Field label="温度（0 到 1）">
            <input
              type="number"
              min={0}
              max={1}
              step={0.1}
              value={Number(value.temperature ?? 0.2)}
              onChange={(e) => set({ temperature: Number(e.target.value) })}
              className={`${inputClass} w-32`}
            />
          </Field>
        </div>
      )
    case 'examples':
      return (
        <Field label="路由示例话术（每行一句）">
          <textarea
            value={strings(value.examples).join('\n')}
            onChange={(e) =>
              set({ examples: e.target.value.split('\n').filter((l) => l.trim() !== '') })
            }
            rows={4}
            className={inputClass}
          />
        </Field>
      )
  }
}
