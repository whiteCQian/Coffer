import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5182, strictPort: true } })
await server.listen()
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage({ viewport: { width: 1366, height: 1000 } })
  const errors = [], requests = []
  page.on('pageerror', error => errors.push(error.message))
  let setting = { mode: 'API', baseUrl: '', modelName: '', dimensions: 1024, configured: false, maskedApiKey: '', enabled: false, validatedAt: null, configurationVersion: 'empty' }
  await page.route('http://127.0.0.1:5182/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname.slice(4)
    requests.push({ path, method: req.method() })
    let data = []
    if (path === '/auth/csrf') data = 'fixture-csrf'
    else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
    else if (path === '/auth/me') data = { id: 1, username: 'admin-fixture', role: 'ADMIN', enabled: true }
    else if (path === '/admin/embedding') {
      if (req.method() === 'PUT') {
        const { apiKey, ...fields } = req.postDataJSON()
        assert.equal(apiKey, 'synthetic-vector-key')
        setting = { ...setting, ...fields, configured: true, maskedApiKey: '********-key', enabled: false, validatedAt: null }
      }
      data = setting
    } else if (path === '/admin/embedding/test') {
      setting = { ...setting, validatedAt: '2026-10-12T12:00:00' }; data = { success: true, message: 'OK', configuration: setting }
    } else if (path === '/admin/embedding/enabled') { setting = { ...setting, enabled: req.postDataJSON().enabled }; data = setting }
    else if (path === '/admin/runtime') data = { readiness: 'UP', checkedAt: '2026-10-12T12:00:00', components: [], alerts: [], queues: {}, connections: {} }
    else if (path === '/admin/runtime/key-rotation') data = { keyId: 'fixture', remaining: 0, verified: 0, rewritten: 0 }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
  })
  await page.goto('http://127.0.0.1:5182/admin')
  const panel = page.locator('.admin-embedding-panel')
  await panel.getByRole('button', { name: '保存向量服务', exact: true }).waitFor()
  assert.equal(await panel.getByRole('button', { name: '启用语义检索', exact: true }).isDisabled(), true)
  await panel.getByLabel('Embedding Base URL', { exact: true }).fill('https://vector.example.com/v1')
  await panel.getByLabel('Embedding 模型名称', { exact: true }).fill('admin-vector-model')
  await panel.getByLabel('Embedding API Key', { exact: true }).fill('synthetic-vector-key')
  await panel.getByRole('button', { name: '保存向量服务', exact: true }).click()
  await page.getByText('向量服务已保存，请先测试连接，再启用', { exact: true }).waitFor()
  assert.equal(await panel.getByRole('button', { name: '启用语义检索', exact: true }).isDisabled(), true)
  await panel.getByRole('button', { name: '测试向量连接', exact: true }).click()
  await page.getByText('向量连接测试通过，可以启用', { exact: true }).waitFor()
  await panel.getByRole('button', { name: '启用语义检索', exact: true }).click()
  await panel.getByRole('button', { name: '停用语义检索', exact: true }).waitFor()
  assert.equal(await panel.getByLabel(/^Embedding API Key/).inputValue(), '')
  assert.equal((await panel.innerText()).includes('synthetic-vector-key'), false)
  assert.equal(requests.some(req => /^\/(settings|files|chat|tasks|privacy|model-execution)/.test(req.path)), false)
  assert.deepEqual(errors, [])
  await mkdir(root + '/.test-tmp', { recursive: true })
  await page.screenshot({ path: root + '/.test-tmp/admin-embedding.png', animations: 'disabled' })
  console.log('PASS: admin saves/tests/enables shared vectors; enable requires validation; Key input clears and remains masked; no user content APIs or real model calls.')
} finally { if (browser) await browser.close(); await server.close() }
