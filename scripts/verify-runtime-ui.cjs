// Read-only browser acceptance using intercepted API fixtures; no real accounts or files are modified.
// Set COFFER_PLAYWRIGHT_MODULE to the locally installed playwright package path when needed.
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const origin = process.env.COFFER_UI_ORIGIN || 'http://127.0.0.1:5173';
const marker = 'R26_PRIVATE_CONTENT_KEY_FILE_NAME';
const snapshot = (state) => ({ checkedAt: new Date().toISOString(), readiness: state,
  components: [{ name: 'storage', status: state, reason: state === 'UP' ? 'OK' : 'STORAGE_UNAVAILABLE', action: state === 'UP' ? 'NONE' : 'CHECK_STORAGE', totalBytes: null, freeBytes: null }],
  queues: { pendingTasks: 2, processingTasks: 1, failedTasks: 0, pendingCompensations: 1, pendingDeletions: 0, manualReview: 0, oldestPendingMinutes: 0, pendingWrites: 0, pendingRenames: 0, pendingWorkSaves: 0, pendingVectorCleanups: 0, failedVectorReindexes: 0 },
  connections: {active: 1, idle: 4, waiting: 0, limit: 10}, alerts: state === 'UP' ? [] : [{ code: 'STORAGE_STORAGE_UNAVAILABLE', severity: 'CRITICAL', action: 'CHECK_STORAGE' }] });
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    for (const role of ['ADMIN', 'USER']) {
      const context = await browser.newContext(); const page = await context.newPage();
      let state = 'UP', unavailable = false;
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.route(origin + '/api/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.includes('/runtime') && unavailable) return route.abort('failed');
        let data = [];
        if (path.endsWith('/auth/csrf')) data = 'fixture-csrf';
        else if (path.endsWith('/auth/status')) data = { setupRequired: false, setupAvailable: false };
        else if (path.endsWith('/auth/me')) data = { id: 1, username: 'runtime-test', role, enabled: true, createdAt: new Date().toISOString() };
        else if (path.endsWith('/runtime/key-rotation')) data = { keyId: '0123456789abcdef', previousKeyConfigured: true, remaining: 2, verified: 0, rewritten: 0 };
        else if (path.endsWith('/runtime/alerts')) data = [];
        else if (path.endsWith('/admin/runtime') || path.endsWith('/runtime/status')) data = snapshot(state);
        else if (path.endsWith('/files')) data = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, msg: 'success', data }) });
      });
      await page.goto(origin + (role === 'ADMIN' ? '/admin' : '/settings'));
      if (role === 'ADMIN') {
        await page.getByText('当前无告警', { exact: true }).waitFor();
        state = 'DOWN'; await page.locator('.runtime-panel').getByRole('button', { name: '刷新', exact: true }).click();
        await page.getByText('文件存储不可用', { exact: true }).waitFor();
        assert.match(await page.locator('.runtime-panel').innerText(), /检查存储服务/);
        assert.doesNotMatch(await page.locator('.runtime-panel').innerText(), /当前无告警/);
        unavailable = true; await page.locator('.runtime-panel').getByRole('button', { name: '刷新', exact: true }).click();
        await page.getByText('运行状态暂时无法核实。检查数据库、后台服务和网络连接后重试。', { exact: true }).waitFor();
        assert.equal(await page.locator('.runtime-panel article').count(), 0);
        assert.equal(await page.getByText('当前密钥标识', { exact: false }).count(), 0);
      } else {
        // Polling responds to dependency failure, and user views never receive aggregates.
        state = 'DOWN'; await page.clock.install(); await page.clock.fastForward(31000);
        await page.getByText('部分服务暂时不可用', { exact: true }).waitFor();
        assert.match(await page.locator('.runtime-banner').innerText(), /检查存储服务/);
        unavailable = true; await page.getByRole('button', { name: '重新检查', exact: true }).click();
        await page.getByText('暂时无法获取服务状态', { exact: true }).waitFor();
      }
      assert.doesNotMatch(await page.locator('body').innerText(), new RegExp(marker));
      assert.deepEqual(errors, []); await context.close();
      process.stdout.write(role + ' runtime UI: PASS\n');
    }
    const context = await browser.newContext(); const page = await context.newPage(); let offline = true;
    await page.route(origin + '/api/**', async route => {
      if (offline) return route.abort('failed');
      const path = new URL(route.request().url()).pathname;
      const data = path.endsWith('/auth/status') ? {setupRequired: false, setupAvailable: false} : 'fixture-csrf';
      await route.fulfill({status: path.endsWith('/auth/me') ? 401 : 200, contentType: 'application/json', body: JSON.stringify({code: path.endsWith('/auth/me') ? 401 : 0, msg: '请求未完成', data})});
    });
    await page.goto(origin + '/login');
    await page.getByText('暂时无法核实服务和账号状态。请检查网络及后台服务，恢复后重新检查。', {exact: true}).waitFor();
    assert.equal(await page.getByText('尚未创建管理员。', {exact: false}).count(), 0);
    assert.equal(await page.locator('.auth-card input').count(), 0);
    offline = false; await page.getByRole('button', {name: '重新检查服务', exact: true}).click();
    await page.locator('input[autocomplete="username"]').waitFor();
    await context.close(); process.stdout.write('ANONYMOUS offline/recovery UI: PASS\n');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
