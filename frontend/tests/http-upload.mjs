import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const origin = process.env.COFFER_UPLOAD_TEST_ORIGIN || 'http://coffer-upload.test:5187'
const server = process.env.COFFER_UPLOAD_TEST_ORIGIN ? null : await createServer({ root,
  server: { host: '127.0.0.1', port: 5187, strictPort: true, allowedHosts: ['coffer-upload.test'] } })
if (server) await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true,
  args: ['--no-proxy-server', '--host-resolver-rules=MAP coffer-upload.test 127.0.0.1'] })
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
const page = await context.newPage(), errors = [], uploads = []
const file = { id: 7, revision: 1, fileName: '手机上传验证.pdf', fileType: 'pdf', fileSize: 80,
  status: 'PENDING', archived: false, tags: [], confirmedTags: [], pendingTags: [], uploadTime: '2026-10-21T00:00:00' }
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
  else if (path === '/storage/usage' || path === '/privacy/quota') data = { usedBytes: 0, limitBytes: 2147483648, objectCount: 0, limitObjects: 1000 }
  else if (path === '/files' || path === '/files/search') data = { content: [], totalElements: 0, totalPages: 0, size: 12, number: 0 }
  else if (path === '/model-execution/target') data = { configurationVersion: 'fixture-model', mode: 'API', targets: [{ capability: 'CHAT', baseUrl: 'https://fixture.example/v1', modelName: 'fixture' }] }
  else if (path === '/files/upload') {
    const key = route.request().headers()['idempotency-key']
    assert.match(key, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    assert(route.request().postDataBuffer().includes(Buffer.from('%PDF-1.4')))
    uploads.push(key)
    data = { taskId: 'fixture-upload', fileName: file.fileName, fileSize: 80, status: 'PENDING' }
  } else if (path === '/tasks/fixture-upload') data = { taskId: 'fixture-upload', status: 'PENDING', progress: 0 }
  else if (path === '/tasks/fixture-upload/file') data = file
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
})
const pdf = { name: file.fileName, mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% Synthetic upload fixture; no real document or model service.\n%%EOF') }
try {
  await page.goto(origin + '/')
  const capabilities = await page.evaluate(() => ({ secure: isSecureContext, uuid: typeof crypto.randomUUID, random: typeof crypto.getRandomValues }))
  assert.deepEqual(capabilities, { secure: false, uuid: 'undefined', random: 'function' })
  await page.locator('.upload-zone input[type="file"]').setInputFiles(pdf)
  await page.getByRole('button', { name: '同意并提交', exact: true }).click()
  await page.getByText(`「${file.fileName}」已上传，请在下方查看处理结果`, { exact: true }).waitFor()
  assert.equal(await page.locator('.upload-zone .uz-btn').isEnabled(), true)
  assert.equal(uploads.length, 1, 'Home PDF upload must reach the server adapter over HTTP')
  await page.goto(origin + '/files')
  await page.locator('input[type="file"]').first().setInputFiles(pdf)
  await page.getByRole('button', { name: '同意并提交', exact: true }).click()
  await page.waitForFunction(() => document.body.textContent.includes('已保存，可在详情查看整理状态'))
  assert.equal(uploads.length, 2)
  assert.notEqual(uploads[0], uploads[1])

  // An unsupported browser must show an error and release the busy state.
  await page.goto(origin + '/')
  await page.evaluate(() => Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined }))
  await page.locator('.upload-zone input[type="file"]').setInputFiles(pdf)
  await page.getByText('当前浏览器无法生成上传编号，请使用系统浏览器重试', { exact: true }).waitFor()
  assert.equal(await page.locator('.upload-zone .uz-btn').isEnabled(), true)
  assert.equal(await page.locator('.upload-zone .uz-btn').innerText(), '上传文件')
  assert.equal(uploads.length, 2)
  assert.deepEqual(errors, [])
  console.log('PASS: real insecure HTTP origin, mobile PDF upload from home and files, UUID deduplication keys, initialization failure releases upload state; APIs use synthetic fixtures')
} finally { await browser.close(); if (server) await server.close() }
