import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const origin = process.env.COFFER_DETAIL_TEST_ORIGIN || 'http://127.0.0.1:5197'
const server = process.env.COFFER_DETAIL_TEST_ORIGIN ? null : await createServer({ root,
  server: { host: '127.0.0.1', port: 5197, strictPort: true } })
if (server) await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
await mkdir(root + '/.test-tmp/detail-presentation', { recursive: true })
try {
  for (const width of [1864, 1440, 390, 320]) {
    const context = await browser.newContext({ viewport: { width, height: 884 }, isMobile: width < 600, hasTouch: width < 600 })
    const page = await context.newPage(), errors = []
    let polls = 0, usageRequests = 0, writes = 0
    const file = { id: 7, revision: 1, fileName: '虚构会议.txt', fileType: 'txt', fileSize: 80,
      summary: '这是虚构的会议样本，用于检验文件详情放大与修改。', category: 'OTHER',
      status: 'COMPLETED', archived: false, taskState: 'AWAITING_CONFIRMATION',
      uploadTime: '2026-10-26T12:00:00', confirmedTags: [], pendingTags: [{ tagId: 1, tagName: '会议', confirmationStatus: 'PENDING_CONFIRMATION' }], tags: [] }
    page.on('pageerror', error => errors.push(error.message))
    await page.route(origin + '/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname.slice(4)
      if (!['GET', 'HEAD'].includes(request.method())) writes++
      let data = []
      if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
      else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false, storageQuotaEnabled: true }
      else if (path === '/auth/csrf') data = 'fixture'
      else if (path === '/storage/usage') { usageRequests++; data = { usedBytes: 80, limitBytes: 2147483648, objectCount: 1, limitObjects: 1000, pendingTaskLimit: 5, concurrentTaskLimit: 1 } }
      else if (path === '/files') data = { content: [file], totalElements: 1, totalPages: 1 }
      else if (path === '/files/7') { polls++; data = file }
      else if (path === '/tasks/overview') data = { processing: [], failed: [], pendingConfirm: [], processingCount: 0, failedCount: 0, pendingConfirmCount: 1 }
      else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
      else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
      else if (path === '/desktop/capabilities') data = { workCopies: false }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
    })
    await page.goto(origin + '/files')
    const quota = page.locator('details.quota-panel')
    await quota.waitFor()
    assert.equal(await quota.getAttribute('open'), null, 'Quota is collapsed by default')
    assert.equal(await quota.locator('.quota-refresh').isVisible(), false)
    const compactHeight = (await quota.boundingBox()).height
    await quota.locator('summary').click()
    await quota.locator('.quota-refresh').waitFor({ state: 'visible' })
    await quota.getByText(/存储已用/).waitFor()
    await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/api/storage/usage'),
      quota.getByRole('button', { name: '刷新配额', exact: true }).click(),
    ])
    assert(usageRequests >= 2)
    assert((await quota.boundingBox()).height > compactHeight)
    await quota.locator('summary').click()
    await page.locator('.desk-file-name').getByText(file.fileName, { exact: true }).click()
    const card = page.locator('.confirmation-card')
    await card.waitFor()
    await card.locator('details summary').click()
    await card.getByLabel('摘要', { exact: true }).fill('尚未提交的摘要修改')
    await card.getByRole('textbox', { name: '新增标签', exact: true }).fill('用户草稿')
    await card.getByRole('button', { name: '添加', exact: true }).click()
    const initialPolls = polls
    await page.getByRole('button', { name: '放大文件详情', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '文件详情', exact: true })
    await dialog.waitFor({ state: 'visible' })
    const rect = await dialog.boundingBox()
    assert(rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width + 1 && rect.y + rect.height <= 885, 'Centered panel stays in viewport')
    assert(Math.abs(rect.x + rect.width / 2 - width / 2) < 3, 'Detail is horizontally centered')
    assert(rect.width >= (width > 600 ? 700 : width - 24), 'Detail gains usable editing width')
    assert.equal(await dialog.getByLabel('摘要', { exact: true }).inputValue(), '尚未提交的摘要修改')
    await page.waitForResponse(response => new URL(response.url()).pathname === '/api/files/7' && polls > initialPolls)
    assert.equal(await dialog.getByLabel('摘要', { exact: true }).inputValue(), '尚未提交的摘要修改', 'Polling preserves the editor')
    assert.equal(await dialog.getByRole('button', { name: '移除标签 用户草稿', exact: true }).count(), 1)
    await dialog.press('Tab')
    assert(await dialog.evaluate(el => el.contains(document.activeElement)), 'Tab focus stays inside the expanded detail')
    await dialog.getByLabel('摘要', { exact: true }).press('Escape')
    assert.equal(await page.getByRole('dialog').count(), 0, 'Escape returns to sidebar')
    assert.equal(await card.getByLabel('摘要', { exact: true }).inputValue(), '尚未提交的摘要修改')
    await page.getByRole('button', { name: '放大文件详情', exact: true }).click()
    await page.locator('.drawer-wrap.is-expanded > .drawer-mask').click({ position: { x: 4, y: 4 } })
    assert.equal(await page.getByRole('dialog').count(), 0, 'Backdrop returns to sidebar without closing the file')
    await page.getByRole('button', { name: '放大文件详情', exact: true }).click()
    await page.screenshot({ path: root + `/.test-tmp/detail-presentation/expanded-${width}.png`, fullPage: true })
    await dialog.locator('.head-x').click()
    assert.equal(await page.locator('.drawer-wrap.is-expanded').count(), 0, 'Closing leaves no modal overlay')
    await page.locator('.desk-file-name').getByText(file.fileName, { exact: true }).click()
    await page.getByRole('button', { name: '放大文件详情', exact: true }).waitFor()
    assert.equal(await page.getByRole('dialog').count(), 0, 'Reopening starts in the sidebar')
    assert.equal(writes, 0, 'Changing presentation never submits edits')
    assert.deepEqual(errors, [])
    console.log('PASS detail presentation', { width, compactHeight, expandedWidth: rect.width, expandedHeight: rect.height })
    await context.close()
  }
} finally { await browser.close(); if (server) await server.close() }
