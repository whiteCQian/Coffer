import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const origin = process.env.COFFER_DETAIL_TEST_ORIGIN || 'http://127.0.0.1:5198'
const server = process.env.COFFER_DETAIL_TEST_ORIGIN ? null : await createServer({ root, server: { host: '127.0.0.1', port: 5198, strictPort: true } })
if (server) await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
await mkdir(root + '/.test-tmp/home-task-expansion', { recursive: true })
try {
  for (const [width, height] of [[1864, 884], [1270, 884], [900, 884], [760, 700], [654, 884], [600, 700], [450, 700], [390, 844], [320, 568]]) {
    const context = await browser.newContext({ viewport: { width, height }, isMobile: width < 600, hasTouch: width < 600 })
    const page = await context.newPage(), errors = []
    let pending = true, pendingCount = 12, unavailable = false, polls = 0, writes = 0, chatRequests = 0
    const longReply = '自适应布局：长回复应自然增加卡片高度，不覆盖说明和输入栏。\n'.repeat(30) + 'https://example.test/' + 'long-path-'.repeat(24)
    const file = { id: 7, revision: 1, fileName: '虚构会议.txt', fileType: 'txt', fileSize: 80, summary: '仅供测试', status: 'COMPLETED', taskState: 'AWAITING_CONFIRMATION', archived: false, uploadTime: '2026-10-26T12:00:00', tags: [], confirmedTags: [], pendingTags: [] }
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message) })
    await page.route(origin + '/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname.slice(4)
      if (!['GET', 'HEAD'].includes(request.method())) writes++
      let data = []
      if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
      else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
      else if (path === '/auth/csrf') data = 'fixture'
      else if (path === '/files') data = { content: Array.from({ length: 8 }, (_, i) => ({ ...file, id: 7 + i, fileName: i ? `虚构最近文件-${i}.txt` : file.fileName, summary: '用于验证最近上传内部滚动，文件列表不应无限撑高。'.repeat(3) })), totalElements: 8, totalPages: 1 }
      else if (path === '/files/7') data = file
      else if (path === '/tasks/overview') {
        polls++
        if (unavailable) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, msg: 'fixture unavailable' }) }); return }
        data = { processing: [], failed: [], pendingConfirm: pending ? Array.from({ length: pendingCount }, (_, i) => ({ fileId: 7 + i, fileName: `虚构待处理-${i}.txt` })) : [], processingCount: 0, failedCount: 0, pendingConfirmCount: pending ? pendingCount : 0 }
      } else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
      else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
      else if (path === '/settings/runtime/endpoints') data = []
      else if (path === '/model-execution/chat-target') data = { configurationVersion: 'fixture', mode: 'API', chatConsentApproved: true, targets: [] }
      else if (path === '/chat/send') { chatRequests++; data = { sessionId: 'fixture-session', reply: chatRequests === 1 ? '这是模拟的 AI 回复' : longReply, citations: [] } }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
    })
    await page.goto(origin + '/')
    const task = page.getByRole('region', { name: '本次上传与处理' })
    await task.waitFor({ state: 'visible' })
    const chat = page.locator('.web-home-side .chat-card'), input = chat.locator('.chat-input-field')
    if (width <= 760) {
      const recent = page.locator('.recent-card'), list = recent.locator('.recent-list')
      await list.locator('.recent-item').nth(7).waitFor()
      const cardBox = await recent.boundingBox(), headY = (await recent.locator('.card-head').boundingBox()).y
      assert(cardBox.height <= Math.max(280, Math.min(height * .52, 460)) + 1, 'Recent uploads has a bounded mobile height')
      const metrics = await list.evaluate(el => ({ overflow: getComputedStyle(el).overflowY, scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, color: getComputedStyle(el).scrollbarColor }))
      assert.equal(metrics.overflow, 'auto')
      assert(metrics.scrollHeight > metrics.clientHeight + 50, 'Recent uploads scrolls internally')
      assert(!metrics.color.startsWith('rgba(0, 0, 0, 0)'), 'Scrollbar thumb has a visible color')
      await list.locator('.recent-item').last().focus()
      assert(await list.evaluate(el => el.scrollTop > 0), 'Keyboard focus reaches the last file by scrolling the list')
      assert(Math.abs((await recent.locator('.card-head').boundingBox()).y - headY) < 1, 'Card header stays above its scrolling list')
    }
    async function assertMobileFlow() {
      if (width > 760) return
      const layout = await page.evaluate(() => {
        const box = el => { const r = el.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, height: r.height } }
        const card = document.querySelector('.web-home-side .chat-card'), task = document.querySelector('#web-task-progress'), side = document.querySelector('.web-home-side')
        const children = ['.card-head', '.chat-guide', '.chat-messages', '.chat-input'].map(s => card.querySelector(s)).filter(el => getComputedStyle(el).display !== 'none').map(box)
        const nested = [side, card, card.querySelector('.chat-messages'), task].map(el => ({ overflow: getComputedStyle(el).overflowY, scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }))
        return { children, nested, side: box(side), chat: box(card), task: box(task), page: { scrollHeight: document.querySelector('.page-viewport').scrollHeight, clientHeight: document.querySelector('.page-viewport').clientHeight } }
      })
      for (const node of layout.nested) {
        assert.equal(node.overflow, 'visible', 'Mobile cards have no nested vertical scroll container')
        assert(node.scrollHeight <= node.clientHeight + 1, 'Card content fits its natural height')
      }
      for (let i = 1; i < layout.children.length; i++) assert(layout.children[i].top >= layout.children[i - 1].bottom - 1, 'AI sections never overlap')
      assert(layout.task.top >= layout.chat.bottom, 'Cards remain in normal vertical order')
      assert(layout.task.bottom <= layout.side.bottom + 1, 'Parent grows around both cards')
      assert(layout.page.scrollHeight > layout.page.clientHeight, 'Content scrolls with the page')
    }
    await assertMobileFlow()
    await input.fill('尚未提交的提问')
    const before = await task.boundingBox()
    assert.equal(await task.getByRole('button', { name: '展开上传与处理' }).getAttribute('aria-expanded'), 'false')
    await task.getByRole('button', { name: '展开上传与处理' }).click()
    await chat.locator('.chat-restore').waitFor({ state: 'visible' })
    assert.equal(await chat.locator('.card-head').isVisible(), false)
    assert.equal(await chat.locator('.chat-messages').isVisible(), false)
    assert.equal(await input.inputValue(), '尚未提交的提问')
    const after = await task.boundingBox(), compact = await chat.boundingBox()
    if (width > 760) assert(after.height > before.height + 40, 'Desktop tasks gain useful height')
    else assert(Math.abs(after.height - before.height) <= 1, 'Mobile task height follows content in both modes')
    assert(compact.height < 100, 'AI card becomes just an input bar')
    assert.equal(await task.getByRole('button', { name: '收起上传与处理' }).getAttribute('aria-expanded'), 'true')
    assert.equal(await task.locator('.upload-receipt').count(), 12)
    await assertMobileFlow()
    const beforePoll = polls
    await Promise.all([page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/overview'), task.getByRole('button', { name: '刷新状态' }).click()])
    assert(polls > beforePoll)
    assert.equal(await input.inputValue(), '尚未提交的提问')
    assert.equal(await page.locator('.tasks-expanded').count(), 1, 'Refresh preserves expansion')
    assert.equal(writes, 0, 'Layout changes and refresh never submit business writes')
    await chat.getByRole('button', { name: '展开 AI 管家' }).click()
    assert.equal(await chat.locator('.card-head').isVisible(), true)
    assert.equal(await input.inputValue(), '尚未提交的提问')
    await task.getByRole('button', { name: '展开上传与处理' }).click()
    await input.press('Enter')
    await chat.getByText('这是模拟的 AI 回复', { exact: true }).waitFor({ state: 'visible' })
    assert.equal(chatRequests, 1)
    assert.equal(await chat.locator('.card-head').isVisible(), true, 'Sending restores the AI panel to show the reply')
    await task.getByRole('button', { name: '展开上传与处理' }).click()
    await task.getByRole('button', { name: '收起上传与处理' }).click()
    assert.equal(await chat.getByText('这是模拟的 AI 回复', { exact: true }).isVisible(), true, 'Restoring keeps conversation history')
    if (width <= 760) {
      const shortHeight = (await chat.boundingBox()).height
      await input.fill('用长回复检验自适应高度')
      await input.press('Enter')
      await chat.locator('.msg-text').last().getByText(longReply, { exact: true }).waitFor({ state: 'visible' })
      assert((await chat.boundingBox()).height > shortHeight + 300, 'AI card grows with long replies')
      await assertMobileFlow()
      pendingCount = 1
      await Promise.all([page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/overview'), task.getByRole('button', { name: '刷新状态' }).click()])
      await task.locator('.upload-receipt').nth(1).waitFor({ state: 'hidden' })
      assert((await task.boundingBox()).height < before.height - 300, 'Task card shrinks when its content shrinks')
      await assertMobileFlow()
      if (width === 450) {
        await page.setViewportSize({ width: 900, height: 700 })
        assert.equal(await chat.locator('.chat-messages').evaluate(el => getComputedStyle(el).overflowY), 'auto', 'Wide layout keeps its own conversation scroll')
        await page.setViewportSize({ width, height })
        await assertMobileFlow()
      }
    }
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No page horizontal overflow')
    await task.getByRole('button', { name: '展开上传与处理' }).click()
    await page.screenshot({ path: root + `/.test-tmp/home-task-expansion/expanded-${width}.png`, fullPage: true })
    if (width === 1270) {
      await task.getByRole('button', { name: '查看并处理', exact: true }).first().click()
      await page.locator('.drawer-panel').getByText(file.fileName, { exact: true }).waitFor()
      await page.locator('.drawer-panel .head-x').click()
      assert.equal(await page.locator('.tasks-expanded').count(), 1, 'Viewing a file preserves task expansion')
    }
    pending = false
    await Promise.all([page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/overview'), task.getByRole('button', { name: '刷新状态' }).click()])
    await task.waitFor({ state: 'hidden' })
    assert.equal(await chat.locator('.card-head').isVisible(), true, 'AI panel restores when no tasks remain')
    if (width === 1270) {
      unavailable = true
      await page.goto(origin + '/')
      await task.getByText('任务列表暂时无法读取', { exact: false }).waitFor()
      await task.getByRole('button', { name: '展开上传与处理' }).click()
      unavailable = false
      await Promise.all([page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/overview'), task.getByRole('button', { name: '刷新状态' }).click()])
      await task.waitFor({ state: 'hidden' })
      assert.equal(await chat.locator('.card-head').isVisible(), true)
      await page.goto(origin + '/#web-task-progress')
      await task.getByRole('button', { name: '收起上传与处理' }).waitFor()
      await task.getByText('当前没有待处理的文件。').waitFor()
    }
    const search = page.getByRole('searchbox', { name: '搜索我的文件', exact: true })
    await page.keyboard.press('Control+k')
    assert.equal(await search.evaluate(el => document.activeElement === el), true, 'Ctrl K still focuses search')
    await search.fill('虚构会议')
    const focus = await search.evaluate(el => ({ outline: getComputedStyle(el).outlineStyle, shadow: getComputedStyle(el).boxShadow }))
    assert.equal(focus.outline, 'none')
    assert.equal(focus.shadow, 'none')
    await search.press('Enter')
    await page.waitForURL('**/files?keyword=*')
    assert.equal(new URL(page.url()).searchParams.get('keyword'), '虚构会议')
    assert.deepEqual(errors, [])
    console.log('PASS home task expansion', { width, compactHeight: compact.height, beforeHeight: before.height, expandedHeight: after.height, chatRequests, polls })
    await context.close()
  }
} finally { await browser.close(); if (server) await server.close() }
