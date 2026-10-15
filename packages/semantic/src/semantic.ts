import type { SemanticPackAsset } from '@eab/spec'

/**
 * 语义查询工具（主提示词 5.5 节）：只在 Agent 挂载的语义包范围内查询。
 * 投影段只写元数据引用，定义由 MetaClient 实时从元数据服务取；
 * 返回结果同时带语义包版本和元数据版本，回答可追溯到具体口径。
 */

export interface MetaFieldDef {
  name: string
  type: string
  label: string
  enum?: string[]
}

export interface MetaObjectDef {
  /** 带版本的引用，例如 erp.pur.Supplier@v1 */
  ref: string
  label: string
  version: number
  permissionDimension: string | null
  fields: MetaFieldDef[]
}

export interface MetaClient {
  getObject(ref: string): Promise<MetaObjectDef | undefined>
}

type Pack = SemanticPackAsset
type PackObject = Pack['spec']['objects'][number]

const packRef = (pack: Pack): string =>
  `${pack.metadata.namespace}/${pack.metadata.name}@${pack.metadata.version}`

/** 去掉版本后缀：erp.pur.Supplier@v1 → erp.pur.Supplier */
const baseRef = (ref: string): string => ref.split('@')[0] ?? ref

/** 中文检索用字符二元组的重合度打分，英文和数字按词；足够演示，不依赖分词库 */
export function tokens(text: string): Set<string> {
  const lower = text.toLowerCase()
  const words = lower.match(/[a-z0-9_]+/g) ?? []
  const cjk = lower.replace(/[^㐀-鿿]/g, '')
  const bigrams = [...cjk].slice(0, -1).map((c, i) => c + (cjk[i + 1] ?? ''))
  return new Set([...words, ...bigrams, ...(cjk.length === 1 ? [cjk] : [])])
}

export function score(query: string, text: string): number {
  const q = tokens(query)
  if (q.size === 0) return 0
  const t = tokens(text)
  let hits = 0
  for (const token of q) if (t.has(token)) hits += 1
  return hits / q.size
}

export interface SearchHit {
  type: 'object' | 'metric' | 'rule' | 'verified_task'
  id: string
  title: string
  summary: string
  pack: string
  score: number
}

export interface KnowledgeSnippet {
  id: string
  source: string
  text: string
}

export class SemanticError extends Error {
  constructor(
    readonly code: 'NOT_FOUND' | 'BAD_INPUT',
    message: string,
  ) {
    super(message)
    this.name = 'SemanticError'
  }
}

export class SemanticService {
  constructor(
    private readonly packs: readonly Pack[],
    private readonly meta: MetaClient,
  ) {}

  get packRefs(): string[] {
    return this.packs.map(packRef)
  }

  #findObject(name: string): { pack: Pack; object: PackObject } | undefined {
    const wanted = name.trim().toLowerCase()
    for (const pack of this.packs) {
      for (const object of pack.spec.objects) {
        const ref = baseRef(object.ref).toLowerCase()
        if (ref === wanted || ref.endsWith(`.${wanted}`) || baseRef(wanted) === ref) return { pack, object }
      }
    }
    // 按说明模糊匹配，例如“供应商”
    let best: { pack: Pack; object: PackObject; s: number } | undefined
    for (const pack of this.packs) {
      for (const object of pack.spec.objects) {
        const s = score(name, `${object.description ?? ''} ${object.ref}`)
        if (s > 0.5 && (best === undefined || s > best.s)) best = { pack, object, s }
      }
    }
    return best
  }

  async describeObject(input: { object: string }): Promise<Record<string, unknown>> {
    if (input.object.trim() === '') throw new SemanticError('BAD_INPUT', '请给出业务对象名')
    const found = this.#findObject(input.object)
    if (found === undefined) {
      throw new SemanticError(
        'NOT_FOUND',
        `挂载的语义包里没有业务对象 ${input.object}，可用的对象：${this.packs
          .flatMap((p) => p.spec.objects.map((o) => baseRef(o.ref)))
          .join('、')}`,
      )
    }
    const { pack, object } = found
    const meta = await this.meta.getObject(object.ref)
    const notes = new Map((object.fields ?? []).map((f) => [f.ref, f]))
    const fields = (meta?.fields ?? []).map((field) => {
      const note = notes.get(field.name)
      return {
        ...field,
        ...(note?.description === undefined ? {} : { description: note.description }),
        ...(note?.enumMeaning === undefined ? {} : { enumMeaning: note.enumMeaning }),
      }
    })
    const ref = baseRef(object.ref)
    const relationships = this.packs
      .flatMap((p) => p.spec.relationships)
      .filter((r) => r.from === ref || r.to === ref)
    return {
      ref,
      label: meta?.label ?? ref,
      description: object.description ?? '',
      fields,
      relationships,
      permissionDimension: meta?.permissionDimension ?? null,
      provenance: {
        semanticPack: packRef(pack),
        metadata: meta === undefined ? '元数据服务未返回该对象' : meta.ref,
      },
    }
  }

  getMetric(input: { name: string }): Record<string, unknown> {
    const candidates = this.packs.flatMap((pack) => pack.spec.metrics.map((metric) => ({ pack, metric })))
    const exact = candidates.find((c) => c.metric.name === input.name || c.metric.displayName === input.name)
    const fuzzy = candidates
      .map((c) => ({ ...c, s: score(input.name, `${c.metric.displayName} ${c.metric.name}`) }))
      .filter((c) => c.s >= 0.5)
      .sort((a, b) => b.s - a.s)[0]
    const hit = exact ?? fuzzy
    if (hit === undefined) {
      throw new SemanticError(
        'NOT_FOUND',
        `没有指标 ${input.name}，可用的指标：${candidates.map((c) => c.metric.displayName).join('、')}`,
      )
    }
    return { ...hit.metric, provenance: { semanticPack: packRef(hit.pack) } }
  }

  findRules(input: { object?: string; keyword?: string }): Record<string, unknown> {
    const domain = input.object === undefined ? undefined : this.#findObject(input.object)?.pack
    const rules = this.packs
      .filter((pack) => domain === undefined || pack === domain)
      .flatMap((pack) => pack.spec.rules.map((rule) => ({ ...rule, semanticPack: packRef(pack) })))
      .filter(
        (rule) =>
          input.keyword === undefined ||
          input.keyword === '' ||
          rule.id === input.keyword ||
          score(input.keyword, `${rule.description ?? ''} ${rule.remedy}`) >= 0.3,
      )
    return { items: rules, total: rules.length }
  }

  search(input: { query: string; limit?: number }): Record<string, unknown> {
    const limit = input.limit ?? 8
    const hits = this.#hits(input.query).slice(0, limit)
    return { items: hits, total: hits.length, packs: this.packRefs }
  }

  #hits(query: string): SearchHit[] {
    const all: SearchHit[] = this.packs.flatMap((pack) => {
      const p = packRef(pack)
      return [
        ...pack.spec.objects.map((o) => ({
          type: 'object' as const,
          id: baseRef(o.ref),
          title: o.description ?? baseRef(o.ref),
          summary: (o.fields ?? []).map((f) => f.description ?? f.ref).join('；'),
          pack: p,
          score: 0,
        })),
        ...pack.spec.metrics.map((m) => ({
          type: 'metric' as const,
          id: m.name,
          title: m.displayName,
          summary: m.definition,
          pack: p,
          score: 0,
        })),
        ...pack.spec.rules.map((r) => ({
          type: 'rule' as const,
          id: r.id,
          title: r.description ?? r.id,
          summary: r.remedy,
          pack: p,
          score: 0,
        })),
        ...pack.spec.verified_tasks.map((t) => ({
          type: 'verified_task' as const,
          id: t.id,
          title: t.question,
          summary: t.expected_conclusion,
          pack: p,
          score: 0,
        })),
      ]
    })
    return all
      .map((hit) => ({ ...hit, score: Math.round(score(query, `${hit.title} ${hit.summary} ${hit.id}`) * 100) / 100 }))
      .filter((hit) => hit.score > 0)
      .sort((a, b) => b.score - a.score)
  }

  /**
   * 知识检索注入（主提示词 8.2 节）：按话术取最相关的指标口径和规则，注入系统提示词。
   * 阈值较高，宁可少注入，也不把无关口径塞进提示词。
   */
  retrieve(query: string, limit = 4): KnowledgeSnippet[] {
    return this.#hits(query)
      .filter((hit) => (hit.type === 'metric' || hit.type === 'rule') && hit.score >= 0.25)
      .slice(0, limit)
      .map((hit) => ({
        id: hit.id,
        source: hit.pack,
        text: hit.type === 'metric' ? `指标 ${hit.title}：${hit.summary}` : `规则 ${hit.id}（${hit.title}）：${hit.summary}`,
      }))
  }
}

/** 四个语义查询工具的入参 JSON Schema，经工具网关暴露给模型 */
export const SEMANTIC_TOOL_SCHEMAS: Readonly<Record<string, Record<string, unknown>>> = {
  'semantic.describe_object': {
    type: 'object',
    required: ['object'],
    properties: { object: { type: 'string', description: '业务对象，例如 erp.pur.Supplier 或 供应商' } },
  },
  'semantic.get_metric': {
    type: 'object',
    required: ['name'],
    properties: { name: { type: 'string', description: '指标名或显示名，例如 overdue_days、逾期交付率' } },
  },
  'semantic.find_rules': {
    type: 'object',
    properties: {
      object: { type: 'string', description: '业务对象，可选' },
      keyword: { type: 'string', description: '关键词或规则编号，可选' },
    },
  },
  'semantic.search': {
    type: 'object',
    required: ['query'],
    properties: { query: { type: 'string' }, limit: { type: 'integer', minimum: 1, maximum: 20 } },
  },
}

/** 按工具名分发调用 */
export async function callSemanticTool(
  service: SemanticService,
  name: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const str = (key: string): string | undefined => (typeof args[key] === 'string' ? (args[key] as string) : undefined)
  switch (name) {
    case 'semantic.describe_object':
      return service.describeObject({ object: str('object') ?? '' })
    case 'semantic.get_metric':
      return service.getMetric({ name: str('name') ?? '' })
    case 'semantic.find_rules': {
      const object = str('object')
      const keyword = str('keyword')
      return service.findRules({ ...(object === undefined ? {} : { object }), ...(keyword === undefined ? {} : { keyword }) })
    }
    case 'semantic.search':
      return service.search({ query: str('query') ?? '', ...(typeof args.limit === 'number' ? { limit: args.limit } : {}) })
    default:
      throw new SemanticError('NOT_FOUND', `没有语义查询工具 ${name}`)
  }
}

/** 从元数据服务（DemoERP 的 /meta/v1）取对象定义 */
export function httpMetaClient(baseUrl: string, fetchImpl: typeof fetch = fetch): MetaClient {
  return {
    async getObject(ref) {
      const response = await fetchImpl(new URL(`/meta/v1/objects/${encodeURIComponent(ref)}`, baseUrl), {
        signal: AbortSignal.timeout(5_000),
      })
      if (response.status === 404) return undefined
      if (!response.ok) throw new Error(`元数据服务返回 ${response.status}`)
      return (await response.json()) as MetaObjectDef
    },
  }
}
