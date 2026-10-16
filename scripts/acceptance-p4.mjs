#!/usr/bin/env node
// P4 验收：对正在运行的演示环境（pnpm demo）走一遍主提示词 P4 的验收项，只用 HTTP 接口。
//   node scripts/acceptance-p4.mjs
// 默认模型提供方是脚本化模型，断网、没有 API Key 也能通过。会提交交期变更单、扩展需求和一条委托，
// 之后可以运行 pnpm seed:reset 恢复。浏览器里的对话界面由 Playwright（e2e/assistant.spec.ts）覆盖。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const NOTIFICATION_WAIT_MS = 45_000

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

async function ask(user, message, target) {
  const token = await login(user)
  const result = await post('/assist', { message, target }, token)
  const data = result.body?.data ?? {}
  const routing = (data.events ?? []).find((e) => e.type === 'routing')?.data
  const final = (data.events ?? []).find((e) => e.type === 'final')?.data
  return { token, data, routing, text: final?.text ?? '', error: result.body?.error }
}

// 1. 分析型：张敏问逾期交付率，经采购助手路由到供应商绩效分析，路由说明里有候选、分数和理由
const zhang = await ask('zhangmin', '华东区本季度逾期交付率是多少')
check(
  '分析型：路由到 采购助手 → 供应商绩效分析',
  zhang.routing?.assistant?.ref === 'vendor.pur/procurement-assistant' &&
    zhang.routing?.target === 'vendor.pur/supplier-performance-analyst',
  `${zhang.routing?.assistant?.name ?? '?'} → ${zhang.routing?.targetName ?? '?'}`,
)
check(
  '路由说明有两级候选、分数与理由',
  zhang.routing?.levels?.length === 2 &&
    zhang.routing.levels.every(
      (l) => l.candidates.length > 0 && typeof l.decision?.reason === 'string',
    ),
  zhang.routing?.reason ?? '',
)
check(
  '分析型回答带口径与数据范围',
  /口径说明/.test(zhang.text) && zhang.text.includes('采购组织：华东'),
)
const trace = await get(`/traces/${zhang.data.traceId}`, zhang.token)
const kinds = new Set((trace.body?.data?.spans ?? []).map((s) => s.kind))
check(
  '本次链路有 routing、model、tool、policy span',
  ['routing', 'model', 'tool', 'policy'].every((k) => kinds.has(k)),
)

// 1b. 张敏和库存助手对话；财务助手对她不开放，在路由说明里写明被过滤的原因
const stock = await ask('zhangmin', '哪些物料低于安全库存')
check(
  '张敏经库存助手路由到库存经营分析',
  stock.routing?.assistant?.ref === 'vendor.scm/inventory-assistant' &&
    stock.text.includes('M-0007'),
  `${stock.routing?.assistant?.name ?? '?'} → ${stock.routing?.targetName ?? '?'}`,
)

// 2. 张敏和李强同问，结果按各自的采购组织过滤
const li = await ask('liqiang', '华东区本季度逾期交付率是多少')
check(
  '张敏与李强同问结果不同',
  li.text.includes('采购组织：华南') && li.text !== zhang.text,
  li.text.split('\n')[0].slice(0, 50),
)

// 3. 事务型：L2 写操作先挂起等待确认，确认后提交交期变更单
const tx = await ask('zhangmin', '把 PO-2026-0117 的交期改到下月 15 号')
const pending = (tx.data.events ?? []).find((e) => e.type === 'confirmation_required')
check(
  '事务型：交期变更需要确认（L2）',
  pending?.data?.preview?.writeLevel === 'L2',
  pending?.data?.title ?? '',
)
const confirmed = await post(`/runs/${tx.data.runId}/confirmation`, { approved: true }, tx.token)
const confirmedText = confirmed.body?.data?.text ?? ''
check(
  '确认后提交交期变更单（或因已有待审批变更单被 BO API 拒绝）',
  /已提交交期变更单|已有待审批的交期变更单/.test(confirmedText),
  confirmedText.slice(0, 60),
)

// 4. 导航型：返回 DemoERP 深链接卡片
const nav = await ask('zhangmin', '打开百润精密的供应商详情')
const card = nav.data.payload?.card
check(
  '导航型：返回百润精密的深链接卡片',
  nav.data.kind === 'navigation' && /^\/erp\/pages\/suppliers\/S-0001\?t=/.test(card?.url ?? ''),
  card?.title ?? JSON.stringify(nav.error),
)

// 5. 信息型：采购制度问答引用知识库
const info = await ask('zhangmin', '我们的降价谈判制度是怎么规定的')
check(
  '信息型：采购制度问答回答降价谈判制度',
  info.routing?.target === 'vendor.pur/procurement-policy-advisor' && info.text.includes('降价'),
  info.text.split('\n')[0].slice(0, 50),
)

// 6. 权限过滤与能力缺口
const filtered = await ask('wangfang', '把 PO-2026-0117 的交期改到下月 15 号')
check(
  '财务经理问采购问题：候选被权限过滤，给出能力缺口',
  filtered.data.kind === 'gap' && JSON.stringify(filtered.routing).includes('filtered'),
)
const gap = await ask('zhangmin', '帮我预订下周去深圳的机票')
const request = await post('/dev-requests', { text: '帮我预订下周去深圳的机票' }, gap.token)
check(
  '能力缺口可以提交扩展需求',
  gap.data.kind === 'gap' &&
    (request.body?.data?.ref ?? '').startsWith('tenant.t001.requests/req-'),
  request.body?.data?.ref ?? JSON.stringify(request.body?.error),
)

// 7. 业务事件：交付逾期事件触发库存经营分析，以孙库管的委托运行，结果推送到通知区
const admin = await login('zhaoadmin')
const delegation = await post(
  '/identity/v1/delegations',
  {
    delegatorUserId: 'sunkg',
    agent: 'vendor.scm/inventory-risk-analyst',
    scope: [
      'inventory_balance_list',
      'material_list',
      'stock_movement_list',
      'purchase_order_list',
    ],
    validDays: 1,
    note: 'P4 验收：交付逾期事件触发库存分析',
  },
  admin,
)
const sunkg = await login('sunkg')
const before = new Set(((await get('/notifications', sunkg)).body?.data ?? []).map((n) => n.id))
const triggered = await post('/demo/v1/events', { type: 'delivery.overdue' }, sunkg)
let notification
const deadline = Date.now() + NOTIFICATION_WAIT_MS
while (notification === undefined && Date.now() < deadline) {
  await new Promise((resolve) => setTimeout(resolve, 1000))
  const list = (await get('/notifications', sunkg)).body?.data ?? []
  notification = list.find((n) => !before.has(n.id) && n.source === 'event:delivery.overdue')
}
check(
  '交付逾期事件触发受托运行，结果推送到孙库管的通知区',
  delegation.status === 201 &&
    triggered.status === 200 &&
    notification?.body?.includes('M-0007') === true,
  notification?.title ?? '没有收到通知',
)

// 8. IM 渠道消息接口可用
const im = await get('/channels/im/messages', await login('zhangmin'))
check('IM 渠道消息接口可用', im.status === 200 && Array.isArray(im.body?.data))

console.info(failures === 0 ? '\nP4 验收全部通过' : `\nP4 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
