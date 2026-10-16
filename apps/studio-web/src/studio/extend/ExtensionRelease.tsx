import { useState, type ReactNode } from 'react'
import {
  CircleCheck,
  CircleX,
  FlaskConical,
  ListChecks,
  LoaderCircle,
  Play,
  Rocket,
  Save,
  TriangleAlert,
  Wand2,
} from 'lucide-react'
import { cn } from '@eab/ui'
import { ApiError } from '../../assist/api'
import type { DraftView } from '../api'
import { errorText } from '../use-studio'
import { TestUserSelect } from '../wizard/EvalStep'
import type { ExtensionEvalView, ExtensionLintIssue } from './api'
import { useExtendApi } from './use-extend'

const BUTTON =
  'inline-flex items-center gap-1.5 rounded-lg border border-line px-3 py-1.5 text-sm text-ink-muted hover:text-ink disabled:opacity-40'

function Suites({ run }: { run: ExtensionEvalView }): ReactNode {
  return (
    <div className="space-y-3" data-testid="extension-eval-results">
      <p
        className={cn(
          'flex items-center gap-1.5 text-sm font-medium',
          run.status === 'passed' ? 'text-allow' : 'text-deny',
        )}
      >
        {run.status === 'passed' ? (
          <CircleCheck aria-hidden className="size-4" />
        ) : (
          <CircleX aria-hidden className="size-4" />
        )}
        {run.status === 'passed' ? '标品评测集和扩展评测集都通过' : '评测未通过，不能发布'}
      </p>
      {run.suites.map((suite) => (
        <section key={suite.suite} className="rounded-lg border border-line">
          <header className="flex items-center gap-2 border-b border-line px-3 py-1.5 text-xs">
            <span className="font-mono">{suite.suite}</span>
            <span className="text-ink-faint">
              {suite.visibility === 'public' ? '标品公开评测集' : '扩展评测集'}
            </span>
            <span className={cn('ml-auto', suite.ok ? 'text-allow' : 'text-deny')}>
              {`${suite.passed}/${suite.total}（要求 ${Math.round(suite.minPassRate * 100)}%）`}
            </span>
          </header>
          <ul className="divide-y divide-line text-xs">
            {suite.cases.map((c) => (
              <li key={c.id} className="px-3 py-1.5">
                <p className="flex items-center gap-1.5">
                  {c.passed ? (
                    <CircleCheck aria-hidden className="size-3.5 text-allow" />
                  ) : (
                    <CircleX aria-hidden className="size-3.5 text-deny" />
                  )}
                  <span className="font-mono text-ink-faint">{c.id}</span>
                  <span>{c.question}</span>
                </p>
                {c.assertions
                  .filter((a) => !a.passed)
                  .map((a, i) => (
                    <p key={i} className="ml-5 text-deny">
                      {`${a.description}：${a.detail}`}
                    </p>
                  ))}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

function LintIssues({ issues }: { issues: readonly ExtensionLintIssue[] }): ReactNode {
  if (issues.length === 0) return <p className="text-xs text-allow">静态检查没有发现问题</p>
  return (
    <ul className="space-y-1 text-xs" data-testid="extension-lint">
      {issues.map((issue, i) => (
        <li
          key={i}
          className={cn('flex gap-1.5', issue.severity === 'error' ? 'text-deny' : 'text-pending')}
        >
          <TriangleAlert aria-hidden className="mt-0.5 size-3 shrink-0" />
          <span>
            <span className="font-mono">{issue.rule} </span>
            {issue.message}
          </span>
        </li>
      ))}
    </ul>
  )
}

function publishError(error: unknown): string {
  if (error instanceof ApiError) {
    const hints: Record<string, string> = {
      EVALS_FAILED: '评测未通过，先修改扩展再运行评测',
      MCP_NOT_APPROVED: '扩展用到的 MCP 工具还没有经租户管理员审批',
      LINT_FAILED: '静态检查有错误',
    }
    return `${hints[error.code] ?? error.code}：${error.message}`
  }
  return errorText(error)
}

/**
 * 评测与发布：生成或编辑扩展评测集，运行时同时跑标品公开评测集和扩展评测集，确认点一律拒绝；
 * 发布前平台再做一次静态检查、MCP 审批检查和评测检查，扩展发布即对本租户生效。
 */
export function ExtensionRelease({
  view,
  onChange,
}: {
  view: DraftView
  onChange: (view: DraftView) => void
}): ReactNode {
  const { api } = useExtendApi()
  const id = view.draft.id
  const [testUser, setTestUser] = useState('wangfang')
  const [evalYaml, setEvalYaml] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [run, setRun] = useState<ExtensionEvalView | null>(null)
  const [lint, setLint] = useState<ExtensionLintIssue[] | null>(null)
  const [published, setPublished] = useState<{
    ref: string
    version: string
    unchanged: boolean
  } | null>(null)

  const act = <T,>(name: string, work: () => Promise<T>, done: (value: T) => void): void => {
    setBusy(name)
    setError(null)
    work().then(
      (value) => {
        setBusy(null)
        done(value)
      },
      (e: unknown) => {
        setBusy(null)
        setError(name === 'publish' ? publishError(e) : errorText(e))
      },
    )
  }
  const yaml = evalYaml ?? view.evalYaml ?? ''

  return (
    <div className="space-y-5" data-testid="extension-release">
      <section className="space-y-2">
        <header className="flex flex-wrap items-center gap-3 text-sm font-medium">
          <FlaskConical aria-hidden className="size-4 text-ink-muted" />
          扩展评测集
          <span className="ml-auto flex items-center gap-2 font-normal">
            <TestUserSelect value={testUser} onChange={setTestUser} />
            <button
              type="button"
              className={BUTTON}
              disabled={busy !== null}
              onClick={() =>
                act(
                  'generate',
                  () => api.generateEvals(id, testUser),
                  (next) => {
                    setEvalYaml(null)
                    onChange(next)
                  },
                )
              }
            >
              <Wand2 aria-hidden className="size-4" />
              按扩展内容生成
            </button>
          </span>
        </header>
        <textarea
          aria-label="扩展评测集 YAML"
          value={yaml}
          onChange={(e) => setEvalYaml(e.target.value)}
          rows={10}
          placeholder="还没有扩展评测集；点“按扩展内容生成”，或直接写 EvalSuite YAML"
          className="block w-full rounded-lg border border-line bg-canvas px-3 py-2 font-mono text-xs outline-none focus:border-line-strong"
        />
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className={BUTTON}
            disabled={busy !== null || evalYaml === null}
            onClick={() =>
              act(
                'save',
                () => api.putEvals(id, yaml),
                (next) => {
                  setEvalYaml(null)
                  onChange(next)
                },
              )
            }
          >
            <Save aria-hidden className="size-4" />
            保存评测集
          </button>
          <button
            type="button"
            className={BUTTON}
            disabled={busy !== null}
            onClick={() => act('run', () => api.runEvals(id), setRun)}
          >
            {busy === 'run' ? (
              <LoaderCircle aria-hidden className="size-4 animate-spin" />
            ) : (
              <Play aria-hidden className="size-4" />
            )}
            运行评测（标品公开集 + 扩展集）
          </button>
          <button
            type="button"
            className={BUTTON}
            disabled={busy !== null}
            onClick={() =>
              act(
                'lint',
                () => api.lint(id),
                (r) => setLint(r.issues),
              )
            }
          >
            <ListChecks aria-hidden className="size-4" />
            静态检查
          </button>
          <button
            type="button"
            disabled={busy !== null}
            onClick={() => act('publish', () => api.publish(id), setPublished)}
            className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-ink px-3 py-1.5 text-sm text-surface disabled:opacity-40"
          >
            {busy === 'publish' ? (
              <LoaderCircle aria-hidden className="size-4 animate-spin" />
            ) : (
              <Rocket aria-hidden className="size-4" />
            )}
            发布扩展
          </button>
        </div>
        <p className="text-[11px] text-ink-faint">
          评测运行时遇到确认点一律拒绝，不会真的发出通知或写入业务数据。
        </p>
      </section>
      {error !== null && (
        <p
          className="rounded-lg border border-deny/30 bg-deny-soft/40 px-3 py-2 text-xs text-deny"
          role="alert"
        >
          {error}
        </p>
      )}
      {published !== null && (
        <p
          className="rounded-lg border border-allow/30 bg-allow-soft/40 px-3 py-2 text-sm text-allow"
          data-testid="extension-published"
        >
          {published.unchanged
            ? `内容没有变化，仍是 ${published.ref} ${published.version}`
            : `已发布 ${published.ref} ${published.version}，本租户的用户下一次提问即按扩展后的定义运行`}
        </p>
      )}
      {lint !== null && <LintIssues issues={lint} />}
      {run !== null && <Suites run={run} />}
    </div>
  )
}
