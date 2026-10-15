import type { PromptSegment } from '@eab/tracing'

/** 有效定义的指令片段（与 @eab/assembler 的 Segment 字段一致，这里只取需要的部分） */
export interface InstructionSegment {
  id: string
  kind: string
  source: string
  field: string
  text: string
  confidential: boolean
}

export interface UserContext {
  userId: string
  userName: string
  tenantId: string
  tenantName: string
  roles: readonly string[]
  dataScope: string
  today: string
  /** 受托自主运行时的委托说明 */
  delegation?: string
}

export interface KnowledgeSnippet {
  id: string
  source: string
  text: string
}

export interface OutputField {
  name: string
  type: string
  description?: string
  enum?: readonly string[]
}

export interface PromptInput {
  segments: readonly InstructionSegment[]
  user: UserContext
  memory: readonly string[]
  outputFields: readonly OutputField[]
}

/**
 * 提示模板：有效定义的片段（每段带来源标记）、用户上下文、长期记忆、检索到的知识、结构化结果格式。
 * 返回拼好的系统提示词，以及写进追踪的片段数组：追踪只保存片段，读取时按查看者裁剪。
 */
export function buildSystemPrompt(
  input: PromptInput,
  knowledge: readonly KnowledgeSnippet[],
): { system: string; segments: PromptSegment[] } {
  const generated: PromptSegment[] = [
    {
      id: 'ctx-user',
      kind: 'user-context',
      source: 'platform/runtime',
      confidential: false,
      text: [
        '## 当前用户',
        `${input.user.userName}（${input.user.userId}），租户 ${input.user.tenantName}，角色 ${input.user.roles.join('、')}`,
        `数据权限：${input.user.dataScope}`,
        `今天是 ${input.user.today}`,
        ...(input.user.delegation === undefined
          ? []
          : [`本次为受托自主运行：${input.user.delegation}`]),
      ].join('\n'),
    },
    ...(input.memory.length === 0
      ? []
      : [
          {
            id: 'ctx-memory',
            kind: 'memory',
            source: 'platform/memory',
            confidential: false,
            text: ['## 用户的长期记忆', ...input.memory.map((m) => `- ${m}`)].join('\n'),
          },
        ]),
    ...(knowledge.length === 0
      ? []
      : [
          {
            id: 'ctx-knowledge',
            kind: 'knowledge',
            source: 'platform/retrieval',
            confidential: false,
            text: [
              '## 相关的业务口径与规则',
              ...knowledge.map((k) => `- ${k.text}（来源：${k.source}）`),
            ].join('\n'),
          },
        ]),
    ...(input.outputFields.length === 0
      ? []
      : [
          {
            id: 'ctx-output',
            kind: 'output-format',
            source: 'platform/runtime',
            confidential: false,
            text: [
              '## 结构化结果',
              '回答的最后附一个 ```json 代码块，包含以下字段：',
              ...input.outputFields.map(
                (f) =>
                  `- ${f.name}（${f.type}${f.enum === undefined ? '' : `，取值 ${f.enum.join('/')}`}）${f.description === undefined ? '' : `：${f.description}`}`,
              ),
            ].join('\n'),
          },
        ]),
  ]
  const segments: PromptSegment[] = [
    ...input.segments.map((s) => ({
      id: s.id,
      kind: s.kind,
      source: s.source,
      confidential: s.confidential,
      text: s.text,
    })),
    ...generated,
  ]
  const system = [
    ...input.segments.map((s) => `<!-- ${s.kind} ${s.source} ${s.field} -->\n${s.text.trim()}`),
    ...generated.map((s) => s.text),
  ].join('\n\n')
  return { system, segments }
}

/** 从回复里取出最后一个 ```json 代码块作为结构化结果，并从展示文本里去掉它 */
export function extractOutput(text: string): { text: string; output?: Record<string, unknown> } {
  const fence = /```json\s*([\s\S]*?)```\s*$/
  const match = fence.exec(text.trimEnd())
  if (match?.[1] === undefined) return { text }
  try {
    const parsed: unknown = JSON.parse(match[1])
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return { text }
    return {
      text: text.trimEnd().slice(0, match.index).trimEnd(),
      output: parsed as Record<string, unknown>,
    }
  } catch {
    return { text }
  }
}
