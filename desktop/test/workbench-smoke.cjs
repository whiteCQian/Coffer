'use strict';
// Actual Electron + bundled backend, using a new isolated test library. No AI calls or real user files.
const { _electron: electron } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../.test-tmp', 'ui-workbench-' + crypto.randomUUID());
const sample = path.join(root, 'ui-import-sample.txt');
fs.mkdirSync(root, { recursive: true });
fs.writeFileSync(sample, 'Isolated UI import preview. This source must remain after cancellation.');
let application;
(async () => {
  try {
    application = await electron.launch({
      executablePath: path.resolve(__dirname, '../node_modules/electron/dist/electron.exe'),
      args: [path.resolve(__dirname, '..'), '--coffer-test-autostart=true', '--coffer-data-directory=' + path.join(root, 'data'), '--coffer-shell-directory=' + path.join(root, 'shell')],
      timeout: 60000,
    });
    const page = await application.firstWindow(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ BrowserWindow }) => { for (const window of BrowserWindow.getAllWindows()) window.hide(); });
    await page.locator('.desktop-sidebar').waitFor({ timeout: 90000 });
    await page.getByRole('heading', { name: '你的文件库，从这里开始', exact: true }).waitFor();
    assert.equal(await page.locator('.icon-rail').count(), 0);
    assert.equal(await page.locator('.desktop-files').count(), 1);
    await application.evaluate(({ dialog }, file) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [file] }); }, sample);
    await page.getByRole('button', { name: '导入文件', exact: true }).click();
    await page.getByRole('button', { name: '确认接管（入库后删除源文件）', exact: true }).waitFor();
    assert.match(await page.locator('.desktop-import').innerText(), /正式入库并校验成功后删除原路径文件/);
    await page.locator('.desktop-import').getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(fs.readFileSync(sample, 'utf8'), 'Isolated UI import preview. This source must remain after cancellation.');
    await page.getByLabel('主导航').getByRole('link', { name: '收件箱', exact: true }).click();
    await page.getByRole('heading', { name: '收件箱', exact: true }).waitFor();
    await page.getByLabel('主导航').getByRole('link', { name: '待确认', exact: true }).click();
    await page.getByText('标签都已确认', { exact: true }).waitFor();
    await page.getByLabel('主导航').getByRole('link', { name: '全部文件', exact: true }).click();
    await page.getByRole('button', { name: 'AI 助手', exact: true }).click();
    await page.locator('.workbench-ai').waitFor();
    await page.getByLabel('收起 AI 助手', { exact: true }).click();
    await page.screenshot({ path: path.join(root, 'native-desktop.png'), animations: 'disabled' });
    assert.deepEqual(errors, []);
    console.log('PASS: actual Electron/bundled Java startup, desktop workbench, native import preview/cancellation, source preservation, inbox, pending tags and AI panel.');
    console.log('Isolated artifacts: ' + root);
  } finally { if (application) await application.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
