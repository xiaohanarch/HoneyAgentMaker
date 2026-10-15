import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { formatIssue, layerOf, tenantIdOfNamespace, type Asset, type Viewer } from '@eab/spec'
import { loadAssetDirectory } from '@eab/spec/node'
import type { RegistryError, RegistryService } from './service'

export interface SeedReport {
  published: string[]
  failed: { file: string; message: string }[]
}

/** 资产按所在层，以本层身份发布：平台规则由平台发布，标品由厂商发布，租户资产由该租户发布 */
function publisherOf(asset: Asset): Viewer {
  const layer = layerOf(asset)
  if (layer === 'tenant') {
    return { type: 'tenant', tenantId: tenantIdOfNamespace(asset.metadata.namespace) ?? '' }
  }
  return { type: layer }
}

/**
 * 把资产目录下指定子目录的 YAML 发布进注册中心（同版本同内容时跳过）。
 * 分两轮：先全部写成草稿，再逐个发布。发布门禁要解析资产之间的引用，
 * 一个文件引用的资产可能排在它后面，两轮可以避免顺序依赖。
 * 平台启动时灌入 platform 和 vendor；tenants/t001 是演示完成态快照，按需加载。
 */
export async function seedAssets(
  registry: RegistryService,
  assetsRoot: string,
  subdirectories: readonly string[],
): Promise<SeedReport> {
  const report: SeedReport = { published: [], failed: [] }
  const staged: { file: string; asset: Asset }[] = []
  for (const sub of subdirectories) {
    const dir = join(assetsRoot, sub)
    if (!existsSync(dir)) continue
    const loaded = await loadAssetDirectory(dir)
    for (const { file, issue } of loaded.issues) {
      report.failed.push({ file: `${sub}/${file}`, message: formatIssue(issue) })
    }
    for (const { file, asset, text } of loaded.assets) {
      const result = registry.stageAsset(publisherOf(asset), asset, text)
      if (result.ok) staged.push({ file: `${sub}/${file}`, asset })
      else report.failed.push({ file: `${sub}/${file}`, message: describe(result.error) })
    }
  }
  for (const { file, asset } of staged) {
    const result = registry.publishAsset(publisherOf(asset), asset, '')
    if (result.ok) {
      report.published.push(
        `${asset.metadata.namespace}/${asset.metadata.name}@${asset.metadata.version}`,
      )
    } else {
      report.failed.push({ file, message: describe(result.error) })
    }
  }
  return report
}

function describe(error: RegistryError): string {
  const details = (error.issues ?? []).map((i) => `${i.path} ${i.message}`.trim())
  return details.length === 0 ? error.message : `${error.message}：${details.join('；')}`
}
