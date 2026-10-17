#!/usr/bin/env node
// P8 验收：对正在运行的演示环境（pnpm demo）走一遍故事 D 和故事 F，只用 HTTP 接口。
//   node scripts/acceptance-p8.mjs
// 故事 F 的升级回归、方案包依赖租户的交易异常扩展（tenant.t001.finance/anomaly-analyst-ext），
// 需要先完成故事 B（node scripts/acceptance-p6.mjs）。脚本会撤销库存 Agent 的委托、发布标品 1.3.0、
// 写入租户语义包，结束后运行 pnpm seed:reset 恢复。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
// 故事 B 发布的扩展名取决于在扩展编辑器里怎么建（例如 tenant.t001.extensions/transaction-anomaly-analyst-ext），
// 也可能是演示完成态快照里的 tenant.t001.finance/anomaly-analyst-ext；下面按血缘图里指向标品的边来找
let EXTENSION = 'tenant.t001.finance/anomaly-analyst-ext'
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

function finalText(data) {
  return (data?.events ?? []).find((e) => e.type === 'final')?.data?.text ?? ''
}

/** 统一入口提问；遇到确认点由本人确认 */
async function ask(user, message, target) {
  const result = await call(
    'POST',
    '/assist',
    user,
    target === undefined ? { message } : { message, target },
  )
  const data = result.body?.data
  let text = finalText(data)
  for (let i = 0; i < 3 && text === '' && data?.runId !== undefined; i++) {
    const confirmed = await call('POST', `/runs/${data.runId}/confirmation`, user, {
      approved: true,
    })
    text = confirmed.body?.data?.text ?? ''
  }
  return { text, data }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ── 故事 D：身份与安全 ──

const question = '列出我们逾期超过 30 天的供应商'
const zhang = await ask('zhangmin', question, ANALYST)
const li = await ask('liqiang', question, ANALYST)
check(
  '张敏的结果注明可见范围是华东',
  zhang.text.includes('可见范围：采购组织：华东'),
  zhang.text.slice(0, 60),
)
check(
  '李强的结果注明可见范围是华南',
  li.text.includes('可见范围：采购组织：华南'),
  li.text.slice(0, 60),
)

const compare = (await call('GET', '/hub/demo/integration-compare?user=zhangmin', 'zhaoadmin')).body
  ?.data
check(
  '集成账号对照：BO API 只返回华东，Open API 返回两个采购组织',
  compare?.bo?.filtered === true &&
    compare?.open?.filtered === false &&
    Object.keys(compare?.open?.byOrg ?? {}).length === 2,
  `BO ${compare?.bo?.total} 条，Open API ${compare?.open?.total} 条`,
)

const changed = await ask('zhangmin', '把 PO-2026-0117 的交期改到下月 15 号')
const changeId = changed.text.match(/DC-\d{4}-\d{4}/)?.[0]
check(
  '改交期经确认后提交了交期变更单',
  changeId !== undefined,
  changeId ?? changed.text.slice(0, 60),
)
const approved = await ask('zhangmin', `把交期变更单 ${changeId} 审批通过`)
check(
  '审批自己提交的变更单被职责分离拒绝',
  approved.text.includes('职责分离'),
  approved.text.slice(0, 80),
)

const delegations = (await call('GET', '/identity/v1/delegations', 'zhaoadmin')).body?.data ?? []
// 接口不输出值为 null 的字段，未撤销的委托没有 revokedAt。
// e2e 和 acceptance-p4 也会给库存 Agent 建委托，要全部撤销，事件触发器才没有可用身份
const active = delegations.filter(
  (d) => d.revokedAt == null && d.agent === 'vendor.scm/inventory-risk-analyst',
)
let revokedAll = active.length > 0
for (const delegation of active) {
  const result = await call('DELETE', `/identity/v1/delegations/${delegation.id}`, 'zhaoadmin')
  revokedAll &&= result.status === 200
}
check(
  '管理员撤销库存 Agent 的委托',
  revokedAll,
  active.map((d) => d.id).join('、') || '没有有效委托',
)
await call('POST', '/demo/v1/events', 'zhaoadmin', { type: 'inventory.below_safety_stock' })
let deniedTrigger
for (let i = 0; i < 30 && deniedTrigger === undefined; i++) {
  await sleep(300)
  const events =
    (await call('GET', '/audit/events?action=trigger.event&decision=deny&limit=20', 'zhaoadmin'))
      .body?.data ?? []
  deniedTrigger = events.find((e) => e.target === 'vendor.scm/inventory-risk-analyst')
}
check(
  '撤销后库存事件触发的运行被拒绝，审计有记录',
  deniedTrigger !== undefined,
  deniedTrigger?.reason ?? '',
)

const findings =
  (await call('GET', '/hub/audit/reverse?object=PO-2026-0117', 'zhaoadmin')).body?.data ?? []
const change = findings.find(
  (f) => f.operation === 'purchase_order_change_delivery_date' && f.decision === 'allow',
)
check(
  '审计反查 PO-2026-0117：张敏通过采购订单处理 Agent 改了交期，能定位到运行',
  change?.userId === 'zhangmin' && change?.runId?.startsWith('run-') === true,
  change === undefined ? '' : `${change.agent} ${change.runId}`,
)

// ── 故事 F：治理与演进 ──

const inventory = (await call('GET', '/hub/inventory', 'zhaoadmin')).body?.data
EXTENSION =
  (inventory?.edges ?? []).find(
    (e) => e.from.startsWith('tenant.t001.') && e.to === 'vendor.fin/transaction-anomaly-analyst',
  )?.from ?? EXTENSION
const hasExtension = (inventory?.items ?? []).some((i) => i.ref === EXTENSION)
check('资产清单里有交易异常扩展（需先完成故事 B）', hasExtension)
check(
  '血缘图里扩展指向标品交易异常解析',
  (inventory?.edges ?? []).some(
    (e) => e.from === EXTENSION && e.to === 'vendor.fin/transaction-anomaly-analyst',
  ),
)

if (hasExtension) {
  const upgrade = await call('POST', '/hub/demo/upgrade', 'zhaoadmin')
  const regression = upgrade.body?.data?.find((r) => r.extension.startsWith(EXTENSION))
  check(
    '发布标品 1.3.0 后自动回归：钩子用例失败，并指出钩子条件失效',
    regression?.status === 'failed' &&
      // 钩子用例：完成态快照里叫 followup-hook-fires，扩展编辑器按扩展内容生成的叫 extension-hooks
      regression.regressions.some((c) => ['followup-hook-fires', 'extension-hooks'].includes(c)) &&
      regression.issues.some((i) => i.includes('HOOK_CONDITION_INVALID')),
    upgrade.status === 200
      ? (regression?.regressions ?? []).join('、')
      : upgrade.body?.error?.message,
  )
  const notes = JSON.stringify((await call('GET', '/notifications', 'zhaoadmin')).body?.data ?? [])
  check('租户管理员收到回归通知', notes.includes('标品升级回归未通过'))
}

const diff = (
  await call(
    'GET',
    '/hub/assets/diff?ref=vendor.fin/transaction-anomaly-analyst&from=1.2.0&to=1.3.0',
    'zhaoadmin',
  )
).body?.data
const instructions = (diff?.changes ?? []).find((c) => c.path === 'spec.instructions')
check(
  '版本差异：租户视角下标品指令只显示“已变更”，字段改名可见',
  instructions?.redacted === true && JSON.stringify(diff).includes('riskLevel'),
)

await call('POST', '/hub/demo/history', 'zhaoadmin')
const metrics = (await call('GET', '/hub/metrics?days=30', 'zhaoadmin')).body?.data
check(
  '三层指标与岗位助手 KPI 有 30 天数据',
  (metrics?.daily ?? []).length >= 29 &&
    (metrics?.process ?? []).length > 0 &&
    (metrics?.business ?? []).length > 0 &&
    (metrics?.assistants ?? []).length > 0,
  `${metrics?.agents?.length ?? 0} 个 Agent`,
)

const anomalyQuestion = '本月有哪些高风险交易异常'
const before = await ask('wangfang', anomalyQuestion)
const submitted = await call('POST', '/feedback', 'wangfang', {
  runId: before.data?.runId,
  reason: '这类跨期冲销不算异常',
})
const feedbackId = submitted.body?.data?.id
check(
  '王芳点“结论有误”，生成候选规则',
  submitted.status === 200 && feedbackId !== undefined,
  submitted.body?.data?.candidate ?? '',
)
const reviewed = await call('POST', `/hub/feedback/${feedbackId}/approve`, 'zhaoadmin', {
  note: '确认',
})
check(
  '管理员审核通过，写入租户语义包',
  reviewed.body?.data?.status === 'approved',
  reviewed.body?.data?.packRef ?? '',
)
const after = await ask('wangfang', anomalyQuestion)
check(
  '重新提问，结论按新规则改变',
  after.text.startsWith('按规则 T001-R-') && after.text !== before.text,
  after.text.slice(0, 60),
)
const trace = (await call('GET', `/traces/${after.data?.traceId}`, 'wangfang')).body?.data
check(
  '追踪里能看到规则来自租户语义包',
  JSON.stringify(trace ?? {}).includes('tenant.t001.semantic/'),
)

if (hasExtension) {
  const pkg = (
    await call('POST', '/hub/solutions/compile', 'zhaoadmin', {
      name: 'finance-extension',
      root: EXTENSION,
    })
  ).body?.data
  check(
    '方案包编译：清单、哈希、签名',
    pkg?.signature !== undefined && (pkg?.manifest?.assets ?? []).length > 0,
    pkg?.id ?? '',
  )
  const skipped = await call('POST', `/hub/solutions/packages/${pkg?.id}/deploy`, 'zhaoadmin', {
    environment: 'prod',
  })
  check(
    '跳过 test 直接部署 prod 被门禁拒绝',
    skipped.body?.error?.code === 'GATE_BLOCKED',
    skipped.body?.error?.code ?? `HTTP ${skipped.status}`,
  )
  let deployed = true
  for (const environment of ['dev', 'test', 'prod']) {
    const r = await call('POST', `/hub/solutions/packages/${pkg?.id}/deploy`, 'zhaoadmin', {
      environment,
    })
    deployed &&= r.status === 200
  }
  check('方案包按 dev → test → prod 部署', deployed)
}

const suites = (await call('GET', '/hub/evals/suites?tier=smoke', 'zhaoadmin')).body?.data ?? []
const pub = suites.find((s) => s.ref === 'vendor.fin.evals/anomaly-public')
const evalRun = await call('POST', '/hub/evals/run', 'zhaoadmin', { suite: pub?.ref })
check(
  '标品公开评测集可以在租户侧运行',
  evalRun.body?.data?.result !== undefined,
  evalRun.body?.data?.result?.status ?? '',
)
const forbidden = await call('GET', '/hub/inventory', 'zhangmin')
check('业务用户不能进入治理中心', forbidden.status === 403)

console.info(failures === 0 ? '\nP8 验收通过' : `\nP8 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
