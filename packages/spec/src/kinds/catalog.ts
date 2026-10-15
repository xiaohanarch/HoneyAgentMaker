/**
 * 能力目录与接入相关的 kind：岗位助手、远程 Agent、知识源、连接、平台规则集。
 */
import { z } from 'zod'
import {
  AssetRefSchema,
  ConnectionAliasSchema,
  RoutingSchema,
  RuleIdSchema,
  defineKind,
} from '../common'
import { TriggerSchema } from './agent'

const KpiSchema = z.strictObject({
  name: z.string().regex(/^[a-z][a-z0-9_]*$/),
  displayName: z.string().min(1),
  unit: z.string().optional(),
  target: z.number().optional(),
  /** 指标越高越好还是越低越好 */
  direction: z.enum(['up', 'down']).default('up'),
})

/** 岗位助手（主提示词第 3 节），统一入口先路由到助手，助手再编排一组 Agent */
export const AssistantSchema = defineKind(
  'Assistant',
  z.strictObject({
    /** 成员 Agent 或远程 Agent */
    members: z.array(AssetRefSchema).min(1),
    routing: RoutingSchema,
    kpis: z.array(KpiSchema).max(4).default([]),
    triggers: z.array(TriggerSchema).default([{ type: 'assistant' }]),
  }),
)
export type AssistantAsset = z.infer<typeof AssistantSchema>

/** 远程 Agent 登记（主提示词 8.4、10.5 节），Agent Card 地址由连接别名绑定 */
export const RemoteAgentSchema = defineKind(
  'RemoteAgent',
  z.strictObject({
    connection: ConnectionAliasSchema,
    /** Agent Card 在连接上的路径 */
    agentCardPath: z.string().startsWith('/').default('/.well-known/agent-card.json'),
    auth: z.strictObject({
      type: z.enum(['platform-service-token', 'none']),
    }),
    routing: RoutingSchema,
    /** 允许调用它的 Agent 或岗位助手，空表示仅能被统一入口直接路由 */
    allowedCallers: z.array(AssetRefSchema).default([]),
    assistant: AssetRefSchema.optional(),
  }),
)
export type RemoteAgentAsset = z.infer<typeof RemoteAgentSchema>

/** 文档知识库（主提示词 5.1 节），文档路径相对于资产目录 */
export const KnowledgeSourceSchema = defineKind(
  'KnowledgeSource',
  z.strictObject({
    documents: z
      .array(
        z.strictObject({
          title: z.string().min(1),
          path: z.string().min(1),
        }),
      )
      .min(1),
  }),
)
export type KnowledgeSourceAsset = z.infer<typeof KnowledgeSourceSchema>

export const ENVIRONMENTS = ['private', 'dev', 'test', 'prod'] as const
export type Environment = (typeof ENVIRONMENTS)[number]

const CredentialRefSchema = z
  .string()
  .regex(
    /^(env|vault):[A-Za-z0-9_./-]+$/,
    '凭据只能写引用，例如 env:DEMO_ERP_CLIENT_SECRET，不能写凭据本身',
  )

const BindingSchema = z.strictObject({
  url: z.url('必须是完整的 URL'),
  auth: z.strictObject({
    type: z.enum(['none', 'client-credentials', 'token-exchange', 'platform-service-token']),
    credentialRef: CredentialRefSchema.optional(),
  }),
})

/**
 * 连接别名的定义（主提示词第 3 节、10.8.2 节）。
 * 这是唯一允许出现地址的 kind；每个环境一份绑定，凭据只写引用，不进方案包。
 */
export const ConnectionSchema = defineKind(
  'Connection',
  z.strictObject({
    alias: ConnectionAliasSchema,
    type: z.enum(['bo-api', 'mcp', 'a2a', 'http']),
    bindings: z.partialRecord(z.enum(ENVIRONMENTS), BindingSchema),
  }),
)
export type ConnectionAsset = z.infer<typeof ConnectionSchema>

/**
 * 平台安全规则（主提示词 13.1 节）。主提示词的 kind 表里没有这一项，
 * 新增它来承载“组装时排在最前、不可覆盖”的平台规则，只能用在 platform 命名空间。
 */
export const PlatformRuleSetSchema = defineKind(
  'PlatformRuleSet',
  z.strictObject({
    rules: z
      .array(
        z.strictObject({
          id: RuleIdSchema,
          name: z.string().min(1),
          description: z.string().min(1),
          /** 注入提示词的规则原文，租户只能看到 name 和 description */
          instruction: z.string().min(1),
          /** prompt：只写进提示词；guard：由监督层确定性执行；both：两者都有 */
          enforcement: z.enum(['prompt', 'guard', 'both']),
        }),
      )
      .min(1),
  }),
)
export type PlatformRuleSetAsset = z.infer<typeof PlatformRuleSetSchema>
