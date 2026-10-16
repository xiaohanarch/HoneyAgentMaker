/** 治理中心接口（/api/hub/*）的响应结构，与平台 hub、lifecycle、feedback 包里的记录类型一一对应 */

export type AssetSourceKind = 'vendor' | 'tenant' | 'extension' | 'external' | 'platform'

export interface InventoryItem {
  ref: string
  kind: string
  version: string
  source: AssetSourceKind
  name: string
  description: string | null
  status: string
  signed: boolean
  environments: Record<string, string>
}

export interface Edge {
  from: string
  to: string
  relation: string
}

export interface Inventory {
  items: InventoryItem[]
  edges: Edge[]
  modelProfiles: Record<string, string>
}

export interface VersionEntry {
  version: string
  status: string
}

export interface Change {
  path: string
  change: 'added' | 'removed' | 'changed'
  before: string | null
  after: string | null
  redacted: boolean
}

export interface Diff {
  ref: string
  from: string
  to: string
  changes: Change[]
}

export interface Check {
  name: string
  passed: boolean
  detail: string
}

export interface AdmissionItem {
  ref: string
  kind: string
  name: string
  version: string
  environments: Record<string, string>
  checks: Check[]
  ready: boolean
}

export interface AgentMetrics {
  agent: string
  name: string
  runs: number
  completionRate: number
  toolCorrectness: number | null
  interventionRate: number
  failedTraces: number
  p50Ms: number
  p95Ms: number
  inputTokens: number
  outputTokens: number
  costUsd: number
}

export interface DailyPoint {
  day: string
  runs: number
  failed: number
  p95Ms: number
  costUsd: number
}

export interface SeriesPoint {
  day: string
  value: number
}

export interface Series {
  metric: string
  label: string
  unit: string
  agent: string | null
  points: SeriesPoint[]
  latest: number | null
}

export interface Kpi {
  name: string
  displayName: string
  unit: string
  target: number | null
  direction: string
  value: number | null
  previous: number | null
}

export interface AssistantKpis {
  ref: string
  name: string
  kpis: Kpi[]
}

export interface MetricsOverview {
  days: number
  agents: AgentMetrics[]
  daily: DailyPoint[]
  process: Series[]
  business: Series[]
  assistants: AssistantKpis[]
}

export interface Finding {
  at: string
  userId: string
  agent: string | null
  operation: string | null
  target: string | null
  decision: string
  reason: string | null
  mode: string | null
  runId: string | null
  traceId: string | null
}

export interface CompareSide {
  channel: string
  identity: string
  filtered: boolean
  scope: string
  total: number
  byOrg: Record<string, number>
}

export interface IntegrationCompare {
  question: string
  bo: CompareSide
  open: CompareSide
  explanation: string
}

export interface AuditEvent {
  id: number
  at: string
  actor: string
  action: string
  target: string | null
  decision: string
  reason: string | null
  userId: string | null
  agent: string | null
  tool: string | null
  checkpoint: string | null
  mode: string | null
}

export interface Delegation {
  id: string
  tenantId: string
  delegatorUserId: string
  agent: string
  scope: string[]
  validFrom: string
  validUntil: string
  revokedAt: string | null
  revokedBy: string | null
  note: string | null
}

export interface Alert {
  id: number
  at: string
  userId: string | null
  agent: string | null
  runId: string | null
  type: string
  severity: string
  message: string
}

export interface UpgradePreset {
  agent: string
  fromVersion: string
  toVersion: string
  summary: string
}

export interface SuiteSummary {
  status: string
  passed: number
  total: number
  cases: { id: string; passed: boolean; failures: string[] }[]
}

export interface Regression {
  id: string
  at: string
  agent: string
  fromVersion: string
  toVersion: string
  tenantId: string
  extension: string
  suite: string
  status: string
  baseline: SuiteSummary | null
  after: SuiteSummary | null
  regressions: string[]
  issues: string[]
  triggeredBy: string
}

export interface Upgrades {
  presets: UpgradePreset[]
  regressions: Regression[]
}

export interface Feedback {
  id: string
  userId: string
  runId: string
  traceId: string | null
  agent: string | null
  utterance: string | null
  reason: string
  candidate: string | null
  decidedBy: string | null
  status: 'pending' | 'approved' | 'rejected'
  reviewer: string | null
  reviewNote: string | null
  packRef: string | null
  createdAt: string
  reviewedAt: string | null
}

export interface ManifestItem {
  ref: string
  kind: string
  version: string
  contentHash: string
}

export interface SolutionPackage {
  id: string
  solution: string
  version: string
  manifest: {
    assets: ManifestItem[]
    requires: ManifestItem[]
    connections: string[]
    compiledAt: string
  }
  manifestHash: string
  signature: string
  createdAt: string
  createdBy: string
}

export interface SolutionEntry {
  package: SolutionPackage
  verified: boolean
  environments: Record<string, string>
}

export interface EvalSuite {
  ref: string
  version: string
  name: string
  target: string
  tier: string
  minPassRate: number
  cases: number
}

export interface CaseResult {
  id: string
  user: string
  question: string
  passed: boolean
  assertions: { type: string; description: string; passed: boolean; detail: string }[]
  text: string | null
  runId: string | null
}

export interface SuiteRun {
  evalRunId: string
  suite: string
  target: string
  result: {
    status: string
    total: number
    passed: number
    passRate: number
    minPassRate: number
    cases: CaseResult[]
    millis: number
  }
}
