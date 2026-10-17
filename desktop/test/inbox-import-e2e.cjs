'use strict';
// V44 regression: unvalidated model errors, one toast, then real native handoff after validation.
// All content and model responses are fixtures in an independent temporary library.
const { _electron: electron } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), http = require('node:http');
const executable = path.resolve(process.env.COFFER_DESKTOP_EXE || path.join(__dirname, '../dist/inbox-import-fix/Coffer.exe'));
const root = path.resolve(__dirname, '../../.test-tmp', 'inbox-e2e-' + crypto.randomUUID());
const legacy = process.env.COFFER_EXPECT_LEGACY_IMPORT_ERROR === 'true';
fs.mkdirSync(root, { recursive: true });
const source = path.join(root, 'VPS搭建.txt'), content = 'Isolated native inbox handoff regression.';
fs.writeFileSync(source, content);
let application, modelServer;
async function api(page, route, method = 'GET', body) {
  return page.evaluate(async ({ route, method, body }) => {
    await fetch('/api/auth/csrf');
    const csrf = document.cookie.split('; ').find(value => value.startsWith('XSRF-TOKEN='));
    const headers = {};
    if (csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf.slice('XSRF-TOKEN='.length));
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch('/api' + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }, { route, method, body });
}
async function waitFor(check, timeout = 90000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error('Inbox regression condition timed out');
}
async function startModel() {
  modelServer = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    const prompt = JSON.stringify(body.messages || []);
    const answer = prompt.includes('Reply with OK only') ? 'OK'
      : JSON.stringify({ category: '报告', tags: ['回归测试', '本地文件', '导入验证'] });
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ id: 'fixture-' + crypto.randomUUID(), object: 'chat.completion',
      created: Math.floor(Date.now() / 1000), model: 'inbox-regression-fixture',
      choices: [{ index: 0, message: { role: 'assistant', content: answer }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 10, completion_tokens: 10, total_tokens: 20 } }));
  });
  for (const port of [1234, 8000, 11434]) {
    try {
      await new Promise((resolve, reject) => {
        const failed = error => reject(error);
        modelServer.once('error', failed);
        modelServer.listen(port, '127.0.0.1', () => { modelServer.removeListener('error', failed); resolve(); });
      });
      return `http://127.0.0.1:${port}/v1`;
    } catch (error) { if (error.code !== 'EADDRINUSE') throw error; }
  }
  throw new Error('No approved local fixture model port is available');
}
(async () => {
  try {
    application = await electron.launch({ executablePath: executable,
      args: ['--coffer-data-directory=' + path.join(root, 'data'), '--coffer-shell-directory=' + path.join(root, 'shell')], timeout: 60000 });
    const page = await application.firstWindow(), errors = [];
    page.setDefaultTimeout(30000);
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.hide()));
    await page.getByRole('button', { name: '下一步', exact: true }).click();
    await page.getByRole('button', { name: '创建并进入', exact: true }).click();
    await page.locator('.desktop-sidebar').waitFor({ timeout: 90000 });
    assert.equal((await api(page, '/settings/runtime')).body.data.currentModeValidated, false);
    await page.getByLabel('主导航').getByRole('link', { name: '收件箱', exact: true }).click();
    await application.evaluate(({ dialog }, file) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [file] }); }, source);
    await page.getByRole('button', { name: '选择本地文件', exact: true }).click();
    await page.getByRole('button', { name: '确认接管（入库后删除源文件）', exact: true }).click();
    await page.locator('.inbox-import-preview').waitFor({ timeout: 90000 });
    await page.locator('.inbox-import-preview').getByRole('button', { name: '确认入库并接管', exact: true }).click();
    const targetRequest = page.waitForResponse(response => response.url().endsWith('/api/model-execution/target'));
    await page.locator('.el-message-box').getByRole('button', { name: '确认入库并接管', exact: true }).click();
    const targetResponse = await targetRequest;
    assert.equal(targetResponse.status(), 400);
    await page.locator('.el-message--error').first().waitFor();
    const messages = await page.locator('.el-message--error').allTextContents();
    assert.equal(messages.length, legacy ? 2 : 1, JSON.stringify(messages));
    if (legacy) assert.ok(messages.every(message => message.includes('请求参数不符合要求')));
    else assert.match(messages[0], /API 模式.*测试当前模式.*源文件已保留/);
    assert.equal(fs.readFileSync(source, 'utf8'), content);
    const pending = (await api(page, '/inbox-imports/progress')).body.data.items.find(item => item.status === 'AWAITING_CONFIRMATION');
    assert.ok(pending);
    if (legacy) { console.log('Legacy screenshot reproduced: unvalidated model, generic 400, two identical toasts, source retained. ' + root); return; }

    const baseUrl = await startModel();
    // V44 retains personal Chat/Vision/Embedding configuration. No migration to the newer admin contract.
    for (const capability of ['CHAT', 'VISION', 'EMBEDDING']) {
      assert.equal((await api(page, `/settings/runtime/config/LOCAL/${capability}`, 'PUT',
        { baseUrl, modelName: 'inbox-regression-fixture', apiKey: '' })).status, 200);
    }
    const validated = await api(page, '/settings/runtime/validate', 'POST', { mode: 'LOCAL' });
    assert.equal(validated.body.data.validationSuccess, true, JSON.stringify(validated.body));
    assert.equal((await api(page, '/settings/runtime/mode', 'PUT', { mode: 'LOCAL' })).status, 200);
    await page.locator('.inbox-import-preview').getByRole('button', { name: '确认入库并接管', exact: true }).click();
    await page.locator('.el-message-box').getByRole('button', { name: '确认入库并接管', exact: true }).click();
    const confirmed = page.waitForResponse(response => response.url().endsWith(`/api/inbox-imports/${pending.id}/confirm`));
    await page.getByRole('button', { name: '同意并提交', exact: true }).click();
    const imported = await confirmed;
    assert.equal(imported.status(), 200, JSON.stringify(await imported.json()));
    assert.equal(imported.request().postDataJSON().targetPath, pending.targetPath);
    await waitFor(() => !fs.existsSync(source));
    const managed = path.join(root, 'data', 'library', ...pending.targetPath.split('/'));
    assert.equal(fs.readFileSync(managed, 'utf8'), content);
    const complete = await waitFor(async () => (await api(page, '/files')).body.data.content?.find(file => file.status === 'COMPLETED'));
    assert.equal(complete.fileName, 'VPS搭建.txt');
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(root, 'verification.json'), JSON.stringify({ schemaVersion: 44,
      actionableError: messages[0], errorToastCount: messages.length, confirmedPath: pending.targetPath,
      sourceRemoved: true, managedSha256: crypto.createHash('sha256').update(fs.readFileSync(managed)).digest('hex'),
      analysisStatus: complete.status, pageErrors: errors }, null, 2));
    console.log('PASS: one actionable model error; source retained until confirmation; Chinese file imported, verified, source removed, analysis completed. ' + root);
  } finally {
    if (application) await application.close();
    if (modelServer?.listening) await new Promise(resolve => modelServer.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
