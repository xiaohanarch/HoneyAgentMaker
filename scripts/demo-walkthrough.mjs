#!/usr/bin/env node
// 对运行中的演示环境走查 demo/DEMO.md 主线的机制：控制台重置、故事 D（数据范围、集成账号对照、
// L2 确认、职责分离、审计反查）、故事 F（起点快照、升级回归、经验回流）与架构视图回放数据。
// 只用 HTTP，脚本化与真实模型都能跑；断言只看路由决策、工具调用与结果、约束触发和审计裁决，
// 不比对模型回复文字，回复片段只作为记录打印。
// D-1 的问话两个能力的路由示例相近：脚本化模型按召回分数决策，置信度不足先请用户澄清，脚本按
// 讲解词点选「供应商绩效分析 Agent」后带 target 重问；真实模型由模型在候选里决策，通常直达。
//   node scripts/demo-walkthrough.mjs          （默认平台地址 http://127.0.0.1:4000）
//   EAB_PLATFORM_URL=http://host:4000 node scripts/demo-walkthrough.mjs
// 起点自带控制台重置，结束后停在故事 F 完成态（扩展与语义包已发布），重新演示时从控制台重置。
// 注意：模拟升级要在发布前后各跑一遍扩展的评测集，真实模型下单次走查约二十分钟，其中升级回归
// 约十分钟；升级请求走的是不带 5 分钟头部超时的 http 客户端（fetch 默认会在 5 分钟断开）。
import http from 'node:http'

const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const ANALYST = 'vendor.pur/supplier-performance-analyst'
const UPGRADE_CAP_MS = 25 * 60 * 1000

let failures = 0
const t0 = Date.now()
function stamp() {
  return `${((Date.now() - t0) / 1000).toFixed(0)}s`
}
function check(label, ok, detail = '') {
  console.info(`${ok ? '✓' : '✗'} ${label}${detail === '' ? '' : `  ${detail}`}`)
  if (!ok) failures += 1
}
function note(label, text = '') {
  console.info(`· ${label}  ${String(text).slice(0, 90).replace(/\s+/g, ' ')}`)
}

async function json(url, init = {}) {
  const response = await fetch(url, init)
  const text = await response.text()
  return { status: response.status, body: text === '' ? null : JSON.parse(text) }
}

/** 长耗时的 POST（升级回归要跑评测集），不受 fetch 默认 5 分钟头部超时限制 */
function postJsonLong(path, token, body, capMs) {
  const { hostname, port } = new URL(PLATFORM)
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body)
    const req = http.request(
      {
        hostname,
        port: Number(port),
        path,
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          accept: 'application/json',
          authorization: `Bearer ${token}`,
          'content-length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        const chunks = []
        res.on('data', (c) => chunks.push(c))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          try {
            resolve({ status: res.statusCode, body: text === '' ? null : JSON.parse(text) })
          } catch (e) {
            reject(e)
          }
        })
      },
    )
    req.setTimeout(capMs, () =>
      req.destroy(new Error(`请求超过 ${Math.round(capMs / 60000)} 分钟`)),
    )
    req.on('error', reject)
    req.end(payload)
  })
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

/** 统一入口提问；遇到确认点由本人确认（最多三轮）。返回最后一次响应与运行状态。 */
async function askOnce(user, message, target) {
  const result = await call(
    'POST',
    '/assist',
    user,
    target === undefined ? { message } : { message, target },
  )
  let data = result.body?.data
  let text = finalText(data)
  for (let i = 0; i < 3 && text === '' && data?.runId !== undefined; i++) {
    const confirmed = await call('POST', `/runs/${data.runId}/confirmation`, user, {
      approved: true,
    })
    if (confirmed.body?.data) data = confirmed.body.data
    text = confirmed.body?.data?.text ?? ''
  }
  const status = data?.payload?.status ?? data?.status ?? ''
  const error = data?.payload?.error ?? data?.error ?? ''
  return { text, data, status, error }
}

/** 路由置信度不足时平台先澄清（kind=clarify），按讲解词点选 preferred 指定的能力后带 target 重问 */
async function askRouted(user, message, preferred) {
  let attempt = await askOnce(user, message)
  const options = attempt.data?.kind === 'clarify' ? (attempt.data?.payload?.options ?? []) : []
  const option = preferred === undefined ? undefined : options.find((o) => o.ref === preferred)
  if (option !== undefined) {
    note(`${user} 路由澄清，按讲解词选 ${option.name}`)
    attempt = await askOnce(user, message, option.ref)
  }
  return attempt
}

/** 只读提问失败时重试一次（真实模型偶发瞬时失败）；写操作不重试，避免重复副作用 */
async function ask(user, message, { retry = true, preferred } = {}) {
  let attempt = await askRouted(user, message, preferred)
  if (retry && attempt.status !== 'completed') {
    note(`${user} 首次提问状态 ${attempt.status || '(无运行)'}，重试一次`, attempt.error)
    attempt = await askRouted(user, message, preferred)
  }
  return attempt
}

async function traceOf(traceId) {
  if (!traceId) return { spans: [], raw: '' }
  const trace = (await call('GET', `/traces/${traceId}`, 'zhaoadmin')).body?.data
  return { spans: trace?.spans ?? [], raw: JSON.stringify(trace ?? {}) }
}

async function runOf(runId) {
  const runs = (await call('GET', '/runs?limit=20', 'zhaoadmin')).body?.data ?? []
  return runs.find((r) => r.runId === runId)
}

// ── 控制台重置（故事 D 起点） ──

const reset = await call('POST', '/demo/v1/reset', 'zhaoadmin', {})
check(
  '控制台重置成功，演示委托重建',
  reset.status === 200 &&
    reset.body?.data?.erpReset === true &&
    reset.body?.data?.delegation === 'DLG-0001',
  JSON.stringify(reset.body?.data ?? {}),
)

// ── 故事 D：数据范围与身份透传 ──

const question = '列出我们逾期超过 30 天的供应商'
const zhang = await ask('zhangmin', question, { preferred: ANALYST })
note('张敏回复', zhang.text || `（状态 ${zhang.status} ${zhang.error}）`)
const zhangRun = await runOf(zhang.data?.runId)
const zhangTrace = await traceOf(zhang.data?.traceId)
check(
  '张敏路由到供应商绩效分析，追踪含路由与工具 span',
  zhangRun?.agent === ANALYST &&
    zhangRun?.status === 'completed' &&
    zhangTrace.raw.includes('路由 ·') &&
    zhangTrace.raw.includes('工具 ·'),
  `${zhangRun?.agent ?? ''} · ${zhangRun?.status ?? ''} · ${zhangTrace.spans.length} spans`,
)
check('张敏的工具结果按华东过滤', zhangTrace.raw.includes('华东'), '')

const li = await ask('liqiang', question, { preferred: ANALYST })
note('李强回复', li.text || `（状态 ${li.status} ${li.error}）`)
const liRun = await runOf(li.data?.runId)
const liTrace = await traceOf(li.data?.traceId)
check(
  '李强同问，工具结果按华南过滤（身份透传）',
  liRun?.agent === ANALYST && liRun?.status === 'completed' && liTrace.raw.includes('华南'),
  `${liRun?.status ?? ''} · ${liTrace.spans.length} spans`,
)

const compare = (await call('GET', '/hub/demo/integration-compare?user=zhangmin', 'zhaoadmin')).body
  ?.data
check(
  '集成账号对照：BO API 按用户过滤，Open API 不过滤',
  compare?.bo?.filtered === true && compare?.open?.filtered === false,
  `BO ${compare?.bo?.total} 条，Open API ${compare?.open?.total} 条`,
)

// ── 故事 D：L2 确认与职责分离 ──

const changed = await ask('zhangmin', '把 PO-2026-0117 的交期改到下月 15 号', { retry: false })
note('改交期回复', changed.text || `（状态 ${changed.status} ${changed.error}）`)
const changedTrace = await traceOf(changed.data?.traceId)
const changeId =
  changed.text.match(/DC-\d{4}-\d{4}/)?.[0] ?? changedTrace.raw.match(/DC-\d{4}-\d{4}/)?.[0]
check(
  '改交期经人工确认后提交变更单（单号来自工具结果）',
  changeId !== undefined && changed.status === 'completed',
  changeId ?? `${changed.status} ${changed.error}`,
)

if (changeId !== undefined) {
  const approved = await ask('zhangmin', `把交期变更单 ${changeId} 审批通过`, { retry: false })
  note('审批回复', approved.text || `（状态 ${approved.status} ${approved.error}）`)
  const approvedTrace = await traceOf(approved.data?.traceId)
  check(
    '审批自己提交的变更单被职责分离拒绝（拒绝理由出现在工具结果里）',
    approvedTrace.raw.includes('职责分离'),
    approved.status,
  )
} else {
  check('审批自己提交的变更单被职责分离拒绝', false, '上一轮没有拿到变更单号，跳过审批检查')
}

const findings =
  (await call('GET', '/hub/audit/reverse?object=PO-2026-0117', 'zhaoadmin')).body?.data ?? []
const change = findings.find(
  (f) => f.operation === 'purchase_order_change_delivery_date' && f.decision === 'allow',
)
check(
  '审计反查：张敏通过采购订单处理 Agent 改了交期，能定位到运行',
  change?.userId === 'zhangmin' && change?.runId?.startsWith('run-') === true,
  change === undefined ? '' : `${change.agent} ${change.runId}`,
)

// ── 故事 F：升级回归（真实模型下要跑评测集，耗时分钟级） ──

const start = await call('POST', '/demo/v1/story-start', 'zhaoadmin', { story: 'F' })
const published = JSON.stringify(start.body?.data?.published ?? [])
check(
  '故事 F 起点发布完成态快照（扩展、评测集）',
  start.status === 200 &&
    published.includes('tenant.t001.finance/anomaly-analyst-ext') &&
    published.includes('tenant.t001.evals/anomaly-ext-smoke'),
  published.slice(0, 80),
)

note('模拟发布 1.3.0，升级回归运行中（真实模型跑评测集，预计数分钟）', stamp())
const upgrade = await postJsonLong(
  '/hub/demo/upgrade',
  await login('zhaoadmin'),
  {},
  UPGRADE_CAP_MS,
)
const upgrades = upgrade.body?.data ?? []
const anomaly = Array.isArray(upgrades)
  ? upgrades.find((r) => (r.extension ?? '').startsWith('tenant.t001.finance/anomaly-analyst-ext'))
  : undefined
check(
  '模拟发布 1.3.0：升级回归暴露扩展钩子用例失败',
  anomaly?.status === 'failed' &&
    JSON.stringify(anomaly?.regressions ?? []).includes('followup-hook-fires') &&
    JSON.stringify(anomaly?.issues ?? []).includes('HOOK_CONDITION_INVALID'),
  `${anomaly?.status ?? upgrade.body?.error?.code ?? ''} · ${stamp()}`,
)
const notifications = (await call('GET', '/notifications', 'zhaoadmin')).body?.data ?? []
check(
  '管理员收到回归通知',
  notifications.some((n) => (n.title ?? '').includes('标品升级回归未通过')),
)

// ── 故事 F：经验回流 ──

const anomalyQuestion = '本月有哪些高风险交易异常'
const before = await ask('wangfang', anomalyQuestion)
note('王芳首次回复', before.text || `（状态 ${before.status} ${before.error}）`)
const submitted = await call('POST', '/feedback', 'wangfang', {
  runId: before.data?.runId,
  reason: '这类跨期冲销不算异常',
})
const feedbackId = submitted.body?.data?.id
check('王芳提交结论反馈，生成候选规则', submitted.status === 200 && feedbackId !== undefined)
const reviewed = await call('POST', `/hub/feedback/${feedbackId}/approve`, 'zhaoadmin', {
  note: '确认',
})
check(
  '管理员审核通过，写入租户语义包',
  reviewed.body?.data?.status === 'approved' &&
    (reviewed.body?.data?.packRef ?? '').includes('tenant.t001.semantic'),
  reviewed.body?.data?.packRef ?? '',
)

const after = await ask('wangfang', anomalyQuestion)
note('王芳再次回复', after.text || `（状态 ${after.status} ${after.error}）`)
const afterTrace = await traceOf(after.data?.traceId)
check(
  '重新提问，追踪里规则来自租户语义包',
  afterTrace.raw.includes('tenant.t001.semantic/'),
  `${afterTrace.spans.length} spans`,
)

// ── 架构视图回放数据 ──

const replayRuns = (await call('GET', '/runs?limit=5', 'zhaoadmin')).body?.data ?? []
const replayRun = replayRuns.find((r) => r.traceId)
const replayTrace = replayRun ? await traceOf(replayRun.traceId) : { spans: [], raw: '' }
check(
  '架构视图回放数据源就绪：最近运行有完整 span 树',
  replayTrace.spans.length > 0 && replayTrace.raw.includes('工具 ·'),
  `${replayTrace.spans.length} spans · ${replayRun?.agent ?? ''}`,
)

console.info(
  failures === 0 ? `\n走查通过（${stamp()}）` : `\n走查有 ${failures} 项未通过（${stamp()}）`,
)
process.exit(failures === 0 ? 0 : 1)
