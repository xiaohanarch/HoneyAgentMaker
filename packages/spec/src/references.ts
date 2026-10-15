import type { Asset, AssetKind } from './assets'
import { normalizeToolEntry, type Hooks, type ToolEntryOrRef } from './kinds/agent-parts'
import type { Step } from './kinds/steps'

/** 资产里的一处引用：位置、引用字符串、允许的目标 kind */
export interface AssetReference {
  path: string
  ref: string
  kinds: readonly AssetKind[]
}

const TOOL_TARGET_KINDS = {
  skill: ['Skill'],
  agent: ['Agent'],
  remoteAgent: ['RemoteAgent'],
  mcp: ['ToolSource'],
  api: ['ToolSource'],
  documents: ['KnowledgeSource'],
} as const satisfies Record<string, readonly AssetKind[]>

/** 扩展里直接写引用字符串时，可能指向的 kind */
const SHORTHAND_TOOL_KINDS: readonly AssetKind[] = [
  'ToolSource',
  'Skill',
  'Agent',
  'RemoteAgent',
  'KnowledgeSource',
]

function toolReferences(entries: readonly ToolEntryOrRef[], base: string): AssetReference[] {
  return entries.flatMap((entry, i): AssetReference[] => {
    const path = `${base}[${i}]`
    if (typeof entry === 'string') return [{ path, ref: entry, kinds: SHORTHAND_TOOL_KINDS }]
    const normalized = normalizeToolEntry(entry)
    if (normalized.type === 'builtin') return []
    return [
      {
        path: `${path}.${normalized.type}`,
        ref: normalized.ref,
        kinds: TOOL_TARGET_KINDS[normalized.type],
      },
    ]
  })
}

function hookReferences(hooks: Hooks | undefined, base: string): AssetReference[] {
  if (hooks === undefined) return []
  return Object.entries(hooks).flatMap(([event, bindings]) =>
    (bindings ?? []).map((binding, i): AssetReference => {
      const path = `${base}.${event}[${i}]`
      return binding.workflow !== undefined
        ? { path: `${path}.workflow`, ref: binding.workflow, kinds: ['Workflow'] }
        : { path: `${path}.skill`, ref: binding.skill ?? '', kinds: ['Skill'] }
    }),
  )
}

function stepReferences(steps: readonly Step[], base: string): AssetReference[] {
  return steps.flatMap((step, i) => {
    const path = `${base}[${i}]`
    const own: AssetReference[] = []
    if (step.skill !== undefined)
      own.push({ path: `${path}.skill`, ref: step.skill, kinds: ['Skill'] })
    if (step.agent !== undefined)
      own.push({ path: `${path}.agent`, ref: step.agent, kinds: ['Agent'] })
    if (step.source !== undefined) {
      own.push({ path: `${path}.source`, ref: step.source, kinds: ['ToolSource'] })
    }
    return [
      ...own,
      ...stepReferences(step.then ?? [], `${path}.then`),
      ...stepReferences(step.else ?? [], `${path}.else`),
      ...(step.branches ?? []).flatMap((b, j) => stepReferences(b, `${path}.branches[${j}]`)),
    ]
  })
}

const refList = (
  refs: readonly string[],
  base: string,
  kinds: readonly AssetKind[],
): AssetReference[] => refs.map((ref, i) => ({ path: `${base}[${i}]`, ref, kinds }))

/** 收集资产里对其他资产的全部引用，静态检查和血缘关系共用 */
export function collectReferences(asset: Asset): AssetReference[] {
  switch (asset.kind) {
    case 'Agent': {
      const s = asset.spec
      return [
        ...toolReferences(s.tools, 'spec.tools'),
        ...refList(s.semanticPacks, 'spec.semanticPacks', ['SemanticPack']),
        ...refList(
          [...(s.executionSteps?.pre ?? []), ...(s.executionSteps?.post ?? [])].flatMap((st) =>
            st.skill === undefined ? [] : [st.skill],
          ),
          'spec.executionSteps',
          ['Skill'],
        ),
        ...hookReferences(s.hooks, 'spec.hooks'),
        ...refList(s.evals?.suites ?? [], 'spec.evals.suites', ['EvalSuite']),
      ]
    }
    case 'AgentExtension': {
      const s = asset.spec
      return [
        { path: 'spec.target', ref: s.target, kinds: ['Agent'] },
        ...toolReferences(s.tools?.add ?? [], 'spec.tools.add'),
        ...refList(s.skills?.add ?? [], 'spec.skills.add', ['Skill']),
        ...refList(s.knowledge?.add ?? [], 'spec.knowledge.add', [
          'SemanticPack',
          'KnowledgeSource',
        ]),
        ...hookReferences(s.hooks, 'spec.hooks'),
        ...refList(s.evals?.suites ?? [], 'spec.evals.suites', ['EvalSuite']),
      ]
    }
    case 'Assistant':
      return refList(asset.spec.members, 'spec.members', ['Agent', 'RemoteAgent'])
    case 'RemoteAgent':
      return [
        ...refList(asset.spec.allowedCallers, 'spec.allowedCallers', ['Agent', 'Assistant']),
        ...(asset.spec.assistant === undefined
          ? []
          : [{ path: 'spec.assistant', ref: asset.spec.assistant, kinds: ['Assistant'] as const }]),
      ]
    case 'Skill':
    case 'Workflow':
      return stepReferences(asset.spec.steps, 'spec.steps')
    case 'EvalSuite':
      return [{ path: 'spec.target', ref: asset.spec.target, kinds: ['Agent', 'AgentExtension'] }]
    case 'SemanticPack':
      return asset.spec.extends === undefined
        ? []
        : [{ path: 'spec.extends', ref: asset.spec.extends, kinds: ['SemanticPack'] }]
    case 'Solution':
      return refList(asset.spec.assets, 'spec.assets', [])
    case 'DevRequest':
      return asset.spec.context.assistant === undefined
        ? []
        : [
            {
              path: 'spec.context.assistant',
              ref: asset.spec.context.assistant,
              kinds: ['Assistant'],
            },
          ]
    case 'ToolSource':
    case 'KnowledgeSource':
    case 'Connection':
    case 'PlatformRuleSet':
      return []
  }
}
