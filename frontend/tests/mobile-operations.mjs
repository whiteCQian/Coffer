import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const origin = process.env.COFFER_OPERATIONS_TEST_ORIGIN || 'http://127.0.0.1:5189'
const server = process.env.COFFER_OPERATIONS_TEST_ORIGIN ? null : await createServer({ root, server: { host: '127.0.0.1', port: 5189, strictPort: true } })
if (server) await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
await mkdir(root + '/.test-tmp/mobile-operations', { recursive: true })
const items = Array.from({ length: 25 }, (_, i) => ({
  id: i + 1, batchId: 'fixture-' + i, fileId: i + 1,
  sourceFileName: i === 24 ? '跨页目标合同.txt' : '测试文件-' + i + '.txt',
  targetFileName: i === 24 ? '跨页目标合同.txt' : '测试文件-' + i + '.txt',
  executionStatus: i === 24 ? 'FAILED' : 'SUCCEEDED', rollbackStatus: 'NOT_REQUESTED',
  attempts: 1, createdAt: '2026-10-21T12:00:00', sourcePath: '/fixture', targetPath: '/fixture',
}))
try {
  for (const scenario of ['legacy', 'modern', 'failure', 'too-many', 'missing-all', 'forbidden']) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(), errors = []
    const counts = { recent: 0, items: 0 }
    let recovered = false
    page.on('pageerror', error => { errors.push(error.message); console.error('UI error:', error.stack) })
    await page.route(origin + '/api/**', async route => {
      const url = new URL(route.request().url()), path = url.pathname.slice(4)
      let data = []
      if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
      else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
      else if (path === '/auth/csrf') data = 'fixture'
      else if (path === '/tasks/overview') data = { processingCount: 0, failedCount: 0, pendingConfirmCount: 0, processing: [], failed: [], pendingConfirm: [] }
      else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
      else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
      else if (path === '/desktop/capabilities') data = { workCopies: false }
      else if (path === '/files') data = { content: [], totalElements: 0, totalPages: 0 }
      else if (/^\/files\/\d+$/.test(path)) data = {
        id: Number(path.split('/').pop()), revision: 1, fileName: '跨页目标合同.txt', fileType: 'txt',
        fileSize: 40, summary: '虚构导航测试', status: 'COMPLETED', archived: true, taskState: 'ARCHIVED',
        confirmedTags: [], pendingTags: [], uploadTime: '2026-10-21T12:00:00',
      }
      else if (path === '/governance/operations/recent') {
        counts.recent++
        if (scenario === 'forbidden') return route.fulfill({ status: 403,
          contentType: 'application/json', body: JSON.stringify({ code: 403, msg: '无权执行此操作', data: null }) })
        if (scenario !== 'modern') return route.fulfill({ status: scenario === 'failure' ? 500 : 404,
          contentType: 'application/json', body: JSON.stringify({ code: scenario === 'failure' ? 500 : 404, msg: 'fixture-error', data: null }) })
        data = { content: items.slice(0, 20), totalElements: 25, totalPages: 2 }
      } else if (path === '/governance/operations/items') {
        counts.items++
        if (scenario === 'missing-all' && !recovered) return route.fulfill({ status: 404,
          contentType: 'application/json', body: JSON.stringify({ code: 404, msg: '资源不存在', data: null }) })
        const size = Number(url.searchParams.get('size')), page = Number(url.searchParams.get('page'))
        assert(size > 0 && size <= 100)
        data = { content: items.slice(page * size, (page + 1) * size), totalElements: scenario === 'too-many' ? 2001 : 25, totalPages: scenario === 'too-many' ? 21 : Math.ceil(25 / size) }
      }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
    })
    await page.goto(origin + '/operations')
    const panel = page.locator('.recent-panel')
    if (scenario === 'legacy' || scenario === 'modern') {
      await panel.locator('.recent-row').first().waitFor({ timeout: 10000 }).catch(async error => {
        console.error('Operations load failure:', { url: page.url(), errors, body: await page.locator('body').innerText() })
        throw error
      })
      assert.equal(await panel.locator('.recent-row').count(), 20)
      assert.equal(await panel.getByRole('link', { name: '查看文件', exact: true }).count(), 0)
      assert.equal(await panel.locator('.recent-row details').count(), 0)
      assert.equal(await page.locator('.web-space-badge').count(), 0)
      assert.equal(await page.locator('.el-message--error').count(), 0, 'Route compatibility must not toast a missing resource')
      if (scenario === 'legacy') {
        await panel.getByRole('button', { name: '下一页', exact: true }).tap()
        await panel.getByText('跨页目标合同.txt', { exact: true }).waitFor()
        assert.equal(await panel.locator('.recent-row').count(), 5)
        await page.getByRole('textbox', { name: '操作文件名', exact: true }).fill('跨页目标')
        await panel.getByRole('button', { name: '查找', exact: true }).tap()
        await panel.getByText('第 1 / 1 页', { exact: true }).waitFor()
        assert.equal(await panel.locator('.recent-row').count(), 1, 'Search must include matching records outside the first page')
        await panel.getByRole('checkbox', { name: '只看需要处理' }).check()
        await panel.getByText('跨页目标合同.txt', { exact: true }).waitFor()
        assert.equal(counts.recent, 1, 'Do not repeatedly request an absent route')
        await page.screenshot({ path: root + '/.test-tmp/mobile-operations/legacy-390.png', fullPage: true })
        const filename = panel.getByRole('link', { name: '跨页目标合同.txt', exact: true })
        await filename.click()
        assert.equal(new URL(page.url()).pathname, '/operations', 'Single click must not navigate')
        await filename.dblclick()
        await page.waitForURL('**/files?file=25')
        await page.goto(origin + '/operations')
        await panel.locator('.recent-row').first().waitFor()
        await panel.getByRole('link', { name: '测试文件-0.txt', exact: true }).press('Enter')
        await page.waitForURL('**/files?file=1')
      } else assert.equal(counts.items, 0, 'Do not use the fallback on a working modern backend')
    } else if (['failure', 'forbidden', 'missing-all'].includes(scenario)) {
      await panel.getByRole('alert').waitFor()
      assert.equal(await panel.getByText('添加并归档文件后，操作记录会出现在这里。', { exact: true }).count(), 0, 'An error must not masquerade as an empty ledger')
      assert.equal(counts.items, scenario === 'missing-all' ? 1 : 0, 'Only 404 triggers compatibility; do not hide server failures or forbidden access')
      assert.equal(await page.locator('.el-message--error').count(), 0, 'Read errors use the persistent page message instead of repeated toasts')
      await page.waitForTimeout(5500)
      assert.equal(counts.recent, 1, 'Pause auto-polling after a failed load')
      if (scenario === 'missing-all') {
        assert.equal(counts.items, 1, 'A missing legacy route must not be polled repeatedly either')
        recovered = true
        await page.getByRole('button', { name: '刷新', exact: true }).click()
        await panel.locator('.recent-row').first().waitFor()
        assert.equal(await panel.getByRole('alert').count(), 0, 'Manual refresh can recover without reloading the page')
        assert.equal(counts.recent, 1, 'Do not probe the absent modern route again')
      }
    } else {
      await panel.locator('.recent-row').first().waitFor()
      await page.getByRole('textbox', { name: '操作文件名', exact: true }).fill('合同')
      await panel.getByRole('button', { name: '查找', exact: true }).tap()
      await panel.getByRole('alert').getByText('当前服务版本的操作记录较多，完整筛选需要更新后端；可清空筛选查看最近记录。', { exact: true }).waitFor()
    }
    assert.deepEqual(errors, [])
    console.log('PASS', scenario, counts)
    await context.close()
  }
} finally { await browser.close(); if (server) await server.close() }
