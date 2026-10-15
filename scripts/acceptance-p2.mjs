#!/usr/bin/env node
// P2 验收：对正在运行的演示环境（pnpm demo）走一遍主提示词 P2 的验收项，只用 HTTP 接口。
//   node scripts/acceptance-p2.mjs
// 需要平台以演示模式运行（EAB_DEMO_MODE=1，pnpm demo 默认开启），并能读取 data/keys/clients.json。
// 注意：最后一步会撤销预置委托 DLG-0001，之后运行 pnpm seed:reset 恢复。
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const ERP = process.env.EAB_DEMO_ERP_URL ?? 'http://127.0.0.1:4100'
const DATA = process.env.EAB_DATA_DIR ?? join(process.cwd(), 'data')

let failures = 0
function check(label, ok, detail = '') {
  console.info(`${ok ? '✓' : '✗'} ${label}${detail === '' ? '' : `  ${detail}`}`)
  if (!ok) failures += 1
}

async function json(url, init = {}) {
  const response = await fetch(url, init)
  const text = await response.text()
  return { status: response.status, body: text === '' ? null : JSON.parse(text) }
}

const post = (url, body, headers = {}) =>
  json(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
const bearer = (token) => ({ authorization: `Bearer ${token}` })

async function login(userId) {
  return (await post(`${PLATFORM}/identity/v1/login`, { userId })).body.data.token
}

async function agentToken(operator, namespace, name) {
  const result = await post(
    `${PLATFORM}/identity/v1/demo/agent-token`,
    { namespace, name, tenantId: 't001' },
    bearer(operator),
  )
  if (result.status !== 200) throw new Error(`签发 Agent 令牌失败：${JSON.stringify(result.body)}`)
  return result.body.data
}

async function exchange(subjectToken, subjectTokenType, actorToken) {
  return post(`${PLATFORM}/identity/v1/token`, {
    grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
    subject_token: subjectToken,
    subject_token_type: subjectTokenType,
    actor_token: actorToken,
    audience: 'demo-erp-bo',
  })
}

const operator = await login('platformops')
const purchasing = await agentToken(operator, 'vendor.pur', 'supplier-performance-analyst')
console.info(`供应商绩效分析 Agent 声明的 BO 操作：${purchasing.operations.join('、')}\n`)

// 1. 张敏、李强调同一个 BO API，结果不同且带过滤说明
const results = {}
for (const user of ['zhangmin', 'liqiang']) {
  const exchanged = await exchange(
    await login(user),
    'urn:ietf:params:oauth:token-type:jwt',
    purchasing.token,
  )
  const response = await json(`${ERP}/bo/v1/purchase-orders?overdueDaysGt=30`, {
    headers: bearer(exchanged.body.access_token),
  })
  results[user] = response.body
  console.info(
    `  ${user}：${response.body.items?.length ?? 0} 张逾期订单，${response.body.scopeLabel}，filtered=${response.body.filtered}`,
  )
}
check(
  '张敏只看到华东，李强只看到华南',
  results.zhangmin.visibleScope?.join() === '华东' &&
    results.liqiang.visibleScope?.join() === '华南',
)
check(
  '张敏的结果里有百润精密 PO-2026-0117，逾期 120 天',
  results.zhangmin.items.some((p) => p.id === 'PO-2026-0117' && p.overdueDays === 120),
)

// 2. 集成账号：Open API 返回全部，BO API 被拒绝
const secret = JSON.parse(readFileSync(join(DATA, 'keys', 'clients.json'), 'utf8'))[
  't001-integration'
]
const client = await post(`${PLATFORM}/identity/v1/token`, {
  grant_type: 'client_credentials',
  client_id: 't001-integration',
  client_secret: secret,
})
const open = await json(`${ERP}/open/v1/purchase-orders?overdueDaysGt=30`, {
  headers: bearer(client.body.access_token),
})
const orgs = [...new Set(open.body.items.map((p) => p.purchasingOrgName))].sort()
check(
  '集成账号调 Open API 返回两个采购组织的全部数据',
  orgs.join() === '华东,华南' && open.body.filtered === false,
  `${open.body.total} 张`,
)
const boWithClient = await json(`${ERP}/bo/v1/purchase-orders`, {
  headers: bearer(client.body.access_token),
})
check(
  '集成账号调 BO API 被拒绝',
  boWithClient.status === 401 && boWithClient.body.error.code === 'AGENT_IDENTITY_REQUIRED',
)

// 3. SoD：策略引擎（鉴权点 3）与 DemoERP（鉴权点 4）都会拒绝自己审批自己提交的单据，
//    完整链路见 backend/platform 的 EndToEndTest；这里用策略试算演示平台侧的裁决
const zhang = await login('zhangmin')
const sod = await post(
  `${PLATFORM}/policy/v1/evaluate`,
  {
    checkpoint: 'tool',
    agent: 'vendor.fin/transaction-anomaly-analyst',
    tool: 'transaction_anomaly_list',
    document: { id: 'DC-2026-0001', action: 'approve', submittedBy: 'zhangmin' },
  },
  bearer(zhang),
)
check(
  '策略引擎对自己审批自己提交的单据给出拒绝（SoD）',
  sod.body.data.outcome === 'deny' && sod.body.data.ruleId === 'TOOL-005',
  sod.body.data.reason,
)

// 4. 撤销委托后受托调用被拒绝
const inventory = await agentToken(operator, 'vendor.scm', 'inventory-risk-analyst')
const before = await exchange(
  'delegation:DLG-0001',
  'urn:eab:params:oauth:token-type:delegation',
  inventory.token,
)
const balances = await json(`${ERP}/bo/v1/inventory-balances?belowSafetyStock=true`, {
  headers: bearer(before.body.access_token),
})
check(
  '委托有效时受托调用成功',
  balances.status === 200,
  `低于安全库存 ${balances.body.total ?? 0} 条`,
)
const admin = await login('zhaoadmin')
await json(`${PLATFORM}/identity/v1/delegations/DLG-0001`, {
  method: 'DELETE',
  headers: bearer(admin),
})
const after = await exchange(
  'delegation:DLG-0001',
  'urn:eab:params:oauth:token-type:delegation',
  inventory.token,
)
check(
  '赵管理员撤销后令牌交换被拒绝',
  after.status === 400 && after.body.error_code === 'DELEGATION_INACTIVE',
)
await new Promise((resolve) => setTimeout(resolve, 5_500)) // 等 DemoERP 的委托状态缓存过期
const stale = await json(`${ERP}/bo/v1/inventory-balances`, {
  headers: bearer(before.body.access_token),
})
check(
  '撤销前签发的令牌也被 DemoERP 拒绝',
  stale.status === 403 && stale.body.error.code === 'DELEGATION_REVOKED',
)

// 5. 所有裁决可在审计 API 中查到
await new Promise((resolve) => setTimeout(resolve, 500))
const audit = await json(`${PLATFORM}/audit/events?limit=200`, { headers: bearer(admin) })
const actions = new Set(audit.body.data.map((e) => `${e.action}:${e.decision}`))
for (const expected of [
  'bo.access:allow',
  'bo.access:deny',
  'identity.token-exchange:allow',
  'identity.token-exchange:deny',
  'identity.delegation.revoke:allow',
  'policy.trial.tool:deny',
]) {
  check(`审计里有 ${expected}`, actions.has(expected))
}

console.info(
  `\n${failures === 0 ? 'P2 验收全部通过' : `${failures} 项未通过`}；运行 pnpm seed:reset 恢复被撤销的委托`,
)
process.exitCode = failures === 0 ? 0 : 1
