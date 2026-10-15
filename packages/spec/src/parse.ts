import { LineCounter, isNode, parseAllDocuments, type Document } from 'yaml'
import { z } from 'zod'
import { KIND_LAYERS, KIND_SCHEMAS, isAssetKind, type Asset } from './assets'
import { API_VERSION } from './common'
import { layerOfNamespace } from './layers'

export interface AssetIssue {
  /** 机器可读的问题编码 */
  code: string
  /** 中文说明 */
  message: string
  /** 字段路径，例如 spec.tools[2].mcp.source */
  path: string
  line?: number
  column?: number
}

export type ParseResult = { ok: true; asset: Asset } | { ok: false; issues: AssetIssue[] }

const zhCN = z.locales.zhCN()

/** 把 zod 路径格式化成 spec.tools[2].mcp.source 的形式 */
export function formatPath(path: readonly PropertyKey[]): string {
  return path.reduce<string>((acc, key) => {
    if (typeof key === 'number') return `${acc}[${key}]`
    const name = String(key)
    return acc === '' ? name : `${acc}.${name}`
  }, '')
}

/** 人读的单行报错：第 12 行第 5 列 spec.tools[2].mcp.source：…… */
export function formatIssue(issue: AssetIssue, source?: string): string {
  const where = issue.line === undefined ? '' : `第 ${issue.line} 行第 ${issue.column ?? 1} 列 `
  const field = issue.path === '' ? '' : `${issue.path}：`
  const file = source === undefined ? '' : `${source} `
  return `${file}${where}${field}${issue.message}`
}

/** 对已经是 JS 对象的资产做校验（API 收到 JSON 时使用），没有行号信息 */
export function parseAssetObject(value: unknown): ParseResult {
  return validate(value, () => ({}))
}

/** 解析单个资产的 YAML 文本，报错带行号和字段路径 */
export function parseAssetYaml(text: string): ParseResult {
  const lineCounter = new LineCounter()
  const documents = parseAllDocuments(text, { lineCounter, prettyErrors: false })
  if (!Array.isArray(documents)) return { ok: false, issues: [emptyIssue()] }
  if (documents.length === 0) return { ok: false, issues: [emptyIssue()] }
  if (documents.length > 1) {
    return {
      ok: false,
      issues: [{ code: 'MULTIPLE_DOCUMENTS', message: '一个文件只能放一个资产', path: '' }],
    }
  }
  const doc = documents[0] as Document.Parsed
  if (doc.errors.length > 0) {
    return {
      ok: false,
      issues: doc.errors.map((error) => {
        const pos = lineCounter.linePos(error.pos[0])
        return {
          code: 'YAML_SYNTAX',
          message: `YAML 语法错误：${error.message.split('\n')[0]}`,
          path: '',
          line: pos.line,
          column: pos.col,
        }
      }),
    }
  }
  return validate(doc.toJS(), (path) => locate(doc, lineCounter, path))
}

function emptyIssue(): AssetIssue {
  return { code: 'EMPTY_DOCUMENT', message: '文件里没有资产内容', path: '' }
}

type Locator = (path: readonly PropertyKey[]) => { line?: number; column?: number }

function validate(value: unknown, locate: Locator): ParseResult {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return {
      ok: false,
      issues: [{ code: 'NOT_AN_OBJECT', message: '资产必须是一个对象', path: '' }],
    }
  }
  const record = value as Record<string, unknown>
  if (record.apiVersion !== API_VERSION) {
    return fail(
      'UNSUPPORTED_API_VERSION',
      `apiVersion 必须是 ${API_VERSION}`,
      ['apiVersion'],
      locate,
    )
  }
  if (!isAssetKind(record.kind)) {
    return fail(
      'UNKNOWN_KIND',
      `未知的 kind "${String(record.kind)}"，可用的 kind：${Object.keys(KIND_SCHEMAS).join('、')}`,
      ['kind'],
      locate,
    )
  }

  let result: ReturnType<(typeof KIND_SCHEMAS)[typeof record.kind]['safeParse']>
  try {
    result = KIND_SCHEMAS[record.kind].safeParse(value, { error: zhCN.localeError })
  } catch (error: unknown) {
    // schema 里的校验函数不应抛异常；万一抛了，也转成问题返回，不让调用方崩溃
    const message = error instanceof Error ? error.message : String(error)
    return fail('SCHEMA_INTERNAL', `校验时发生内部错误：${message}`, [], locate)
  }
  if (!result.success) {
    return {
      ok: false,
      issues: result.error.issues.map((issue) => ({
        code: `SCHEMA_${issue.code.toUpperCase()}`,
        message: issue.message,
        path: formatPath(issue.path),
        ...locate(issue.path),
      })),
    }
  }

  const asset = result.data as Asset
  const layerIssue = checkKindLayer(asset)
  if (layerIssue !== undefined) {
    return fail(layerIssue.code, layerIssue.message, ['metadata', 'namespace'], locate)
  }
  return { ok: true, asset }
}

function checkKindLayer(asset: Asset): { code: string; message: string } | undefined {
  const allowed = KIND_LAYERS[asset.kind]
  if (allowed === undefined) return undefined
  const layer = layerOfNamespace(asset.metadata.namespace)
  if (allowed.includes(layer)) return undefined
  return {
    code: 'KIND_LAYER_MISMATCH',
    message: `${asset.kind} 只能放在 ${allowed.join('、')} 层的命名空间里，当前是 ${layer} 层`,
  }
}

function fail(code: string, message: string, path: PropertyKey[], locate: Locator): ParseResult {
  return { ok: false, issues: [{ code, message, path: formatPath(path), ...locate(path) }] }
}

/**
 * 从最深的路径开始往上找，取第一个存在的 YAML 节点的位置。
 * 缺少必填字段时路径指向不存在的键，这时定位到它所在的父对象。
 */
function locate(
  doc: Document.Parsed,
  lineCounter: LineCounter,
  path: readonly PropertyKey[],
): { line?: number; column?: number } {
  for (let depth = path.length; depth >= 0; depth -= 1) {
    const node = doc.getIn(path.slice(0, depth) as unknown[], true)
    if (isNode(node) && node.range !== undefined && node.range !== null) {
      const pos = lineCounter.linePos(node.range[0])
      return { line: pos.line, column: pos.col }
    }
  }
  return {}
}
