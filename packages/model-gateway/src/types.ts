import type { ModelProfile } from '@eab/spec'

/** 模型看到的一次工具调用；arguments 已解析为对象 */
export interface ToolCall {
  id: string
  name: string
  arguments: Record<string, unknown>
}

export type ChatMessage =
  | { role: 'user'; content: string }
  | { role: 'assistant'; content: string; toolCalls?: ToolCall[] }
  | { role: 'tool'; toolCallId: string; name: string; content: string }

/** 暴露给模型的工具，inputSchema 是 JSON Schema */
export interface ToolSpec {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

/** 脚本化模型和回退回复需要的运行上下文，真实模型不使用 */
export interface ModelCallContext {
  /** namespace/name */
  agent: string
  agentDisplayName: string
  userId: string
  /** Agent 的示例话术，剧本未覆盖时列给用户 */
  examples: readonly string[]
}

export interface ModelRequest {
  profile: ModelProfile
  system: string
  messages: readonly ChatMessage[]
  tools: readonly ToolSpec[]
  temperature?: number
  context: ModelCallContext
}

export interface Usage {
  inputTokens: number
  outputTokens: number
  /** 脚本化模型按字数估算，不是真实计费 */
  estimated: boolean
}

export interface ModelResponse {
  text: string
  toolCalls: ToolCall[]
  /** 结构化结果；脚本化模型由剧本给出，真实模型由运行时从回复里的 JSON 代码块解析 */
  output?: Record<string, unknown>
  usage: Usage
  provider: string
  model: string
  stopReason: 'end' | 'tool_use' | 'max_tokens' | 'blocked'
  /** 脚本化模型：命中的剧本编号；未命中时为 fallback */
  scriptId?: string
}

/** 模型提供方：实际的模型名由网关按档位解析后传入 */
export interface ModelProvider {
  readonly name: string
  complete(request: ModelRequest & { model: string }): Promise<ModelResponse>
}

export class ModelError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ModelError'
  }
}
