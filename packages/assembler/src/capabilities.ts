import {
  SEMANTIC_TOOLS,
  approvalStrictness,
  findBuiltinTool,
  isAssetRef,
  layerOf,
  normalizeToolEntry,
  resolveAsset,
  type AgentAsset,
  type AgentExtensionAsset,
  type Asset,
  type AssetResolver,
  type AssetStatus,
  type ExtensionPoint,
  type NormalizedToolEntry,
  type ToolEntryOrRef,
} from '@eab/spec'
import { approvalEnvironment, checkExpression } from './conditions'
import { versionedRef } from './segments'
import type { AssemblyIssue, EffectiveApproval, EffectiveModel, EffectiveTool } from './types'

interface ResolveContext {
  resolver: AssetResolver
  statuses: readonly AssetStatus[]
}

/** 知识源作为文档检索工具暴露时的工具名 */
export function documentsToolName(knowledgeSourceName: string): string {
  return `kb_${knowledgeSourceName.replace(/-/g, '_')}`
}

/** 扩展里直接写的引用字符串，按被引用资产的 kind 归一化成工具条目 */
function normalizeEntry(
  entry: ToolEntryOrRef,
  ctx: ResolveContext,
): NormalizedToolEntry | undefined {
  if (typeof entry !== 'string') return normalizeToolEntry(entry)
  const asset = resolveAsset(ctx.resolver, entry, ctx)
  switch (asset?.kind) {
    case 'ToolSource':
      return { type: asset.spec.type === 'mcp' ? 'mcp' : 'api', ref: entry }
    case 'Skill':
      return { type: 'skill', ref: entry }
    case 'Agent':
      return { type: 'agent', ref: entry }
    case 'RemoteAgent':
      return { type: 'remoteAgent', ref: entry }
    case 'KnowledgeSource':
      return { type: 'documents', ref: entry }
    default:
      return undefined
  }
}

type Expanded = { tools: EffectiveTool[]; issues: AssemblyIssue[] }

function unresolved(ref: string, owner: Asset, path: string): Expanded {
  return {
    tools: [],
    issues: [
      {
        code: 'REFERENCE_UNRESOLVED',
        severity: 'error',
        message: `工具引用 ${ref} 不存在、版本不满足，或者 kind 不对`,
        source: versionedRef(owner),
        path,
      },
    ],
  }
}

/** 把一个工具条目展开成暴露给模型的工具（工具来源可能包含多个工具） */
function expandEntry(
  entry: NormalizedToolEntry,
  owner: Asset,
  path: string,
  ctx: ResolveContext,
): Expanded {
  const layer = layerOf(owner)
  if (entry.type === 'builtin') {
    const builtin = findBuiltinTool(entry.name)
    if (builtin === undefined) return unresolved(entry.name, owner, path)
    return {
      tools: [{ ...builtin, type: 'builtin', layer: 'platform', source: 'platform/builtin' }],
      issues: [],
    }
  }

  const asset = resolveAsset(ctx.resolver, entry.ref, ctx)
  if (asset === undefined) return unresolved(entry.ref, owner, path)
  const source = versionedRef(asset)
  const description =
    asset.metadata.description ?? asset.metadata.displayName ?? asset.metadata.name

  switch (entry.type) {
    case 'skill':
      if (asset.kind !== 'Skill') return unresolved(entry.ref, owner, path)
      return {
        tools: [
          {
            name: asset.metadata.name,
            type: 'skill',
            layer,
            source,
            description,
            writeLevel: asset.spec.writeLevel,
          },
        ],
        issues: [],
      }
    case 'agent':
      if (asset.kind !== 'Agent') return unresolved(entry.ref, owner, path)
      return {
        tools: [
          {
            name: asset.metadata.name,
            type: 'agent',
            layer,
            source,
            description: asset.spec.expertise,
            writeLevel: asset.spec.writePolicy.maxLevel,
          },
        ],
        issues: [],
      }
    case 'remoteAgent':
      if (asset.kind !== 'RemoteAgent') return unresolved(entry.ref, owner, path)
      return {
        tools: [
          {
            name: asset.metadata.name,
            type: 'remoteAgent',
            layer,
            source,
            description: asset.spec.routing.description,
          },
        ],
        issues: [],
      }
    case 'documents':
      if (asset.kind !== 'KnowledgeSource') return unresolved(entry.ref, owner, path)
      return {
        tools: [
          {
            name: documentsToolName(asset.metadata.name),
            type: 'documents',
            layer,
            source,
            description: `检索文档：${description}`,
            writeLevel: 'L0',
          },
        ],
        issues: [],
      }
    case 'mcp':
    case 'api': {
      if (asset.kind !== 'ToolSource') return unresolved(entry.ref, owner, path)
      const wanted = entry.tools
      const missing = (wanted ?? []).filter(
        (name) => !asset.spec.tools.some((t) => t.name === name),
      )
      if (missing.length > 0)
        return unresolved(`${entry.ref} 中的 ${missing.join('、')}`, owner, path)
      const tools = asset.spec.tools
        .filter((t) => wanted === undefined || wanted.includes(t.name))
        .map((t): EffectiveTool => ({
          name: t.name,
          type: entry.type,
          layer,
          source,
          description: t.description,
          ...(t.writeLevel === undefined ? {} : { writeLevel: t.writeLevel }),
        }))
      return { tools, issues: [] }
    }
  }
}

/**
 * 工具集合 = 标品工具 ∪ 租户新增工具（主提示词 5.4 节第 2 条）。
 * 租户工具与已有工具同名时报 TOOL_NAME_CONFLICT，发布会被拒绝。
 * 挂了语义包的 Agent 自动获得语义查询工具。
 */
export function buildTools(
  agent: AgentAsset,
  extensions: readonly AgentExtensionAsset[],
  hasSemanticPacks: boolean,
  ctx: ResolveContext,
): { tools: EffectiveTool[]; issues: AssemblyIssue[] } {
  const vendor = agent.spec.tools.map((entry, i) =>
    expandEntry(normalizeToolEntry(entry), agent, `spec.tools[${i}]`, ctx),
  )
  const semantic: EffectiveTool[] = hasSemanticPacks
    ? SEMANTIC_TOOLS.map((t) => ({
        ...t,
        type: 'semantic',
        layer: 'platform',
        source: 'platform/semantic',
      }))
    : []

  // 标品自身重复挂载同一个工具时去重
  const base = dedupeByName([...semantic, ...vendor.flatMap((e) => e.tools)])
  const issues = vendor.flatMap((e) => e.issues)

  const tenant = extensions.flatMap((ext) => [
    ...(ext.spec.tools?.add ?? []).map((entry, i) => {
      const normalized = normalizeEntry(entry, ctx)
      const path = `spec.tools.add[${i}]`
      return normalized === undefined
        ? unresolved(typeof entry === 'string' ? entry : JSON.stringify(entry), ext, path)
        : expandEntry(normalized, ext, path, ctx)
    }),
    ...(ext.spec.skills?.add ?? []).map((ref, i) =>
      expandEntry({ type: 'skill', ref }, ext, `spec.skills.add[${i}]`, ctx),
    ),
    ...(ext.spec.knowledge?.add ?? []).flatMap((ref, i) => {
      const asset = resolveAsset(ctx.resolver, ref, ctx)
      return asset?.kind === 'KnowledgeSource'
        ? [expandEntry({ type: 'documents', ref }, ext, `spec.knowledge.add[${i}]`, ctx)]
        : []
    }),
  ])

  const conflicts: AssemblyIssue[] = []
  const merged = tenant
    .flatMap((e) => e.tools)
    .reduce<EffectiveTool[]>((acc, tool) => {
      if (acc.some((t) => t.name === tool.name)) {
        conflicts.push({
          code: 'TOOL_NAME_CONFLICT',
          severity: 'error',
          message: `租户工具 ${tool.name}（${tool.source}）与已有工具重名，不能发布`,
          source: tool.source,
        })
        return acc
      }
      return [...acc, tool]
    }, base)

  return { tools: merged, issues: [...issues, ...tenant.flatMap((e) => e.issues), ...conflicts] }
}

function dedupeByName(tools: readonly EffectiveTool[]): EffectiveTool[] {
  return tools.filter((tool, i) => tools.findIndex((t) => t.name === tool.name) === i)
}

/** 确认点匹配工具：按工具名，或按工具所在资产（不带版本的引用） */
export function approvalMatchesTool(approvalTool: string, tool: EffectiveTool): boolean {
  if (approvalTool === tool.name) return true
  if (!isAssetRef(approvalTool)) return false
  return tool.source.split('@')[0] === approvalTool.split('@')[0]
}

/**
 * 人工确认策略取最严格者（主提示词 5.4 节第 3 条）。
 * 标品和租户的确认点都保留，运行时对匹配的规则取最严；
 * 租户对标品已要求确认的工具声明更宽松的无条件策略时，报 APPROVAL_RELAXED。
 */
export function mergeApprovals(
  agent: AgentAsset,
  extensions: readonly AgentExtensionAsset[],
  tools: readonly EffectiveTool[],
): { approvals: EffectiveApproval[]; issues: AssemblyIssue[] } {
  const fromAsset = (owner: Asset, rules: AgentAsset['spec']['approvals']): EffectiveApproval[] =>
    rules.map((r) => ({
      tool: r.tool,
      policy: r.policy,
      ...(r.when === undefined ? {} : { when: r.when }),
      ...(r.approverRole === undefined ? {} : { approverRole: r.approverRole }),
      layer: layerOf(owner),
      source: versionedRef(owner),
    }))

  const vendor = fromAsset(agent, agent.spec.approvals)
  const tenant = extensions.flatMap((ext) => fromAsset(ext, ext.spec.approvals?.add ?? []))
  const env = approvalEnvironment()

  const issues: AssemblyIssue[] = [...vendor, ...tenant].flatMap((approval): AssemblyIssue[] => {
    const found: AssemblyIssue[] = []
    if (!tools.some((t) => approvalMatchesTool(approval.tool, t))) {
      found.push({
        code: 'REFERENCE_UNRESOLVED',
        severity: 'error',
        message: `确认点指向的工具 ${approval.tool} 不在这个 Agent 的工具集合里`,
        source: approval.source,
      })
    }
    if (approval.when !== undefined) {
      const check = checkExpression(env, approval.when)
      if (!check.valid) {
        found.push({
          code: 'APPROVAL_CONDITION_INVALID',
          severity: 'error',
          message: `确认点条件 "${approval.when}" 无效：${check.reason}`,
          source: approval.source,
        })
      }
    }
    return found
  })

  // 租户确认点只能更严格（主提示词 5.3 节）：不能声明 auto，也不能对标品已要求更严策略的工具声明更宽松的策略
  const relaxed = tenant.flatMap((t): AssemblyIssue[] => {
    const relax = (message: string): AssemblyIssue[] => [
      { code: 'APPROVAL_RELAXED', severity: 'error', message, source: t.source },
    ]
    if (t.policy === 'auto')
      return relax(`租户扩展不能把 ${t.tool} 的确认策略声明为 auto，确认点只能更严格`)
    const affected = tools.filter((tool) => approvalMatchesTool(t.tool, tool))
    const stricter = vendor.find(
      (v) =>
        v.when === undefined &&
        approvalStrictness(v.policy) > approvalStrictness(t.policy) &&
        affected.some((tool) => approvalMatchesTool(v.tool, tool)),
    )
    return stricter === undefined
      ? []
      : relax(`标品对 ${stricter.tool} 要求 ${stricter.policy}，租户扩展不能放宽为 ${t.policy}`)
  })

  return { approvals: [...vendor, ...tenant], issues: [...issues, ...relaxed] }
}

/** 模型配置：租户只能在标品声明的范围内选择（主提示词 5.4 节第 5 条） */
export function resolveModel(
  agent: AgentAsset,
  extensions: readonly AgentExtensionAsset[],
): { model: EffectiveModel; issues: AssemblyIssue[] } {
  const vendorModel: EffectiveModel = {
    profile: agent.spec.model.profile,
    ...(agent.spec.model.temperature === undefined
      ? {}
      : { temperature: agent.spec.model.temperature }),
    layer: layerOf(agent),
  }
  const point: ExtensionPoint | undefined = agent.spec.extensionPoints.find(
    (p) => p.type === 'model.select',
  )
  return extensions.reduce<{ model: EffectiveModel; issues: AssemblyIssue[] }>(
    (acc, ext) => {
      const profile = ext.spec.model?.profile
      if (profile === undefined) return acc
      // 标品没有开放 model.select 时，由扩展点检查报 EXTENSION_POINT_UNDECLARED
      if (point === undefined) return acc
      if (point.allowedProfiles?.includes(profile) !== true) {
        const allowed = point.allowedProfiles?.join('、') ?? '（未开放选择）'
        return {
          ...acc,
          issues: [
            ...acc.issues,
            {
              code: 'MODEL_NOT_ALLOWED',
              severity: 'error',
              message: `模型档位 ${profile} 不在标品允许的范围内：${allowed}`,
              source: versionedRef(ext),
              path: 'spec.model.profile',
            },
          ],
        }
      }
      return { ...acc, model: { ...acc.model, profile, layer: layerOf(ext) } }
    },
    { model: vendorModel, issues: [] },
  )
}

/** 匹配某个工具的全部确认点，运行时先按 when 条件过滤，再用 strictestPolicy 取最严 */
export function matchingApprovals(
  approvals: readonly EffectiveApproval[],
  tool: EffectiveTool,
): EffectiveApproval[] {
  return approvals.filter((approval) => approvalMatchesTool(approval.tool, tool))
}

/** 人工确认策略取最严格者（主提示词 5.4 节第 3 条），没有匹配时为 auto */
export function strictestPolicy(
  approvals: readonly EffectiveApproval[],
): EffectiveApproval['policy'] {
  return approvals.reduce<EffectiveApproval['policy']>(
    (strictest, a) =>
      approvalStrictness(a.policy) > approvalStrictness(strictest) ? a.policy : strictest,
    'auto',
  )
}

const WRITE_LEVEL_ORDER = ['L0', 'L1', 'L2', 'L3'] as const

/** 租户新增的工具写级别不能超过标品 Agent 的写级别上限，租户也不能抬高这个上限 */
export function checkWriteLevels(
  agent: AgentAsset,
  tools: readonly EffectiveTool[],
): AssemblyIssue[] {
  const max = WRITE_LEVEL_ORDER.indexOf(agent.spec.writePolicy.maxLevel)
  return tools
    .filter(
      (t) =>
        t.layer === 'tenant' &&
        t.writeLevel !== undefined &&
        WRITE_LEVEL_ORDER.indexOf(t.writeLevel) > max,
    )
    .map((t) => ({
      code: 'TOOL_WRITE_LEVEL_EXCEEDED',
      severity: 'error',
      message: `租户工具 ${t.name} 的写级别 ${t.writeLevel ?? ''} 超过了 Agent 的上限 ${agent.spec.writePolicy.maxLevel}`,
      source: t.source,
    }))
}
