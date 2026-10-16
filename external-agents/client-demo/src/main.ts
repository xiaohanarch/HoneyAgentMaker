import { CALLER, TARGET, callAssistant, stateOf, textOf } from './demo.js'

const platformUrl = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const user = process.env.DEMO_USER ?? 'zhangmin'
const question = process.argv.slice(2).join(' ') || '华东区本季度逾期交付率是多少'

console.info(`外部平台 ${CALLER} 经 A2A 调用 ${platformUrl} 上的 ${TARGET}`)

try {
  await callAssistant({ platformUrl, user, question, anonymous: true })
  console.error('意外：没有身份的调用被接受了')
  process.exitCode = 1
} catch (error) {
  console.info(`1. 不带用户令牌：被拒绝（${(error as Error).message}）`)
}

const result = await callAssistant({ platformUrl, user, question })
console.info(`2. 以 ${user} 的身份提问“${question}”：任务状态 ${stateOf(result)}`)
console.info(textOf(result))
console.info('治理中心的审计里可以看到这次入站调用（动作 a2a.inbound，调用方与用户）。')
