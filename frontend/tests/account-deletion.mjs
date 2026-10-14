import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
const { chromium } = createRequire(import.meta.url)(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright')
const root = fileURLToPath(new URL('..', import.meta.url))
const server = await createServer({ root, server: { host: '127.0.0.1', port: 5183, strictPort: true } })
await server.listen()
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage({ viewport: { width: 1366, height: 1000 } })
  const errors = [], deleted = [], retries = []
  page.on('pageerror', error => errors.push(error.message))
  let users = [
    { id: 1, username: 'current-admin', role: 'ADMIN', enabled: true },
    { id: 2, username: 'temporary-admin', role: 'ADMIN', enabled: false },
    { id: 3, username: 'normal-user', role: 'USER', enabled: true },
    { id: 4, username: 'failed-cleanup', role: 'USER', enabled: false, deletionStatus: 'FAILED' },
  ]
  const pending = new Map()
  await page.route('http://127.0.0.1:5183/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname.slice(4)
    let data = []
    if (path === '/auth/csrf') data = 'fixture-csrf'
    else if (path === '/auth/status') data = { setupRequired: false, desktopPersonal: false, passwordMinLength: 8 }
    else if (path === '/auth/me') data = users[0]
    else if (path === '/admin/embedding') data = { mode: 'API', baseUrl: '', modelName: '', dimensions: 1024, configured: false, maskedApiKey: '', enabled: false, validatedAt: null }
    else if (path === '/admin/runtime') data = { readiness: 'UP', checkedAt: '2026-10-12T12:00:00', components: [], alerts: [], queues: {}, connections: {} }
    else if (path === '/admin/runtime/key-rotation') data = { keyId: 'fixture', remaining: 0, verified: 0, rewritten: 0 }
    else if (path === '/admin/users') {
      for (const [id, count] of pending) {
        if (count >= 1) { users = users.filter(user => user.id !== id); pending.delete(id) }
        else pending.set(id, count + 1)
      }
      data = users
    } else if (/^\/admin\/users\/\d+$/.test(path) && req.method() === 'DELETE') {
      const id = Number(path.split('/').pop()), body = req.postDataJSON(), user = users.find(user => user.id === id)
      assert.equal(body.confirmation, user.username); assert.equal(body.deleteData, true)
      deleted.push(id); user.enabled = false; user.deletionStatus = 'QUEUED'; pending.set(id, 0)
      data = { targetUserId: id, status: 'QUEUED' }
    } else if (path.endsWith('/retry-deletion')) {
      const id = Number(path.split('/')[3]); retries.push(id)
      users.find(user => user.id === id).deletionStatus = 'QUEUED'; pending.set(id, 0)
      data = { targetUserId: id, status: 'QUEUED' }
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) })
  })
  await page.goto('http://127.0.0.1:5183/admin')
  const row = name => page.locator('.user-row').filter({ hasText: name })
  await row('temporary-admin').getByRole('button', { name: '删除账号', exact: true }).waitFor()
  assert.equal(await row('current-admin').getByRole('button', { name: '删除账号', exact: true }).count(), 0)
  await mkdir(root + '/.test-tmp', { recursive: true })
  await page.locator('.user-list').screenshot({ path: root + '/.test-tmp/account-deletion.png' })
  await row('temporary-admin').getByRole('button', { name: '删除账号', exact: true }).click()
  const dialog = page.locator('.el-message-box')
  await dialog.locator('input').fill('wrong-account')
  await dialog.getByRole('button', { name: '永久删除', exact: true }).click()
  await page.getByText('账号名不一致，请重新输入', { exact: true }).waitFor()
  assert.deepEqual(deleted, [])
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  assert.deepEqual(deleted, [])
  await row('temporary-admin').getByRole('button', { name: '删除账号', exact: true }).click()
  await dialog.locator('input').fill('temporary-admin')
  await dialog.getByRole('button', { name: '永久删除', exact: true }).click()
  await page.getByText('账号已停止访问，正在清理关联数据', { exact: true }).waitFor()
  await row('temporary-admin').waitFor({ state: 'detached' })
  assert.deepEqual(deleted, [2])
  await row('failed-cleanup').getByRole('button', { name: '重试删除', exact: true }).click()
  await row('failed-cleanup').waitFor({ state: 'detached' })
  assert.deepEqual(retries, [4])
  assert.equal(await row('normal-user').count(), 1)
  assert.deepEqual(errors, [])
  console.log('PASS: typed confirmation, cancel/wrong-name safety, other-admin deletion, protected current account, automatic progress refresh and failed cleanup retry.')
} finally { if (browser) await browser.close(); await server.close() }
