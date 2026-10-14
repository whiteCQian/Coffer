import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5184, strictPort: true } })
await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
const errors = [], updates = []
page.on('pageerror', error => errors.push(error.message))
let preview = { previewId: 'short-counter', status: 'READY', source: 'REANALYZE', runMode: 'API',
  totalCount: 1, readyCount: 1, failedCount: 0, createdAt: '2026-10-13T00:00:00', items: [{
    id: 1, previewId: 'short-counter', fileId: 1, sourceRevision: 0,
    sourceFileName: '订单123456789.pdf', sourcePath: 'users/1/files/source.pdf',
    suggestedFileName: '订单123456789-0001.pdf', suggestedPath: 'users/1/archive/contracts/2026/10/13/订单123456789-0001.pdf',
    suggestedCategory: 'CONTRACT', suggestedTags: ['订单', '采购'], suggestedSummary: '虚构订单摘要', status: 'READY', sourceSize: 20,
  }] }
await page.route('http://127.0.0.1:5184/api/**', async route => {
  const path = new URL(route.request().url()).pathname.slice(4), method = route.request().method()
  let data = []
  if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
  else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
  else if (path === '/auth/csrf') data = 'fixture'
  else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
  else if (path === '/desktop/capabilities') data = { workCopies: false }
  else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
  else if (path === '/tasks/overview') data = { processingCount: 0, failedCount: 0, pendingConfirmCount: 0, processing: [], failed: [], pendingConfirm: [] }
  else if (path === '/inbox-imports/progress') data = { enabled: false, items: [] }
  else if (path === '/governance/previews/short-counter') data = preview
  else if (path === '/governance/previews/short-counter/items/1' && method === 'PATCH') {
    const body = route.request().postDataJSON(); updates.push(body)
    const name = body.suggestedFileName.replace(/\.pdf$/, '') + '-0001.pdf'
    preview = { ...preview, items: [{ ...preview.items[0], ...body, suggestedFileName: name,
      suggestedPath: 'users/1/archive/contracts/2026/10/13/' + name, status: 'EDITED' }] }
    data = preview
  }
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
})
try {
  await page.goto('http://127.0.0.1:5184/governance/previews/short-counter')
  const input = page.locator('.suggestion-grid label').filter({ hasText: '建议文件名' }).locator('input')
  await input.waitFor()
  assert.equal(await input.inputValue(), '订单123456789.pdf')
  await page.getByText(/最终名称：订单123456789-0001.pdf/).waitFor()
  await input.fill('采购订单123456789.pdf')
  await page.getByRole('button', { name: '保存建议', exact: true }).click()
  await page.getByText(/最终名称：采购订单123456789-0001.pdf/).waitFor()
  assert.equal(updates.length, 1)
  assert.equal(updates[0].suggestedFileName, '采购订单123456789.pdf')
  assert.equal(await input.inputValue(), '采购订单123456789.pdf')
  assert.deepEqual(errors, [])
  await mkdir(root + '/.test-tmp', { recursive: true })
  await page.screenshot({ path: root + '/.test-tmp/short-counter.png', fullPage: true })
  console.log('PASS: editable business name preserves long order number; final name shows four-character counter; editing does not stack counters')
} finally { await browser.close(); await server.close() }
