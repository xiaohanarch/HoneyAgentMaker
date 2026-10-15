/**
 * @eab/model-gateway：模型网关、模型档位、脚本化模型与处理管道（主提示词 8.2 节）。
 */
import { configFromEnv, type GatewayConfig } from './config'
import { ModelGateway } from './gateway'
import { AnthropicProvider, OpenAiProvider } from './providers/http-providers'
import { ScriptedProvider, type Script } from './providers/scripted'
import type { ModelProvider } from './types'

export * from './config'
export {
  ModelGateway,
  type GatewayRequest,
  type GatewayResponse,
  type TraceTarget,
} from './gateway'
export { INPUT_BLOCKED_REPLY, checkInput, filterOutput } from './pipeline/filters'
export { Masker, type MaskEntry } from './pipeline/masking'
export {
  buildSystemPrompt,
  extractOutput,
  type InstructionSegment,
  type KnowledgeSnippet,
  type OutputField,
  type PromptInput,
  type UserContext,
} from './pipeline/template'
export {
  AnthropicProvider,
  OpenAiProvider,
  decodeToolName,
  encodeToolName,
  fromAnthropicResponse,
  fromOpenAiResponse,
  toAnthropicBody,
  toOpenAiBody,
} from './providers/http-providers'
export { ScriptLoadError, loadScripts } from './providers/load-scripts'
export {
  ScriptFileSchema,
  ScriptSchema,
  ScriptedProvider,
  fallbackReply,
  matchScript,
  templateKey,
  type Script,
} from './providers/scripted'
export * from './types'

/** 按配置创建提供方；脚本化模型需要传入已加载的剧本 */
export function createProvider(
  config: GatewayConfig,
  options: { scripts: readonly Script[]; today?: () => string; fetch?: typeof fetch },
): ModelProvider {
  if (config.provider === 'anthropic' && config.anthropic !== undefined) {
    return new AnthropicProvider({
      ...config.anthropic,
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
    })
  }
  if (config.provider === 'openai' && config.openai !== undefined) {
    return new OpenAiProvider({
      ...config.openai,
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
    })
  }
  return new ScriptedProvider({
    scripts: options.scripts,
    ...(options.today === undefined ? {} : { today: options.today }),
  })
}

export function createModelGateway(options: {
  scripts: readonly Script[]
  today?: () => string
  env?: Record<string, string | undefined>
}): ModelGateway {
  const config = configFromEnv(options.env)
  return new ModelGateway(createProvider(config, options), config)
}
