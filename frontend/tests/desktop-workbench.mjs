// Real Vue UI with isolated API/desktop fixtures. Never imports or changes the user's files.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const artifacts = fileURLToPath(new URL('../.test-tmp/workbench/', import.meta.url))
await mkdir(artifacts, { recursive: true })
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5176, strictPort: true } })
await server.listen()
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage(), errors = []
page.on('pageerror', error => errors.push(error.message))
let pending = true, failFiles = false, emptyLibrary = false, personal = true, delayedDetail = null, releaseDetail
const requests = []
const files = () => [
  { id: 1, fileName: '采购合同.docx', fileType: 'docx', fileSize: 1258291, summary: '采购设备合同，包含交付安排、付款条件与售后服务条款。', tagStatus: pending ? 'PENDING_CONFIRMATION' : 'CONFIRMED', status: 'COMPLETED', tags: [{ tagId: 11, tagName: '合同', confirmationStatus: pending ? 'PENDING_CONFIRMATION' : 'CONFIRMED' }], uploadTime: '2026-10-10T10:15:00', archived: true },
  { id: 2, fileName: '毕业设计.docx', fileType: 'docx', fileSize: 876544, summary: '智能文件管理系统的需求分析、架构设计与实现记录。', status: 'COMPLETED', tags: [{ tagId: 12, tagName: '论文', confirmationStatus: 'CONFIRMED' }], uploadTime: '2026-10-09T12:20:00', archived: true },
  { id: 3, fileName: '费用清单.xlsx', fileType: 'xlsx', fileSize: 153600, summary: '本月办公费用、采购明细和报销记录。', status: 'COMPLETED', tags: [{ tagId: 13, tagName: '票据', confirmationStatus: 'CONFIRMED' }], uploadTime: '2026-10-08T09:00:00', archived: true },
  { id: 4, fileName: '项目需求.txt', fileType: 'txt', fileSize: 23040, summary: null, status: 'PROCESSING', tags: [], uploadTime: '2026-10-07T10:00:00', archived: false },
]
await context.addInitScript(() => {
  window.cofferDesktop = {
    selectFiles: async () => [{ id: 'fixture-selection', name: '导入示例.txt', size: 64, targetPath: 'users/1/inbox/导入示例.txt', sha256: 'fixture-sha' }],
    commitInbox: async () => [{ name: '导入示例.txt', targetPath: 'users/1/inbox/导入示例.txt', ok: true }],
  }
})
await page.route('http://127.0.0.1:5176/api/**', async route => {
  const url = new URL(route.request().url()), path = url.pathname.slice(4), method = route.request().method()
  requests.push({ path, method, body: route.request().postDataJSON() })
  let data = []
  if (path === '/auth/csrf') data = 'fixture-csrf'
  else if (path === '/auth/status') data = { setupRequired: false, setupAvailable: false, desktopPersonal: personal }
  else if (path === '/auth/me') data = { id: 1, username: 'fixture', role: 'USER', enabled: true }
  else if (path === '/runtime/status') data = { readiness: 'UP', components: [], queues: {} }
  else if (path === '/files/tags/candidates') data = [{ name: '合同', fileCount: 1 }, { name: '论文', fileCount: 1 }, { name: '票据', fileCount: 1 }]
  else if (path === '/files/tags/suggest') data = []
  else if (path === '/files/tags/confirm') { assert.deepEqual(route.request().postDataJSON(), { fileId: 1, tagId: 11 }); pending = false; data = null }
  else if (path === '/tasks/overview') data = { processingCount: emptyLibrary ? 0 : 1, failedCount: 0, pendingConfirmCount: pending ? 1 : 0, processing: emptyLibrary ? [] : [{ taskId: 'fixture-task', fileName: '项目需求.txt', progress: 65 }], failed: [], pendingConfirm: pending ? [{ fileId: 1, fileName: '采购合同.docx', uploadTime: '2026-10-10T10:15:00' }] : [] }
  else if (path === '/inbox-imports/progress') data = { enabled: true, totalCount: 0, importedCount: 0, duplicateCount: 0, discoveredCount: 0, stableCount: 0, failedCount: 0, manualReviewCount: 0, awaitingConfirmationCount: 0, requiresPathConfirmation: true, items: [] }
  else if (path === '/desktop/capabilities') data = { workCopies: true }
  else if (path === '/desktop/work-copies') data = []
  else if (path === '/settings/models') data = { configured: { DEEPSEEK: true } }
  else if (path === '/model-execution/target') data = { configurationVersion: 'fixture-model', mode: 'LOCAL', targets: [{ capability: 'CHAT', baseUrl: 'http://localhost:1234', modelName: 'fixture-model' }] }
  else if (path === '/chat/send') data = { sessionId: 'fixture-chat', reply: '找到了相关采购合同，付款条款见文件原文。', citations: [{ fileId: 1, fileName: '采购合同.docx', revision: 1, fileType: 'docx', sourceKind: 'PARAGRAPH', sourceStart: 1, snippet: '交付设备后支付余款。', retrievalType: 'KEYWORD', relevanceScore: 1 }] }
  else if (path === '/files' || path === '/files/search') {
    if (failFiles) return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, msg: 'fixture unavailable' }) })
    const keyword = url.searchParams.get('keyword'), tag = url.searchParams.get('tag')
    const content = emptyLibrary ? [] : files().filter(file => (!keyword || file.fileName.includes(keyword)) && (!tag || file.tags.some(t => t.tagName === tag)))
    data = { content, totalElements: content.length, totalPages: content.length ? 1 : 0, number: 0, size: 12 }
  } else if (/^\/files\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop()), file = files().find(file => file.id === id)
    if (delayedDetail === id) { delayedDetail = null; await new Promise(resolve => { releaseDetail = resolve }) }
    data = { ...file, revision: 1, previewUrl: null, confirmedTags: file.tags.filter(t => t.confirmationStatus === 'CONFIRMED'), pendingTags: file.tags.filter(t => t.confirmationStatus === 'PENDING_CONFIRMATION') }
  }
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
})
const waitFor = async condition => { const until = Date.now() + 10000; while (Date.now() < until) { if (await condition()) return; await new Promise(resolve => setTimeout(resolve, 50)) } throw new Error('UI condition timed out') }
async function screenshot(name) {
  await page.locator('.el-message-box__wrapper').first().waitFor({ state: 'hidden' })
  await page.locator('.el-message').first().waitFor({ state: 'hidden' })
  await page.screenshot({ path: artifacts + name, animations: 'disabled' })
}
try {
  await page.goto('http://127.0.0.1:5176/')
  await page.locator('.file-table tbody tr').first().waitFor()
  assert.equal(await page.locator('.desktop-sidebar').count(), 1)
  assert.equal(await page.locator('.file-table tbody tr').count(), 4)
  await page.keyboard.press('Control+k')
  assert.equal(await page.getByRole('searchbox').evaluate(el => el === document.activeElement), true)
  await page.locator('.file-table tbody tr').first().click()
  await page.locator('.is-inline .drawer-name').getByText('采购合同.docx', { exact: true }).waitFor()
  assert.equal(await page.locator('.is-inline .drawer-mask').count(), 0)
  assert.equal(await page.locator('.desktop-brand .coffer-logo').evaluate(el => el.complete && el.naturalWidth > 0), true)
  const leftHandle = page.getByRole('separator', { name: '调整主区域左边界' })
  const rightHandle = page.getByRole('separator', { name: '调整主区域右边界' })
  const paneWidth = locator => locator.evaluate(el => el.getBoundingClientRect().width)
  const drag = async (locator, delta) => {
    const box = await locator.boundingBox()
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width / 2 + delta, box.y + box.height / 2, { steps: 8 })
    await page.mouse.up()
  }
  const initialMain = await paneWidth(page.locator('.file-browser'))
  await drag(leftHandle, 60)
  assert.equal(await paneWidth(page.locator('.desktop-sidebar')), 280)
  assert.ok(Math.abs(await paneWidth(page.locator('.file-browser')) - initialMain + 60) < 2)
  await drag(rightHandle, -80)
  assert.equal(await paneWidth(page.locator('.drawer-wrap.is-inline')), 420)
  assert.ok(Math.abs(await paneWidth(page.locator('.file-browser')) - initialMain + 140) < 2)
  assert.equal(await page.evaluate(() => document.body.style.cursor), '')
  await page.reload()
  await page.locator('.file-table tbody tr').first().click()
  await page.locator('.is-inline .drawer-name').getByText('采购合同.docx', { exact: true }).waitFor()
  assert.equal(await paneWidth(page.locator('.desktop-sidebar')), 280)
  assert.equal(await paneWidth(page.locator('.drawer-wrap.is-inline')), 420)
  await leftHandle.focus(); await page.keyboard.press('ArrowLeft')
  assert.equal(await paneWidth(page.locator('.desktop-sidebar')), 270)
  await leftHandle.dblclick()
  await rightHandle.focus(); await page.keyboard.press('Enter')
  assert.equal(await paneWidth(page.locator('.desktop-sidebar')), 220)
  assert.equal(await paneWidth(page.locator('.drawer-wrap.is-inline')), 340)
  assert.equal(await page.locator('.workbench-status').evaluate(el => getComputedStyle(el).fontSize), '11px')
  await screenshot('desktop-light.png')
  await page.getByLabel('网格视图', { exact: true }).click()
  assert.equal(await page.locator('.desk-file-card').count(), 4)
  await page.getByLabel('列表视图', { exact: true }).click()
  await page.locator('.sidebar-tags').getByRole('link', { name: '论文 1' }).click()
  await waitFor(async () => await page.locator('.file-table tbody tr').count() === 1)
  assert.match(page.url(), /tag=/)
  await page.getByLabel('主导航').getByRole('link', { name: '全部文件' }).click()
  await waitFor(async () => await page.locator('.file-table tbody tr').count() === 4)
  await page.getByLabel('选择本页全部文件').check()
  assert.equal(await page.locator('.selection-toolbar').getByText('已选择 4 个文件').count(), 1)
  await page.getByRole('button', { name: '取消选择', exact: true }).click()
  await page.locator('.file-table tbody tr').first().click({ button: 'right' })
  await page.locator('.file-context-menu').getByRole('button', { name: '查看文件详情' }).click()
  await page.getByLabel('主导航').getByRole('link', { name: '待确认 1', exact: true }).click()
  await page.getByRole('button', { name: '去确认标签', exact: true }).click()
  await page.getByTitle('确认标签', { exact: true }).click()
  await waitFor(() => !pending)
  await waitFor(async () => await page.locator('.is-inline .pending-block').count() === 0)
  await page.getByText('标签都已确认', { exact: true }).waitFor()
  await page.getByLabel('主导航').getByRole('link', { name: '全部文件' }).click()
  // Rapid switching must never let an older detail response replace the selected file.
  await page.locator('.file-table tbody tr').nth(1).click()
  await page.locator('.is-inline .drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
  delayedDetail = 1
  await page.locator('.file-table tbody tr').first().click()
  await waitFor(() => !!releaseDetail)
  await page.locator('.file-table tbody tr').nth(1).click()
  await page.locator('.is-inline .drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
  releaseDetail()
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await page.locator('.is-inline .drawer-name').innerText(), '毕业设计.docx')
  await page.getByRole('button', { name: 'AI 助手', exact: true }).click()
  await page.locator('.workbench-ai .chat-input-field').fill('找一下采购合同')
  await page.locator('.workbench-ai .chat-send').click()
  await page.locator('.workbench-ai .citation-card').waitFor()
  assert.equal(await page.getByRole('button', { name: '同意并提交', exact: true }).count(), 0)
  await screenshot('desktop-ai.png')
  await page.locator('.workbench-ai .citation-card').click()
  await page.locator('.workbench-ai .drawer-wrap:not(.is-inline) .drawer-name').waitFor()
  await page.locator('.workbench-ai .drawer-wrap:not(.is-inline) .head-x').click()
  await page.getByLabel('收起 AI 助手', { exact: true }).click()
  await page.getByLabel('切换到深色主题', { exact: true }).click()
  assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).filter), 'none')
  assert.equal(await page.locator('.app-shell').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(21, 28, 35)')
  assert.equal(await page.locator('.desktop-sidebar').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(25, 34, 43)')
  assert.equal(await page.locator('.desktop-files').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(29, 39, 49)')
  assert.equal(await page.locator('.coffer-brand .brand-copy strong').evaluate(el => getComputedStyle(el).color), 'rgb(226, 234, 241)')
  await screenshot('desktop-dark.png')
  assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'dark')
  await page.setViewportSize({ width: 1024, height: 720 })
  await drag(leftHandle, 800)
  await drag(rightHandle, -800)
  assert.ok(await paneWidth(page.locator('.file-browser')) >= 319)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.setViewportSize({ width: 900, height: 720 })
  assert.equal(await rightHandle.count(), 0)
  await page.setViewportSize({ width: 1024, height: 720 })
  await leftHandle.dblclick(); await rightHandle.dblclick()
  await screenshot('desktop-compact.png')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.getByLabel('切换到亮色主题', { exact: true }).click()
  assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).filter), 'none')
  assert.equal(await page.locator('.desktop-sidebar').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(241, 244, 246)')
  await page.getByRole('button', { name: '导入文件', exact: true }).click()
  await page.getByRole('button', { name: '确认接管（入库后删除源文件）', exact: true }).waitFor()
  assert.match(await page.locator('.desktop-import').innerText(), /正式入库并校验成功后删除原路径文件/)
  await page.locator('.desktop-import').getByRole('button', { name: '取消', exact: true }).click()
  await page.getByLabel('主导航').getByRole('link', { name: '收件箱', exact: true }).click()
  await page.getByRole('heading', { name: '收件箱', exact: true }).waitFor()
  await page.getByLabel('主导航').getByRole('link', { name: '待确认', exact: true }).click()
  await page.getByText('标签都已确认', { exact: true }).waitFor()
  await page.getByLabel('主导航').getByRole('link', { name: 'AI 搜索', exact: true }).click()
  await page.locator('.search-ai .chat-card').waitFor()
  await page.locator('.search-controls .field-input').first().fill('毕业设计')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await page.locator('.result-row').waitFor()
  await page.locator('.result-row').click()
  await page.locator('.is-inline .drawer-name').getByText('毕业设计.docx', { exact: true }).waitFor()
  await page.getByLabel('主导航').getByRole('link', { name: '全部文件' }).click()
  failFiles = true
  await page.getByLabel('刷新文件库').click()
  await page.getByText('文件库暂时无法加载', { exact: true }).waitFor()
  failFiles = false; emptyLibrary = true
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await page.getByText('你的文件库，从这里开始', { exact: true }).waitFor()
  await page.reload()
  await page.getByText('当前没有正在解析的文件', { exact: true }).waitFor()
  await screenshot('desktop-empty.png')
  personal = false; emptyLibrary = false
  await page.goto('http://127.0.0.1:5176/files')
  await page.locator('.web-files .file-table tbody tr').first().waitFor()
  assert.equal(await page.locator('.icon-rail').count(), 1)
  assert.equal(await page.getByRole('separator').count(), 0)
  assert.equal(await page.evaluate(() => document.documentElement.dataset.desktop), 'false')
  assert.deepEqual(errors, [])
  console.log('PASS: desktop navigation, views, tag filter/confirmation, selection, context menu, stale detail, AI citations, themes, sizing, import confirmation, queues, search, errors/empty states and Web layout.')
  console.log('Screenshots: ' + artifacts)
} catch (error) {
  console.error('UI errors:', errors)
  console.error('Requests:', requests.slice(-12))
  console.error('Page:', (await page.locator('body').innerText()).slice(0, 2200))
  await page.screenshot({ path: artifacts + 'failure.png' })
  throw error
} finally {
  releaseDetail?.()
  await browser.close()
  await server.close()
}
