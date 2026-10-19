import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'

// P9 专业代码通道：陈工在低代码编辑器里把草稿导出为代码工程，zip 与 pnpm eab init 的模板同构，
// 本地编辑后 pnpm eab push 推回同一条草稿（界面与文件是同一份数据的两种视图）。只验证导出，不发布。

const TARGET = 'vendor.fin/transaction-anomaly-analyst'
const WAIT = { timeout: 60_000 }

async function openAs(page: Page, userId: string, path: string): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('eab.identity', id), userId)
  await page.goto(path)
}

test('扩展草稿导出为代码工程：文件名带 -project.zip，内容是 zip 包', async ({ browser }) => {
  test.setTimeout(180_000)
  const page = await browser.newPage()
  await openAs(page, 'chengong', `/studio/vendor-agents?ref=${TARGET}`)
  const detail = page.getByTestId('vendor-agent-detail')
  await expect(detail).toContainText('扩展点', WAIT)
  // 已有草稿时是“继续编辑扩展草稿”链接，没有时是“新建扩展”按钮
  await detail
    .getByRole('button', { name: /新建扩展|修改扩展/ })
    .or(detail.getByRole('link', { name: '继续编辑扩展草稿' }))
    .click()
  await page.waitForURL(/studio\/extensions\//, WAIT)

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByTestId('export-project').click(),
  ])
  expect(download.suggestedFilename()).toMatch(/-project\.zip$/)
  const path = await download.path()
  if (path !== null) {
    const head = (await readFile(path)).subarray(0, 2).toString('latin1')
    expect(head).toBe('PK')
  }
})
