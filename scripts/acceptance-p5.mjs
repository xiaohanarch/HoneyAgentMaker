#!/usr/bin/env node
// P5 验收：对正在运行的演示环境（pnpm demo）走一遍故事 A，只用 HTTP 接口。
//   node scripts/acceptance-p5.mjs
// 默认模型提供方是脚本化模型，断网、没有 API Key 也能通过。会新建草稿、发布价格谈判助手并晋级到 prod，
// 之后可以运行 pnpm seed:reset 恢复（草稿、部署、评测记录与已发布的租户 Agent 一并清空，重新走一遍即可重演）。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const INTENT = '帮采购经理制定和供应商的降价谈判策略'
const AGENT = 'tenant.t001.agents/price-negotiation-assistant'
const PROMPT = '查一下百润精密最近一次询价，帮我出一份谈判策略'
const STEPS = [
  'purpose',
  'naming',
  'process',
  'collaborators',
  'knowledge',
  'tools',
  'triggers',
  'channels',
  'instructions',
  'model',
]

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

const tokens = new Map()
async function login(userId) {
  if (!tokens.has(userId)) {
    const result = await json(`${PLATFORM}/identity/v1/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userId }),
    })
    tokens.set(userId, result.body.data.token)
  }
  return tokens.get(userId)
}

async function call(method, path, user, body) {
  return json(`${PLATFORM}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      accept: 'application/json',
      authorization: `Bearer ${await login(user)}`,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// 1. 向导：每一步的 AI 推荐经模型网关，一键采纳
const created = await call('POST', '/studio/drafts', 'chengong', { intent: INTENT })
const draftId = created.body?.data?.draft?.id
check('从一句话意图新建草稿', created.status === 200 && draftId !== undefined, draftId ?? '')
const decided = []
for (const step of STEPS) {
  const rec = await call('POST', `/studio/drafts/${draftId}/recommend`, 'chengong', { step })
  const value = rec.body?.data?.value ?? {}
  decided.push(`${step}:${rec.body?.data?.decidedBy}`)
  await call('POST', `/studio/drafts/${draftId}/apply`, 'chengong', { step, value })
}
check(
  '向导 10 步的推荐都来自模型网关（脚本化模型按剧本）',
  decided.every((d) => d.endsWith(':model')),
  decided.filter((d) => !d.endsWith(':model')).join(' '),
)
const draft = (await call('GET', `/studio/drafts/${draftId}`, 'chengong')).body?.data
check(
  '草稿包含名称、S2P 流程、协作 Agent、采购语义包、询价技能、PUR-R-007、reasoning 档',
  [
    'price-negotiation-assistant',
    'source-to-pay',
    'vendor.pur/supplier-performance-analyst',
    'vendor.pur/procurement',
    'rfq-lookup',
    'PUR-R-007',
    'profile: reasoning',
  ].every((t) => draft?.yaml?.includes(t)) && draft?.issues?.length === 0,
)

// 2. 私有环境测试：以张敏的身份运行草稿
const test = await call('POST', `/studio/drafts/${draftId}/test`, 'chengong', {
  message: PROMPT,
  asUser: 'zhangmin',
})
const testData = test.body?.data ?? {}
const testTools = (testData.events ?? [])
  .filter((e) => e.type === 'tool_call')
  .map((e) => e.data.tool)
check(
  '私有环境测试调用了询价技能、采购订单查询和供应商绩效分析 Agent',
  ['rfq-lookup', 'purchase_order_list', 'supplier-performance-analyst'].every((t) =>
    testTools.includes(t),
  ),
)
check(
  '约束 PUR-R-007 触发，回复先提示交付问题',
  (testData.events ?? []).some((e) => e.type === 'constraint' && e.data.rule === 'PUR-R-007') &&
    (testData.text ?? '').startsWith('先说结论：按规则 PUR-R-007'),
  (testData.text ?? '').split('\n')[0].slice(0, 50),
)
const testTrace = await call('GET', `/traces/${testData.traceId}`, 'chengong')
check(
  '开发者能查看测试运行的追踪，里面有约束 span',
  JSON.stringify(testTrace.body?.data?.spans ?? []).includes('约束 · PUR-R-007 触发'),
)

// 3. 评测、静态检查
await call('POST', `/studio/drafts/${draftId}/evals/generate`, 'chengong', { testUser: 'zhangmin' })
const evals = await call('POST', `/studio/drafts/${draftId}/evals/run`, 'chengong')
check(
  '评测集从已验证任务生成，运行通过',
  evals.body?.data?.status === 'passed',
  `${evals.body?.data?.passed}/${evals.body?.data?.total}`,
)
const lint = await call('POST', `/studio/drafts/${draftId}/lint`, 'chengong')
check('静态检查没有错误', lint.body?.data?.ok === true)

// 4. 发布与晋级
const published = await call('POST', `/studio/drafts/${draftId}/publish`, 'chengong', {})
const version = published.body?.data?.version
check('发布并部署到 dev', published.status === 200 && version !== undefined, version ?? '')
for (const environment of ['test', 'prod']) {
  const promoted = await call('POST', '/studio/deployments/promote', 'chengong', {
    ref: AGENT,
    version,
    environment,
  })
  check(
    `晋级到 ${environment}`,
    promoted.status === 200,
    (promoted.body?.data?.gates ?? promoted.body?.error?.details ?? [])
      .map((g) => `${g.name}${g.passed ? '✓' : '✗'}`)
      .join(' '),
  )
}

// 5. 张敏在统一入口直接使用
const assist = await call('POST', '/assist', 'zhangmin', { message: PROMPT })
const routing = (assist.body?.data?.events ?? []).find((e) => e.type === 'routing')?.data
check('统一入口路由到价格谈判助手', routing?.target === AGENT, routing?.targetName ?? '')
const trace = await call('GET', `/traces/${assist.body?.data?.traceId}`, 'zhangmin')
check(
  '张敏的运行追踪里 PUR-R-007 可见地触发',
  JSON.stringify(trace.body?.data?.spans ?? []).includes('约束 · PUR-R-007 触发'),
)

// 6. 连接中心
const connections = await call('GET', '/studio/connections', 'chengong')
check(
  '连接中心列出 DEMO_ERP、SUPPLIER_RISK_AGENT 等别名',
  ['DEMO_ERP', 'SUPPLIER_RISK_AGENT'].every((a) =>
    (connections.body?.data ?? []).some((c) => c.alias === a),
  ),
)

console.info(failures === 0 ? '\nP5 验收全部通过' : `\nP5 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
