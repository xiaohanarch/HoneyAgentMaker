import { expect, test, type Page } from '@playwright/test'

// P8 治理中心：资产清单与血缘、版本差异、运行指标、审计反查、委托、经验回流的界面。
// 模型是脚本化模型（demo/scripts），断网也能跑。

const RUN_TIMEOUT = { timeout: 30_000 }

async function openHub(page: Page, userId: string, tab?: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(tab === undefined ? '/hub' : `/hub?tab=${tab}`)
  await expect(page.getByRole('heading', { name: '治理中心' })).toBeVisible(RUN_TIMEOUT)
}

test('业务用户进入治理中心只看到提示，没有分区导航', async ({ page }) => {
  await openHub(page, 'zhangmin')
  await expect(page.getByText('治理中心面向租户管理员')).toBeVisible(RUN_TIMEOUT)
  await expect(page.getByRole('navigation', { name: '治理中心分区' })).toHaveCount(0)
})

test('管理员查看资产清单：血缘图有助手与 Agent，选中标品 Agent 后显示版本差异面板', async ({
  page,
}) => {
  await openHub(page, 'zhaoadmin')
  const graph = page.getByRole('img', { name: '资产血缘图' })
  await expect(graph).toBeVisible(RUN_TIMEOUT)
  await expect(graph).toContainText('采购助手')
  await page.getByRole('row', { name: /供应商绩效分析/ }).click()
  await expect(page.getByRole('complementary', { name: '版本差异' })).toContainText(
    'vendor.pur/supplier-performance-analyst',
  )
})

test('开发者只读：能看清单，看不到生成演示数据的按钮', async ({ page }) => {
  await openHub(page, 'chengong', 'metrics')
  await expect(page.getByText('只读')).toBeVisible(RUN_TIMEOUT)
  await expect(page.getByRole('button', { name: '生成 30 天演示数据' })).toHaveCount(0)
})

test('运行指标：生成 30 天演示数据后显示技术指标表、流程指标、业务结果与岗位助手 KPI', async ({
  page,
}) => {
  await openHub(page, 'zhaoadmin', 'metrics')
  await page.getByRole('button', { name: '生成 30 天演示数据' }).click()
  await expect(page.getByText('技术指标', { exact: true })).toBeVisible(RUN_TIMEOUT)
  await expect(page.getByText('流程指标', { exact: true })).toBeVisible()
  await expect(page.getByText('业务结果', { exact: true })).toBeVisible()
  await expect(page.getByText('岗位助手 KPI')).toBeVisible()
  await expect(page.getByRole('img', { name: '每日运行次数与失败次数' })).toBeVisible()
})

test('审计反查 PO-2026-0117，并对照集成账号与用户身份的数据范围', async ({ page }) => {
  await openHub(page, 'zhaoadmin', 'audit')
  await page.getByRole('button', { name: '反查' }).click()
  await expect(
    page.getByTestId('reverse-findings').or(page.getByText('没有涉及 PO-2026-0117')),
  ).toBeVisible(RUN_TIMEOUT)
  await page.getByRole('button', { name: '以张敏的身份对照' }).click()
  const compare = page.getByTestId('integration-compare')
  await expect(compare).toContainText('按用户的数据范围过滤', RUN_TIMEOUT)
  await expect(compare).toContainText('没有按用户过滤')
})

test('委托管理列出库存 Agent 的委托', async ({ page }) => {
  await openHub(page, 'zhaoadmin', 'delegations')
  await expect(page.getByText('vendor.scm/inventory-risk-analyst').first()).toBeVisible(RUN_TIMEOUT)
})

test('经验回流：王芳在结果卡片上点“结论有误”提交理由，管理员在治理中心看到待审核的反馈', async ({
  page,
  browser,
}) => {
  await page.addInitScript(() => localStorage.setItem('eab.identity', 'wangfang'))
  await page.goto('/assistant')
  await expect(page.getByLabel('输入问题')).toBeEnabled(RUN_TIMEOUT)
  await page.getByLabel('输入问题').fill('本月有哪些高风险交易异常')
  await page.getByRole('button', { name: '发送' }).click()
  const result = page.getByTestId('result-card')
  // 故事 B 的扩展发布后（e2e/extend.spec.ts 先运行），回答前要确认 IM 通知和跟进任务；只点可用的确认按钮
  const confirm = page.getByRole('button', { name: '确认执行', disabled: false })
  for (let i = 0; i < 5; i++) {
    await expect(confirm.or(result).first()).toBeVisible(RUN_TIMEOUT)
    if ((await result.count()) > 0) break
    await confirm.first().click()
    await expect(confirm).toHaveCount(0, RUN_TIMEOUT)
  }
  await expect(result).toBeVisible(RUN_TIMEOUT)
  await result.getByRole('button', { name: '结论有误' }).click()
  await result.getByRole('textbox').fill('这类跨期冲销不算异常')
  await result.getByRole('button', { name: '提交反馈' }).click()
  await expect(result).toContainText('已提交反馈', RUN_TIMEOUT)

  const admin = await browser.newPage()
  await openHub(admin, 'zhaoadmin', 'feedback')
  await expect(admin.getByTestId('feedback-item').first()).toContainText(
    '这类跨期冲销不算异常',
    RUN_TIMEOUT,
  )
  await admin.close()
})
