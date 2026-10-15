import semver from 'semver'
import {
  extensionPointViolations,
  parseAssetRef,
  type AgentExtensionAsset,
  type AssetRef,
  type AssetResolver,
  type AssetStatus,
  type PlatformRuleSetAsset,
} from '@eab/spec'
import { buildTools, checkWriteLevels, mergeApprovals, resolveModel } from './capabilities'
import { mergeHooks } from './hooks'
import { buildSegments, resolveSemanticPacks, versionedRef } from './segments'
import type { AssembleResult, AssemblyIssue, EffectiveDefinition } from './types'

export interface AssembleInput {
  /** 要组装的 Agent，namespace/name，可带版本范围 */
  agent: string
  tenantId?: string
  /** 候选扩展：调用方取出该租户全部已发布的扩展，这里按 target 过滤 */
  extensions?: readonly AgentExtensionAsset[]
  /** 平台安全规则，排在最前，不可覆盖 */
  platformRules?: readonly PlatformRuleSetAsset[]
  resolver: AssetResolver
  /** 参与解析的资产状态，默认只用已发布版本；私有环境测试时可以带上草稿 */
  statuses?: readonly AssetStatus[]
}

const DEFAULT_STATUSES: readonly AssetStatus[] = ['published']

const error = (issue: Omit<AssemblyIssue, 'severity'>): AssemblyIssue => ({
  ...issue,
  severity: 'error',
})

/**
 * 运行时组装（主提示词 5.4 节）。纯函数：只读解析器，不抛异常，问题通过 issues 返回。
 * 存在 error 级问题时不产出有效定义。
 */
export function assemble(input: AssembleInput): AssembleResult {
  const ctx = { resolver: input.resolver, statuses: input.statuses ?? DEFAULT_STATUSES }
  let requested: AssetRef
  try {
    requested = parseAssetRef(input.agent)
  } catch (cause: unknown) {
    const message = cause instanceof Error ? cause.message : String(cause)
    return { ok: false, issues: [error({ code: 'AGENT_NOT_FOUND', message })] }
  }
  const targeting = (input.extensions ?? []).filter((ext) => {
    const target = parseAssetRef(ext.spec.target)
    return target.namespace === requested.namespace && target.name === requested.name
  })

  // 版本解析（第 6 条）：先按请求解析出当前生效的标品版本，再看每个扩展的 target 范围是否覆盖它
  const candidates = input.resolver
    .listVersions(requested.namespace, requested.name)
    .filter((v) => ctx.statuses.includes(v.status))
    .map((v) => v.version)
  const version = semver.maxSatisfying(candidates, requested.range ?? '*') ?? undefined
  if (version === undefined) {
    return {
      ok: false,
      issues: [
        error({ code: 'AGENT_NOT_FOUND', message: `找不到 Agent ${input.agent} 的可用版本` }),
      ],
    }
  }

  const agent = input.resolver.get(requested.namespace, requested.name, version)
  if (agent?.kind !== 'Agent') {
    return {
      ok: false,
      issues: [error({ code: 'AGENT_NOT_FOUND', message: `${input.agent} 不是 Agent` })],
    }
  }

  // 范围不覆盖当前版本的扩展本次不生效，给出警告；不让一个过期扩展拖垮整个 Agent
  const extensions = targeting.filter((ext) =>
    semver.satisfies(version, parseAssetRef(ext.spec.target).range ?? '*'),
  )
  const staleIssues: AssemblyIssue[] = targeting
    .filter((ext) => !extensions.includes(ext))
    .map((ext) => ({
      code: 'TARGET_UNRESOLVED',
      severity: 'warning',
      message: `扩展的目标范围 ${parseAssetRef(ext.spec.target).range ?? '*'} 不包含当前标品版本 ${version}，本次未生效`,
      source: versionedRef(ext),
      path: 'spec.target',
    }))

  // 模型档位越界由 resolveModel 报 MODEL_NOT_ALLOWED，这里不重复报
  const pointIssues = extensions.flatMap((ext) =>
    extensionPointViolations(ext.spec, agent.spec.extensionPoints)
      .filter((v) => v.path !== 'spec.model.profile')
      .map((v) =>
        error({
          code: 'EXTENSION_POINT_UNDECLARED',
          message: v.message,
          source: versionedRef(ext),
          path: v.path,
        }),
      ),
  )

  const packs = resolveSemanticPacks(agent, extensions, ctx)
  const segments = buildSegments({
    platformRules: input.platformRules ?? [],
    agent,
    packs: packs.packs,
    extensions,
  })
  const tools = buildTools(agent, extensions, packs.packs.length > 0, ctx)
  const approvals = mergeApprovals(agent, extensions, tools.tools)
  const hooks = mergeHooks(agent, extensions)
  const model = resolveModel(agent, extensions)

  const issues = [
    ...staleIssues,
    ...pointIssues,
    ...packs.issues,
    ...segments.issues,
    ...tools.issues,
    ...approvals.issues,
    ...checkWriteLevels(agent, tools.tools),
    ...hooks.issues,
    ...model.issues,
  ]
  if (issues.some((i) => i.severity === 'error')) return { ok: false, issues }

  const agentRef = versionedRef(agent)
  const definition: EffectiveDefinition = {
    name: agent.metadata.name,
    displayName: agent.metadata.displayName ?? agent.metadata.name,
    expertise: agent.spec.expertise,
    segments: segments.segments,
    tools: tools.tools,
    approvals: approvals.approvals,
    hooks: hooks.hooks,
    model: model.model,
    semanticPacks: packs.packs.map(versionedRef),
    writePolicy: agent.spec.writePolicy,
    executionSteps: agent.spec.executionSteps ?? { pre: [], post: [] },
    ...(agent.spec.output === undefined ? {} : { output: agent.spec.output }),
    routing: agent.spec.routing,
    provenance: {
      identity: `agent:${agent.metadata.namespace}/${agent.metadata.name}`,
      ...(input.tenantId === undefined ? {} : { tenantId: input.tenantId }),
      agent: agentRef,
      ...(extensions.length === 0
        ? {}
        : {
            resolvedTarget: {
              ranges: extensions.map((ext) => parseAssetRef(ext.spec.target).range ?? '*'),
              version,
            },
          }),
      extensions: extensions.map(versionedRef),
      platformRules: (input.platformRules ?? []).map(versionedRef),
      semanticPacks: packs.packs.map(versionedRef),
    },
  }
  return { ok: true, definition, issues }
}
