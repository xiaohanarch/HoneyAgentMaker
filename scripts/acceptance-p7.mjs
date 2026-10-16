#!/usr/bin/env node
// P7 验收：对正在运行的演示环境（pnpm demo，含外部 Agent）走一遍故事 C 和 P7 的验收项，只用 HTTP 接口。
//   node scripts/acceptance-p7.mjs
// 默认模型提供方是脚本化模型，断网、没有 API Key 也能通过。供应商风险舆情 Agent 要在 4200 上运行（pnpm demo 自动启动）。
// 会发布一个 RemoteAgent 新版本（内容相同时复用），之后可以运行 pnpm seed:reset 清理审计与运行记录。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const RISK_CARD = process.env.EAB_SUPPLIER_RISK_CARD ?? 'http://127.0.0.1:4200/.well-known/agent-card.json'

let failures = 0
function check(label, ok, detail = '') {
  console.info(`${ok ? '✓' : '✗'} ${label}${detail === '' ? '' : `  ${detail}`}`)
  if (!ok) failures += 1
}

async function json(url, init = {}) {
  const response = await fetch(url, init)
  const text = await response.text()
  let body = null
  try {
    body = text === '' ? null : JSON.parse(text)
  } catch {
    body = text
  }
  return { status: response.status, body }
}

const headers = (token, extra = {}) => ({
  'content-type': 'application/json',
  accept: 'application/json',
  ...(token === undefined ? {} : { authorization: `Bearer ${token}` }),
  ...extra,
})
const post = (path, body, token, extra) =>
  json(`${PLATFORM}${path}`, { method: 'POST', headers: headers(token, extra), body: JSON.stringify(body) })
const get = (path, token) => json(`${PLATFORM}${path}`, { headers: headers(token) })

async function login(userId) {
  const result = await post('/identity/v1/login', { userId })
  return result.body.data.token
}

const finalText = (data) => data.events?.find((e) => e.type === 'final')?.data.text ?? ''
const routing = (data) => data.events?.find((e) => e.type === 'routing')?.data ?? {}

const chengong = await login('chengong')
const zhangmin = await login('zhangmin')

// 1. 陈工在 Studio 输入 Agent Card 地址：展示技能、交互模式和认证方式，连通性测试有请求与响应原文，发布
const inspected = await post('/studio/external/inspect', { cardUrl: RISK_CARD }, chengong)
const card = inspected.body?.data
check('Studio 解读外部 Agent 的 Agent Card', card?.card?.name === '供应商风险舆情 Agent', card?.card?.name ?? JSON.stringify(inspected.body))
check('展示技能、流式与推送能力、认证方式', card?.skills?.length > 0 && card.card.streaming && card.card.auth.length > 0)
const tested = await post('/studio/external/test', { cardUrl: RISK_CARD, message: '百润精密最近有没有什么风险' }, chengong)
check(
  '连通性测试完成并返回请求与响应原文（令牌脱敏）',
  tested.body?.data?.state === 'completed' && JSON.stringify(tested.body.data.exchanges).includes('Bearer ***'),
  tested.body?.data?.state,
)
const published = await post(
  '/studio/external/publish',
  {
    cardUrl: RISK_CARD,
    name: 'supplier-risk',
    displayName: '供应商风险舆情 Agent',
    connection: card?.suggestion?.connection ?? 'SUPPLIER_RISK_AGENT',
    routing: card?.suggestion?.routing ?? { description: '供应商风险舆情', examples: [], direct: false },
    assistant: 'vendor.pur/procurement-assistant',
    allowedCallers: [],
  },
  chengong,
)
check('发布为 RemoteAgent 资产', String(published.body?.data?.ref ?? '').startsWith('tenant.t001.remote/supplier-risk@'), published.body?.data?.ref)

// 2. 张敏在统一入口问风险：路由选中外部 Agent（agent-request），链路里有 A2A 请求
const asked = await post('/assist', { message: '百润精密最近有没有什么风险' }, zhangmin)
const route = routing(asked.body?.data ?? {})
check('路由选中外部 Agent', route.target === 'tenant.t001.remote/supplier-risk' && route.action === 'agent-request', route.target)
check('回复来自外部 Agent', finalText(asked.body.data).includes('风险等级'), finalText(asked.body.data).slice(0, 40))
const trace = await get(`/traces/${asked.body.data.traceId}`, zhangmin)
check('本次链路里有 A2A span 与请求原文', JSON.stringify(trace.body).includes('A2A · ') && JSON.stringify(trace.body).includes('eab.a2a.exchanges'))

// 3. 多轮：没说供应商时外部 Agent 追问，同一会话回答后续接
const first = await post('/assist', { message: '最近有没有什么风险舆情需要注意' }, zhangmin)
check('外部 Agent 追问（input-required）展示给用户', finalText(first.body.data).includes('需要补充信息'))
const second = await post('/assist', { message: '华远电子', sessionId: first.body.data.sessionId }, zhangmin)
check('同一会话续接 contextId、taskId 后完成', finalText(second.body.data).includes('华远电子'), routing(second.body.data).decidedBy)

// 4. 入站：采购助手的 Agent Card 两个路径，缺少身份拒绝，带身份成功并写审计
const cardPath = '/a2a/agents/vendor.pur/procurement-assistant/.well-known'
const inboundCard = await json(`${PLATFORM}${cardPath}/agent-card.json`)
const legacyCard = await json(`${PLATFORM}${cardPath}/agent.json`)
check('入站 Agent Card 在标准路径和旧路径都可取', inboundCard.body?.name === '采购助手' && legacyCard.body?.protocolVersion === '0.3.0')
const rpc = (text) => ({
  jsonrpc: '2.0',
  id: '1',
  method: 'SendMessage',
  params: { message: { messageId: `m-${Date.now()}`, role: 'ROLE_USER', parts: [{ text }] } },
})
const caller = { 'X-A2A-Caller': 'acceptance-p7', 'A2A-Version': '1.0' }
const anonymous = await post('/a2a/agents/vendor.pur/procurement-assistant', rpc('华东区本季度逾期交付率是多少'), undefined, caller)
check('入站调用缺少身份时被拒绝', anonymous.status === 401, String(anonymous.status))
const inbound = await post('/a2a/agents/vendor.pur/procurement-assistant', rpc('华东区本季度逾期交付率是多少'), zhangmin, caller)
check('带用户令牌的入站调用经采购助手完成', inbound.body?.result?.task?.status?.state === 'TASK_STATE_COMPLETED', inbound.body?.result?.task?.status?.state)
const audit = await get('/audit/events?action=a2a.inbound', await login('zhaoadmin'))
const auditText = JSON.stringify(audit.body)
check('审计里有入站调用和调用方', auditText.includes('acceptance-p7'), String(audit.status))

if (failures > 0) {
  console.error(`\nP7 验收未通过：${failures} 项失败`)
  process.exit(1)
}
console.info('\nP7 验收全部通过')
