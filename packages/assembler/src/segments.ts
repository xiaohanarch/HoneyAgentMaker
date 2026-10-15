import {
  isRuleId,
  layerOf,
  resolveAssetOfKind,
  type AgentAsset,
  type AgentExtensionAsset,
  type Asset,
  type AssetResolver,
  type AssetStatus,
  type PlatformRuleSetAsset,
  type SemanticPackAsset,
  type SemanticRule,
} from '@eab/spec'
import type { AssemblyIssue, Segment } from './types'

export function versionedRef(asset: Asset): string {
  return `${asset.metadata.namespace}/${asset.metadata.name}@${asset.metadata.version}`
}

interface ResolveContext {
  resolver: AssetResolver
  statuses: readonly AssetStatus[]
}

export interface PackResolution {
  packs: SemanticPackAsset[]
  issues: AssemblyIssue[]
}

const MAX_EXTENDS_DEPTH = 5

/**
 * 解析 Agent 与扩展挂载的语义包，连同 extends 链上的父包，按出现顺序去重。
 * 扩展的 knowledge.add 里只有 SemanticPack 会进这里，KnowledgeSource 作为文档工具处理。
 */
export function resolveSemanticPacks(
  agent: AgentAsset,
  extensions: readonly AgentExtensionAsset[],
  ctx: ResolveContext,
): PackResolution {
  const packs: SemanticPackAsset[] = []
  const issues: AssemblyIssue[] = []
  const seen = new Set<string>()

  const visit = (ref: string, from: Asset, depth: number, optional: boolean): void => {
    const pack = resolveAssetOfKind(ctx.resolver, ref, 'SemanticPack', ctx)
    if (pack === undefined) {
      if (!optional) {
        issues.push({
          code: 'REFERENCE_UNRESOLVED',
          severity: 'error',
          message: `语义包 ${ref} 不存在或没有满足范围的已发布版本`,
          source: versionedRef(from),
        })
      }
      return
    }
    const key = `${pack.metadata.namespace}/${pack.metadata.name}`
    if (seen.has(key)) return
    seen.add(key)
    if (pack.spec.extends !== undefined && depth < MAX_EXTENDS_DEPTH) {
      visit(pack.spec.extends, pack, depth + 1, false)
    }
    packs.push(pack)
  }

  for (const ref of agent.spec.semanticPacks) visit(ref, agent, 0, false)
  for (const ext of extensions) {
    for (const ref of ext.spec.knowledge?.add ?? []) visit(ref, ext, 0, true)
  }
  return { packs, issues }
}

function ruleIndex(
  packs: readonly SemanticPackAsset[],
): Map<string, { rule: SemanticRule; pack: SemanticPackAsset }> {
  return new Map(
    packs.flatMap((pack) => pack.spec.rules.map((rule) => [rule.id, { rule, pack }] as const)),
  )
}

interface SegmentInput {
  platformRules: readonly PlatformRuleSetAsset[]
  agent: AgentAsset
  packs: readonly SemanticPackAsset[]
  extensions: readonly AgentExtensionAsset[]
}

/**
 * 按固定顺序拼接指令（主提示词 5.4 节第 1 条）：
 * 平台安全规则 → 标品指令 → 标品约束 → 语义包 agent_instructions → 租户追加指令 → 租户追加约束。
 */
export function buildSegments(input: SegmentInput): {
  segments: Segment[]
  issues: AssemblyIssue[]
} {
  const rules = ruleIndex(input.packs)
  const issues: AssemblyIssue[] = []
  const { agent } = input
  const agentRef = versionedRef(agent)
  // 标品层的指令对租户保密；租户自己的 Agent（例如“复制为租户 Agent”的副本）对本租户可见
  const agentConfidential = layerOf(agent) !== 'tenant'

  const constraintSegment = (
    text: string,
    owner: Asset,
    field: string,
    kind: 'vendor-constraint' | 'tenant-constraint',
  ): Segment | undefined => {
    const base = { kind, layer: layerOf(owner), source: versionedRef(owner), field }
    if (!isRuleId(text)) {
      return {
        ...base,
        id: `${kind}:${base.source}:${field}`,
        text,
        confidential: kind === 'vendor-constraint' && agentConfidential,
      }
    }
    const found = rules.get(text)
    if (found === undefined) {
      issues.push({
        code: 'REFERENCE_UNRESOLVED',
        severity: 'error',
        message: `约束引用的规则 ${text} 在挂载的语义包里找不到`,
        source: versionedRef(owner),
        path: field,
      })
      return undefined
    }
    // 规则来自语义包，对租户可见；文本取规则的处置建议
    return {
      ...base,
      id: `${kind}:${base.source}:${text}`,
      text: `[${text}] ${found.rule.remedy}`,
      label: text,
      confidential: false,
    }
  }

  const segments: Segment[] = [
    ...input.platformRules.flatMap((set) =>
      set.spec.rules.map((rule, i): Segment => ({
        id: `platform-rule:${rule.id}`,
        kind: 'platform-rule',
        layer: 'platform',
        source: versionedRef(set),
        field: `spec.rules[${i}].instruction`,
        text: rule.instruction,
        label: rule.name,
        confidential: true,
      })),
    ),
    {
      id: 'vendor-instructions',
      kind: 'vendor-instructions',
      layer: layerOf(agent),
      source: agentRef,
      field: 'spec.instructions',
      text: agent.spec.instructions,
      confidential: agentConfidential,
    },
    ...(agent.spec.additionalContext === undefined
      ? []
      : [
          {
            id: 'vendor-context',
            kind: 'vendor-context' as const,
            layer: layerOf(agent),
            source: agentRef,
            field: 'spec.additionalContext',
            text: agent.spec.additionalContext,
            confidential: agentConfidential,
          },
        ]),
    ...agent.spec.constraints.flatMap((c, i) => {
      const s = constraintSegment(c, agent, `spec.constraints[${i}]`, 'vendor-constraint')
      return s === undefined ? [] : [s]
    }),
    ...input.packs.flatMap((pack) =>
      pack.spec.agent_instructions.map((text, i): Segment => ({
        id: `semantic-instruction:${pack.metadata.namespace}/${pack.metadata.name}:${i}`,
        kind: 'semantic-instruction',
        layer: layerOf(pack),
        source: versionedRef(pack),
        field: `spec.agent_instructions[${i}]`,
        text,
        confidential: false,
      })),
    ),
    ...input.extensions.flatMap((ext) =>
      ext.spec.instructions === undefined
        ? []
        : [
            {
              id: `tenant-instructions:${versionedRef(ext)}`,
              kind: 'tenant-instructions' as const,
              layer: layerOf(ext),
              source: versionedRef(ext),
              field: 'spec.instructions.append',
              text: ext.spec.instructions.append,
              confidential: false,
            },
          ],
    ),
    ...input.extensions.flatMap((ext) =>
      (ext.spec.constraints?.append ?? []).flatMap((c, i) => {
        const s = constraintSegment(c, ext, `spec.constraints.append[${i}]`, 'tenant-constraint')
        return s === undefined ? [] : [s]
      }),
    ),
  ]
  return { segments, issues }
}
