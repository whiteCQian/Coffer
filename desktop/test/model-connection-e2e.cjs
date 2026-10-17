'use strict';
// Real V44 settings UI and backend, with a local provider that changes from 503 to 200.
const { _electron: electron } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const crypto = require('node:crypto'), http = require('node:http');
const root = path.resolve(__dirname, '../../.test-tmp', 'model-connection-e2e-' + crypto.randomUUID());
fs.mkdirSync(root, { recursive: true });
let application, server, providerStatus = 503, calls = 0;
const responseDelayMs = Number(process.env.COFFER_MODEL_TEST_DELAY_MS || 0);
(async () => {
  try {
    server = http.createServer(async (request, response) => {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      assert.equal(request.url, '/chat/completions');
      assert.equal(body.model, 'deepseek-flash');
      calls++;
      const payload = providerStatus === 200
        ? { id: 'fixture', object: 'chat.completion', created: 1, model: 'deepseek-flash',
          choices: [{ index: 0, message: { role: 'assistant', content: 'OK' }, finish_reason: 'stop' }] }
        : { error: { code: 'service_unavailable_error', message: 'PRIVATE_BODY_MARKER synthetic-key' } };
      response.writeHead(providerStatus, { 'Content-Type': 'application/json' });
      if (providerStatus === 200 && responseDelayMs > 0) {
        response.flushHeaders();
        response.write(' ');
        await new Promise(resolve => setTimeout(resolve, responseDelayMs));
      }
      response.end(JSON.stringify(payload));
    });
    for (const port of [1234, 8000, 11434]) {
      try {
        await new Promise((resolve, reject) => {
          const fail = error => reject(error);
          server.once('error', fail);
          server.listen(port, '127.0.0.1', () => { server.removeListener('error', fail); resolve(); });
        });
        break;
      } catch (error) { if (error.code !== 'EADDRINUSE') throw error; }
    }
    assert.ok(server.listening);
    application = await electron.launch({
      executablePath: path.resolve(process.env.COFFER_DESKTOP_EXE || path.join(__dirname, '../dist/inbox-import-fix/Coffer.exe')),
      args: ['--coffer-data-directory=' + path.join(root, 'data'), '--coffer-shell-directory=' + path.join(root, 'shell')], timeout: 60000 });
    const page = await application.firstWindow(), errors = [];
    page.setDefaultTimeout(30000);
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.hide()));
    await page.getByRole('button', { name: '下一步', exact: true }).click();
    await page.getByRole('button', { name: '创建并进入', exact: true }).click();
    await page.locator('.desktop-sidebar').waitFor({ timeout: 90000 });
    await page.goto(new URL(page.url()).origin + '/settings');
    await page.getByText('Chat Base URL', { exact: true }).waitFor();
    await page.locator('.mode-tab').filter({ hasText: '本地模式' }).click();
    await page.getByText('Chat Base URL', { exact: true }).locator('..').locator('input')
      .fill(`http://127.0.0.1:${server.address().port}`);
    await page.getByText('Chat 模型名称', { exact: true }).locator('..').locator('input').fill('deepseek-flash');
    const tested = page.waitForResponse(response => response.url().endsWith('/api/settings/runtime/config/test'));
    await page.getByRole('button', { name: '测试 Chat', exact: true }).click();
    const result = await tested;
    assert.equal(result.status(), 200, JSON.stringify(await result.json()));
    await page.getByText('模型服务暂时不可用（HTTP 503），请稍后重试', { exact: true }).waitFor();
    assert.equal(calls, 1);
    assert.equal((await page.locator('.el-message--error').allTextContents()).length, 1);
    assert.ok(!(await page.locator('body').innerText()).includes('PRIVATE_BODY_MARKER'));
    providerStatus = 200;
    const recoveryStarted = Date.now();
    await page.getByRole('button', { name: '测试 Chat', exact: true }).click();
    await page.getByText('Chat 连接成功', { exact: true }).waitFor({ timeout: Math.max(30000, responseDelayMs + 10000) });
    assert.equal(calls, 2);
    if (responseDelayMs > 0) assert.ok(Date.now() - recoveryStarted >= responseDelayMs);
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify({ failedStatus: 503, clearMessage: true,
      errorToastCount: 1, unchangedConfigurationRecovers: true, responseDelayMs, requests: calls, pageErrors: errors }, null, 2));
    console.log('PASS: settings shows provider HTTP 503, hides raw error/key, and succeeds after recovery with unchanged DeepSeek configuration. ' + root);
  } finally {
    if (application) await application.close();
    if (server?.listening) await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
