import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const origin = process.env.COFFER_TAG_TEST_ORIGIN || 'http://127.0.0.1:5188'
const server = process.env.COFFER_TAG_TEST_ORIGIN ? null : await createServer({ root,
  server: { host: '127.0.0.1', port: 5188, strictPort: true, watch: { ignored: ['**/.test-tmp/**'] } } })
if (server) await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
try {
  await mkdir(root + '/.test-tmp/mobile-tags', { recursive: true })
  for (const width of [390, 320]) {
    const context = await browser.newContext({ viewport: { width, height: 844 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(), errors = [], mutations = []
    let polls = 0, archived = true, failNextConfirm = true
    const archiveRequests = []
    const tags = [
      { tagId: 11, tagName: '计算机科学与技术毕业设计任务书', confirmationStatus: 'PENDING_CONFIRMATION' },
      { tagId: 12, tagName: '非常长的错误标签用于验证手机移除操作', confirmationStatus: 'PENDING_CONFIRMATION' },
      { tagId: 13, tagName: '保留标签', confirmationStatus: 'PENDING_CONFIRMATION' },
    ]
    const detail = () => ({ id: 7, revision: 1 + mutations.length, fileName: '标签验证.pdf', fileType: 'pdf', fileSize: 80,
      summary: '虚构标签测试文件', status: 'COMPLETED', archived, taskState: archived ? 'ARCHIVED' : 'AWAITING_CONFIRMATION', tags,
      confirmedTags: tags.filter(t => t.confirmationStatus === 'CONFIRMED'),
      pendingTags: tags.filter(t => t.confirmationStatus === 'PENDING_CONFIRMATION'), uploadTime: '2026-10-21T00:00:00' })
    page.on('pageerror', error => errors.push(error.message))
    await page.route(origin + '/api/**', async route => {
      const path = new URL(route.request().url()).pathname.slice(4)
      let data = []
      if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
      else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
      else if (path === '/auth/csrf') data = 'fixture'
      else if (path === '/tasks/overview') data = { processingCount: 0, failedCount: 0, pendingConfirmCount: 0, processing: [], failed: [], pendingConfirm: [] }
      else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
      else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
      else if (path === '/desktop/capabilities') data = { workCopies: false }
      else if (path === '/inbox-imports/progress') data = { enabled: false, items: [] }
      else if (path === '/storage/usage') data = { usedBytes: 80, limitBytes: 2147483648, objectCount: 1, limitObjects: 1000 }
      else if (path === '/files' || path === '/files/search') data = { content: [detail()], totalElements: 1, totalPages: 1, size: 12, number: 0 }
      else if (path === '/files/7') { polls++; data = detail() }
      else if (path === '/files/tags/confirm' || path === '/files/tags/reject') {
        const payload = route.request().postDataJSON()
        assert.equal(payload.fileId, 7)
        if (path.endsWith('/confirm') && failNextConfirm) {
          failNextConfirm = false
          return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 500, msg: '测试请求失败，请重试', data: null }) })
        }
        const tag = tags.find(t => t.tagId === payload.tagId)
        assert(tag)
        tag.confirmationStatus = path.endsWith('/confirm') ? 'CONFIRMED' : 'REJECTED'
        mutations.push({ path, tagId: payload.tagId }); data = null
      } else if (path === '/files/7/confirm-archive') {
        archiveRequests.push(route.request().postDataJSON()); archived = true
        data = { batchId: 'fixture-archive', status: 'PENDING' }
      }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
    })
    await page.goto(origin + '/files?file=7')
    await page.locator('.pending-block').waitFor()
    await page.screenshot({ path: root + `/.test-tmp/mobile-tags/${width}-before.png`, fullPage: true })
    const panel = await page.locator('.drawer-wrap.is-open').boundingBox()
    assert(panel.width >= width - 24, `Phone detail panel is too narrow: ${panel.width}/${width}`)
    const tag11 = page.locator('.tag.is-pending').filter({ hasText: tags[0].tagName })
    const confirm = tag11.getByTitle('确认标签', { exact: true })
    const box = await confirm.boundingBox()
    console.log(JSON.stringify({ width, detailWidth: panel.width, controlWidth: box.width, controlHeight: box.height }))
    if (!process.argv.includes('--baseline')) assert(box.width >= 40 && box.height >= 40, 'Touch controls must be large enough')
    await confirm.tap()
    await page.getByText('测试请求失败，请重试', { exact: true }).waitFor()
    assert.equal(await confirm.isEnabled(), true)
    await confirm.tap()
    await page.locator('.tag.is-confirmed').filter({ hasText: tags[0].tagName }).waitFor()
    const tag12 = page.locator('.tag.is-pending').filter({ hasText: tags[1].tagName })
    await tag12.locator('.op-btn.is-no').tap()
    await page.locator('.correction .mini-btn.is-primary').tap()
    await tag12.waitFor({ state: 'hidden' })
    const previousPolls = polls
    await page.waitForResponse(response => new URL(response.url()).pathname === '/api/files/7' && polls > previousPolls)
    assert.equal(await page.locator('.tag.is-pending').filter({ hasText: tags[1].tagName }).count(), 0)
    assert.equal(await page.locator('.tag.is-pending').filter({ hasText: '保留标签' }).count(), 1)
    await page.locator('.tag.is-confirmed').getByRole('button', { name: `移除标签 ${tags[0].tagName}`, exact: true }).tap()
    await page.locator('.correction .mini-btn.is-primary').tap()
    await page.locator('.tag.is-confirmed').waitFor({ state: 'hidden' })
    assert.deepEqual(mutations, [{ path: '/files/tags/confirm', tagId: 11 }, { path: '/files/tags/reject', tagId: 12 }, { path: '/files/tags/reject', tagId: 11 }])

    tags.push({ tagId: 14, tagName: '逐个确认测试', confirmationStatus: 'PENDING_CONFIRMATION' })
    archived = false
    await page.reload()
    const card = page.locator('.confirmation-card')
    await card.getByRole('button', { name: '移除标签 保留标签', exact: true }).tap()
    await card.getByRole('textbox', { name: '新增标签', exact: true }).fill('用户修订标签')
    await card.getByRole('button', { name: '添加', exact: true }).tap()
    const beforeRefresh = polls
    await page.waitForResponse(response => new URL(response.url()).pathname === '/api/files/7' && polls > beforeRefresh)
    assert.equal(await card.getByRole('button', { name: '移除标签 保留标签', exact: true }).count(), 0, 'Auto-refresh must not restore a removed draft tag')
    await card.getByRole('button', { name: '移除标签 用户修订标签', exact: true }).waitFor()
    const cardConfirm = card.getByRole('button', { name: '确认标签 逐个确认测试', exact: true })
    const cardConfirmBox = await cardConfirm.boundingBox()
    assert(cardConfirmBox.width >= 40 && cardConfirmBox.height >= 40, 'Unarchived suggestions need visible touch confirmation')
    await page.screenshot({ path: root + `/.test-tmp/mobile-tags/${width}-confirmation.png`, fullPage: true })
    failNextConfirm = true
    await cardConfirm.tap()
    await card.getByRole('status').getByText('测试请求失败，请重试', { exact: true }).waitFor()
    assert.equal(await cardConfirm.isEnabled(), true)
    await cardConfirm.tap()
    await card.getByLabel('已确认标签 逐个确认测试', { exact: true }).waitFor()
    assert.equal(await card.getByRole('button', { name: '移除标签 保留标签', exact: true }).count(), 0, 'Confirming another tag must not restore a removed draft tag')
    await card.getByRole('button', { name: '移除标签 用户修订标签', exact: true }).waitFor()
    await card.getByRole('button', { name: '确认并归档', exact: true }).tap()
    await card.waitFor({ state: 'hidden' })
    assert.deepEqual(archiveRequests[0].tags, ['逐个确认测试', '用户修订标签'])
    assert.deepEqual(errors, [])
    await page.screenshot({ path: root + `/.test-tmp/mobile-tags/${width}-after.png`, fullPage: true })
    await context.close()
  }
  console.log('PASS: phone 390/320px touch confirmation/removal, confirmed-tag removal, failure/retry, draft edits survive refresh and submit correctly; synthetic APIs')
} finally { await browser.close(); if (server) await server.close() }
