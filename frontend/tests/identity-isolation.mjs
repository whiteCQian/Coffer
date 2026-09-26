// Browser regression for client state. API fixtures are intentional; backend authorization is covered by Spring integration tests.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
const server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { host: '127.0.0.1', port: 5174, strictPort: true } })
await server.listen()
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
page.on('pageerror', error => console.error('Browser error:', error.message))
let user = null
let delayFiles = false
let releaseFiles
const requests = []
const account = name => ({ id: name === 'alice' ? 1 : name === 'bob' ? 2 : 3, username: name, role: name === 'admin' ? 'ADMIN' : 'USER', enabled: true })
const file = owner => ({ id: owner.id, fileName: `${owner.username}_PRIVATE.txt`, fileType: 'txt', fileSize: 12, uploadTime: '2026-09-26T10:00:00', summary: `${owner.username}_SUMMARY`, status: 'COMPLETED', tags: [], revision: 0 })
await context.route(url => new URL(url).pathname.startsWith('/api/'), async route => {
  const request = route.request(); const path = new URL(request.url()).pathname.slice('/api'.length)
  requests.push({ path, user: user?.username })
  const reply = async (data, status = 200) => { try { await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code: status === 200 ? 0 : status, data, msg: status === 200 ? 'ok' : '登录已失效' }) }) } catch {} }
  if (path === '/auth/csrf') return reply('token')
  if (path === '/auth/status') return reply({ setupRequired: false, setupAvailable: false })
  if (path === '/auth/me') return reply(user, user ? 200 : 401)
  if (path === '/auth/login') { user = account(request.postDataJSON().username); return reply(user) }
  if (path === '/auth/logout') { user = null; return reply(null) }
  if (!user) return reply(null, 401)
  if (path === '/admin/users') return reply([account('alice'), account('bob'), account('admin')])
  if (path === '/admin/audit') return reply([])
  if (path === '/admin/operations') return reply({ pendingTasks: 0, failedTasks: 0 })
  if (path === '/files' || path === '/files/search') {
    const captured = user
    if (delayFiles) { delayFiles = false; await new Promise(resolve => { releaseFiles = resolve }) }
    return reply({ content: [file(captured)], totalElements: 1, totalPages: 1 })
  }
  if (/^\/files\/\d+$/.test(path)) return reply({ ...file(user), previewUrl: null })
  if (path === '/files/tags/candidates' || path === '/files/tags/suggest') return reply([])
  if (path.includes('credential')) return reply({ configured: { DEEPSEEK: true } })
  if (path.includes('overview')) return reply({ processing: [], failed: [], pendingConfirm: [], processingCount: 0, failedCount: 0, pendingConfirmCount: 0 })
  if (path.includes('inbox')) return reply({ enabled: false })
  return reply([])
})
async function signIn(name) {
  await page.locator('input[autocomplete="username"]').fill(name)
  await page.locator('input[autocomplete="current-password"]').fill('fixture-password-123')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(name === 'admin' ? '**/admin' : /\/($|files)/)
}
async function signOut(name) {
  await page.getByTitle(`${name} · 退出登录`, { exact: true }).click()
  await page.waitForURL('**/login')
}
try {
  await page.goto('http://127.0.0.1:5174/files')
  await signIn('alice')
  await page.getByText('alice_PRIVATE.txt', { exact: true }).first().waitFor()
  await page.getByText('alice_PRIVATE.txt', { exact: true }).first().click()
  await page.getByText('alice_SUMMARY', { exact: true }).first().waitFor()
  await signOut('alice')
  await signIn('bob')
  await page.getByTitle('全部文件', { exact: true }).click()
  await page.getByText('bob_PRIVATE.txt', { exact: true }).first().waitFor()
  assert.doesNotMatch(await page.locator('body').innerText(), /alice_PRIVATE|alice_SUMMARY/)
  // A delayed result captured for Bob must not update Alice's new page or show a stale error toast.
  delayFiles = true
  await page.getByTitle('首页', { exact: true }).click()
  await page.waitForFunction(() => true)
  for (let i=0; i<100 && !releaseFiles; i++) await new Promise(resolve => setTimeout(resolve, 20))
  assert.ok(releaseFiles, 'delayed request was dispatched')
  await signOut('bob'); await signIn('alice'); releaseFiles()
  await page.getByTitle('全部文件', { exact: true }).click()
  await page.getByText('alice_PRIVATE.txt', { exact: true }).first().waitFor()
  assert.doesNotMatch(await page.locator('body').innerText(), /bob_PRIVATE|bob_SUMMARY/)
  await signOut('alice')
  const adminStart = requests.length
  await signIn('admin')
  await page.getByRole('heading', { name: '管理控制台' }).waitFor()
  assert.equal(await page.getByTitle('全部文件', { exact: true }).count(), 0)
  assert.equal(await page.getByTitle('搜索', { exact: true }).count(), 0)
  assert.ok(!requests.slice(adminStart).some(r => /^\/(files|chat|settings|model|privacy|tasks)/.test(r.path)), 'admin must not load private APIs')
  await page.goto('http://127.0.0.1:5174/files')
  await page.getByRole('heading', { name: '无权访问此页面' }).waitFor()
  assert.doesNotMatch(await page.locator('body').innerText(), /alice_PRIVATE|bob_PRIVATE/)
  await signOut('admin'); await signIn('bob')
  user = null
  await page.waitForURL('**/login?reason=expired')
  assert.doesNotMatch(await page.locator('body').innerText(), /(?:alice|bob)_PRIVATE|(?:alice|bob)_SUMMARY/)
  console.log('PASS: same browser Alice/Bob/admin, drawer clearing, delayed response rejection, admin route/API isolation, expired session')
} catch (error) { console.error('URL:', page.url(), 'Body:', await page.locator('body').innerText(), 'Requests:', requests); throw error }
finally { releaseFiles?.(); await browser.close(); await server.close() }
