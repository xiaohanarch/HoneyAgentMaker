/**
 * 只在 Node 环境使用的工具（读文件系统），通过 `@eab/spec/node` 引入，不进前端包。
 */
import { readFile, readdir } from 'node:fs/promises'
import { join, relative, sep } from 'node:path'
import type { Asset } from './assets'
import { parseAssetYaml, type AssetIssue } from './parse'

export interface LoadedAsset {
  /** 相对于加载根目录的路径，统一用 / 分隔 */
  file: string
  asset: Asset
  /** YAML 原文 */
  text: string
}

export interface LoadIssue {
  file: string
  issue: AssetIssue
}

export interface LoadResult {
  assets: LoadedAsset[]
  issues: LoadIssue[]
}

const YAML_FILE = /\.ya?ml$/i

/** 递归读取目录下的全部 .yaml/.yml 资产文件，按路径排序保证结果稳定 */
export async function loadAssetDirectory(root: string): Promise<LoadResult> {
  const entries = await readdir(root, { recursive: true, withFileTypes: true })
  const files = entries
    .filter((e) => e.isFile() && YAML_FILE.test(e.name))
    .map((e) => join(e.parentPath, e.name))
    .sort()

  const results = await Promise.all(
    files.map(async (path) => {
      const file = relative(root, path).split(sep).join('/')
      const text = await readFile(path, 'utf8')
      return { file, text, parsed: parseAssetYaml(text) }
    }),
  )

  return {
    assets: results.flatMap(({ file, text, parsed }) =>
      parsed.ok ? [{ file, asset: parsed.asset, text }] : [],
    ),
    issues: results.flatMap(({ file, parsed }) =>
      parsed.ok ? [] : parsed.issues.map((issue) => ({ file, issue })),
    ),
  }
}
