#!/usr/bin/env node
// P10 验收：对正在运行的演示环境（pnpm demo）验证演示控制台、故事 F 起点、升级回归、
// 一键完成态与架构视图的回放数据，只用 HTTP 接口。
//   node scripts/acceptance-p10.mjs
// 演示状态操作只对租户管理员开放（业务用户与开发者 403）；控制台重置把平台与 DemoERP
// 都回到首次启动状态并重建演示委托；张敏在统一入口问一个不需要确认的问题，赵管理员在
// 运行列表里看到它、追踪里有路由与工具 span，这是架构视图回放的数据源；故事 F 起点发布
// 完成态快照（扩展、技能、评测集），模拟发布标品 1.3.0 后升级回归暴露扩展的钩子用例失败，
// 重复升级被拒绝；一键完成态生成 30 天模拟历史，治理中心有趋势数据。
// 脚本以纯重置收尾，回到首次启动状态，可重复运行。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const QUESTION = '华东区本季度逾期交付率是多少'
const ANALYST = 'vendor.pur/supplier-performance-analyst'

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

// ── 门禁：演示状态操作只对租户管理员开放 ──

const byUser = await call('POST', '/demo/v1/reset', 'zhangmin', {})
check(
  '业务用户不能重置演示状态（403）',
  byUser.status === 403 && byUser.body?.error?.code === 'FORBIDDEN',
  byUser.body?.error?.code ?? '',
)
const byDev = await call('POST', '/demo/v1/story-start', 'chengong', { story: 'F' })
check(
  '租户开发者不能跳故事起点（403）',
  byDev.status === 403 && byDev.body?.error?.code === 'FORBIDDEN',
  byDev.body?.error?.code ?? '',
)

// ── 重置：平台与 DemoERP 回到首次启动状态 ──

const reset = await call('POST', '/demo/v1/reset', 'zhaoadmin', {})
check(
  '控制台重置成功，DemoERP 重新生成、演示委托重建',
  reset.status === 200 &&
    reset.body?.data?.erpReset === true &&
    reset.body?.data?.delegation === 'DLG-0001',
  JSON.stringify(reset.body?.data ?? {}),
)
check(
  '非法故事编号被拒绝',
  (await call('POST', '/demo/v1/story-start', 'zhaoadmin', { story: 'G' })).body?.error?.code ===
    'BAD_REQUEST',
)

// ── 统一入口的一次运行：架构视图的回放对象 ──

const asked = await call('POST', '/assist', 'zhangmin', { message: QUESTION })
const events = asked.body?.data?.events ?? []
const reply = events.find((e) => e.type === 'final')?.data?.text ?? ''
check('张敏在统一入口问到逾期交付率', reply.includes('逾期交付率'), reply.slice(0, 60))

const runs = (await call('GET', '/runs?limit=10', 'zhaoadmin')).body?.data ?? []
const run = runs.find((r) => r.utterance === QUESTION)
check(
  '赵管理员在运行列表看到这次运行',
  Boolean(run) && run.userId === 'zhangmin' && run.agent === ANALYST && run.status === 'completed',
  run ? `${run.agent} · ${run.status}` : 'not found',
)
const trace = (await call('GET', `/traces/${run?.traceId ?? ''}`, 'zhaoadmin')).body?.data
const spans = trace?.spans ?? []
check(
  '追踪里有路由与工具 span，架构视图可以回放点亮组件',
  spans.length > 0 &&
    JSON.stringify(spans).includes('路由 ·') &&
    JSON.stringify(spans).includes('工具 ·'),
  `${spans.length} spans`,
)

// ── 故事 F 起点：发布完成态快照，升级回归暴露钩子用例失败 ──

const start = await call('POST', '/demo/v1/story-start', 'zhaoadmin', { story: 'F' })
const published = JSON.stringify(start.body?.data?.published ?? [])
check(
  '故事 F 起点发布完成态快照（扩展、技能、评测集）',
  start.status === 200 &&
    published.includes('tenant.t001.finance/anomaly-analyst-ext') &&
    published.includes('tenant.t001.pur/purchase-order-clerk-ext') &&
    published.includes('tenant.t001.evals/anomaly-ext-smoke'),
  published,
)

const upgrades = (await call('POST', '/hub/demo/upgrade', 'zhaoadmin', {})).body?.data ?? []
const anomaly = Array.isArray(upgrades)
  ? upgrades.find((r) => (r.extension ?? '').startsWith('tenant.t001.finance/anomaly-analyst-ext'))
  : undefined
check(
  '升级回归暴露扩展的钩子用例失败',
  anomaly?.status === 'failed' &&
    JSON.stringify(anomaly?.regressions ?? []).includes('followup-hook-fires') &&
    JSON.stringify(anomaly?.issues ?? []).includes('HOOK_CONDITION_INVALID'),
  `${anomaly?.extension ?? ''} · ${anomaly?.status ?? ''}`,
)
check(
  '重复升级同一版本被拒绝',
  (await call('POST', '/hub/demo/upgrade', 'zhaoadmin', {})).body?.error?.code === 'UPGRADE_FAILED',
)
const notifications = (await call('GET', '/notifications', 'zhaoadmin')).body?.data ?? []
check(
  '管理员收到“标品升级回归未通过”的通知',
  notifications.some((n) => (n.title ?? '').includes('标品升级回归未通过')),
)

// ── 一键完成态：快照加 30 天模拟历史 ──

const complete = await call('POST', '/demo/v1/complete', 'zhaoadmin', {})
const history = complete.body?.data?.history ?? {}
check(
  '一键完成态生成模拟历史',
  complete.status === 200 && (history.runs ?? 0) > 0 && (history.metricPoints ?? 0) > 0,
  JSON.stringify(history),
)
const metrics = (await call('GET', '/hub/metrics?days=30', 'zhaoadmin')).body?.data
check(
  '治理中心 30 天指标有趋势数据',
  (metrics?.daily ?? []).length >= 29 && (metrics?.agents ?? []).length > 3,
  `daily ${metrics?.daily?.length ?? 0} · agents ${metrics?.agents?.length ?? 0}`,
)

// ── 收尾重置：回到首次启动状态，可重复运行 ──

const trailing = await call('POST', '/demo/v1/reset', 'zhaoadmin', {})
check(
  '收尾重置成功',
  trailing.status === 200 && trailing.body?.data?.erpReset === true,
)
const after = (await call('GET', '/runs?limit=5', 'zhaoadmin')).body?.data ?? []
check('重置后运行列表为空', after.length === 0, `${after.length} runs`)

console.info(failures === 0 ? '\nP10 验收通过' : `\nP10 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
