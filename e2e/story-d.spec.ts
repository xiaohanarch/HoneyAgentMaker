import { expect, test, type Page } from '@playwright/test'

// P10 故事 D 的界面版（后端断言见 GovernanceEndToEndTest）：数据范围对照、集成账号对照、
// L2 确认与职责分离、撤销委托后事件被拒、审计反查。模型是脚本化模型，断网也能跑。

const RUN_TIMEOUT = { timeout: 30_000 }

async function openAssistant(page: Page, userId: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto('/assistant')
  await expect(page.getByLabel('输入问题')).toBeEnabled(RUN_TIMEOUT)
}

async function ask(page: Page, question: string): Promise<void> {
  await page.getByLabel('输入问题').fill(question)
  await page.getByRole('button', { name: '发送' }).click()
}

test('故事 D 起点：控制台重置后回到首次启动状态', async ({ page }) => {
  test.setTimeout(120_000)
  await openAssistant(page, 'zhaoadmin')
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-story-D').click()
  await page.getByTestId('console-story-D').click()
  await expect(page.getByTestId('console-message')).toContainText('故事 D 起点', {
    timeout: 60_000,
  })
})

test('D-1 张敏和李强问同一个问题，结果按各自的采购组织过滤', async ({ page, browser }) => {
  test.setTimeout(120_000)
  await openAssistant(page, 'zhangmin')
  await ask(page, '列出我们逾期超过 30 天的供应商')
  // 两个 Agent 的路由示例相近，路由器先请用户在两个能力之间澄清；故事 D 选供应商绩效分析
  await page.getByRole('button', { name: '供应商绩效分析 Agent' }).click()
  const east = page.getByTestId('result-card')
  await expect(east).toContainText('已按你的数据权限过滤', RUN_TIMEOUT)
  await expect(east).toContainText('采购组织：华东')
  await expect(east).not.toContainText('（华南）')

  const south = await browser.newPage()
  await openAssistant(south, 'liqiang')
  await ask(south, '列出我们逾期超过 30 天的供应商')
  await south.getByRole('button', { name: '供应商绩效分析 Agent' }).click()
  await expect(south.getByTestId('result-card')).toContainText('采购组织：华南', RUN_TIMEOUT)
  await expect(south.getByTestId('result-card')).not.toContainText('（华东）')
  await south.close()
})

test('D-2 控制台的集成账号对照：BO API 按李强的数据范围过滤，Open API 全量', async ({ page }) => {
  await openAssistant(page, 'zhaoadmin')
  await page.getByTestId('demo-console-trigger').click()
  await page.getByLabel('对照用户').selectOption('liqiang')
  await page.getByTestId('console-compare').click()
  await expect(page.getByTestId('demo-console')).toContainText('BO API · 用户身份', RUN_TIMEOUT)
  await expect(page.getByTestId('demo-console')).toContainText('采购组织：华南')
  await expect(page.getByTestId('demo-console')).toContainText('未按用户过滤')
})

test('D-3 张敏改交期经 L2 确认提交；让 Agent 审批自己提交的变更单被职责分离拒绝', async ({
  page,
}) => {
  test.setTimeout(150_000)
  await openAssistant(page, 'zhangmin')
  await ask(page, '把 PO-2026-0117 的交期改到下月 15 号')
  const card = page.getByTestId('confirmation-card')
  await expect(card).toContainText('purchase_order_change_delivery_date', RUN_TIMEOUT)
  await expect(card).toContainText('L2')
  await card.getByRole('button', { name: '确认执行' }).click()
  const result = page.getByTestId('result-card')
  await expect(result).toContainText(/已提交交期变更单|已有待审批的交期变更单/, RUN_TIMEOUT)
  const changeId = /DC-\d{4}-\d{4}/.exec(await result.innerText())?.[0]
  if (changeId === undefined) throw new Error('结果里没有交期变更单号')

  await ask(page, `把交期变更单 ${changeId} 审批通过`)
  // 审批同样是 L2 写操作，先经用户确认；确认后提交人审批自己的单据才被职责分离拦截
  const approve = page.getByTestId('confirmation-card').last()
  await expect(approve).toContainText(changeId, RUN_TIMEOUT)
  await approve.getByRole('button', { name: '确认执行' }).click()
  const denied = result.last()
  await expect(denied).toContainText(`没有审批交期变更单 ${changeId}`, RUN_TIMEOUT)
  await expect(denied).toContainText('职责分离')
})

test('D-4 撤销库存 Agent 的委托后触发库存事件，Agent 被拒绝运行，审计有记录', async ({
  page,
  request,
}) => {
  test.setTimeout(120_000)
  const login = await request.post('/api/identity/v1/login', { data: { userId: 'zhaoadmin' } })
  const { token } = ((await login.json()) as { data: { token: string } }).data
  const auth = { authorization: `Bearer ${token}` }
  const delegations = await request.get('/api/identity/v1/delegations', { headers: auth })
  const list = ((await delegations.json()) as { data: { id: string; agent: string }[] }).data
  const inventory = list.find((d) => d.agent === 'vendor.scm/inventory-risk-analyst')
  if (inventory === undefined) throw new Error('列表里没有库存经营分析 Agent 的演示委托')
  const revoked = await request.delete(`/api/identity/v1/delegations/${inventory.id}`, {
    headers: auth,
  })
  expect(revoked.ok()).toBeTruthy()
  const pushed = await request.post('/api/demo/v1/events', {
    headers: auth,
    data: { type: 'inventory.below_safety_stock' },
  })
  expect(pushed.status()).toBe(200)
  await expect
    .poll(
      async () => {
        const events = await request.get('/api/audit/events?limit=50&decision=deny', {
          headers: auth,
        })
        const items = ((await events.json()) as { data: { action: string; target: string }[] }).data
        return items.some(
          (e) => e.action === 'trigger.event' && e.target === 'vendor.scm/inventory-risk-analyst',
        )
      },
      { timeout: 30_000 },
    )
    .toBeTruthy()

  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), 'zhaoadmin')
  await page.goto('/hub?tab=audit')
  await expect(page.getByRole('table').first()).toContainText('trigger.event', RUN_TIMEOUT)
  await expect(page.getByRole('table').first()).toContainText('vendor.scm/inventory-risk-analyst')
})

test('D-5 审计反查 PO-2026-0117：交期是张敏通过采购订单处理 Agent 改的', async ({ page }) => {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), 'zhaoadmin')
  await page.goto('/hub?tab=audit')
  await page.getByRole('button', { name: '反查' }).click()
  const findings = page.getByTestId('reverse-findings')
  await expect(findings).toBeVisible(RUN_TIMEOUT)
  await expect(findings).toContainText('purchase_order_change_delivery_date')
  await expect(findings).toContainText('zhangmin')
  await expect(findings).toContainText('vendor.pur/purchase-order-clerk')
})
