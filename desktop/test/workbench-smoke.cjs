'use strict';
// Actual Electron + bundled backend, using a new isolated test library. No AI calls or real user files.
const { _electron: electron } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../.test-tmp', 'ui-workbench-' + crypto.randomUUID());
const sample = path.join(root, 'ui-import-sample.txt');
fs.mkdirSync(root, { recursive: true });
fs.writeFileSync(sample, 'Isolated UI import preview. This source must remain after cancellation.');
let application;
const executable = process.env.COFFER_DESKTOP_EXE;
(async () => {
  try {
    application = await electron.launch({
      executablePath: executable || path.resolve(__dirname, '../node_modules/electron/dist/electron.exe'),
      args: [...(executable ? [] : [path.resolve(__dirname, '..')]), '--coffer-test-autostart=true', '--coffer-data-directory=' + path.join(root, 'data'), '--coffer-shell-directory=' + path.join(root, 'shell')],
      timeout: 60000,
    });
    const page = await application.firstWindow(), errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ BrowserWindow }) => { for (const window of BrowserWindow.getAllWindows()) window.hide(); });
    if (executable) {
      await page.locator('.mark').waitFor();
      await page.waitForFunction(() => document.querySelector('.mark')?.naturalWidth > 0);
      await page.getByRole('button', { name: '下一步', exact: true }).click();
      await page.getByRole('button', { name: '创建并进入', exact: true }).click();
    }
    await page.locator('.desktop-sidebar').waitFor({ timeout: 90000 });
    await page.getByRole('heading', { name: '你的文件库，从这里开始', exact: true }).waitFor();
    assert.equal(await page.locator('.icon-rail').count(), 0);
    assert.equal(await page.locator('.desktop-files').count(), 1);
    assert.equal(await page.locator('.desktop-brand .coffer-logo').evaluate(el => el.complete && el.naturalWidth > 0), true);
    await page.getByText('当前没有正在解析的文件', { exact: true }).waitFor();
    assert.equal(await page.locator('.workbench-status').evaluate(el => getComputedStyle(el).fontSize), '11px');
    const boundary = page.getByRole('separator', { name: '调整主区域左边界' });
    await boundary.focus(); await page.keyboard.press('ArrowRight');
    assert.equal(await page.locator('.desktop-sidebar').evaluate(el => el.getBoundingClientRect().width), 230);
    await boundary.dblclick();
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
    const sideBoundary = page.getByRole('separator', { name: '调整主区域右边界' });
    await sideBoundary.focus(); await page.keyboard.press('ArrowLeft');
    assert.equal(await page.locator('.workbench-ai').evaluate(el => el.getBoundingClientRect().width), 350);
    await sideBoundary.dblclick();
    await sideBoundary.focus(); await page.keyboard.press('ArrowLeft');
    await page.waitForFunction(async () => (await window.cofferDesktop.getPaneWidths())['coffer:side-panel-width'] === 350);
    await page.getByLabel('收起 AI 助手', { exact: true }).click();
    await boundary.focus(); await page.keyboard.press('ArrowRight');
    await page.waitForFunction(async () => (await window.cofferDesktop.getPaneWidths())['coffer:sidebar-width'] === 230);
    const invalidPreference = await page.evaluate(async () => {
      try { await window.cofferDesktop.savePaneWidth('../outside', 100); return null; } catch (error) { return error.message; }
    });
    assert.match(invalidPreference, /面板宽度参数无效/);
    const firstOrigin = new URL(page.url()).origin;
    await page.getByLabel('切换到深色主题', { exact: true }).click();
    await page.waitForFunction(async () => (await window.cofferDesktop.getTheme()) === 'dark');
    assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).filter), 'none');
    assert.equal(await page.locator('.desktop-sidebar').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(25, 34, 43)');
    await page.screenshot({ path: path.join(root, 'native-desktop.png'), animations: 'disabled' });
    assert.deepEqual(errors, []);
    await application.close();
    application = await electron.launch({
      executablePath: executable || path.resolve(__dirname, '../node_modules/electron/dist/electron.exe'),
      args: [...(executable ? [] : [path.resolve(__dirname, '..')]), '--coffer-data-directory=' + path.join(root, 'data'), '--coffer-shell-directory=' + path.join(root, 'shell')],
      timeout: 60000,
    });
    const reopened = await application.firstWindow();
    await application.evaluate(({ BrowserWindow }) => { for (const window of BrowserWindow.getAllWindows()) window.hide(); });
    await reopened.locator('.desktop-sidebar').waitFor({ timeout: 90000 });
    assert.notEqual(new URL(reopened.url()).origin, firstOrigin);
    assert.equal(await reopened.evaluate(() => document.documentElement.dataset.theme), 'dark');
    assert.equal(await reopened.evaluate(() => getComputedStyle(document.documentElement).filter), 'none');
    assert.equal(await reopened.locator('.desktop-sidebar').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(25, 34, 43)');
    await reopened.getByLabel('切换到亮色主题', { exact: true }).click();
    await reopened.waitForFunction(async () => (await window.cofferDesktop.getTheme()) === 'light');
    assert.equal(await reopened.locator('.desktop-sidebar').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(241, 244, 246)');
    await reopened.waitForFunction(() => document.querySelector('.desktop-sidebar').getBoundingClientRect().width === 230);
    await reopened.getByRole('button', { name: 'AI 助手', exact: true }).click();
    await reopened.waitForFunction(() => document.querySelector('.workbench-ai').getBoundingClientRect().width === 350);
    console.log('PASS: actual Electron/Java workbench, actual light/dark colors, theme and width persistence across restart, shared logo, status font, bounded preference IPC, native import cancellation, queues and AI panel.');
    console.log('Isolated artifacts: ' + root);
  } finally { if (application) await application.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
