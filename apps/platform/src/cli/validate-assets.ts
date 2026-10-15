/**
 * pnpm assets:validate
 * 校验仓库里的全部资产：schema、静态检查（主提示词 10.8.1 节），并试组装每个 Agent 及其租户扩展。
 * 有 error 级问题时以退出码 1 结束。
 */
import { styleText } from 'node:util'
import { lintAsset, parseAssetRef, tenantIdOfNamespace, type Asset } from '@eab/spec'
import { assembleFromRegistry } from '../registry/effective'
import { loadRepositoryRegistry } from './repo-registry'

interface Problem {
  where: string
  severity: 'error' | 'warning'
  message: string
}

function agentTargets(assets: readonly Asset[]): { agent: string; tenantId?: string }[] {
  const vendorAgents = assets
    .filter((a) => a.kind === 'Agent')
    .map((a) => ({ agent: `${a.metadata.namespace}/${a.metadata.name}` }))
  const extended = assets.flatMap((a) => {
    if (a.kind !== 'AgentExtension') return []
    const target = parseAssetRef(a.spec.target)
    const tenantId = tenantIdOfNamespace(a.metadata.namespace)
    return tenantId === undefined ? [] : [{ agent: `${target.namespace}/${target.name}`, tenantId }]
  })
  return [...vendorAgents, ...extended]
}

async function main(): Promise<number> {
  const { services, seed } = await loadRepositoryRegistry()
  const { registry } = services
  const { assets, rejected } = registry.latestPublished({})
  const resolver = registry.resolverFor({ type: 'platform' })

  const problems: Problem[] = [
    ...seed.failed.map((f) => ({ where: f.file, severity: 'error' as const, message: f.message })),
    ...rejected.map((ref) => ({
      where: ref,
      severity: 'error' as const,
      message: '完整性校验失败，已拒绝加载',
    })),
    ...assets.flatMap((asset) =>
      lintAsset(asset, { resolver }).map((issue) => ({
        where: `${asset.metadata.namespace}/${asset.metadata.name} ${issue.path}`,
        severity: issue.severity,
        message: `[${issue.rule}] ${issue.message}`,
      })),
    ),
    ...agentTargets(assets).flatMap(({ agent, tenantId }) => {
      const viewer =
        tenantId === undefined
          ? ({ type: 'vendor' } as const)
          : ({ type: 'tenant', tenantId } as const)
      const outcome = assembleFromRegistry(registry, viewer, {
        agent,
        ...(tenantId === undefined ? {} : { tenantId }),
      })
      return outcome.issues.map((issue) => ({
        where: `组装 ${agent}${tenantId === undefined ? '' : ` + ${tenantId}`}`,
        severity: issue.severity,
        message: `[${issue.code}] ${issue.message}`,
      }))
    }),
  ]

  for (const p of problems) {
    const tag = p.severity === 'error' ? styleText('red', '错误') : styleText('yellow', '警告')
    console.info(`${tag} ${styleText('dim', p.where)}  ${p.message}`)
  }
  const errors = problems.filter((p) => p.severity === 'error').length
  console.info(
    `\n共 ${assets.length} 个资产，${errors} 个错误，${problems.length - errors} 个警告` +
      (errors === 0 ? styleText('green', '，校验通过') : ''),
  )
  services.db.$client.close()
  return errors === 0 ? 0 : 1
}

process.exitCode = await main()
