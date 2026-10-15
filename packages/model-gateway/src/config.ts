import type { ModelProfile } from '@eab/spec'
import type { Usage } from './types'

export const PROVIDER_NAMES = ['scripted', 'openai', 'anthropic'] as const
export type ProviderName = (typeof PROVIDER_NAMES)[number]

export interface GatewayConfig {
  provider: ProviderName
  /** 档位到模型名的映射，Agent 只引用档位 */
  profiles: Record<ModelProfile, string>
  openai?: { baseUrl: string; apiKey: string }
  anthropic?: { baseUrl: string; apiKey: string }
  /** 每百万 token 的美元单价，用于估算成本 */
  prices: Record<string, { input: number; output: number }>
}

const DEFAULT_PROFILES: Record<ProviderName, Record<ModelProfile, string>> = {
  scripted: { fast: 'scripted', reasoning: 'scripted', judge: 'scripted' },
  anthropic: {
    fast: 'claude-haiku-5-5',
    reasoning: 'claude-sonnet-5-5',
    judge: 'claude-opus-5-5',
  },
  openai: { fast: '', reasoning: '', judge: '' },
}

/** 演示用的估算单价，可以用 EAB_MODEL_PRICES（JSON）覆盖，不代表任何厂商的实际报价 */
const DEFAULT_PRICES: GatewayConfig['prices'] = {
  scripted: { input: 0, output: 0 },
  'claude-haiku-5-5': { input: 1, output: 5 },
  'claude-sonnet-5-5': { input: 3, output: 15 },
  'claude-opus-5-5': { input: 5, output: 25 },
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ConfigError'
  }
}

/**
 * 从环境变量读取模型网关配置。Key 只从环境变量读取，不写入任何文件；
 * 选择了真实提供方却缺少 Key 或模型名时启动即报错，而不是静默退回脚本化模型。
 */
export function configFromEnv(
  env: Record<string, string | undefined> = process.env,
): GatewayConfig {
  const provider = (env.EAB_MODEL_PROVIDER ?? 'scripted') as ProviderName
  if (!PROVIDER_NAMES.includes(provider)) {
    throw new ConfigError(
      `EAB_MODEL_PROVIDER 取值应为 ${PROVIDER_NAMES.join('、')}，实际为 ${provider}`,
    )
  }
  const defaults = DEFAULT_PROFILES[provider]
  const profiles: Record<ModelProfile, string> = {
    fast: env.EAB_MODEL_FAST ?? defaults.fast,
    reasoning: env.EAB_MODEL_REASONING ?? defaults.reasoning,
    judge: env.EAB_MODEL_JUDGE ?? defaults.judge,
  }
  const prices = { ...DEFAULT_PRICES, ...parsePrices(env.EAB_MODEL_PRICES) }
  const config: GatewayConfig = { provider, profiles, prices }

  if (provider === 'anthropic') {
    const apiKey = requireEnv(env, 'ANTHROPIC_API_KEY')
    return {
      ...config,
      anthropic: { apiKey, baseUrl: env.EAB_ANTHROPIC_BASE_URL ?? 'https://api.anthropic.com' },
    }
  }
  if (provider === 'openai') {
    const apiKey = requireEnv(env, 'OPENAI_API_KEY')
    const baseUrl = requireEnv(env, 'EAB_OPENAI_BASE_URL')
    for (const [profile, model] of Object.entries(profiles)) {
      if (model === '')
        throw new ConfigError(`OpenAI 兼容提供方需要设置 EAB_MODEL_${profile.toUpperCase()}`)
    }
    return { ...config, openai: { apiKey, baseUrl } }
  }
  return config
}

function requireEnv(env: Record<string, string | undefined>, name: string): string {
  const value = env[name]
  if (value === undefined || value === '') throw new ConfigError(`缺少环境变量 ${name}`)
  return value
}

function parsePrices(raw: string | undefined): GatewayConfig['prices'] {
  if (raw === undefined || raw === '') return {}
  try {
    return JSON.parse(raw) as GatewayConfig['prices']
  } catch {
    throw new ConfigError('EAB_MODEL_PRICES 不是合法的 JSON')
  }
}

/** 估算成本（美元），没有单价的模型记为 0 并在追踪里标明 */
export function estimateCost(
  prices: GatewayConfig['prices'],
  model: string,
  usage: Usage,
): { usd: number; priced: boolean } {
  const price = prices[model]
  if (price === undefined) return { usd: 0, priced: false }
  const usd = (usage.inputTokens * price.input + usage.outputTokens * price.output) / 1_000_000
  return { usd: Math.round(usd * 1_000_000) / 1_000_000, priced: true }
}

/** 粗略估算 token 数：中文约一字一个 token，其他字符约四个一个 */
export function estimateTokens(text: string): number {
  const cjk = text.match(/[㐀-鿿]/g)?.length ?? 0
  return cjk + Math.ceil((text.length - cjk) / 4)
}
