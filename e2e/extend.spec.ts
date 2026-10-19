import { expect, test, type Page } from '@playwright/test'

// P6 故事 B：陈工在 Studio 扩展交易异常解析 Agent（追加指令、增加 IM 通知工具、结果钩子、确认点），
// 有效定义预览里标品指令是锁住的占位块；评测通过后发布，王芳在统一入口提问，IM 通知前弹出确认，结论给出责任部门。
// 工具中心和技能编辑器各走一遍主路径。

const TARGET = 'vendor.fin/transaction-anomaly-analyst'
const APPEND = '本公司规定：单笔金额超过 50 万元的异常，结论中必须给出责任部门。'
const WAIT = { timeout: 60_000 }

async function openAs(page: Page, userId: string, path: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
}

async function tab(page: Page, label: string): Promise<void> {
  await page
    .getByRole('navigation', { name: '扩展编辑' })
    .getByRole('button', { name: label })
    .click()
}

async function save(page: Page): Promise<void> {
  await page.getByRole('button', { name: '保存这一页' }).click()
  await expect(page.getByText('已保存，右侧 YAML 和有效定义已更新')).toBeVisible(WAIT)
}

test('故事 B：扩展标品 Agent，评测后发布，王芳在统一入口用上扩展', async ({ browser }) => {
  test.setTimeout(300_000)
  const studio = await browser.newPage()
  await openAs(studio, 'chengong', `/studio/vendor-agents?ref=${TARGET}`)
  const detail = studio.getByTestId('vendor-agent-detail')
  await expect(detail).toContainText('扩展点', WAIT)
  await detail
    .getByRole('button', { name: /新建扩展|修改扩展/ })
    .or(detail.getByRole('link', { name: '继续编辑扩展草稿' }))
    .click()
  await studio.waitForURL(/studio\/extensions\//, WAIT)

  await studio.getByLabel('追加指令').fill(APPEND)
  await save(studio)

  await tab(studio, '工具')
  await studio.locator('input[aria-label="tools"]').fill('tenant.t001.tools/notify-im')
  await studio.locator('input[aria-label="tools"]').press('Enter')
  await save(studio)

  await tab(studio, '钩子')
  await studio.getByLabel('onResult 引用').fill('tenant.t001.wf/create-followup-task')
  await studio.getByLabel('onResult 条件').fill("result.severity in ['high','critical']")
  await save(studio)

  await tab(studio, '确认点')
  if ((await studio.getByLabel('确认点工具').count()) === 0) {
    await studio.getByRole('button', { name: '增加确认点' }).click()
  }
  await studio.getByLabel('确认点工具').first().fill('tenant.t001.tools/notify-im')
  await save(studio)
  await expect(studio.getByTestId('yaml-panel')).toContainText('notify-im')

  // 有效定义：标品指令锁住，租户追加的指令高亮并标来源
  await tab(studio, '有效定义')
  const segments = studio.getByTestId('effective-segments')
  // 锁住的片段里有平台安全规则和标品指令，都只显示占位
  await expect(
    segments.locator('[data-tone="locked"]').filter({ hasText: '标品指令（不可见，运行时注入）' }),
  ).not.toHaveCount(0, WAIT)
  await expect(segments.locator('[data-tone="tenant"]').first()).toContainText('50 万元')
  await expect(segments).not.toContainText('你是交易异常解析专家')

  // 评测与发布
  await tab(studio, '评测与发布')
  await studio.getByRole('button', { name: '按扩展内容生成' }).click()
  await expect(studio.getByLabel('扩展评测集 YAML')).toHaveValue(/notify_im/, WAIT)
  await studio.getByRole('button', { name: /运行评测/ }).click()
  await expect(studio.getByTestId('extension-eval-results')).toContainText('都通过', WAIT)
  await studio.getByRole('button', { name: '发布扩展' }).click()
  await expect(studio.getByTestId('extension-published')).toBeVisible(WAIT)

  // 王芳在统一入口提问：IM 通知前确认，结论给出责任部门
  const assistant = await browser.newPage()
  await openAs(assistant, 'wangfang', '/assistant')
  await assistant.getByLabel('输入问题').fill('本月有哪些高风险交易异常')
  await assistant.getByLabel('输入问题').press('Enter')
  const result = assistant.getByTestId('result-card')
  // 扩展加了两个确认点：发 IM 通知前、结果钩子创建跟进任务前。确认后要等卡片换成下一个确认点
  // （data-key 变了）或结果卡出现，不能等“确认按钮清零”——那只是运行恢复期间按钮暂时禁用的短窗口
  const confirm = assistant.getByRole('button', { name: '确认执行', disabled: false })
  const card = assistant.getByTestId('confirmation-card')
  for (let i = 0; i < 5; i++) {
    await expect(confirm.or(result).first()).toBeVisible(WAIT)
    if ((await result.count()) > 0) break
    const key = await card.getAttribute('data-key')
    await confirm.first().click()
    await expect
      .poll(
        async () => (await result.count()) > 0 || (await card.getAttribute('data-key')) !== key,
        WAIT,
      )
      .toBeTruthy()
  }
  await expect(result).toContainText('责任部门', WAIT)
})

test('工具中心与技能编辑器：查看工具引用，新建技能并单独测试', async ({ page }) => {
  test.setTimeout(180_000)
  await openAs(page, 'chengong', '/studio/tools')
  await expect(page.getByTestId('tool-sources')).toContainText('demo-erp-bo', WAIT)
  await expect(page.getByTestId('bo-import')).toContainText('rfq_list', WAIT)
  await expect(page.getByTestId('mcp-approvals')).toContainText('只有租户管理员可以审批')

  const name = `e2e-skill-${Date.now().toString(36)}`
  await page.getByLabel('技能名').fill(name)
  await page.getByLabel('显示名', { exact: true }).fill('端到端测试技能')
  await page.getByRole('button', { name: '新建技能' }).click()
  await page.waitForURL(/studio\/skills\//, WAIT)
  await expect(page.getByTestId('skill-graph')).toBeVisible()
  await page.getByLabel('测试入参').fill('{"hello": "world"}')
  await page.getByRole('button', { name: '保存并测试' }).click()
  const result = page.getByTestId('skill-test-result')
  await expect(result).toContainText('技能执行成功', WAIT)
  await expect(result).toContainText('step-1')
  await expect(result).toContainText('world')
})
