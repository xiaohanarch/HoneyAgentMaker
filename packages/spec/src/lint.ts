import type { Asset } from './assets'
import { findBuiltinTool } from './builtins'
import { isRuleId } from './common'
import { extensionPointViolations } from './kinds/agent-extension'
import { normalizeToolEntry } from './kinds/agent-parts'
import { collectReferences, type AssetReference } from './references'
import { parseAssetRef } from './refs'
import { canReferenceNamespace } from './visibility'
import { resolveAsset, resolveAssetOfKind, type AssetResolver, type AssetStatus } from './resolver'

/** 静态检查（主提示词 10.8.1 节），发布前运行，结果显示在发布面板上 */
export const LINT_RULES = {
  'no-hardcoded-endpoint': '资产里不得出现硬编码地址和凭据，只能引用连接别名',
  'tool-write-level': '工具写操作级别必须声明，付款、过账类最高 L2',
  'extension-point-declared': '扩展只能使用目标 Agent 已声明的扩展点',
  'reference-resolvable': '引用的语义包规则、技能、工具必须存在且版本兼容',
  'reference-scope': '只能引用平台层、标品层和本租户的资产',
} as const
export type LintRuleId = keyof typeof LINT_RULES

export interface LintIssue {
  rule: LintRuleId
  severity: 'error' | 'warning'
  message: string
  path: string
}

export interface LintContext {
  resolver: AssetResolver
  /** 引用解析时认为“存在”的状态，默认除已下线外都算 */
  statuses?: readonly AssetStatus[]
}

const LIVE_STATUSES: readonly AssetStatus[] = ['draft', 'testing', 'published']

export function lintAsset(asset: Asset, context: LintContext): LintIssue[] {
  const ctx = { resolver: context.resolver, statuses: context.statuses ?? LIVE_STATUSES }
  return [
    ...checkHardcodedEndpoints(asset),
    ...checkToolWriteLevels(asset),
    ...checkExtensionPoints(asset, ctx),
    ...checkReferenceScope(asset),
    ...checkReferences(asset, ctx),
  ]
}

/** 引用范围在解析之前检查，越界的引用不进入解析，避免借报错信息探测其他租户的资产 */
function checkReferenceScope(asset: Asset): LintIssue[] {
  return outOfScopeReferences(asset).map((reference) => ({
    rule: 'reference-scope',
    severity: 'error',
    message: `不能引用 ${reference.ref}：只能引用平台层、标品层和本租户的资产`,
    path: reference.path,
  }))
}

export function outOfScopeReferences(asset: Asset): AssetReference[] {
  return collectReferences(asset).filter((reference) => {
    try {
      return !canReferenceNamespace(
        asset.metadata.namespace,
        parseAssetRef(reference.ref).namespace,
      )
    } catch {
      return false
    }
  })
}

// ── no-hardcoded-endpoint ────────────────────────────────────────────────

const ENDPOINT_PATTERNS: readonly RegExp[] = [
  /\b(?:https?|wss?):\/\/\S+/i,
  /\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{2,5})?\b/,
  /\blocalhost:\d{2,5}\b/i,
]
const SECRET_PATTERNS: readonly RegExp[] = [
  /\bsk-[A-Za-z0-9_-]{16,}/,
  /\bAKIA[0-9A-Z]{16}\b/,
  /\bghp_[A-Za-z0-9]{20,}/,
  /\bxox[bp]-[A-Za-z0-9-]{10,}/,
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
]
const SECRET_KEY_NAMES = /^(password|passwd|secret|client_?secret|api_?key|access_?token|token)$/i

function walkStrings(
  value: unknown,
  path: string,
  visit: (text: string, path: string, key: string | undefined) => void,
  key?: string,
): void {
  if (typeof value === 'string') visit(value, path, key)
  else if (Array.isArray(value)) value.forEach((v, i) => walkStrings(v, `${path}[${i}]`, visit))
  else if (typeof value === 'object' && value !== null) {
    for (const [k, v] of Object.entries(value))
      walkStrings(v, path === '' ? k : `${path}.${k}`, visit, k)
  }
}

function checkHardcodedEndpoints(asset: Asset): LintIssue[] {
  const issues: LintIssue[] = []
  const issue = (path: string, message: string): LintIssue => ({
    rule: 'no-hardcoded-endpoint',
    severity: 'error',
    message,
    path,
  })
  walkStrings(asset.spec, 'spec', (text, path, key) => {
    // Connection 的环境绑定是唯一允许出现地址的地方
    if (asset.kind === 'Connection' && path.startsWith('spec.bindings.')) return
    if (ENDPOINT_PATTERNS.some((p) => p.test(text))) {
      issues.push(issue(path, '出现了硬编码地址，请改为引用连接别名'))
    } else if (SECRET_PATTERNS.some((p) => p.test(text))) {
      issues.push(issue(path, '疑似包含凭据，凭据只能通过连接的 credentialRef 引用'))
    } else if (key !== undefined && SECRET_KEY_NAMES.test(key) && !/^(env|vault):/.test(text)) {
      issues.push(issue(path, `字段 ${key} 看起来是凭据，只能写 env: 或 vault: 引用`))
    }
  })
  return issues
}

// ── tool-write-level ─────────────────────────────────────────────────────

function checkToolWriteLevels(asset: Asset): LintIssue[] {
  if (asset.kind !== 'ToolSource') return []
  return asset.spec.tools.flatMap((tool, i): LintIssue[] => {
    const path = `spec.tools[${i}]`
    if (tool.writeLevel === undefined) {
      return [
        {
          rule: 'tool-write-level',
          severity: 'error',
          message: `工具 ${tool.name} 没有声明写操作级别 writeLevel`,
          path,
        },
      ]
    }
    const capped = tool.category === 'payment' || tool.category === 'posting'
    if (capped && tool.writeLevel === 'L3') {
      return [
        {
          rule: 'tool-write-level',
          severity: 'error',
          message: `工具 ${tool.name} 属于付款或过账类，写操作级别最高只能到 L2`,
          path: `${path}.writeLevel`,
        },
      ]
    }
    return []
  })
}

// ── extension-point-declared ─────────────────────────────────────────────

interface ResolvedContext {
  resolver: AssetResolver
  statuses: readonly AssetStatus[]
}

function checkExtensionPoints(asset: Asset, ctx: ResolvedContext): LintIssue[] {
  if (asset.kind !== 'AgentExtension') return []
  const target = resolveAssetOfKind(ctx.resolver, asset.spec.target, 'Agent', ctx)
  if (target === undefined) return [] // 由 reference-resolvable 报告
  return extensionPointViolations(asset.spec, target.spec.extensionPoints).map((v) => ({
    rule: 'extension-point-declared',
    severity: 'error',
    message: v.message,
    path: v.path,
  }))
}

// ── reference-resolvable ─────────────────────────────────────────────────

function checkReferences(asset: Asset, ctx: ResolvedContext): LintIssue[] {
  const outOfScope = new Set(outOfScopeReferences(asset).map((r) => r.path))
  const inScope = collectReferences(asset).filter((r) => !outOfScope.has(r.path))
  const issues: LintIssue[] = inScope.flatMap((reference): LintIssue[] => {
    const resolved = resolveAsset(ctx.resolver, reference.ref, ctx)
    if (resolved === undefined) {
      const { range } = parseAssetRef(reference.ref)
      const detail = range === undefined ? '不存在' : `不存在，或者没有满足 ${range} 的版本`
      return [refIssue(`引用的资产 ${reference.ref} ${detail}`, reference.path)]
    }
    if (reference.kinds.length > 0 && !reference.kinds.includes(resolved.kind)) {
      return [
        refIssue(
          `引用的资产 ${reference.ref} 是 ${resolved.kind}，这里需要 ${reference.kinds.join(' 或 ')}`,
          reference.path,
        ),
      ]
    }
    return []
  })
  return [...issues, ...checkBuiltinTools(asset), ...checkRuleIds(asset, ctx)]
}

function checkBuiltinTools(asset: Asset): LintIssue[] {
  if (asset.kind !== 'Agent') return []
  return asset.spec.tools.flatMap((entry, i) => {
    const tool = normalizeToolEntry(entry)
    if (tool.type !== 'builtin' || findBuiltinTool(tool.name) !== undefined) return []
    return [refIssue(`内置工具 ${tool.name} 不存在`, `spec.tools[${i}].builtin`)]
  })
}

function refIssue(message: string, path: string): LintIssue {
  return { rule: 'reference-resolvable', severity: 'error', message, path }
}

/** 约束里引用的规则编号，必须能在相关语义包里找到 */
function checkRuleIds(asset: Asset, ctx: ResolvedContext): LintIssue[] {
  let constraints: readonly string[]
  let packRefs: readonly string[]
  let base: string
  if (asset.kind === 'Agent') {
    constraints = asset.spec.constraints
    packRefs = asset.spec.semanticPacks
    base = 'spec.constraints'
  } else if (asset.kind === 'AgentExtension') {
    const target = resolveAssetOfKind(ctx.resolver, asset.spec.target, 'Agent', ctx)
    constraints = asset.spec.constraints?.append ?? []
    packRefs = [...(target?.spec.semanticPacks ?? []), ...(asset.spec.knowledge?.add ?? [])]
    base = 'spec.constraints.append'
  } else {
    return []
  }
  const known = knownRuleIds(packRefs, ctx)
  return constraints.flatMap((c, i) =>
    isRuleId(c) && !known.has(c)
      ? [refIssue(`约束引用的规则 ${c} 在所挂载的语义包里找不到`, `${base}[${i}]`)]
      : [],
  )
}

function knownRuleIds(packRefs: readonly string[], ctx: ResolvedContext): Set<string> {
  const ids = new Set<string>()
  const visit = (ref: string, depth: number): void => {
    const pack = resolveAssetOfKind(ctx.resolver, ref, 'SemanticPack', ctx)
    if (pack === undefined) return
    for (const rule of pack.spec.rules) ids.add(rule.id)
    if (pack.spec.extends !== undefined && depth < 5) visit(pack.spec.extends, depth + 1)
  }
  for (const ref of packRefs) visit(ref, 0)
  return ids
}
