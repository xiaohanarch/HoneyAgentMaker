#!/usr/bin/env node
// P9 验收：对正在运行的演示环境（pnpm demo）走一遍故事 E 的扩展需求闭环，只用 HTTP 接口。
//   node scripts/acceptance-p9.mjs
// 张敏问标品采购订单处理 Agent 办不到的事，Agent 如实说明缺什么，统一入口补“提交扩展需求”卡片；
// 补一句大额审批要求后提交，平台按需求文本检索语义对象与规则附带给开发者；陈工在 Studio 首页
// 看到需求，生成六步方案卡（脚本化模型按剧本输出），改一处边界后保存，采纳成工作区草稿（幂等），
// 标记交付后张敏在通知区收到消息；第二个需求走拒绝路径。
// 发布扩展与重新提问的完整链路由 StoryEEndToEndTest 覆盖，这里不发布扩展，保证缺口故事可以反复演示；
// 需求与工作区草稿会累积在演示数据里（重复运行幂等），不影响其他故事。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const QUESTION = '百润精密这批货到不了，帮我从备选供应商里找有现货的下单'
const SUPPLEMENT = '金额超过 10 万的必须人工审批'

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

const pathOf = (ref) =>
  `/studio/requests/${ref.slice(0, ref.indexOf('/'))}/${ref.slice(ref.indexOf('/') + 1)}`

// ── 缺口：标品 Agent 如实说明，统一入口补卡片 ──

const asked = await call('POST', '/assist', 'zhangmin', { message: QUESTION })
const events = asked.body?.data?.events ?? []
const reply = events.find((e) => e.type === 'final')?.data?.text ?? ''
const gap = events.find((e) => e.type === 'gap')?.data
check(
  '标品 Agent 如实说明缺少的能力，没有拼凑替代方案',
  reply.includes('没有查询物料认证备选供应商的能力'),
  reply.slice(0, 60),
)
check(
  '统一入口补“提交扩展需求”卡片，写明缺失的能力',
  (gap?.message ?? '').includes('从备选供应商查询现货并创建请购单草稿'),
  gap?.message ?? '',
)

const submitted = await call('POST', '/dev-requests', 'zhangmin', {
  text: gap?.originalText ?? QUESTION,
  assistant: gap?.assistant ?? '',
  supplement: SUPPLEMENT,
})
const ref = submitted.body?.data?.ref ?? ''
check(
  '补上大额审批要求后提交，落到租户需求命名空间',
  ref.startsWith('tenant.t001.requests/req-'),
  ref,
)
check(
  '按需求文本检索到备选供应商语义对象',
  JSON.stringify(submitted.body?.data?.semanticObjects ?? []).includes('SupplierAlternative'),
)
check(
  '检索到大额审批规则 PUR-R-008',
  JSON.stringify(submitted.body?.data?.rules ?? []).includes('PUR-R-008'),
)

const home = (await call('GET', '/studio/home', 'chengong')).body?.data
const mine = (home?.devRequests ?? []).find((r) => r.ref === ref)
check(
  '陈工在 Studio 首页看到待处理需求',
  mine?.status === 'submitted' && (mine?.displayName ?? '').includes('百润精密'),
  mine?.displayName ?? '',
)
const detail = (await call('GET', pathOf(ref), 'chengong')).body?.data
check(
  '详情里有原话、补充说明与附带的口径',
  detail?.originalText === QUESTION &&
    JSON.stringify(detail?.supplements ?? []).includes(SUPPLEMENT) &&
    JSON.stringify(detail?.context ?? {}).includes('PUR-R-008'),
)
check('张敏不能进 Studio 看需求', (await call('GET', pathOf(ref), 'zhangmin')).status === 403)

// ── 意图驱动生成：六步方案卡 ──

const plan = (await call('POST', `${pathOf(ref)}/plan`, 'chengong')).body?.data?.plan
check(
  'fit-gap 给出三档结论里的“扩展标品即可”，匹配度超过八成',
  plan?.fitGap?.verdict === 'extend' && plan?.fitGap?.score > 0.8,
  `${plan?.fitGap?.verdict ?? ''} ${plan?.fitGap?.score ?? ''}`,
)
check('需求摘要带上大额审批边界', (plan?.summary?.boundaries ?? '').includes('10 万元'))
const drafts = JSON.stringify(plan?.drafts ?? [])
check(
  '资产草稿是备选供应商技能与标品扩展',
  drafts.includes('alternative-supplier-lookup') && drafts.includes('purchase-order-clerk-ext'),
)
check('评测集草稿随方案生成', plan?.evalSuite?.name === 'purchase-clerk-ext-smoke')

const edited = JSON.parse(JSON.stringify(plan))
edited.summary.boundaries = `${edited.summary.boundaries}；验收补充：补货数量由技能按订单缺口计算`
const saved = await call('PUT', `${pathOf(ref)}/plan`, 'chengong', { plan: edited })
check(
  '修改方案后保存，读回改过的边界',
  (saved.body?.data?.plan?.summary?.boundaries ?? '').includes(
    '验收补充：补货数量由技能按订单缺口计算',
  ),
)
const invalid = await call('PUT', `${pathOf(ref)}/plan`, 'chengong', { plan: { fitGap: 'broken' } })
check('不合格的方案被拒绝', invalid.body?.error?.code === 'PLAN_INVALID')

const adopted = await call('POST', `${pathOf(ref)}/adopt`, 'chengong')
const adoptedRefs = (adopted.body?.data?.drafts ?? []).map((d) => d.ref)
check(
  '采纳方案：技能与扩展落成工作区草稿',
  adoptedRefs.includes('tenant.t001.skills/alternative-supplier-lookup') &&
    adoptedRefs.includes('tenant.t001.pur/purchase-order-clerk-ext'),
  adoptedRefs.join('、'),
)
const again = await call('POST', `${pathOf(ref)}/adopt`, 'chengong')
const idsOf = (r) =>
  (r.body?.data?.drafts ?? [])
    .map((d) => d.id)
    .sort()
    .join(',')
check('重复采纳幂等，返回同一批草稿', idsOf(again) !== '' && idsOf(again) === idsOf(adopted))

// ── 交付与通知 ──

const marker = Date.now().toString(36)
const summary = `备选供应商补货与大额请购确认已上线（验收 ${marker}）`
const delivered = await call('POST', `${pathOf(ref)}/deliver`, 'chengong', { summary })
check('标记已交付，需求转为只读', delivered.body?.data?.status === 'delivered')
check(
  '已交付的需求不能再处理',
  (await call('POST', `${pathOf(ref)}/deliver`, 'chengong', {})).body?.error?.code ===
    'REQUEST_CLOSED',
)
const notifications = (await call('GET', '/notifications', 'zhangmin')).body?.data ?? []
check(
  '张敏收到交付通知，提示回到统一入口重新提问',
  notifications.some(
    (n) =>
      (n.title ?? '').includes('已交付') &&
      (n.body ?? '').includes(marker) &&
      (n.body ?? '').includes('重新提问'),
  ),
)

const second = await call('POST', '/dev-requests', 'zhangmin', {
  text: '帮我自动给所有供应商群发降价通知',
})
const rejected = await call('POST', `${pathOf(second.body?.data?.ref ?? '')}/reject`, 'chengong', {
  reason: '群发通知不适合用 Agent 做',
})
check('第二个需求走拒绝', rejected.body?.data?.status === 'rejected')
const after = (await call('GET', '/notifications', 'zhangmin')).body?.data ?? []
check(
  '张敏收到没有通过评估的通知',
  after.some(
    (n) => (n.title ?? '').includes('没有通过评估') && (n.body ?? '').includes('群发通知不适合'),
  ),
)

console.info(failures === 0 ? '\nP9 验收通过' : `\nP9 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
