#!/usr/bin/env node
// P6 验收：对正在运行的演示环境（pnpm demo）走一遍故事 B 和工具中心、技能编辑器，只用 HTTP 接口。
//   node scripts/acceptance-p6.mjs
// 默认模型提供方是脚本化模型，断网、没有 API Key 也能通过。会发布交易异常解析 Agent 的租户扩展、新建一个技能并发出
// 一条 IM 通知（演示用的 IM 渠道），之后可以运行 pnpm seed:reset 恢复：演示中发布的扩展与技能一并撤回，故事 B 可重演。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const TARGET = 'vendor.fin/transaction-anomaly-analyst'
const PROBE = '你是交易异常解析专家'
const PLACEHOLDER = '标品指令（不可见，运行时注入）'

let failures = 0
function check(label, ok, detail = '') {
  console.info(`${ok ? '✓' : '✗'} ${label}${detail === '' ? '' : `  ${detail}`}`)
  if (!ok) failures += 1
}

async function json(url, init = {}) {
  const response = await fetch(url, init)
  const text = await response.text()
  return { status: response.status, body: text === '' ? null : JSON.parse(text), text }
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

/** 挂起的运行逐个确认，最多 5 次；返回最后的运行结果和出现过的确认内容 */
async function confirmAll(path, first, user) {
  let current = first
  const pending = []
  for (let i = 0; i < 5 && current?.status === 'suspended'; i++) {
    pending.push(JSON.stringify(current.pending ?? {}))
    const next = await call('POST', path.replace('{runId}', current.runId), user, {
      approved: true,
    })
    current = next.body?.data
  }
  return { done: current, pending }
}

// 1. 标品 Agent 目录与详情：看得到扩展点，看不到指令原文
const catalog = await call('GET', '/studio/vendor-agents', 'chengong')
check(
  '标品 Agent 目录列出交易异常解析 Agent 及其扩展点',
  catalog.text.includes(TARGET) && catalog.text.includes('instructions.append'),
)
const detail = await call('GET', `/studio/vendor-agents/${TARGET}`, 'chengong')
check(
  '标品详情里指令是占位块，没有原文',
  detail.text.includes(PLACEHOLDER) && !detail.text.includes(PROBE),
)

// 2. 扩展编辑：按扩展点分页保存
const created = await call('POST', '/studio/extensions', 'chengong', { target: TARGET })
const draftId = created.body?.data?.draft?.id
check('新建扩展草稿', created.status === 200 && draftId !== undefined, draftId ?? '')
const sections = [
  ['instructions', { append: '本公司规定：单笔金额超过 50 万元的异常，结论中必须给出责任部门。' }],
  ['tools', { add: ['tenant.t001.tools/notify-im'] }],
  [
    'hooks',
    {
      onResult: {
        workflow: 'tenant.t001.wf/create-followup-task',
        when: "result.severity in ['high','critical']",
      },
    },
  ],
  ['approvals', { add: [{ tool: 'tenant.t001.tools/notify-im', policy: 'require-confirmation' }] }],
]
let last
for (const [section, value] of sections) {
  last = await call('POST', `/studio/drafts/${draftId}/extension`, 'chengong', { section, value })
}
check('按扩展点分页保存，草稿没有 schema 问题', (last.body?.data?.issues ?? []).length === 0)
const effective = await call('GET', `/studio/drafts/${draftId}/effective`, 'chengong')
check(
  '有效定义预览：标品指令锁住，租户追加的指令带来源',
  effective.text.includes(PLACEHOLDER) &&
    effective.text.includes('tenant-instructions') &&
    !effective.text.includes(PROBE),
)
const lint = await call('POST', `/studio/drafts/${draftId}/extension/lint`, 'chengong', {})
check('静态检查通过', lint.body?.data?.ok === true)

// 3. 私有环境测试：IM 通知前需要确认，结论里有责任部门
const tested = await call('POST', `/studio/drafts/${draftId}/extension/test`, 'chengong', {
  message: '本月有哪些高风险交易异常',
  asUser: 'wangfang',
})
const privateRun = await confirmAll(
  '/studio/test-runs/{runId}/confirmation',
  tested.body?.data,
  'chengong',
)
check(
  '私有环境测试：notify_im 前确认，结论给出责任部门',
  privateRun.pending.some((p) => p.includes('notify_im')) &&
    String(privateRun.done?.text ?? '').includes('责任部门'),
)

// 4. 评测（标品公开集 + 扩展集）与发布
await call('POST', `/studio/drafts/${draftId}/extension/evals/generate`, 'chengong', {
  testUser: 'wangfang',
})
const evals = await call('POST', `/studio/drafts/${draftId}/extension/evals/run`, 'chengong', {})
check(
  '标品公开评测集和扩展评测集都通过',
  evals.body?.data?.status === 'passed',
  (evals.body?.data?.suites ?? []).map((s) => `${s.suite} ${s.passed}/${s.total}`).join('，'),
)
const published = await call('POST', `/studio/drafts/${draftId}/extension/publish`, 'chengong', {})
check(
  '发布扩展',
  published.status === 200,
  published.body?.data?.version ?? published.body?.error?.message ?? '',
)

// 5. 王芳在统一入口提问：扩展生效
const assist = await call('POST', '/assist', 'wangfang', { message: '本月有哪些高风险交易异常' })
const runId = assist.body?.data?.runId
const run = await call('GET', `/runs/${runId}`, 'wangfang')
const story = await confirmAll(
  '/runs/{runId}/confirmation',
  { status: run.body?.data?.status, runId, pending: run.body?.data?.pending },
  'wangfang',
)
check(
  '故事 B：IM 通知前弹出确认，确认后结论给出责任部门',
  story.pending.some((p) => p.includes('notify_im')) &&
    String(story.done?.text ?? '').includes('责任部门'),
)
const trace = await call('GET', `/traces/${assist.body?.data?.traceId}`, 'wangfang')
check(
  '运行追踪里有租户追加指令，没有标品指令原文',
  trace.text.includes('tenant-instructions') && !trace.text.includes(PROBE),
)

// 6. 复制为租户 Agent
const copy = await call('POST', `/studio/vendor-agents/${TARGET}/copy`, 'chengong', {})
check(
  '复制为租户 Agent：提示不再随标品升级，副本不含指令原文',
  String(copy.body?.data?.notice ?? '').includes('不再随标品升级') && !copy.text.includes(PROBE),
)

// 7. 租户改不了标品资产
const vendorWrite = await call('PATCH', `/registry/assets/${TARGET}/1.2.0/status`, 'chengong', {
  status: 'retired',
})
check('租户改标品资产状态被拒绝', vendorWrite.status === 403)

// 8. 工具中心与技能编辑器
const tools = await call('GET', '/studio/tools', 'chengong')
check(
  '工具中心列出工具源、写级别和被引用关系',
  (tools.body?.data?.sources ?? []).some(
    (s) => s.ref === 'tenant.t001.tools/notify-im' && s.usedBy.length > 0,
  ),
)
const ops = await call('GET', '/studio/tools/bo-operations', 'chengong')
check('从 DemoERP 的 OpenAPI 列出 BO 操作', (ops.body?.data ?? []).length > 0)
const approvals = await call('GET', '/studio/mcp-approvals', 'chengong')
check('MCP 审批列表可读', approvals.status === 200)

const skill = await call('POST', '/studio/skills', 'chengong', {
  name: `acceptance-${Date.now().toString(36)}`,
  displayName: '验收技能',
  from: 'vendor.pur.skills/rfq-lookup',
})
const skillId = skill.body?.data?.draft?.id
const skillTest = await call('POST', `/studio/drafts/${skillId}/skill-test`, 'chengong', {
  input: { supplierName: '百润精密', latestOnly: true },
  asUser: 'zhangmin',
})
check(
  '技能单独测试：每一步有输入输出',
  skillTest.body?.data?.status === 'ok' &&
    (skillTest.body?.data?.steps ?? []).some((s) => s.id === 'query' && s.output !== null),
  (skillTest.body?.data?.steps ?? []).map((s) => `${s.id}:${s.status}`).join(' '),
)
const skillPublished = await call('POST', `/studio/drafts/${skillId}/skill-publish`, 'chengong', {})
check('发布技能', skillPublished.status === 200, skillPublished.body?.data?.ref ?? '')

console.info(failures === 0 ? '\nP6 验收全部通过' : `\nP6 验收有 ${failures} 项未通过`)
process.exit(failures === 0 ? 0 : 1)
