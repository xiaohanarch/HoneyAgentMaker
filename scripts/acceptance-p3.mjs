#!/usr/bin/env node
// P3 验收：对正在运行的演示环境（pnpm demo）走一遍主提示词 P3 的验收项，只用 HTTP 接口。
//   node scripts/acceptance-p3.mjs
// 默认模型提供方是脚本化模型，断网、没有 API Key 也能通过。会创建一条跟进任务，之后可以运行 pnpm seed:reset 恢复。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'

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

const headers = (token) => ({
  'content-type': 'application/json',
  accept: 'application/json',
  authorization: `Bearer ${token}`,
})
const post = (path, body, token) =>
  json(`${PLATFORM}${path}`, {
    method: 'POST',
    headers: headers(token),
    body: JSON.stringify(body),
  })
const get = (path, token) => json(`${PLATFORM}${path}`, { headers: headers(token) })

async function login(userId) {
  const result = await json(`${PLATFORM}/identity/v1/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ userId }),
  })
  return result.body.data.token
}

async function run(user, agent, message) {
  const token = await login(user)
  const result = await post('/runs', { agent, message }, token)
  return { token, data: result.body?.data ?? {}, error: result.body?.error }
}

const kindsOf = (trace) => new Set(trace.spans.map((s) => s.kind))

// 1. 四个标品 Agent 各跑一条话术
const cases = [
  [
    'zhangmin',
    'vendor.pur/supplier-performance-analyst',
    '百润精密最近表现怎么样',
    ['PO-2026-0117', 'PUR-R-007'],
  ],
  [
    'wangfang',
    'vendor.fin/transaction-anomaly-analyst',
    '本月有哪些高风险交易异常',
    ['ANO-2026-0003'],
  ],
  [
    'wangfang',
    'vendor.fin/period-close-checker',
    '本期资产核算的结账检查还有哪些没通过',
    ['结账就绪率 50'],
  ],
  ['sunkg', 'vendor.scm/inventory-risk-analyst', '哪些物料低于安全库存', ['M-0007']],
]
let firstTrace
for (const [user, agent, message, expected] of cases) {
  const { token, data, error } = await run(user, agent, message)
  const text = data.text ?? ''
  check(
    `${agent} 跑通「${message}」`,
    data.status === 'completed' && expected.every((e) => text.includes(e)),
    error === undefined ? `${text.split('\n')[0].slice(0, 60)}…` : JSON.stringify(error),
  )
  if (firstTrace === undefined && data.traceId !== undefined) {
    firstTrace = { token, traceId: data.traceId }
  }
}

// 2. 追踪里能看到组装、模型调用、工具调用、鉴权裁决、守卫结果；租户视角看不到标品指令原文
if (firstTrace !== undefined) {
  const trace = (await get(`/traces/${firstTrace.traceId}`, firstTrace.token)).body.data
  const kinds = kindsOf(trace)
  for (const kind of ['assemble', 'model', 'pipeline', 'tool', 'policy', 'guard']) {
    check(`追踪里有 ${kind} span`, kinds.has(kind))
  }
  const raw = JSON.stringify(trace)
  check(
    '租户视角的追踪里没有标品指令原文',
    !raw.includes('你是供应商绩效分析专家') && raw.includes('标品指令（不可见，运行时注入）'),
  )
  const operator = await login('platformops')
  const platformView = JSON.stringify((await get(`/traces/${firstTrace.traceId}`, operator)).body)
  check('平台运维能看到片段原文', platformView.includes('你是供应商绩效分析专家'))
  const other = await get(`/traces/${firstTrace.traceId}`, await login('zhouting'))
  check('其他租户读不到这条追踪', other.status === 404)
}

// 3. 写操作需要确认：挂起 → 确认 → 写入；另一次拒绝 → 不写入
const pending = await run(
  'sunkg',
  'vendor.scm/inventory-risk-analyst',
  '为 M-0007 创建补货跟进任务',
)
check(
  '创建跟进任务时挂起等待确认',
  pending.data.status === 'suspended',
  pending.data.pending?.title ?? '',
)
const confirmed = await post(
  `/runs/${pending.data.runId}/confirmation`,
  { approved: true },
  pending.token,
)
check(
  '确认后写入 ERP',
  confirmed.body?.data?.status === 'completed' &&
    confirmed.body.data.text.startsWith('已创建跟进任务 TASK-'),
  confirmed.body?.data?.text ?? JSON.stringify(confirmed.body),
)
const second = await run('sunkg', 'vendor.scm/inventory-risk-analyst', '为 M-0006 创建补货跟进任务')
const rejected = await post(
  `/runs/${second.data.runId}/confirmation`,
  { approved: false },
  second.token,
)
check('拒绝后不写入', rejected.body?.data?.text?.startsWith('没有创建跟进任务：用户拒绝') === true)

// 4. 审计里能查到运行时的鉴权裁决与 BO 访问
const admin = await login('zhaoadmin')
const audit = (await get('/audit/events?limit=200', admin)).body.data
check(
  '审计里有鉴权点 3 的裁决',
  audit.some((e) => e.action === 'policy.tool'),
)
check(
  '审计里有经运行时发起的 BO 写操作',
  audit.some((e) => e.tool === 'followup_task_create'),
)

// 5. 平台对外的 MCP 端点
const mcp = await fetch(`${PLATFORM}/mcp`, {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    authorization: `Bearer ${await login('zhangmin')}`,
  },
  body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} }),
})
const mcpText = await mcp.text()
check(
  'MCP 端点列出只读工具',
  mcp.status === 200 &&
    mcpText.includes('purchase_order_list') &&
    !mcpText.includes('followup_task_create'),
)

console.info(failures === 0 ? '\nP3 验收全部通过' : `\nP3 验收有 ${failures} 项没有通过`)
process.exitCode = failures === 0 ? 0 : 1
