// Isolated browser fixtures: do not access real accounts, source files or backend data.
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const origin = process.env.COFFER_UI_ORIGIN || 'http://127.0.0.1:5173';
const targetPath = 'users/7/managed/files/2026/10/08/txt/preview-fixed_报告.txt';
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage(); const errors = []; const submitted = []; let imported = false;
    page.on('pageerror', error => errors.push(error.message));
    await page.route(origin + '/api/**', async route => {
      const request = route.request(); const path = new URL(request.url()).pathname; let data = [];
      if (path.endsWith('/auth/me')) data = {id:7, username:'inbox-fixture', role:'USER', enabled:true};
      else if (path.endsWith('/auth/status')) data = {setupRequired:false, setupAvailable:false};
      else if (path.endsWith('/auth/csrf')) data = 'fixture-csrf';
      else if (path.endsWith('/runtime/status')) data = {checkedAt:new Date().toISOString(), readiness:'UP', components:[], queues:{}, connections:{}, alerts:[]};
      else if (path.endsWith('/tasks/overview')) data = {processingCount:0, pendingConfirmCount:0, failedCount:0, processing:[], pendingConfirm:[], failed:[]};
      else if (path.endsWith('/files')) data = {content:[], totalElements:0, totalPages:0, number:0, size:20};
      else if (path.endsWith('/model-execution/target')) data = {configurationVersion:'fixture-version', mode:'LOCAL',
        targets:[{capability:'CHAT', baseUrl:'http://127.0.0.1:11434/v1', modelName:'fixture-local'}]};
      else if (path.endsWith('/inbox-imports/progress')) data = {enabled:true, requiresPathConfirmation:true,
        awaitingConfirmationCount:imported ? 0 : 1, totalCount:1, importedCount:imported ? 1 : 0, duplicateCount:0,
        discoveredCount:0, stableCount:0, failedCount:0, manualReviewCount:0,
        items:[{id:31, fileName:'报告.txt', targetPath, status:imported ? 'IMPORTED' : 'AWAITING_CONFIRMATION'}]};
      else if (path.endsWith('/inbox-imports/31/confirm')) {
        submitted.push(request.postDataJSON());
        assert.equal(request.headers()['x-coffer-model-version'], 'fixture-version');
        imported = true; data = null;
      }
      await route.fulfill({contentType:'application/json', body:JSON.stringify({code:0,msg:'success',data})});
    });
    await page.goto(origin + '/');
    const preview = page.locator('.inbox-import-preview');
    await preview.getByRole('button', {name:'确认路径并复制', exact:true}).waitFor();
    assert.match(await preview.innerText(), /报告\.txt/);
    assert.match(await preview.innerText(), /preview-fixed_报告\.txt/);
    await preview.getByRole('button', {name:'确认路径并复制', exact:true}).click();
    await page.getByRole('button', {name:'取消', exact:true}).click();
    assert.equal(submitted.length, 0);
    await preview.getByRole('button', {name:'确认路径并复制', exact:true}).click();
    await page.getByRole('button', {name:'确认复制', exact:true}).click();
    await page.getByRole('button', {name:'同意并提交', exact:true}).waitFor();
    assert.match(await page.locator('.model-consent-dialog').innerText(), /127\.0\.0\.1:11434/);
    await page.getByRole('button', {name:'同意并提交', exact:true}).click();
    await page.getByText('已复制入库并登记处理任务', {exact:true}).waitFor();
    assert.deepEqual(submitted, [{targetPath}]);
    assert.deepEqual(errors, []);
    await context.close();
    process.stdout.write('R31 inbox UI: PASS fixed path, cancellation, model consent, exact confirmation payload\n');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
