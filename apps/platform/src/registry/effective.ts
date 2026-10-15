import {
  assemble,
  viewEffectiveDefinition,
  type AssemblyIssue,
  type AssemblyIssueCode,
  type EffectiveDefinition,
  type EffectiveDefinitionView,
} from '@eab/assembler'
import type { AgentExtensionAsset, AssetStatus, PlatformRuleSetAsset, Viewer } from '@eab/spec'
import type { RegistryService } from './service'

export interface EffectiveRequest {
  /** namespace/name，可带版本范围 */
  agent: string
  /** 按哪个租户组装：叠加该租户已发布的扩展，并以该租户的可见范围解析资产 */
  tenantId?: string
  statuses?: readonly AssetStatus[]
  /** 替换候选扩展，发布门禁试组装时传入“其他已发布扩展 + 待发布的扩展” */
  candidateExtensions?: readonly AgentExtensionAsset[]
}

export type RegistryIssueCode = AssemblyIssueCode | 'INTEGRITY_VIOLATION' | 'PLATFORM_RULES_MISSING'

export type RegistryAssemblyIssue = Omit<AssemblyIssue, 'code'> & { code: RegistryIssueCode }

export type EffectiveOutcome =
  | {
      ok: true
      definition: EffectiveDefinition
      view: EffectiveDefinitionView
      issues: AssemblyIssue[]
    }
  | { ok: false; issues: RegistryAssemblyIssue[] }

const integrity = (refs: readonly string[]): EffectiveOutcome => ({
  ok: false,
  issues: refs.map((ref) => ({
    code: 'INTEGRITY_VIOLATION',
    severity: 'error',
    message: `资产 ${ref} 完整性校验失败，已拒绝加载`,
    source: ref,
  })),
})

/**
 * 从注册中心取出平台规则、标品 Agent 和租户扩展，组装出有效定义，并按查看者裁剪。
 * definition 是完整内容，只能交给运行时；对外只返回 view。
 *
 * 资产解析的范围：给了 tenantId 时按该租户可见的范围（平台层、已发布的标品层、本租户），
 * 否则按查看者本人的范围。调用方负责先确认查看者有权以该租户的身份组装。
 * 平台规则缺失或任何资产完整性校验失败时，组装失败，而不是少一段规则继续运行。
 */
export function assembleFromRegistry(
  registry: RegistryService,
  viewer: Viewer,
  request: EffectiveRequest,
): EffectiveOutcome {
  const scope: Viewer =
    request.tenantId === undefined ? viewer : { type: 'tenant', tenantId: request.tenantId }
  const resolver = registry.resolverFor(scope)

  const rules = registry.latestPublished({ kind: 'PlatformRuleSet', layer: 'platform' }, scope)
  if (rules.rejected.length > 0) return integrity(rules.rejected)
  const platformRules = rules.assets.filter(
    (a): a is PlatformRuleSetAsset => a.kind === 'PlatformRuleSet',
  )
  if (platformRules.length === 0) {
    return {
      ok: false,
      issues: [
        {
          code: 'PLATFORM_RULES_MISSING',
          severity: 'error',
          message: '没有可用的平台安全规则，拒绝组装',
        },
      ],
    }
  }

  let extensions: readonly AgentExtensionAsset[] = []
  if (request.candidateExtensions !== undefined) {
    extensions = request.candidateExtensions
  } else if (request.tenantId !== undefined) {
    const published = registry.latestPublished(
      { kind: 'AgentExtension', tenantId: request.tenantId },
      scope,
    )
    if (published.rejected.length > 0) return integrity(published.rejected)
    extensions = published.assets.filter(
      (a): a is AgentExtensionAsset => a.kind === 'AgentExtension',
    )
  }

  const result = assemble({
    agent: request.agent,
    ...(request.tenantId === undefined ? {} : { tenantId: request.tenantId }),
    extensions,
    platformRules,
    resolver,
    ...(request.statuses === undefined ? {} : { statuses: request.statuses }),
  })
  const failures = resolver.integrityFailures()
  if (failures.length > 0) return integrity(failures)
  if (!result.ok) return { ok: false, issues: result.issues }
  return {
    ok: true,
    definition: result.definition,
    view: viewEffectiveDefinition(result.definition, viewer, {
      digest: (text) => registry.signer.digest(text),
    }),
    issues: result.issues,
  }
}
