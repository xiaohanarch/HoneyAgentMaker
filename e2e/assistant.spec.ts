import { expect, test, type Page } from '@playwright/test'

// P4 统一入口：四种交互模式、两级路由的说明面板、本次链路、L2 确认卡片、能力缺口、业务事件通知、IM 渠道。
// 模型是脚本化模型（demo/scripts），断网也能跑。

const RUN_TIMEOUT = { timeout: 30_000 }

async function openAs(
  page: Page,
  userId: string,
  path = '/assistant',
  input = '输入问题',
): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
  await expect(page.getByLabel(input)).toBeEnabled(RUN_TIMEOUT)
}

async function ask(page: Page, question: string): Promise<void> {
  await page.getByLabel('输入问题').fill(question)
  await page.getByRole('button', { name: '发送' }).click()
}

test('分析型：张敏问华东区逾期交付率，路由面板显示两级候选与理由，链路面板有路由和工具 span', async ({
  page,
}) => {
  await openAs(page, 'zhangmin')
  await page.getByRole('button', { name: /华东区本季度逾期交付率是多少/ }).click()
  const result = page.getByTestId('result-card')
  await expect(result).toContainText('逾期交付率', RUN_TIMEOUT)
  await expect(result).toContainText('采购组织：华东')
  const routing = page.getByTestId('routing-panel')
  await expect(routing).toContainText('第一级 · 岗位助手')
  await expect(routing).toContainText('采购助手')
  await expect(routing).toContainText('供应商绩效分析')
  await expect(page.getByTestId('tool-card').first()).toBeVisible()
  const trace = page.getByTestId('trace-panel')
  await expect(trace).toContainText('routing', RUN_TIMEOUT)
  await expect(trace).toContainText('tool')
})

test('张敏和库存助手对话：路由到库存经营分析，回答里有低于安全库存的物料', async ({ page }) => {
  await openAs(page, 'zhangmin')
  await ask(page, '哪些物料低于安全库存')
  await expect(page.getByTestId('result-card')).toContainText('M-0007', RUN_TIMEOUT)
  await expect(page.getByTestId('routing-panel')).toContainText('库存助手')
  await expect(page.getByTestId('routing-panel')).toContainText('库存经营分析')
})

test('同一个问题，李强的结果按华南采购组织过滤', async ({ page }) => {
  await openAs(page, 'liqiang')
  await ask(page, '华东区本季度逾期交付率是多少')
  await expect(page.getByTestId('result-card')).toContainText('采购组织：华南', RUN_TIMEOUT)
})

test('事务型：改交期先出 L2 确认卡片，确认后提交交期变更单', async ({ page }) => {
  await openAs(page, 'zhangmin')
  await page.getByRole('button', { name: /把 PO-2026-0117 的交期改到下月 15 号/ }).click()
  const card = page.getByTestId('confirmation-card')
  await expect(card).toContainText('purchase_order_change_delivery_date', RUN_TIMEOUT)
  await expect(card).toContainText('L2')
  await card.getByRole('button', { name: '确认执行' }).click()
  await expect(page.getByTestId('result-card')).toContainText(
    /已提交交期变更单|已有待审批的交期变更单/,
    RUN_TIMEOUT,
  )
  await expect(card).toContainText('已处理')
})

test('导航型：返回百润精密供应商详情的 DemoERP 深链接卡片', async ({ page }) => {
  await openAs(page, 'zhangmin')
  await page.getByRole('button', { name: /打开百润精密的供应商详情/ }).click()
  const card = page.getByTestId('navigation-card')
  await expect(card).toContainText('供应商 · 百润精密', RUN_TIMEOUT)
  await expect(card).toHaveAttribute('href', /^\/erp\/pages\/suppliers\/S-0001\?t=/)
})

test('信息型：降价谈判制度由采购制度问答 Agent 回答', async ({ page }) => {
  await openAs(page, 'zhangmin')
  await page.getByRole('button', { name: /我们的降价谈判制度是怎么规定的/ }).click()
  await expect(page.getByTestId('result-card')).toContainText('降价', RUN_TIMEOUT)
  await expect(page.getByTestId('routing-panel')).toContainText('采购制度问答')
})

test('能力缺口：可以提交扩展需求', async ({ page }) => {
  await openAs(page, 'zhangmin')
  await ask(page, '帮我预订下周去深圳的机票')
  const gap = page.getByTestId('gap-card')
  await expect(gap).toBeVisible(RUN_TIMEOUT)
  await gap.getByRole('button', { name: '提交扩展需求' }).click()
  await expect(gap).toContainText('扩展需求已提交')
})

test('业务事件：交付逾期事件触发库存经营分析，结果推送到孙库管的通知区', async ({
  page,
  request,
}) => {
  // 演示委托可能已被 P2 验收撤销；由赵管理员登记一条新的只读委托，保证受托运行有有效委托
  const login = await request.post('/api/identity/v1/login', { data: { userId: 'zhaoadmin' } })
  const { token } = ((await login.json()) as { data: { token: string } }).data
  const created = await request.post('/api/identity/v1/delegations', {
    headers: { authorization: `Bearer ${token}` },
    data: {
      delegatorUserId: 'sunkg',
      agent: 'vendor.scm/inventory-risk-analyst',
      scope: [
        'inventory_balance_list',
        'material_list',
        'stock_movement_list',
        'purchase_order_list',
      ],
      validDays: 1,
      note: 'e2e：交付逾期事件触发库存分析',
    },
  })
  expect(created.status()).toBe(201)
  await openAs(page, 'sunkg')
  await page.getByRole('button', { name: '触发交付逾期事件' }).click()
  await expect(page.getByTestId('notification').first()).toContainText('delivery.overdue', {
    timeout: 45_000,
  })
})

test('IM 渠道页面可以打开，并能以 IM 方式提问', async ({ page }) => {
  await openAs(page, 'zhangmin', '/channels/im', 'IM 消息')
  await expect(page.getByTestId('im-pushed')).toBeVisible()
  await page.getByLabel('IM 消息').fill('打开百润精密的供应商详情')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.getByTestId('im-chat')).toContainText('百润精密', RUN_TIMEOUT)
})
