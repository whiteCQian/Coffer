// Screenshot and interaction review of actual Web pages with isolated sample data.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const baseline = process.argv.includes('--baseline')
const root = fileURLToPath(new URL('..', import.meta.url))
const artifacts = fileURLToPath(new URL('../.test-tmp/web-ui/', import.meta.url))
await mkdir(artifacts, { recursive: true })
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5177, strictPort: true } })
await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
const page = await context.newPage(), errors = [], requests = []
page.on('pageerror', error => errors.push(error.message))
let user = { id: 1, username: 'lin', role: 'USER', enabled: true }, confirmed = false
const files = () => [
  { id: 1, fileName: '采购合同.docx', fileType: 'docx', fileSize: 1258291, summary: '设备采购合同，包含交付安排、付款条件与售后服务条款。', status: 'COMPLETED', tagStatus: confirmed ? 'CONFIRMED' : 'PENDING_CONFIRMATION', tags: [{ tagId: 11, tagName: '合同', confirmationStatus: confirmed ? 'CONFIRMED' : 'PENDING_CONFIRMATION' }], uploadTime: '2026-10-10T10:15:00', archived: true },
  { id: 2, fileName: '毕业设计.docx', fileType: 'docx', fileSize: 876544, summary: '智能文件管理系统的需求分析、架构设计与实现记录。', status: 'COMPLETED', tags: [{ tagId: 12, tagName: '论文', confirmationStatus: 'CONFIRMED' }], uploadTime: '2026-10-09T12:20:00', archived: true },
  { id: 3, fileName: '费用清单.xlsx', fileType: 'xlsx', fileSize: 153600, summary: '本月办公费用、采购明细和报销记录。', status: 'COMPLETED', tags: [{ tagId: 13, tagName: '票据', confirmationStatus: 'CONFIRMED' }], uploadTime: '2026-10-08T09:00:00', archived: true },
  { id: 4, fileName: '项目需求.txt', fileType: 'txt', fileSize: 23040, summary: null, status: 'PROCESSING', tags: [], uploadTime: '2026-10-07T10:00:00', archived: false },
]
const modeStatus = { activeMode: 'API', currentModeValidated: true, validatedModes: { API: true, LOCAL: false }, validatedAt: { API: '2026-10-10T10:00:00', LOCAL: null }, validationSuccess: true, capabilities: { chat: 'OK', vision: 'OK', embedding: 'DISABLED' } }
await page.route('http://127.0.0.1:5177/api/**', async route => {
  const url = new URL(route.request().url()), path = url.pathname.slice(4), method = route.request().method()
  requests.push({ path, method })
  let data = []
  if (path === '/auth/csrf') data = 'fixture-csrf'
  else if (path === '/auth/status') data = { setupRequired: false, setupAvailable: false, desktopPersonal: false, passwordMinLength: 8, strongPasswordRequired: false, storageQuotaEnabled: true }
  else if (path === '/auth/me') {
    if (!user) return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, msg: '请先登录', data: null }) })
    data = user
  }
  else if (path === '/auth/logout') { user = null; data = null }
  else if (path === '/auth/login') { user = { id: 1, username: 'lin', role: 'USER', enabled: true }; data = user }
  else if (path === '/runtime/status' || path === '/admin/runtime') data = { checkedAt: '2026-10-11T10:00:00+08:00', readiness: 'UP', components: [], queues: {}, connections: {}, alerts: [] }
  else if (path === '/runtime/key-rotation' || path === '/admin/runtime/key-rotation') data = { keyId: 'fixture-key', remaining: 0, verified: 0, rewritten: 0 }
  else if (path === '/files/tags/candidates') data = [{ name: '合同', fileCount: 1 }, { name: '论文', fileCount: 1 }, { name: '票据', fileCount: 1 }]
  else if (path === '/files/tags/suggest') data = []
  else if (path === '/files/tags/confirm') { confirmed = true; data = null }
  else if (path === '/tasks/overview') data = { processingCount: 1, failedCount: 0, pendingConfirmCount: confirmed ? 0 : 1, processing: [{ taskId: 'fixture-task', fileName: '项目需求.txt', progress: 65 }], failed: [], pendingConfirm: confirmed ? [] : [{ fileId: 1, fileName: '采购合同.docx', uploadTime: '2026-10-10T10:15:00' }] }
  else if (path === '/inbox-imports/progress') data = { enabled: false, items: [] }
  else if (path === '/desktop/capabilities') data = { workCopies: false }
  else if (path === '/settings/models') data = { configured: { DEEPSEEK: true } }
  else if (path === '/settings/runtime') data = modeStatus
  else if (path === '/settings/runtime/config') data = ['API', 'LOCAL'].flatMap(mode => ['CHAT', 'VISION', 'EMBEDDING'].map(capability => ({ mode, capability, baseUrl: mode === 'API' ? 'https://api.example.com/v1' : 'http://localhost:1234', modelName: capability === 'CHAT' ? 'chat-model' : capability === 'VISION' ? 'vision-model' : 'embedding-model', configured: true, maskedApiKey: '••••abcd', source: 'CUSTOM', credentialProvider: 'DEEPSEEK', embeddingEnabled: false })))
  else if (path === '/model-execution/inbox') data = null
  else if (path === '/model-diagnostics') data = { content: [], totalPages: 0 }
  else if (path === '/privacy') data = { conversations: 0, pendingVectors: 0, pendingMemories: 0 }
  else if (path === '/storage/usage') data = { usedBytes: 2259475, limitBytes: 1073741824, objectCount: 4, limitObjects: 10000, pendingTaskLimit: 20, concurrentTaskLimit: 2 }
  else if (path === '/model-execution/target') data = { configurationVersion: 'fixture-model', mode: 'API', targets: [{ capability: 'CHAT', baseUrl: 'https://api.example.com/v1', modelName: 'fixture-model' }] }
  else if (path === '/chat/send') data = { sessionId: 'fixture-chat', reply: '找到了相关采购合同，付款条件见文件原文。', citations: [{ fileId: 1, fileName: '采购合同.docx', revision: 1, fileType: 'docx', sourceKind: 'PARAGRAPH', sourceStart: 1, snippet: '交付设备后支付余款。', retrievalType: 'KEYWORD', relevanceScore: 1 }] }
  else if (path === '/files/upload') data = { taskId: 'fixture-upload', fileId: 5 }
  else if (path === '/files' || path === '/files/search') {
    const keyword = url.searchParams.get('keyword'), tag = url.searchParams.get('tag')
    const matches = files().filter(file => (!keyword || file.fileName.includes(keyword)) && (!tag || file.tags.some(t => t.tagName === tag)))
    data = { content: matches.slice(0, Number(url.searchParams.get('size') ?? 12)), totalElements: matches.length, totalPages: matches.length ? 1 : 0, number: 0, size: 12 }
  } else if (/^\/files\/\d+$/.test(path)) {
    const file = files().find(file => file.id === Number(path.split('/').pop()))
    data = { ...file, revision: 1, previewUrl: null, confirmedTags: file.tags.filter(t => t.confirmationStatus === 'CONFIRMED'), pendingTags: file.tags.filter(t => t.confirmationStatus === 'PENDING_CONFIRMATION') }
  } else if (path === '/governance/operations' || path === '/governance/operations/items') data = { content: [], totalElements: 0, totalPages: 0 }
  else if (path === '/admin/users') data = [user]
  else if (path === '/admin/operations') data = { pendingTasks: 0, failedTasks: 0 }
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
})
async function capture(name, path, width = 1440, height = 960) {
  await page.setViewportSize({ width, height })
  await page.goto('http://127.0.0.1:5177' + path)
  await page.locator('.page-viewport').waitFor()
  await page.waitForTimeout(500)
  await page.screenshot({ path: artifacts + (baseline ? 'before-' : 'after-') + name + '.png', animations: 'disabled' })
}
try {
  await capture('home', '/')
  await capture('files', '/files')
  await page.locator('.quota-panel').getByText(/存储已用/).waitFor()
  await page.locator('.quota-panel').screenshot({ path: artifacts + 'quota-light.png' })
  await capture('search', '/search')
  await capture('settings', '/settings')
  await capture('operations', '/operations')
  await capture('mobile-home', '/', 390, 844)
  if (!baseline) {
    const fit = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'page must fit browser width')
    await fit()
    await page.getByLabel('打开导航', { exact: true }).click()
    await page.locator('.web-nav').getByRole('button', { name: '全部文件', exact: true }).click()
    await page.locator('.file-table tbody tr').first().waitFor()
    await page.locator('.icon-rail').waitFor({ state: 'hidden' })
    await fit()
    await page.locator('.file-table tbody tr').first().click()
    await page.locator('.is-inline .drawer-name').waitFor()
    const inspector = await page.locator('.drawer-wrap.is-inline').boundingBox()
    assert.ok(inspector.width > 300 && inspector.x >= 0)
    await page.screenshot({ path: artifacts + 'after-mobile-detail.png', animations: 'disabled' })
    await page.locator('.is-inline .head-x').click()
    await page.setViewportSize({ width: 1440, height: 960 })
    await page.locator('.web-global-search input').fill('毕业设计')
    await page.locator('.web-global-search input').press('Enter')
    await page.waitForURL(/keyword=/)
    await page.waitForTimeout(400)
    assert.equal(await page.locator('.file-table tbody tr').count(), 1)
    await page.locator('.file-table tbody tr').first().click()
    await page.locator('.drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
    await page.locator('.web-nav').getByRole('button', { name: '全部文件', exact: true }).click()
    await page.waitForTimeout(400)
    await page.getByLabel('网格视图', { exact: true }).click()
    assert.equal(await page.locator('.desk-file-card').count(), 4)
    await page.reload()
    await page.locator('.desk-file-card').first().waitFor()
    assert.equal(await page.getByLabel('网格视图', { exact: true }).getAttribute('aria-pressed'), 'true')
    await page.getByLabel('列表视图', { exact: true }).click()
    await page.getByLabel('选择本页全部文件').check()
    await page.locator('.selection-toolbar').getByText('已选择 4 个文件').waitFor()
    await page.getByRole('button', { name: '取消选择', exact: true }).click()
    await page.locator('.web-tags').getByRole('button', { name: '合同 1', exact: true }).click()
    await page.waitForTimeout(400)
    assert.equal(await page.locator('.file-table tbody tr').count(), 1)
    await page.locator('.file-table tbody tr').first().click()
    await page.getByTitle('确认标签', { exact: true }).click()
    await page.locator('.is-inline .pending-block').waitFor({ state: 'hidden' })
    assert.equal(confirmed, true)
    await page.locator('.web-nav').getByRole('button', { name: '工作台', exact: true }).click()
    await page.getByRole('button', { name: '查看 毕业设计.docx', exact: true }).click()
    await page.locator('.drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
    await page.locator('.web-nav').getByRole('button', { name: '工作台', exact: true }).click()
    await page.locator('.upload-zone input[type="file"]').setInputFiles({ name: 'isolated-ui-sample.txt', mimeType: 'text/plain', buffer: Buffer.from('Isolated Web upload test.') })
    await page.getByRole('button', { name: '同意并提交', exact: true }).click()
    await page.waitForURL('**/files')
    assert.ok(requests.some(request => request.path === '/files/upload' && request.method === 'POST'))
    assert.equal(await page.locator('.desktop-import').count(), 0)
    await page.locator('.web-nav').getByRole('button', { name: '工作台', exact: true }).click()
    await page.locator('.chat-input-field').fill('找一下采购合同')
    await page.locator('.chat-send').click()
    await page.getByRole('button', { name: '同意并提交', exact: true }).click()
    await page.locator('.citation-card').waitFor()
    await page.locator('.el-message-box__wrapper').waitFor({ state: 'hidden' })
    await page.locator('.el-message').first().waitFor({ state: 'hidden' })
    await page.screenshot({ path: artifacts + 'after-home-ai.png', animations: 'disabled' })
    await page.locator('.citation-card').click()
    await page.locator('.drawer-wrap:not(.is-inline) .drawer-name').waitFor()
    await page.locator('.drawer-wrap:not(.is-inline) .head-x').click()
    await page.locator('.web-nav').getByRole('button', { name: 'AI 搜索', exact: true }).click()
    await page.locator('.search-controls .field-input').first().fill('毕业设计')
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await page.locator('.result-row').waitFor()
    await page.locator('.result-row').press('Enter')
    await page.locator('.drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
    await page.locator('.is-inline .head-x').click()
    await page.locator('.web-nav').getByRole('button', { name: '设置', exact: true }).click()
    await page.getByRole('tab', { name: '账号与安全', exact: true }).click()
    await page.locator('.account-panel').waitFor()
    assert.equal(await page.locator('.config-section').isVisible(), false)
    await page.getByRole('tab', { name: '数据与隐私', exact: true }).click()
    await page.getByRole('heading', { name: 'AI 内容发送与诊断', exact: true }).waitFor()
    await page.getByRole('tab', { name: '关于', exact: true }).click()
    await page.getByRole('heading', { name: '关于 Coffer', exact: true }).waitFor()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('tab', { name: '模型连接', exact: true }).click()
    await fit()
    await page.screenshot({ path: artifacts + 'after-mobile-settings.png', animations: 'disabled' })
    await page.setViewportSize({ width: 1440, height: 960 })
    await page.getByLabel('切换到深色主题', { exact: true }).click()
    await capture('dark-home', '/')
    assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).filter), 'none')
    assert.equal(await page.locator('.icon-rail').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(34, 38, 39)')
    assert.equal(await page.locator('.web-shell').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(27, 30, 32)')
    await capture('dark-files', '/files')
    await page.locator('.quota-panel').screenshot({ path: artifacts + 'quota-dark.png' })
    await page.getByLabel('切换到亮色主题', { exact: true }).click()
    await capture('tablet-home', '/', 1024, 768)
    await fit()
    await page.setViewportSize({ width: 1440, height: 960 })
    user = { id: 3, username: 'admin', role: 'ADMIN', enabled: true }
    const adminStart = requests.length
    await capture('admin', '/admin')
    const creation = page.locator('.create-grid')
    await creation.locator('input[autocomplete="off"]').fill('numeric-fixture')
    await creation.locator('input[autocomplete="new-password"]').fill('12345678')
    const [creationRequest] = await Promise.all([
      page.waitForRequest(request => new URL(request.url()).pathname === '/api/admin/users' && request.method() === 'POST'),
      creation.getByRole('button', { name: '创建用户', exact: true }).click(),
    ])
    assert.equal(creationRequest.postDataJSON().password, '12345678')
    assert.equal(await page.locator('.web-global-search').count(), 0)
    assert.equal(await page.locator('.web-tags').count(), 0)
    assert.ok(!requests.slice(adminStart).some(request => /^\/(files|tasks|chat|settings|model|privacy)/.test(request.path)), 'admin UI must not load user file APIs')
    user = null
    await page.goto('http://127.0.0.1:5177/login')
    await page.getByRole('heading', { name: '登录 Coffer', exact: true }).waitFor()
    const passwordField = page.locator('input[autocomplete="current-password"]')
    assert.equal(await passwordField.getAttribute('minlength'), '8')
    assert.equal(await passwordField.getAttribute('maxlength'), '16')
    await page.getByText('8–16 个字符', { exact: true }).waitFor()
    await page.screenshot({ path: artifacts + 'after-login.png', animations: 'disabled' })
    await page.locator('input[autocomplete="username"]').fill('lin')
    await passwordField.fill('Fx8!mP2')
    const loginRequests = requests.filter(request => request.path === '/auth/login').length
    await page.getByRole('button', { name: '登录', exact: true }).click()
    assert.equal(requests.filter(request => request.path === '/auth/login').length, loginRequests)
    await passwordField.fill('12345678')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.locator('.icon-rail').waitFor()
    assert.equal(requests.filter(request => request.path === '/auth/login').length, loginRequests + 1)
    assert.deepEqual(errors, [])
    console.log('PASS: Web navigation/search, recent file opening, view persistence, bulk selection, tags, upload/model consent, AI citations, settings sections, mobile/tablet sizing, themes and admin isolation.')
  }
  console.log((baseline ? 'Baseline' : 'Updated') + ' Web screenshots: ' + artifacts)
  if (errors.length) console.log('Page errors:', errors)
} finally { await browser.close(); await server.close() }
