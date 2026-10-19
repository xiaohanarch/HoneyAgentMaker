import { expect, test } from '@playwright/test'

// P0 骨架的冒烟测试：四个入口可切换、身份可切换、后端健康、1280 宽度无横向滚动。
// 六个演示故事的端到端测试在后续阶段各自补充。

test('前端框架加载，后端服务健康', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/assistant$/)
  const healthy = { timeout: 15_000 }
  await expect(page.getByTestId('health-platform')).toHaveAttribute('data-state', 'up', healthy)
  await expect(page.getByTestId('health-demo-erp')).toHaveAttribute('data-state', 'up', healthy)
})

test('顶部可在统一入口、Studio、治理中心、架构视图之间切换', async ({ page }) => {
  await page.goto('/assistant')
  await page.getByRole('link', { name: /Studio/ }).click()
  await expect(page.getByRole('heading', { name: '设计、测试、发布、运行、观测' })).toBeVisible()
  await page.getByRole('link', { name: /治理中心/ }).click()
  await expect(page.getByRole('heading', { name: '治理中心' })).toBeVisible()
  await page.getByRole('link', { name: /架构视图/ }).click()
  await expect(page.getByRole('heading', { name: '架构视图' })).toBeVisible()
})

test('切换演示身份后进入该身份的默认入口', async ({ page }) => {
  await page.goto('/assistant')
  await page.getByTestId('identity-switcher').click()
  await page.getByRole('menuitem', { name: /陈工/ }).click()
  await expect(page).toHaveURL(/\/studio$/)
  await expect(page.getByTestId('identity-switcher')).toContainText('陈工')
})

test('1280 宽度下四个入口都没有横向滚动', async ({ page }) => {
  for (const path of ['/assistant', '/studio', '/hub', '/architecture']) {
    await page.goto(path)
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, path).toBeLessThanOrEqual(0)
  }
})
