import { getJson, postJson, putJson, streamPost, type RunEvent } from '../../assist/api'
import type { AssertionResult, DraftView } from '../api'

/** P6 的接口（docs/studio-api.md 的 P6 一节）：标品 Agent 目录、扩展、工具中心、技能编辑器 */

export interface ExtensionPoint {
  type: string
  description: string
  allowedProfiles?: string[]
  events?: string[]
}

export interface VendorAgentSummary {
  ref: string
  name: string
  description: string
  version: string
  writeLevel: string
  extensionPoints: ExtensionPoint[] | null
  tools: Record<string, unknown>[] | null
  evals: string[] | null
  extensions: { ref: string; version?: string; draftId?: string; status: 'published' | 'draft' }[]
}

/** 租户视角下被裁剪的文本 */
export interface RedactedText {
  $redacted: true
  label: string
  digest: string
}

export interface EffectiveSegment {
  id: string
  kind: string
  layer: 'platform' | 'vendor' | 'tenant'
  source: string
  field: string
  text: string | RedactedText
  label: string | null
  confidential: boolean
}

export interface EffectiveView {
  name: string
  displayName: string
  segments: EffectiveSegment[]
  tools: {
    name: string
    type: string
    layer: string
    source: string
    description: string | null
    writeLevel: string | null
  }[]
  approvals: { tool: string; policy: string; when: string | null; layer: string; source: string }[]
  hooks: {
    event: string
    layer: string
    source: string
    workflow: string | null
    skill: string | null
    when: string | null
    failurePolicy: string
  }[]
  model: { profile: string; temperature: number | null; layer: string } | null
  provenance: { agent: string; extensions: string[] } | null
}

export interface AssemblyIssue {
  code: string
  severity?: string
  message: string
  source?: string | null
  path?: string | null
}

export interface VendorAgentDetail {
  asset: Record<string, unknown>
  summary: VendorAgentSummary
  effective?: EffectiveView
  issues: AssemblyIssue[]
}

export interface EffectivePreview {
  ok: boolean
  view?: EffectiveView
  issues: AssemblyIssue[]
  extension: string
}

export interface ExtensionLintIssue {
  rule: string
  severity: 'error' | 'warning'
  message: string
  path?: string
}

export interface ExtensionCaseResult {
  id: string
  user: string
  question: string
  passed: boolean
  text: string | null
  runId: string
  traceId: string
  assertions: AssertionResult[]
}

export interface SuiteResult {
  suite: string
  visibility: string
  minPassRate: number
  total: number
  passed: number
  ok: boolean
  cases: ExtensionCaseResult[]
}

export interface ExtensionEvalView {
  id: string
  status: 'passed' | 'failed'
  suites: SuiteResult[]
}

export interface ToolEntry {
  name: string
  description: string
  writeLevel: string
  category: string
  usedBy: string[]
}

export interface ToolSourceView {
  ref: string
  version: string
  name: string
  layer: string
  type: 'bo-api' | 'mcp' | 'http'
  connection: string
  approval?: 'pending' | 'approved' | 'rejected' | 'unregistered'
  usedBy: string[]
  tools: ToolEntry[]
}

export interface SkillView {
  ref: string
  version: string
  name: string
  layer: string
  writeLevel: string
  steps: number
  usedBy: string[]
}

export interface BoOperation {
  name: string
  description: string
  method: string
  path: string
  writeLevel: string
  imported: boolean
}

export interface McpToolView {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

export interface McpApproval {
  id: number
  source: string
  status: 'pending' | 'approved' | 'rejected'
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  note: string | null
}

export interface StepResult {
  id: string
  name: string
  type: string
  status: string
  input: string | null
  output: string | null
  error: string | null
  durationMs: number
}

export interface SkillTestView {
  status: 'ok' | 'failed' | 'confirmation'
  output: unknown
  error: string | null
  pending: string | null
  steps: StepResult[]
  runId: string
  traceId: string
}

const enc = encodeURIComponent

export function extendApi(token: string | undefined) {
  return {
    vendorAgents: () => getJson<VendorAgentSummary[]>('/studio/vendor-agents', token),
    vendorAgent: (ref: string) => getJson<VendorAgentDetail>(`/studio/vendor-agents/${ref}`, token),
    copyAsTenant: (ref: string) =>
      postJson<{ draft: DraftView; notice: string }>(
        `/studio/vendor-agents/${ref}/copy`,
        token,
        {},
      ),
    createExtension: (target: string) =>
      postJson<DraftView>('/studio/extensions', token, { target }),
    applySection: (id: string, section: string, value: Record<string, unknown>) =>
      postJson<DraftView>(`/studio/drafts/${enc(id)}/extension`, token, { section, value }),
    effective: (id: string) =>
      getJson<EffectivePreview>(`/studio/drafts/${enc(id)}/effective`, token),
    generateEvals: (id: string, testUser: string) =>
      postJson<DraftView>(`/studio/drafts/${enc(id)}/extension/evals/generate`, token, {
        testUser,
      }),
    putEvals: (id: string, yaml: string) =>
      putJson<DraftView>(`/studio/drafts/${enc(id)}/extension/evals`, token, { yaml }),
    runEvals: (id: string) =>
      postJson<ExtensionEvalView>(`/studio/drafts/${enc(id)}/extension/evals/run`, token, {}),
    lint: (id: string) =>
      postJson<{ ok: boolean; issues: ExtensionLintIssue[] }>(
        `/studio/drafts/${enc(id)}/extension/lint`,
        token,
        {},
      ),
    publish: (id: string) =>
      postJson<{ ref: string; version: string; unchanged: boolean; evalRun: string }>(
        `/studio/drafts/${enc(id)}/extension/publish`,
        token,
        {},
      ),
    test: (
      id: string,
      body: { message: string; asUser: string; sessionId?: string },
      onEvent: (event: RunEvent) => void,
      signal?: AbortSignal,
    ) => streamPost(`/studio/drafts/${enc(id)}/extension/test`, token, body, onEvent, signal),

    tools: () =>
      getJson<{ sources: ToolSourceView[]; skills: SkillView[] }>('/studio/tools', token),
    boOperations: () => getJson<BoOperation[]>('/studio/tools/bo-operations', token),
    importBo: (name: string, displayName: string, operations: string[]) =>
      postJson<{ ref: string; version: string; tools: number }>('/studio/tools/bo-import', token, {
        name,
        displayName,
        operations,
      }),
    mcpTools: (connection: string) =>
      getJson<McpToolView[]>(`/studio/tools/mcp-tools?connection=${enc(connection)}`, token),
    registerMcp: (body: {
      name: string
      displayName: string
      connection: string
      tools: { name: string; writeLevel: string }[]
    }) =>
      postJson<{ ref: string; version: string; approval: string }>(
        '/studio/tools/mcp-servers',
        token,
        body,
      ),
    approvals: () => getJson<McpApproval[]>('/studio/mcp-approvals', token),
    decide: (id: number, approved: boolean, note: string) =>
      postJson<McpApproval>(`/studio/mcp-approvals/${id}/decision`, token, { approved, note }),

    createSkill: (name: string, displayName: string, from?: string) =>
      postJson<DraftView>('/studio/skills', token, { name, displayName, from }),
    putSteps: (id: string, steps: Record<string, unknown>[]) =>
      putJson<DraftView>(`/studio/drafts/${enc(id)}/skill-steps`, token, { steps }),
    testSkill: (id: string, input: Record<string, unknown>, asUser: string) =>
      postJson<SkillTestView>(`/studio/drafts/${enc(id)}/skill-test`, token, { input, asUser }),
    publishSkill: (id: string) =>
      postJson<{ ref: string; version: string; unchanged: boolean }>(
        `/studio/drafts/${enc(id)}/skill-publish`,
        token,
        {},
      ),
  }
}

export type ExtendApi = ReturnType<typeof extendApi>
