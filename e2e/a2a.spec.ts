import { execFileSync } from 'node:child_process'
import { expect, test, type Page } from '@playwright/test'

// P7 故事 C：陈工在 Studio 输入供应商风险舆情 Agent（LangGraph + A2A）的 Agent Card 地址，看到技能、做连通性测试、发布；
// 张敏在统一入口问风险，路由选中外部 Agent，链路里能看到 A2A 请求；client-demo 模拟外部平台经 A2A 调用采购助手，
// 审计里出现这次入站调用和调用方。外部 Agent 由 pnpm demo 在 4200 启动。

const WAIT = { timeout: 60_000 }
const PLATFORM = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const RISK_CARD = process.env.EAB_SUPPLIER_RISK_CARD

async function openAs(page: Page, userId: string, path: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
}

test('故事 C：接入 LangGraph 写的外部 Agent，统一入口调用它，并被外部平台反向调用', async ({
  browser,
  request,
}) => {
  test.setTimeout(240_000)

  // 1. Studio：读取 Agent Card → 技能与能力 → 连通性测试 → 发布
  const studio = await browser.newPage()
  await openAs(studio, 'chengong', '/studio/external/new')
  if (RISK_CARD !== undefined) await studio.getByLabel('Agent Card 地址').fill(RISK_CARD)
  await studio.getByRole('button', { name: '读取 Agent Card' }).click()
  const card = studio.getByTestId('agent-card')
  await expect(card).toContainText('供应商风险舆情 Agent', WAIT)
  await expect(card).toContainText('流式')
  await expect(card).toContainText('platformToken')
  await studio.getByRole('button', { name: '发送' }).click()
  await expect(studio.getByTestId('test-result')).toContainText('连通', WAIT)
  await expect(studio.getByTestId('a2a-exchanges')).toContainText('Bearer ***')
  await studio.getByLabel('所属岗位助手').selectOption('vendor.pur/procurement-assistant')
  await studio.getByRole('button', { name: '发布' }).click()
  await expect(studio.getByTestId('publish-result')).toContainText(
    'tenant.t001.remote/supplier-risk',
    WAIT,
  )

  // 2. 统一入口：张敏问风险，路由面板显示外部 Agent，链路里有 A2A 请求原文
  const assistant = await browser.newPage()
  await openAs(assistant, 'zhangmin', '/assistant')
  await expect(assistant.getByLabel('输入问题')).toBeEnabled(WAIT)
  await assistant.getByLabel('输入问题').fill('百润精密最近有没有什么风险')
  await assistant.getByRole('button', { name: '发送' }).click()
  await expect(assistant.getByTestId('result-card')).toContainText('风险等级', WAIT)
  const routing = assistant.getByTestId('routing-panel')
  await expect(routing).toContainText('外部 Agent · agent-request')
  await expect(routing).toContainText('供应商风险舆情 Agent')
  const trace = assistant.getByTestId('trace-panel')
  await expect(trace).toContainText('A2A · 供应商风险舆情 Agent', WAIT)
  await trace.getByText('A2A 请求与响应原文').first().click()
  await expect(trace.getByTestId('a2a-exchanges').first()).toContainText('SendStreamingMessage')

  // 3. 外部平台经 A2A 调用采购助手：没有身份被拒绝，带身份成功，审计里有调用方
  const output = execFileSync('pnpm', ['--filter', '@eab/client-demo', 'run', 'start'], {
    encoding: 'utf8',
    shell: true,
    env: { ...process.env, EAB_PLATFORM_URL: PLATFORM },
  })
  expect(output).toContain('不带用户令牌：被拒绝')
  expect(output).toContain('TASK_STATE_COMPLETED')
  const login = await request.post(`${PLATFORM}/identity/v1/login`, {
    data: { userId: 'zhaoadmin' },
  })
  const token = ((await login.json()) as { data: { token: string } }).data.token
  const audit = await request.get(`${PLATFORM}/audit/events?action=a2a.inbound`, {
    headers: { authorization: `Bearer ${token}` },
  })
  expect(JSON.stringify(await audit.json())).toContain('partner-procurement-platform')
})
