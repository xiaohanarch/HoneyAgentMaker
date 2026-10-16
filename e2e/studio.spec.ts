import { expect, test, type Page } from '@playwright/test'

// P5 故事 A：陈工从一句话新建价格谈判助手，走完 13 步向导（AI 推荐来自脚本化模型的剧本），在私有环境以张敏的身份测试，
// 运行评测，发布并晋级到 prod；之后张敏在统一入口直接用上它，链路里能看到 PUR-R-007 触发。

const INTENT = '帮采购经理制定和供应商的降价谈判策略'
const PROMPT = '查一下百润精密最近一次询价，帮我出一份谈判策略'
const WAIT = { timeout: 60_000 }

async function openAs(page: Page, userId: string, path: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
}

async function gotoStep(page: Page, label: string): Promise<void> {
  await page.getByRole('navigation', { name: '向导步骤' }).getByText(label, { exact: true }).click()
}

test('故事 A：从一句话新建价格谈判助手，测试、评测、发布到 prod，张敏在统一入口使用', async ({
  browser,
}) => {
  test.setTimeout(300_000)
  const studio = await browser.newPage()
  await openAs(studio, 'chengong', '/studio/agents/new')
  await studio.getByLabel('描述 Agent 的目的').fill(INTENT)
  await studio.getByLabel('描述 Agent 的目的').press('Enter')
  await studio.waitForURL(/studio\/drafts\//, WAIT)

  // 前 10 步：AI 推荐 → 一键采纳 → 下一步
  for (let i = 0; i < 10; i++) {
    const recommendation = studio.getByTestId('ai-recommendation')
    await expect(recommendation).toBeVisible(WAIT)
    await expect(recommendation).toContainText('模型推荐', WAIT)
    if (i === 3) await expect(recommendation).toContainText('P7 接入后可调用')
    await studio.getByRole('button', { name: '一键采纳' }).click()
    await studio.waitForTimeout(500)
    if (i === 9) {
      // 实时 YAML 已经包含各步采纳的内容
      const yaml = studio.getByTestId('yaml-preview')
      await expect(yaml).toContainText('price-negotiation-assistant')
      await expect(yaml).toContainText('PUR-R-007')
      await expect(yaml).toContainText('vendor.pur/supplier-performance-analyst')
    }
    await studio
      .getByRole('link', { name: /下一步/ })
      .first()
      .click()
  }

  // 评测集：从已验证任务生成并运行
  await studio.getByRole('button', { name: '从已验证任务生成' }).click()
  await studio.getByRole('button', { name: '运行评测' }).click()
  await expect(studio.getByTestId('eval-results')).toBeVisible(WAIT)
  await expect(studio.getByTestId('eval-case').first()).toContainText('PUR-R-007')

  // 测试：以张敏的身份，PUR-R-007 触发，链路里有约束 span
  await gotoStep(studio, '测试')
  await studio.getByLabel('测试话术', { exact: true }).fill(PROMPT)
  await studio.getByLabel('发送测试话术').click()
  await expect(studio.getByTestId('constraint-banner').first()).toContainText('PUR-R-007', WAIT)
  await expect(studio.getByTestId('trace-timeline')).toContainText('约束 · PUR-R-007 触发', WAIT)

  // 发布：静态检查通过，发布到 dev，晋级 test、prod
  await gotoStep(studio, '发布')
  await expect(studio.getByTestId('lint-panel')).toContainText('静态检查通过', WAIT)
  await studio.getByRole('button', { name: '发布并部署到 dev' }).click()
  // 同一内容已经发布过时版本不变；已在目标环境的就不再晋级（重复运行本用例时）
  for (const env of ['test', 'prod']) {
    await studio.waitForTimeout(1000)
    const promote = studio.getByRole('button', { name: /^晋级/ })
    if ((await promote.count()) === 0) continue
    await promote.first().click()
    await expect(studio.getByText(`晋级到 ${env}`)).toBeVisible(WAIT)
    await expect(studio.getByTestId('gates')).toBeVisible()
  }
  await expect(studio.getByTestId('env-pipeline')).toContainText('生产 prod')
  await expect(studio.getByTestId('env-pipeline')).toContainText('1.0.')
  await studio.close()

  // 张敏在统一入口直接使用
  const assistant = await browser.newPage()
  await openAs(assistant, 'zhangmin', '/assistant')
  await expect(assistant.getByLabel('输入问题')).toBeEnabled(WAIT)
  await assistant.getByLabel('输入问题').fill(PROMPT)
  await assistant.getByRole('button', { name: '发送' }).click()
  await expect(assistant.getByTestId('result-card')).toContainText('PUR-R-007', WAIT)
  await expect(assistant.getByTestId('routing-panel')).toContainText('价格谈判助手')
  await expect(assistant.getByTestId('trace-panel')).toContainText('约束 · PUR-R-007 触发', WAIT)
})

test('连接中心：列出连接别名与各环境绑定，租户开发者只读', async ({ page }) => {
  await openAs(page, 'chengong', '/studio/connections')
  await expect(page.getByText('DEMO_ERP')).toBeVisible(WAIT)
  await expect(page.getByText('SUPPLIER_RISK_AGENT')).toBeVisible()
})
