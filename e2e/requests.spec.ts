import { expect, test, type Page } from '@playwright/test'

// P9 故事 E：张敏要的事标品采购订单处理 Agent 办不了，缺口卡片上补一句大额审批要求后提交扩展需求，
// 平台把检索到的业务对象与规则附带给开发者；陈工在 Studio 首页看到新需求，生成六步方案卡（脚本化模型按剧本输出），
// 改一处 YAML 保存，采纳成工作区草稿，标记交付后张敏在通知区收到重新提问的消息。
// 发布与重新提问的完整链路由 StoryEEndToEndTest 覆盖；这里不发布扩展，保证缺口故事可以反复演示。

const WAIT = { timeout: 60_000 }
const QUESTION = '百润精密这批货到不了，帮我从备选供应商里找有现货的下单'
const SUPPLEMENT = '金额超过 10 万的必须人工审批'

async function openAs(page: Page, userId: string, path: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
}

test('故事 E：缺口卡片提交需求，陈工生成方案、采纳草稿、标记交付，张敏收到通知', async ({
  browser,
}) => {
  test.setTimeout(240_000)

  // 张敏提问：标品 Agent 如实说明缺少的能力，缺口卡片里补上大额审批要求后提交
  const assistant = await browser.newPage()
  await openAs(assistant, 'zhangmin', '/assistant')
  await assistant.getByLabel('输入问题').fill(QUESTION)
  await assistant.getByRole('button', { name: '发送' }).click()
  const gap = assistant.getByTestId('gap-card')
  await expect(gap).toBeVisible(WAIT)
  // Agent 的如实说明在结果卡里（脚本回复），缺口卡只带缺失的能力与提交入口
  await expect(assistant.getByTestId('result-card')).toContainText(
    '没有查询物料认证备选供应商的能力',
    WAIT,
  )
  await expect(gap).toContainText('从备选供应商查询现货并创建请购单草稿')
  await gap.getByLabel('补充说明').fill(SUPPLEMENT)
  await gap.getByRole('button', { name: '提交扩展需求' }).click()
  await expect(gap).toContainText('扩展需求已提交', WAIT)
  await expect(gap).toContainText('已附带给开发者')

  // 陈工在 Studio 首页看到新需求（最新一条），进去后有原话、补充说明和附带的口径
  const studio = await browser.newPage()
  await openAs(studio, 'chengong', '/studio')
  const newest = studio
    .getByTestId('home-requests')
    .getByRole('link')
    .filter({ hasText: '百润精密' })
    .first()
  await expect(newest).toContainText('待处理', WAIT)
  await newest.click()
  await studio.waitForURL(/\/studio\/requests\/tenant\.t001\.requests\/req-/, WAIT)

  const header = studio.getByTestId('request-header')
  await expect(header).toContainText('张敏 提出的扩展需求', WAIT)
  await expect(header).toContainText(QUESTION)
  await expect(header.getByLabel('补充说明')).toContainText(SUPPLEMENT)
  const attached = header.getByLabel('自动附带的口径')
  await expect(attached).toContainText('SupplierAlternative')
  await expect(attached).toContainText('PUR-R-008')
  await expect(studio.getByTestId('plan-empty')).toBeVisible()

  // 生成方案：六张卡齐了，fit-gap 是扩展标品即可，草稿与评测集来自剧本
  await studio.getByRole('button', { name: '生成方案' }).click()
  await expect(studio.getByTestId('plan-meta')).toContainText('模型生成', WAIT)
  const fitGap = studio.getByTestId('plan-fit-gap')
  await expect(fitGap).toContainText('扩展标品即可')
  await expect(fitGap).toContainText('90%')
  await expect(studio.getByTestId('plan-summary')).toContainText('10 万元')
  const assets = studio.getByTestId('plan-assets')
  await expect(assets).toContainText('alternative-supplier-lookup')
  await expect(assets).toContainText('purchase-order-clerk-ext')
  const drafts = studio.getByTestId('plan-drafts')
  await expect(drafts).toContainText('备选供应商补货')
  await expect(drafts).toContainText('purchase-order-clerk-ext')
  await expect(studio.getByTestId('plan-eval-suite')).toContainText('purchase-clerk-ext-smoke')

  // 重新生成要确认；确认后方案还在。等生成时间换新再继续：重新生成会覆盖六张卡并清空未保存的编辑，
  // 旧卡还显示时就往下编辑，会被完成后的重置清掉，保存按钮就一直点不了
  studio.on('dialog', (dialog) => dialog.accept())
  const meta = studio.getByTestId('plan-meta')
  const beforeRegenerate = await meta.textContent()
  await studio.getByRole('button', { name: '重新生成' }).click()
  await expect(meta).not.toHaveText(beforeRegenerate ?? '', WAIT)
  await expect(meta).toContainText('模型生成', WAIT)

  // 陈工改一处 YAML 后保存
  const firstDraft = studio.getByTestId('plan-draft').first()
  await firstDraft.getByRole('button', { name: '编辑' }).click()
  const box = firstDraft.getByLabel('编辑 YAML')
  await box.fill(`${await box.inputValue()}\n# e2e：陈工补充的备注\n`)
  await studio.getByRole('button', { name: '保存修改' }).click()
  await expect(firstDraft.getByText('已修改，点“保存修改”后生效')).toHaveCount(0, WAIT)
  await expect(box).toHaveValue(/e2e：陈工补充的备注/, WAIT)

  // 采纳方案：技能与扩展落成工作区草稿
  await studio.getByRole('button', { name: '采纳方案，创建草稿' }).click()
  const adopted = studio.getByTestId('adopted-drafts')
  await expect(adopted).toContainText('alternative-supplier-lookup', WAIT)
  await expect(adopted).toContainText('purchase-order-clerk-ext')

  // 标记交付：需求转只读，张敏在通知区收到重新提问的消息
  const marker = Date.now().toString(36)
  await studio.getByLabel('交付说明').fill(`备选供应商补货与大额请购确认已上线（e2e ${marker}）`)
  await studio.getByRole('button', { name: '标记已交付' }).click()
  await expect(header).toContainText('已交付', WAIT)
  await expect(studio.getByTestId('request-actions')).toHaveCount(0)

  const notified = assistant
    .getByTestId('notification')
    .filter({ hasText: `e2e ${marker}` })
    .first()
  await expect(notified).toContainText('已交付', WAIT)
  await expect(notified).toContainText('重新提问')
})
