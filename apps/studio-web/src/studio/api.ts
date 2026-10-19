/**
 * Studio 调用的平台接口（docs/studio-api.md）。请求都带当前身份的 Bearer 令牌，经同源的 /api 转发到平台。
 */
import {
  ApiError,
  deleteJson,
  getJson,
  postJson,
  putJson,
  streamPost,
  type RunEvent,
} from '../assist/api'

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
    displayName: string
    originalText: string
    requester: string
    status: RequestStatus
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

// ── 扩展需求与意图生成（P9，docs/studio-api.md 的 P9 一节） ──

export type RequestStatus = 'submitted' | 'in-progress' | 'delivered' | 'rejected'

/** fit-gap 的三档结论 */
export type FitGapVerdict = 'satisfied' | 'extend' | 'new'

/** 技术规格里对每个资产的动作 */
export type PlanAssetAction = 'reuse' | 'create'

/** 方案里资产草稿的种类 */
export type PlanDraftKind = 'Skill' | 'AgentExtension'

/** 意图生成的六步方案卡 */
export interface RequestPlan {
  decidedBy: 'model' | 'retrieval'
  generatedAt: string
  clarifications: { question: string; options: string[] }[]
  fitGap: {
    verdict: FitGapVerdict
    score: number
    matched: { ref: string; reason: string }[]
    reason: string
  }
  summary: { user: string; scenario: string; metrics: string[]; boundaries: string }
  assets: { action: PlanAssetAction; kind: string; ref: string; note: string }[]
  drafts: { kind: PlanDraftKind; yaml: string }[]
  evalSuite: { name: string; yaml: string } | null
}

/** 扩展需求：提出人的原话与补充说明、自动附带的口径，以及生成的方案 */
export interface RequestView {
  ref: string
  originalText: string
  requester: string
  requesterName: string
  status: RequestStatus
  createdAt: string
  updatedAt: string
  supplements: string[]
  context: { assistant: string | null; semanticObjects: string[]; rules: string[] }
  plan: RequestPlan | null
}

/** 采纳方案后落到工作区的草稿 */
export interface AdoptedDraft {
  id: string
  kind: PlanDraftKind
  ref: string
  name: string
}

const enc = encodeURIComponent

/** 下载二进制（导出代码工程的 zip）；失败时响应体是 JSON 信封，读出来转成 ApiError */
async function getBlob(path: string, token: string | undefined): Promise<Blob> {
  const headers: Record<string, string> = {}
  if (token !== undefined) headers.authorization = `Bearer ${token}`
  const response = await fetch(`/api${path}`, { headers })
  if (!response.ok) {
    let error: ApiError | undefined
    try {
      const body = (await response.json()) as {
        error: { code: string; message: string; details?: unknown } | null
      }
      if (body.error !== null) {
        error = new ApiError(body.error.code, body.error.message, body.error.details)
      }
    } catch {
      // 响应体不是 JSON 时退回到 HTTP 状态
    }
    throw error ?? new ApiError(`HTTP_${response.status}`, `请求失败：HTTP ${response.status}`)
  }
  return await response.blob()
}

export function studioApi(token: string | undefined) {
  return {
    home: () => getJson<HomeView>('/studio/home', token),
    request: (namespace: string, name: string) =>
      getJson<RequestView>(`/studio/requests/${enc(namespace)}/${enc(name)}`, token),
    planRequest: (namespace: string, name: string) =>
      postJson<RequestPlan>(`/studio/requests/${enc(namespace)}/${enc(name)}/plan`, token, {}),
    putRequestPlan: (namespace: string, name: string, plan: RequestPlan) =>
      putJson<RequestPlan>(`/studio/requests/${enc(namespace)}/${enc(name)}/plan`, token, { plan }),
    adoptRequest: (namespace: string, name: string) =>
      postJson<{ drafts: AdoptedDraft[] }>(
        `/studio/requests/${enc(namespace)}/${enc(name)}/adopt`,
        token,
        {},
      ),
    deliverRequest: (namespace: string, name: string, summary?: string) =>
      postJson<{ status: RequestStatus }>(
        `/studio/requests/${enc(namespace)}/${enc(name)}/deliver`,
        token,
        { summary },
      ),
    rejectRequest: (namespace: string, name: string, reason?: string) =>
      postJson<{ status: RequestStatus }>(
        `/studio/requests/${enc(namespace)}/${enc(name)}/reject`,
        token,
        { reason },
      ),
    catalog: () => getJson<StudioCatalog>('/studio/catalog', token),
    createDraft: (intent: string) => postJson<DraftView>('/studio/drafts', token, { intent }),
    draft: (id: string) => getJson<DraftView>(`/studio/drafts/${enc(id)}`, token),
    deleteDraft: (id: string) => deleteJson<void>(`/studio/drafts/${enc(id)}`, token),
    /** 导出为代码工程（zip，与 pnpm eab init 的模板同构，可经 pnpm eab push 推回同一条草稿） */
    exportProject: (id: string) => getBlob(`/studio/drafts/${enc(id)}/export`, token),
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
