import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import type { AgentCard } from '@a2a-js/sdk'
import { DefaultRequestHandler, InMemoryTaskStore } from '@a2a-js/sdk/server'
import { UserBuilder, agentCardHandler, jsonRpcHandler } from '@a2a-js/sdk/server/express'
import { AuthError, type Verifier } from './auth.js'
import { FxRateExecutor } from './executor.js'

const CARD_PATHS = ['/.well-known/agent-card.json', '/.well-known/agent.json']

export function agentCard(publicUrl: string): AgentCard {
  return {
    name: '汇率查询 Agent',
    description: '查询币种之间的参考汇率并换算金额（演示数据，非实时报价）',
    supportedInterfaces: [
      { url: `${publicUrl}/`, protocolBinding: 'JSONRPC', tenant: '', protocolVersion: '1.0' },
    ],
    provider: { organization: 'ERP Agent Builder 样例', url: publicUrl },
    version: '1.0.0',
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
        id: 'fx-rate',
        name: '汇率查询',
        description: '按话术里的币种和金额给出参考汇率与换算结果',
        tags: ['fx', 'finance'],
        examples: ['美元兑人民币的汇率是多少', '1000 美元折合多少人民币'],
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
  const handler = new DefaultRequestHandler(card, new InMemoryTaskStore(), new FxRateExecutor())
  const app = express()
  for (const path of CARD_PATHS) {
    app.use(path, agentCardHandler({ agentCardProvider: handler }))
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
  app.post('/', jsonRpcHandler({ requestHandler: handler, userBuilder: UserBuilder.noAuthentication }))
  return app
}
