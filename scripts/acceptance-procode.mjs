#!/usr/bin/env node
// P9 专业代码通道验收：对正在运行的演示环境（pnpm demo）验证 Studio MCP Server 与代码工程导出，只用 HTTP。
//   node scripts/acceptance-procode.mjs
// MCP 用裸 JSON-RPC over /studio-mcp（与 Claude Code 等 MCP 客户端的连接方式一致）：initialize 后
// tools/list 应登记 14 个工具；陈工（租户开发者）能查语义与扩展点目录，张敏（业务用户）与匿名调用被拒。
// 导出：建扩展草稿（已存在则复用）→ 导出断言 zip 魔数、文件名与工程清单 → 本轮新建的草稿删掉、复用的保留。
// 不发布任何资产，重复运行幂等。
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const TARGET = 'vendor.pur/purchase-order-clerk'

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

// ── MCP：裸 JSON-RPC over /studio-mcp ──

let nextId = 1
async function rpc(user, method, params, notification = false) {
  const message = { jsonrpc: '2.0', method }
  if (!notification) message.id = nextId++
  if (params !== undefined) message.params = params
  const headers = {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
  }
  if (user !== null) headers.authorization = `Bearer ${await login(user)}`
  const response = await fetch(`${PLATFORM}/studio-mcp`, {
    method: 'POST',
    headers,
    body: JSON.stringify(message),
  })
  let payload = await response.text()
  // 无状态 Streamable HTTP：单次响应用 JSON，也可能走 SSE 的一条 data
  if ((response.headers.get('content-type') ?? '').includes('text/event-stream')) {
    payload = payload
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).replace(/^ /, ''))
      .join('')
  }
  return { status: response.status, body: payload === '' ? null : JSON.parse(payload) }
}

const toolText = (message) => message?.body?.result?.content?.[0]?.text ?? ''

const init = await rpc('chengong', 'initialize', {
  protocolVersion: '2025-06-18',
  capabilities: {},
  clientInfo: { name: 'acceptance-procode', version: '0.0.0' },
})
check(
  'MCP initialize 握手，服务器是 eab-studio',
  init.body?.result?.serverInfo?.name === 'eab-studio',
  init.body?.result?.serverInfo?.name ?? init.body?.error?.message ?? '',
)
await rpc('chengong', 'notifications/initialized', undefined, true)

const EXPECTED_TOOLS = [
  'semantic_describe_object',
  'semantic_get_metric',
  'semantic_find_rules',
  'semantic_search',
  'studio_list_capabilities',
  'studio_list_vendor_agents',
  'studio_get_vendor_agent',
  'studio_list_drafts',
  'studio_get_draft',
  'studio_validate_asset',
  'studio_generate_evals',
  'studio_run_evals',
  'studio_deploy_draft',
  'studio_promote',
]
const listed = await rpc('chengong', 'tools/list', {})
const names = (listed.body?.result?.tools ?? []).map((tool) => tool.name)
check(
  '工具清单登记 14 个：语义查询四个加 Studio 十个',
  names.length === 14 && EXPECTED_TOOLS.every((name) => names.includes(name)),
  `${names.length} 个：${names.join('、')}`,
)

const search = await rpc('chengong', 'tools/call', {
  name: 'semantic_search',
  arguments: { query: '备选供应商' },
})
check(
  '陈工查语义：semantic_search 命中备选供应商',
  search.body?.result?.isError !== true && toolText(search).includes('备选供应商'),
  toolText(search).slice(0, 60),
)
const agents = await rpc('chengong', 'tools/call', {
  name: 'studio_list_vendor_agents',
  arguments: {},
})
check(
  '扩展点目录里有采购订单处理 Agent 与可扩展点',
  agents.body?.result?.isError !== true && toolText(agents).includes(TARGET),
  toolText(agents).slice(0, 60),
)

const denied = await rpc('zhangmin', 'tools/call', { name: 'studio_list_drafts', arguments: {} })
check(
  '张敏不是开发者，调用被拒并说明原因',
  denied.body?.result?.isError === true &&
    toolText(denied).includes('只对租户开发者和租户管理员开放'),
  toolText(denied).slice(0, 60),
)
const anonymous = await rpc(null, 'tools/call', { name: 'studio_list_drafts', arguments: {} })
check(
  '匿名调用被拒，提示缺少令牌',
  anonymous.body?.result?.isError === true && toolText(anonymous).includes('令牌'),
  toolText(anonymous).slice(0, 60),
)

// ── 导出为代码工程 ──

const home = (await call('GET', '/studio/home', 'chengong')).body?.data
const known = new Set((home?.drafts ?? []).map((draft) => draft.id))
const created = await call('POST', '/studio/extensions', 'chengong', { target: TARGET })
const draftId = created.body?.data?.draft?.id ?? ''
check(
  `建${known.size === 0 ? '' : '（或复用）'}${TARGET} 的扩展草稿`,
  draftId !== '',
  created.body?.error?.message ?? draftId,
)

const exported = await fetch(`${PLATFORM}/studio/drafts/${draftId}/export`, {
  headers: { authorization: `Bearer ${await login('chengong')}` },
})
const disposition = exported.headers.get('content-disposition') ?? ''
const zip = Buffer.from(await exported.arrayBuffer())
check(
  '导出返回 zip（魔数 PK，类型 application/zip）',
  exported.status === 200 &&
    zip.subarray(0, 2).toString('latin1') === 'PK' &&
    (exported.headers.get('content-type') ?? '').includes('application/zip'),
  `${exported.status} ${(exported.headers.get('content-type') ?? '').slice(0, 40)}`,
)
check('文件名是 <名字>-project.zip', /-project\.zip"?$/.test(disposition), disposition)
check(
  'zip 里有工程清单、资产骨架与 .gitignore',
  zip.includes('eab-project.yaml') && zip.includes('.gitignore'),
  '',
)

// 本轮新建的草稿删掉，之前就有的（别的演示流程留下的）保持原样
if (draftId !== '' && !known.has(draftId)) {
  const deleted = await call('DELETE', `/studio/drafts/${draftId}`, 'chengong')
  check('清理本轮新建的草稿，演示状态不变', deleted.status === 200, '')
}

console.info(
  failures === 0 ? '\n专业代码通道验收通过' : `\n专业代码通道验收有 ${failures} 项未通过`,
)
process.exit(failures === 0 ? 0 : 1)
