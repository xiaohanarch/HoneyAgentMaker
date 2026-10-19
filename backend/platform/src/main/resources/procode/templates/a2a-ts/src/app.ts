import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import { AgentCard } from '@a2a-js/sdk'
import { DefaultRequestHandler, InMemoryTaskStore } from '@a2a-js/sdk/server'
import { UserBuilder, agentCardHandler, jsonRpcHandler } from '@a2a-js/sdk/server/express'
import { AuthError, type Verifier } from './auth.js'
import { {{namePascal}}Executor } from './executor.js'

const CARD_PATHS = ['/.well-known/agent-card.json', '/.well-known/agent.json']

export function agentCard(publicUrl: string): AgentCard {
  return {
    name: '{{name}}',
    description: '<一句话描述这个外部 Agent 做什么>',
    supportedInterfaces: [
      { url: `${publicUrl}/`, protocolBinding: 'JSONRPC', tenant: '', protocolVersion: '1.0' },
    ],
    provider: { organization: '<组织名>', url: publicUrl },
    version: '0.1.0',
    capabilities: { streaming: false, pushNotifications: false, extensions: [] },
    securitySchemes: {
      platformToken: {
        scheme: {
          $case: 'httpAuthSecurityScheme',
          value: { description: '平台签发的服务令牌', scheme: 'bearer', bearerFormat: 'JWT' },
        },
      },
    },
    securityRequirements: [{ schemes: { platformToken: { list: [] } } }],
    defaultInputModes: ['text/plain'],
    defaultOutputModes: ['text/plain'],
    skills: [
      {
        id: '{{name}}',
        name: '<技能名>',
        description: '<技能说明>',
        tags: [],
        examples: ['<用户会怎么问>'],
        inputModes: [],
        outputModes: [],
        securityRequirements: [],
      },
    ],
    signatures: [],
  }
}

/** Agent Card 公开；JSON-RPC 端点要求平台签发的服务令牌，校验失败返回 401 */
export function createApp(publicUrl: string, verify: Verifier): Express {
  const card = agentCard(publicUrl)
  const handler = new DefaultRequestHandler(card, new InMemoryTaskStore(), new {{namePascal}}Executor())
  const app = express()
  // 卡片按协议的 JSON 形式输出（oneof 字段写成 httpAuthSecurityScheme），其他语言的 SDK 才能解析
  const cardJson = AgentCard.toJSON(card) as AgentCard
  for (const path of CARD_PATHS) {
    app.use(path, agentCardHandler({ agentCardProvider: async () => cardJson }))
  }
  app.post('/', async (req: Request, res: Response, next: NextFunction) => {
    try {
      await verify(req.header('authorization'))
      next()
    } catch (error) {
      const message = error instanceof AuthError ? error.message : '鉴权失败'
      res.status(401).json({ error: message })
    }
  })
  app.post(
    '/',
    jsonRpcHandler({ requestHandler: handler, userBuilder: UserBuilder.noAuthentication }),
  )
  return app
}
