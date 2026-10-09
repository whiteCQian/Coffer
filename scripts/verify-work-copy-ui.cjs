// All responses are isolated fixtures; this never opens Office or accesses a real file library.
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const origin = process.env.COFFER_UI_ORIGIN || 'http://127.0.0.1:5173';
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1500, height: 1100 } });
    const page = await context.newPage(), errors = [], posts = [];
    page.on('pageerror', error => errors.push(error.message));
    let row = { id: '91a27120-2c74-4204-8e9e-72678c39559b', fileId: 33, fileName: 'Office报告.docx',
      workPath: 'D:\\library\\users\\7\\work\\91a27120-2c74-4204-8e9e-72678c39559b\\edit.docx',
      status: 'READY', busy: false, modified: true, sha256: 'a'.repeat(64), size: 1200, modifiedTime: '2026-10-09T00:00:00Z', errorCode: null, recoveredFileId: null };
    await page.route(origin + '/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname; let data = [];
      if (path.endsWith('/auth/me')) data = {id:7, username:'work-fixture', role:'USER', enabled:true};
      else if (path.endsWith('/auth/status')) data = {setupRequired:false, setupAvailable:false};
      else if (path.endsWith('/auth/csrf')) data = 'fixture-csrf';
      else if (path.endsWith('/runtime/status')) data = {checkedAt:new Date().toISOString(), readiness:'UP', components:[], queues:{}, connections:{}, alerts:[]};
      else if (path.endsWith('/tasks/overview')) data = {processingCount:0,pendingConfirmCount:0,failedCount:0,processing:[],pendingConfirm:[],failed:[]};
      else if (path.endsWith('/inbox-imports/progress')) data = {enabled:false,items:[]};
      else if (path.endsWith('/desktop/capabilities')) data = {workCopies:true};
      else if (path.endsWith('/desktop/work-copies')) data = [row];
      else if (path.endsWith('/model-execution/target')) data = {configurationVersion:'fixture-version', mode:'LOCAL', targets:[{capability:'CHAT',baseUrl:'http://127.0.0.1:11434/v1',modelName:'local-fixture'}]};
      else if (path.endsWith('/governance/archive-operations/batches')) data = {content:[],totalElements:0};
      else if (path.includes('/desktop/work-copies/') && request.method() === 'POST') {
        posts.push({path, body:request.postDataJSON()});
        if (path.endsWith('/open')) row = {...row,status:'OPENED'};
        if (path.endsWith('/save')) { assert.equal(request.headers()['x-coffer-model-version'],'fixture-version'); row = {...row,status:'CONFLICTED',errorCode:'FORMAL_FILE_CHANGED'}; }
        if (path.endsWith('/save-as')) row = {...row,status:'SAVED_AS',errorCode:null,recoveredFileId:133};
        if (path.endsWith('/close')) {
          const action = request.postDataJSON().action;
          row = {...row, status:action === 'REPORT_EXIT' ? 'INTERRUPTED' : action === 'KEEP' ? 'KEPT' : 'DISCARDED',
            errorCode:action === 'REPORT_EXIT' ? 'APP_EXIT_UNCONFIRMED' : null};
        }
        data = row;
      }
      await route.fulfill({contentType:'application/json', body:JSON.stringify({code:0,msg:'success',data})});
    });
    await page.goto(origin + '/operations'); const panel = page.locator('.work-copy-panel');
    await panel.getByRole('button',{name:'打开副本',exact:true}).waitFor();
    await panel.getByRole('button',{name:'打开副本',exact:true}).click();
    assert.match(await page.locator('.el-message-box').innerText(), /work/);
    await page.getByRole('button',{name:'取消',exact:true}).click(); assert.equal(posts.length,0);
    await panel.getByRole('button',{name:'打开副本',exact:true}).click();
    await page.getByRole('button',{name:'打开外部应用',exact:true}).click();
    await panel.getByText(/外部编辑中/).waitFor();
    await panel.getByRole('button',{name:'记录应用异常 / 未保存退出',exact:true}).click();
    await panel.getByText(/只能恢复写到磁盘/).waitFor();
    await panel.getByRole('button',{name:'关闭 / 放弃副本',exact:true}).click();
    await page.getByRole('button',{name:'确认选择',exact:true}).click();
    await panel.getByText(/已保留，下次可继续/).waitFor();
    await panel.getByRole('button',{name:'保存新版本',exact:true}).click();
    await page.getByRole('button',{name:'同意并提交',exact:true}).click();
    await panel.getByText(/两份文件均保留/).waitFor();
    assert.equal(await panel.getByRole('button',{name:'保存新版本',exact:true}).isDisabled(),true);
    await panel.getByRole('button',{name:'另存独立文件',exact:true}).click();
    await page.locator('.el-message-box').getByRole('button',{name:'另存独立文件',exact:true}).click();
    await panel.getByText(/独立文件 #133/).waitFor();
    await panel.getByRole('button',{name:'关闭 / 放弃副本',exact:true}).click();
    await page.getByText('放弃磁盘副本（原件保留）',{exact:true}).click();
    assert.equal(await page.getByRole('radio',{name:'放弃磁盘副本（原件保留）',exact:true}).isChecked(),true);
    await page.getByRole('button',{name:'确认选择',exact:true}).click();
    await panel.getByText(/已放弃副本/).waitFor();
    assert.equal(posts.at(-1).body.sha256,'a'.repeat(64)); assert.equal(posts.at(-1).body.action,'DISCARD');
    // Restart fixture shows durable interruption and lock; saving must be visibly unavailable.
    row = {...row,status:'INTERRUPTED',errorCode:'BACKEND_EXIT_UNCONFIRMED',busy:true,recoveredFileId:null};
    await page.reload(); await panel.getByText(/后端上次退出时未确认编辑结束/).waitFor();
    assert.equal(await panel.getByRole('button',{name:'保存新版本',exact:true}).isDisabled(),true);
    assert.match(await panel.innerText(),/占用或不可读/); assert.deepEqual(errors,[]);
    if (process.env.COFFER_UI_SCREENSHOT) await page.screenshot({path:process.env.COFFER_UI_SCREENSHOT,fullPage:true});
    process.stdout.write('R33 UI PASS: work path confirmation/cancel, app exit, keep, authorized save/conflict, save-as, digest-confirmed discard, restart/Office lock\n');
    await context.close();
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
