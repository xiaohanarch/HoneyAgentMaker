import {
  formatAssetRef,
  lintAsset,
  parseAssetRef,
  type AgentExtensionAsset,
  type AssetIssue,
} from '@eab/spec'
import { assembleFromRegistry } from './effective'
import type { PublishGate } from './service'

const LIVE = ['published', 'testing', 'draft'] as const

/**
 * 发布门禁（主提示词 10.8.1、5.4 节）：
 * - 所有资产发布前跑静态检查，error 级问题阻止发布；
 * - 租户扩展还要用“该租户其他已发布扩展 + 本扩展”试组装目标 Agent，
 *   组装错误阻止发布（例如租户工具重名），本扩展自身的警告也阻止发布（例如钩子条件无效）。
 * 评测门禁在 P8 加入。
 */
export const publishGate: PublishGate = (asset, publisher, registry) => {
  const resolver = registry.resolverFor(publisher)
  const lint: AssetIssue[] = lintAsset(asset, { resolver })
    .filter((issue) => issue.severity === 'error')
    .map((issue) => ({ code: `LINT_${issue.rule}`, message: issue.message, path: issue.path }))
  if (asset.kind !== 'AgentExtension' || publisher.type !== 'tenant' || lint.length > 0) return lint

  const self = `${asset.metadata.namespace}/${asset.metadata.name}`
  const others = registry
    .latestPublished({ kind: 'AgentExtension', tenantId: publisher.tenantId }, publisher)
    .assets.filter(
      (a): a is AgentExtensionAsset =>
        a.kind === 'AgentExtension' && `${a.metadata.namespace}/${a.metadata.name}` !== self,
    )
  const target = parseAssetRef(asset.spec.target)
  const outcome = assembleFromRegistry(registry, publisher, {
    agent: formatAssetRef({ namespace: target.namespace, name: target.name }),
    tenantId: publisher.tenantId,
    statuses: LIVE,
    candidateExtensions: [...others, asset],
  })
  const selfRef = `${self}@${asset.metadata.version}`
  return outcome.issues
    .filter((issue) => issue.severity === 'error' || issue.source === selfRef)
    .map((issue) => ({
      code: `ASSEMBLY_${issue.code}`,
      message: issue.message,
      path: issue.path ?? '',
    }))
}
