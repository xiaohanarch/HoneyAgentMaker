import { expect, test, type Locator, type Page } from '@playwright/test'

// P10 故事 F 的界面版（后端断言见 GovernanceEndToEndTest）：升级回归、经验回流、架构视图回放。
// 以重置收尾，后续 studio.spec 拿到首次启动状态。模型是脚本化模型，断网也能跑。

const RUN_TIMEOUT = { timeout: 30_000 }

async function openAssistant(page: Page, userId: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto('/assistant')
  await expect(page.getByLabel('输入问题')).toBeEnabled(RUN_TIMEOUT)
}

/** 统一入口提问并确认确认点（故事 F 的扩展发布后，一次提问可能先后挂起多个确认点） */
async function askAndConfirm(page: Page, question: string): Promise<Locator> {
  const result = page.getByTestId('result-card')
  const existing = await result.count()
  await page.getByLabel('输入问题').fill(question)
  await page.getByRole('button', { name: '发送' }).click()
  const confirm = page.getByRole('button', { name: '确认执行', disabled: false })
  const card = page.getByTestId('confirmation-card').last()
  for (let i = 0; i < 5; i++) {
    await expect(confirm.or(result.nth(existing)).first()).toBeVisible(RUN_TIMEOUT)
    if ((await result.count()) > existing) break
    const key = await card.getAttribute('data-key')
    await confirm.first().click()
    await expect
      .poll(
        async () =>
          (await result.count()) > existing || (await card.getAttribute('data-key')) !== key,
        RUN_TIMEOUT,
      )
      .toBeTruthy()
  }
  return result.nth(existing)
}

test('F-1 故事 F 起点与模拟升级：扩展的跟进钩子用例回归失败，治理中心与通知可见', async ({
  page,
}) => {
  test.setTimeout(180_000)
  await openAssistant(page, 'zhaoadmin')
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-story-F').click()
  await page.getByTestId('console-story-F').click()
  await expect(page.getByTestId('console-message')).toContainText('故事 F 起点', {
    timeout: 60_000,
  })
  await page.getByTestId('console-upgrade').click()
  await expect(page.getByTestId('console-message')).toContainText('回归未通过', { timeout: 60_000 })
  await page.goto('/hub?tab=upgrades')
  const regression = page.getByTestId('regression').first()
  await expect(regression).toContainText('回归未通过', RUN_TIMEOUT)
  await expect(regression).toContainText('tenant.t001.finance/anomaly-analyst-ext')
  await expect(regression).toContainText('followup-hook-fires')
  await expect(regression).toContainText('HOOK_CONDITION_INVALID')
  await page.goto('/assistant')
  await expect(page.getByTestId('notification').first()).toContainText(
    '标品升级回归未通过',
    RUN_TIMEOUT,
  )
})

test('F-2 经验回流：王芳指出跨期冲销不算异常，管理员写入语义包后重新提问结论改变', async ({
  page,
  browser,
}) => {
  test.setTimeout(240_000)
  await openAssistant(page, 'wangfang')
  const first = await askAndConfirm(page, '本月有哪些高风险交易异常')
  await expect(first).toContainText('reversal', RUN_TIMEOUT)
  await first.getByRole('button', { name: '结论有误' }).click()
  await first.getByRole('textbox').fill('这类跨期冲销不算异常')
  await first.getByRole('button', { name: '提交反馈' }).click()
  await expect(first).toContainText('已提交反馈', RUN_TIMEOUT)

  const admin = await browser.newPage()
  await admin.addInitScript((id) => localStorage.setItem('eab.identity', id), 'zhaoadmin')
  await admin.goto('/hub?tab=feedback')
  await expect(admin.getByRole('heading', { name: '治理中心' })).toBeVisible(RUN_TIMEOUT)
  const item = admin.getByTestId('feedback-item').first()
  await expect(item).toContainText('这类跨期冲销不算异常', RUN_TIMEOUT)
  await item.getByRole('button', { name: '通过并写入语义包' }).click()
  await expect(admin.getByText(/已写入 tenant\.t001\.semantic/)).toBeVisible(RUN_TIMEOUT)
  await admin.close()

  const second = await askAndConfirm(page, '本月有哪些高风险交易异常')
  await expect(second).toContainText('按规则 T001-R-001', RUN_TIMEOUT)
  await expect(second).not.toContainText('reversal')
  await expect(page.getByTestId('trace-panel')).toContainText('T001-R-001')
})

test('F-3 架构视图：回放最近的运行，模型与 BO 工具经过的组件依次点亮', async ({ page }) => {
  test.setTimeout(180_000)
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), 'zhaoadmin')
  await page.goto('/architecture')
  await expect(page.getByRole('heading', { name: '架构视图' })).toBeVisible(RUN_TIMEOUT)
  await expect(page.getByTestId('run-picker')).toHaveValue(/run-/, RUN_TIMEOUT)

  const component = (id: string) => page.locator(`[data-testid="arch-component"][data-id="${id}"]`)
  // 开场帧点亮入口与鉴权：鉴权点 1 没有独立 span，由开场帧合成
  await expect(component('ux-entry')).toHaveAttribute('data-state', 'active', { timeout: 60_000 })
  await expect(component('wing-auth')).toHaveAttribute('data-state', 'active')

  // 单步到模型调用与 BO 工具，经过的组件按映射点亮
  const caption = page.getByTestId('replay-caption')
  const next = page.getByRole('button', { name: '下一步' })
  for (let i = 0; i < 60 && !(await next.isDisabled()); i++) {
    if ((await caption.innerText()).includes('模型调用')) break
    await next.click()
  }
  await expect(caption).toContainText('模型调用')
  await expect(component('cap-model')).toHaveAttribute('data-state', 'active')

  for (let i = 0; i < 60 && !(await next.isDisabled()); i++) {
    if ((await caption.innerText()).includes('transaction_anomaly_list')) break
    await next.click()
  }
  await expect(caption).toContainText('transaction_anomaly_list')
  await expect(component('cap-mcp')).toHaveAttribute('data-state', 'active')
  await expect(component('app-erp')).toHaveAttribute('data-state', 'active')
  await expect(component('wing-auth')).toHaveAttribute('data-state', 'active')

  // 播放与暂停
  await page.getByRole('button', { name: '播放' }).click()
  await expect(page.getByRole('button', { name: '暂停' })).toBeVisible()
  await page.getByRole('button', { name: '暂停' }).click()
  await expect(page.getByRole('button', { name: '播放' })).toBeVisible()

  // 时间线上选中一个 span，下方显示明细
  await page.getByTestId('strip-span').first().click()
  await expect(page.getByTestId('span-detail')).toBeVisible(RUN_TIMEOUT)
})

test('收尾重置：运行数据清空，后续 spec 拿到首次启动状态', async ({ page }) => {
  test.setTimeout(120_000)
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), 'zhaoadmin')
  await page.goto('/assistant')
  await page.getByTestId('demo-console-trigger').click()
  await page.getByTestId('console-reset').click()
  await page.getByTestId('console-reset').click()
  await expect(page.getByTestId('console-message')).toContainText('已重置', { timeout: 60_000 })
})
