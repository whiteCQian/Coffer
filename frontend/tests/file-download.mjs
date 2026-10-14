import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir, readFile } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const name = '下载验证-0001.txt', bytes = Buffer.from('这是原始下载验证文件。\n第二行内容保持不变。\n', 'utf8')
const downloads = [], errors = []
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5185, strictPort: true, watch: { ignored: ['**/.test-tmp/**'] } }, plugins: [{
  name: 'native-download-fixture',
  configureServer(vite) {
    vite.middlewares.use((request, response, next) => {
      const url = new URL(request.url, 'http://127.0.0.1:5185')
      if (url.pathname !== '/api/files/7/content') return next()
      downloads.push({ download: url.searchParams.get('download'), revision: url.searchParams.get('revision') })
      response.setHeader('Content-Type', 'text/plain; charset=utf-8')
      response.setHeader('Content-Disposition', "attachment; filename*=UTF-8''" + encodeURIComponent(name))
      response.setHeader('Content-Length', bytes.length)
      response.end(bytes)
    })
  },
}] })
await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
const file = { id: 7, fileName: name, fileType: 'txt', fileSize: bytes.length, status: 'COMPLETED',
  summary: '虚构的下载测试文件', archived: true, tags: [], uploadTime: '2026-10-13T00:00:00' }
page.on('pageerror', error => errors.push(error.message))
await page.route('http://127.0.0.1:5185/api/**', async route => {
  const url = new URL(route.request().url()), path = url.pathname.slice(4)
  if (path === '/files/7/content') {
    return route.continue()
  }
  let data = []
  if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
  else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
  else if (path === '/auth/csrf') data = 'fixture'
  else if (path === '/tasks/overview') data = { processingCount: 0, failedCount: 0, pendingConfirmCount: 0, processing: [], failed: [], pendingConfirm: [] }
  else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
  else if (path === '/settings/runtime') data = { activeMode: 'API', currentModeValidated: true }
  else if (path === '/desktop/capabilities') data = { workCopies: false }
  else if (path === '/inbox-imports/progress') data = { enabled: false, items: [] }
  else if (path === '/privacy/quota') data = { usedBytes: bytes.length, limitBytes: 2147483648, objectCount: 1, limitObjects: 1000 }
  else if (path === '/files' || path === '/files/search') data = { content: [file], totalElements: 1, totalPages: 1, size: 12, number: 0 }
  else if (path === '/files/7') data = { ...file, revision: 3, previewUrl: null, confirmedTags: [], pendingTags: [] }
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
})
async function download(link, label) {
  const pending = page.waitForEvent('download')
  await link.click()
  const saved = await pending
  assert.equal(saved.suggestedFilename(), name)
  await saved.saveAs(root + '/.test-tmp/download-' + label + '.txt')
  assert.deepEqual(await readFile(root + '/.test-tmp/download-' + label + '.txt'), bytes)
  assert.equal(await saved.failure(), null)
}
try {
  await mkdir(root + '/.test-tmp', { recursive: true })
  await page.goto('http://127.0.0.1:5185/files')
  await download(page.locator('.file-browser').getByRole('link', { name: '下载 ' + name }), 'list')
  assert.equal(await page.locator('.drawer-name').count(), 0, 'Download should not open the detail drawer')
  await page.locator('.file-table tbody tr').first().click()
  assert.equal(await page.getByRole('button', { name: '授权聊天搜索', exact: true }).count(), 0)
  await download(page.locator('.drawer-actions').getByRole('link', { name: '下载到本地' }), 'detail')
  assert.deepEqual(downloads[1], { download: 'true', revision: '3' })
  await page.screenshot({ path: root + '/.test-tmp/file-download-detail.png', fullPage: true })
  await page.locator('.file-table tbody tr').first().click({ button: 'right' })
  await download(page.locator('.file-context-menu').getByRole('link', { name: '下载到本地' }), 'context')
  await page.goto('http://127.0.0.1:5185/search')
  await page.locator('.field-input').first().fill('下载验证')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await download(page.locator('.result-row').getByRole('link', { name: '下载 ' + name }), 'search')
  assert.equal(downloads.length, 4)
  assert(downloads.every(item => item.download === 'true'))
  assert.deepEqual(errors, [])
  console.log('PASS: list, detail, context menu and search download original bytes with Chinese filename; detail download pins the visible revision')
} finally { await browser.close(); await server.close() }
