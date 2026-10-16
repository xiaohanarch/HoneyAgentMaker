/**
 * Studio 调用的平台接口（docs/studio-api.md）。请求都带当前身份的 Bearer 令牌，经同源的 /api 转发到平台。
 */
import { deleteJson, getJson, postJson, putJson, streamPost, type RunEvent } from '../assist/api'

export type StepKey =
  | 'purpose'
  | 'naming'
  | 'process'
  | 'collaborators'
  | 'knowledge'
  | 'tools'
  | 'triggers'
  | 'channels'
  | 'instructions'
  | 'model'
  | 'examples'

export interface DraftSummary {
  id: string
  intent: string
  step: number
  kind: 'Agent' | 'AgentExtension' | 'Skill'
  namespace: string
  name: string
  publishedVersion: string | null
  owner: string
  updatedAt: string
}

export interface SchemaIssue {
  code: string
  message: string
  path: string | null
  line?: number
}

export interface DraftView {
  draft: DraftSummary
  yaml: string
  asset: Record<string, unknown> | null
  issues: SchemaIssue[]
  evalYaml: string | null
}

export interface Recommendation {
  step: StepKey
  value: Record<string, unknown>
  candidates: Record<string, unknown[]>
  reason: string
  decidedBy: 'model' | 'retrieval'
}

export interface CatalogItem {
  ref: string
  name: string
  description: string | null
  layer: string
  writeLevel: string | null
  available: boolean
  note: string | null
  examples: string[]
}

export interface CatalogTool {
  source: string
  name: string
  description: string | null
  writeLevel: string | null
  category: string | null
}

export interface CatalogProcess {
  code: string
  name: string
  description: string
}

export interface CatalogRule {
  id: string
  severity: string
  description: string | null
  remedy: string
  checkable: boolean
}

export interface CatalogOption {
  value: string
  label: string
  description: string
}

export interface CatalogPack {
  ref: string
  name: string
  description: string | null
  objects: string[]
  rules: CatalogRule[]
  verifiedTasks: {
    id: string
    question: string
    expectedConclusion: string
    failIf: string | null
    tools: string[]
  }[]
}

/** 能力清单：候选的完整集合，推荐接口给出的候选缺失时用它 */
export interface StudioCatalog {
  processes: CatalogProcess[]
  agents: CatalogItem[]
  semanticPacks: CatalogPack[]
  knowledge: CatalogItem[]
  skills: CatalogItem[]
  tools: CatalogTool[]
  channels: CatalogOption[]
  profiles: CatalogOption[]
}

export interface HomeView {
  drafts: DraftSummary[]
  agents: {
    ref: string
    name: string
    version: string
    environments: Partial<Record<Environment, string>>
  }[]
  devRequests: {
    ref: string
    originalText: string
    requester: string
    status: string
    createdAt: string
  }[]
}

export interface TestRunResult {
  runId: string
  traceId: string
  sessionId: string
  status: string
  text: string | null
  output: Record<string, unknown> | null
  error: string | null
  events: RunEvent[]
}

export interface Span {
  spanId: string
  parentSpanId: string | null
  name: string
  kind: string
  startTime: string
  endTime: string | null
  status: 'ok' | 'error' | 'suspended'
  statusMessage: string | null
  attributes: Record<string, unknown> | null
  promptSegments:
    | {
        id: string
        kind: string
        layer: string
        source: string
        text: string | null
        label: string | null
        redacted: boolean
      }[]
    | null
}

export interface TraceView {
  traceId: string
  runId: string
  agent: string
  spans: Span[]
}

export interface AssertionResult {
  type: string
  description: string
  passed: boolean
  detail: string
}

export interface EvalCaseResult {
  id: string
  user: string
  question: string
  passed: boolean
  text: string
  runId: string
  traceId: string
  assertions: AssertionResult[]
}

export interface EvalRunView {
  id: string
  status: 'passed' | 'failed'
  total: number
  passed: number
  minPassRate: number
  passRate: number
  cases: EvalCaseResult[]
}

export interface LintIssue {
  rule: string
  severity: 'error' | 'warning'
  message: string
  path: string | null
}

export interface LintView {
  ok: boolean
  issues: LintIssue[]
}

export type Environment = 'dev' | 'test' | 'prod'

export const ENVIRONMENTS: readonly Environment[] = ['dev', 'test', 'prod']

export interface Deployment {
  environment: Environment
  version: string
  deployedAt: string
  deployedBy: string
  note: string | null
}

export interface DeploymentsView {
  environments: Partial<Record<Environment, Deployment>>
  history: Deployment[]
}

export interface Gate {
  name: string
  passed: boolean
  detail: string
}

export interface PublishResult {
  ref: string
  version: string
  deployment: Deployment
}

export interface PromoteResult {
  deployment: Deployment
  gates: Gate[]
}

const enc = encodeURIComponent

export function studioApi(token: string | undefined) {
  return {
    home: () => getJson<HomeView>('/studio/home', token),
    catalog: () => getJson<StudioCatalog>('/studio/catalog', token),
    createDraft: (intent: string) => postJson<DraftView>('/studio/drafts', token, { intent }),
    draft: (id: string) => getJson<DraftView>(`/studio/drafts/${enc(id)}`, token),
    deleteDraft: (id: string) => deleteJson<void>(`/studio/drafts/${enc(id)}`, token),
    recommend: (id: string, step: StepKey) =>
      postJson<Recommendation>(`/studio/drafts/${enc(id)}/recommend`, token, { step }),
    apply: (id: string, step: StepKey, value: Record<string, unknown>) =>
      postJson<DraftView>(`/studio/drafts/${enc(id)}/apply`, token, { step, value }),
    putYaml: (id: string, yaml: string) =>
      putJson<DraftView>(`/studio/drafts/${enc(id)}/yaml`, token, { yaml }),
    generateEvals: (id: string, testUser: string) =>
      postJson<DraftView>(`/studio/drafts/${enc(id)}/evals/generate`, token, { testUser }),
    putEvals: (id: string, yaml: string) =>
      putJson<DraftView>(`/studio/drafts/${enc(id)}/evals`, token, { yaml }),
    runEvals: (id: string) =>
      postJson<EvalRunView>(`/studio/drafts/${enc(id)}/evals/run`, token, {}),
    lint: (id: string) => postJson<LintView>(`/studio/drafts/${enc(id)}/lint`, token, {}),
    publish: (id: string, changeNote?: string) =>
      postJson<PublishResult>(`/studio/drafts/${enc(id)}/publish`, token, { changeNote }),
    deployments: (ref: string) =>
      getJson<DeploymentsView>(`/studio/deployments?ref=${enc(ref)}`, token),
    promote: (ref: string, version: string, environment: Exclude<Environment, 'dev'>) =>
      postJson<PromoteResult>('/studio/deployments/promote', token, { ref, version, environment }),
    rollback: (ref: string, environment: Environment) =>
      postJson<{ deployment: Deployment }>('/studio/deployments/rollback', token, {
        ref,
        environment,
      }),
    trace: (traceId: string) => getJson<TraceView>(`/traces/${enc(traceId)}`, token),
    test: (
      id: string,
      body: { message: string; asUser: string; sessionId?: string },
      onEvent: (event: RunEvent) => void,
      signal?: AbortSignal,
    ) => streamPost(`/studio/drafts/${enc(id)}/test`, token, body, onEvent, signal),
    confirm: (
      runId: string,
      approved: boolean,
      onEvent: (event: RunEvent) => void,
      signal?: AbortSignal,
    ) =>
      streamPost(
        `/studio/test-runs/${enc(runId)}/confirmation`,
        token,
        { approved },
        onEvent,
        signal,
      ),
  }
}

export type StudioApi = ReturnType<typeof studioApi>
