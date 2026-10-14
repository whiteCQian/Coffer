// The optional vector service must not block or be changed by saving Chat.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5181, strictPort: true } })
await server.listen()
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage()
  const errors = [], writes = []
  page.on('pageerror', error => errors.push(error.message))
  const configs = ['API', 'LOCAL'].flatMap(mode => ['CHAT', 'VISION'].map(capability => ({
    mode, capability, baseUrl: capability === 'EMBEDDING' ? '' : mode === 'API' ? 'https://api.example.com/v1' : 'http://localhost:1234/v1',
    modelName: capability === 'EMBEDDING' ? '' : 'fixture-model',
    configured: capability !== 'EMBEDDING', maskedApiKey: capability === 'EMBEDDING' ? null : '••••fixture',
    source: 'DEFAULT', embeddingEnabled: false, useChatCredentials: false,
  })))
  const status = { activeMode: 'API', currentModeValidated: false, validatedModes: { API: false, LOCAL: false }, validatedAt: {}, capabilities: {} }
  await page.route('http://127.0.0.1:5181/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname.slice(4)
    let data = []
    if (path === '/auth/csrf') data = 'fixture-csrf'
    else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false }
    else if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
    else if (path === '/runtime/status') data = { readiness: 'UP', components: [], alerts: [] }
    else if (path === '/settings/embedding') data = {configured: true, enabled: false, available: false, mode: 'API', modelName: 'admin-vector', dimensions: 1024, message: '语义向量检索由管理员统一配置'}
    else if (path === '/settings/runtime') data = status
    else if (path === '/settings/runtime/config') data = configs.map(({ apiKey, ...masked }) => masked)
    else if (request.method() === 'PUT' && path.startsWith('/settings/runtime/config/')) {
      const body = request.postDataJSON()
      writes.push(body)
      const card = configs.find(config => config.mode === body.mode && config.capability === body.capability)
      Object.assign(card, body, { source: 'CUSTOM', configured: true, maskedApiKey: '••••fixture' })
      // The real endpoint never returns API Key plaintext.
      const { apiKey, ...masked } = card
      data = masked
    }
    else if (path === '/privacy') data = { conversations: 0, pendingVectors: 0, pendingMemories: 0 }
    else if (path === '/model-diagnostics') data = { content: [], totalPages: 0 }
    else if (path === '/model-execution/inbox') data = null
    else if (path === '/storage/usage') data = { usedBytes: 0, limitBytes: 1073741824, objectCount: 0, limitObjects: 1000 }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
  })
  await page.goto('http://127.0.0.1:5181/settings')
  await page.getByRole('button', { name: '保存回答模型', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '测试 Embedding', exact: true }).count(), 0)
  await page.getByRole('button', { name: '保存回答模型', exact: true }).click()
  await page.getByText('Chat 配置已保存，运行模式需要重新校验', { exact: true }).waitFor()
  assert.deepEqual(writes.map(write => [write.mode, write.capability]), [['API', 'CHAT']])

  assert.equal(await page.getByLabel('Embedding API Key', { exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '配置向量模型', exact: true }).count(), 0)
  await page.locator('.mode-tab').filter({ hasText: '本地模式' }).click()
  assert.equal(await page.getByRole('button', { name: '测试 Embedding', exact: true }).count(), 0)
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/api/settings/runtime') && response.request().method() === 'GET'),
    page.getByRole('button', { name: '保存回答模型', exact: true }).click(),
  ])
  assert.deepEqual(writes.map(write => [write.mode, write.capability]), [['API', 'CHAT'], ['LOCAL', 'CHAT']])
  assert.deepEqual(errors, [])
  console.log('PASS: owner Chat saves in API/LOCAL without vector settings; no owner Embedding editor or test button; no external model calls.')
} finally {
  if (browser) await browser.close()
  await server.close()
}
