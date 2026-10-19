import { expect, test, type Page } from '@playwright/test'

// P10 演示控制台：非管理员只读；管理员的完整走查——重置、故事 F 起点与模拟升级、集成账号对照、
// 一键完成态、事件推送、切换身份。以故事 A 起点（纯重置）收尾，后续 spec 拿到首次启动状态。
// 模型是脚本化模型（demo/scripts），断网也能跑。

const RUN_TIMEOUT = { timeout: 30_000 }

async function openConsole(page: Page, userId: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto('/assistant')
  await page.getByTestId('demo-console-trigger').click()
  await expect(page.getByTestId('demo-console')).toBeVisible(RUN_TIMEOUT)
}

test('业务用户打开控制台：演示状态与升级不可用，事件与切换身份可见', async ({ page }) => {
  await openConsole(page, 'zhangmin')
  await expect(page.getByTestId('console-reset')).toHaveCount(0)
  await expect(page.getByTestId('console-complete')).toHaveCount(0)
  await expect(page.getByTestId('console-story-A')).toHaveCount(0)
  await expect(page.getByTestId('console-upgrade')).toHaveCount(0)
  await expect(page.getByTestId('demo-console')).toContainText('只对租户管理员开放')
  await expect(page.getByTestId('console-event-delivery.overdue')).toBeVisible()
  await expect(page.getByTestId('console-user-zhaoadmin')).toBeVisible()
})

test('管理员走查：重置、故事 F 起点与升级、集成账号对照、完成态、事件推送与切换身份', async ({
  page,
}) => {
  test.setTimeout(300_000)
  await openConsole(page, 'zhaoadmin')

  // 重置：清空运行并重建演示委托（孙库管对库存经营分析 Agent 的只读委托）
  await page.getByTestId('console-reset').click()
  await page.getByTestId('console-reset').click()
  await expect(page.getByTestId('console-message')).toContainText('已重置', { timeout: 60_000 })
  const runs = await page.evaluate(async () => {
    const login = await fetch('/api/identity/v1/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userId: 'zhaoadmin' }),
    })
    const { data } = await login.json()
    const res = await fetch('/api/runs?limit=100', {
      headers: { authorization: `Bearer ${data.token}` },
    })
    return (await res.json()).data.length
  })
  expect(runs).toBe(0)
  await page.goto('/hub?tab=delegations')
  await expect(page.getByText('vendor.scm/inventory-risk-analyst').first()).toBeVisible(RUN_TIMEOUT)

  // 故事 F 起点：按快照重建完成态；模拟发布 1.3.0，扩展的跟进钩子用例回归失败
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-story-F').click()
  await page.getByTestId('console-story-F').click()
  await expect(page.getByTestId('console-message')).toContainText('故事 F 起点', {
    timeout: 60_000,
  })
  await page.getByTestId('console-upgrade').click()
  await expect(page.getByTestId('console-message')).toContainText('回归未通过', { timeout: 60_000 })

  // 集成账号对照：BO API 按李强的数据范围过滤，Open API 全量
  await page.getByLabel('对照用户').selectOption('liqiang')
  await page.getByTestId('console-compare').click()
  await expect(page.getByTestId('demo-console')).toContainText('BO API · 用户身份', RUN_TIMEOUT)
  await expect(page.getByTestId('demo-console')).toContainText('采购组织：华南')
  await expect(page.getByTestId('demo-console')).toContainText('未按用户过滤')

  // 一键完成态：治理中心三层指标与岗位助手 KPI 有数据
  await page.getByTestId('console-complete').click()
  await page.getByTestId('console-complete').click()
  await expect(page.getByTestId('console-message')).toContainText('完成态已生成', {
    timeout: 60_000,
  })
  await page.goto('/hub?tab=metrics')
  await expect(page.getByText('技术指标', { exact: true })).toBeVisible(RUN_TIMEOUT)
  await expect(page.getByText('流程指标', { exact: true })).toBeVisible()
  await expect(page.getByText('业务结果', { exact: true })).toBeVisible()
  await expect(page.getByText('岗位助手 KPI')).toBeVisible()

  // 切换到孙库管触发交付逾期事件：默认委托在，Agent 受托运行并推送通知
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-user-sunkg').click()
  await expect(page).toHaveURL(/\/assistant$/)
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-event-delivery.overdue').click()
  await expect(page.getByTestId('console-message')).toContainText('事件已推送', RUN_TIMEOUT)
  await expect(page.getByTestId('notification').first()).toContainText('delivery.overdue', {
    timeout: 45_000,
  })

  // 切回管理员，以故事 A 起点（纯重置）收尾。事件推送后弹层仍开着，直接选身份即可
  await page.getByTestId('console-user-zhaoadmin').click()
  await expect(page).toHaveURL(/\/hub/)
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-story-A').click()
  await page.getByTestId('console-story-A').click()
  await expect(page.getByTestId('console-message')).toContainText('故事 A 起点', {
    timeout: 60_000,
  })
})
