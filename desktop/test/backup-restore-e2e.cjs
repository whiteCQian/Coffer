'use strict';
// Own R34 fixture only; runs the packaged bridge and JRE without mocking backup/restore APIs.
const { _electron: electron }=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const exe=path.resolve(process.env.COFFER_DESKTOP_EXE || path.join(__dirname,'../dist/win-unpacked/Coffer.exe'));
const source=path.resolve(process.env.COFFER_BACKUP_SOURCE || path.join(__dirname,'../.test-tmp/installer-validation/workspace-final/data'));
const root=path.resolve(process.env.COFFER_BACKUP_TEST_ROOT || path.join(__dirname,'../.test-tmp/r35-'+crypto.randomUUID()));
const copies=path.join(root,'snapshots'),target=path.join(root,'restored-data'),password='R35-browser-backup-passphrase';fs.mkdirSync(copies,{recursive:true});fs.mkdirSync(target);
const env={};for(const key of ['SystemRoot','WINDIR','COMSPEC','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP'])if(process.env[key])env[key]=process.env[key];env.PATH=path.join(process.env.SystemRoot || 'C:\\Windows','System32');
let app;
async function request(page,route,method='GET',body){return page.evaluate(async({route,method,body})=>{await fetch('/api/auth/csrf');const xsrf=document.cookie.split('; ').find(cookie=>cookie.startsWith('XSRF-TOKEN='));const headers=xsrf?{'X-XSRF-TOKEN':decodeURIComponent(xsrf.slice(11))}:{};if(body!==undefined)headers['Content-Type']='application/json';const response=await fetch('/api'+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});const value=await response.text();return {status:response.status,data:value?JSON.parse(value).data:null};},{route,method,body});}
async function waitReady(page){await page.waitForURL(url=>url.pathname!=='/__desktop/start',{timeout:90000});}
(async()=>{
  app=await electron.launch({executablePath:exe,args:[`--coffer-data-directory=${source}`,`--coffer-shell-directory=${path.join(root,'source-shell')}`],env});const page=await app.firstWindow();await waitReady(page);
  assert.equal((await request(page,'/auth/login','POST',{username:'r34-user-a',password:'R34-user-pass'})).status,200);
  const file=(await request(page,'/files')).data.content[0];assert.ok(file);
  const before=(await request(page,`/files/${file.id}`)).data;
  const work=(await request(page,'/desktop/work-copies','POST',{fileId:file.id})).data;fs.writeFileSync(work.workPath,'R35 未完成工作副本，在恢复后继续处理。');
  await request(page,`/desktop/work-copies/${work.id}/close`,'POST',{action:'KEEP'});
  assert.equal((await request(page,'/auth/login','POST',{username:'r34-admin',password:'R34-admin-pass'})).status,200);await page.reload();const origin=new URL(page.url()).origin;await page.goto(origin+'/admin');
  await app.evaluate(({dialog},directory)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[directory]});dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});},copies);
  const panel=page.locator('.desktop-backup');await panel.getByRole('button',{name:'选择备份介质目录',exact:true}).click();
  await panel.locator('input[type=password]').nth(0).fill(password);await panel.locator('input[type=password]').nth(1).fill(password);
  await panel.locator('input[type=checkbox]').check();await panel.getByRole('button',{name:'生成并验证整套备份',exact:true}).click();
  await panel.getByText('本机加密快照已验证，独立介质备份待完成',{exact:true}).waitFor({timeout:180000});
  const archive=path.join(copies,fs.readdirSync(copies).find(name=>name.endsWith('.cofferbackup')));assert.ok(fs.existsSync(archive));
  const key=fs.readFileSync(path.join(source,'.coffer-encryption-key'));assert.ok(!fs.readFileSync(archive).includes(key));
  await app.close();app=null;
  const emptySource=path.join(root,'unused-first-run-data');app=await electron.launch({executablePath:exe,args:[`--coffer-data-directory=${emptySource}`,`--coffer-shell-directory=${path.join(root,'new-machine-shell')}`],env});const recovery=await app.firstWindow();
  await recovery.getByRole('button',{name:'选择备份包与空恢复目录',exact:true}).waitFor();
  await app.evaluate(({dialog},selection)=>{let step=0;dialog.showOpenDialog=async()=>({canceled:false,filePaths:[step++===0?selection.archive:selection.target]});dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});},{archive,target});
  await recovery.getByRole('button',{name:'选择备份包与空恢复目录',exact:true}).click();await recovery.locator('#backup-password').fill(password);
  await recovery.getByRole('button',{name:'验证并恢复整套数据',exact:true}).click();await recovery.getByText(/整套恢复已验证/).waitFor({timeout:180000});
  assert.deepEqual(fs.readFileSync(path.join(target,'.coffer-encryption-key')),key);assert.equal(fs.existsSync(emptySource),false);
  await recovery.getByRole('button',{name:'打开已有数据 / 重启',exact:true}).click();await waitReady(recovery);
  assert.equal((await request(recovery,'/auth/login','POST',{username:'r34-user-a',password:'R34-user-pass'})).status,200);
  const restored=(await request(recovery,`/files/${file.id}`)).data;assert.equal(restored.fileName,before.fileName);assert.equal(restored.contentSha256,before.contentSha256);
  const copy=(await request(recovery,`/desktop/work-copies/${work.id}`)).data;assert.equal(copy.status,'KEPT');assert.match(fs.readFileSync(copy.workPath,'utf8'),/未完成工作副本/);
  assert.equal((await request(recovery,'/auth/login','POST',{username:'r34-user-b',password:'R34-user-pass'})).status,200);assert.equal((await request(recovery,`/files/${file.id}`)).status,404);
  const result={passed:true,localSnapshotOnly:true,independentMediaAcceptance:false,accountLoginRestored:true,chineseFile:before.fileName,fileSha256Verified:true,pendingWorkRestored:true,originalKeyRetained:true,emptyReplacementRefused:true};
  fs.writeFileSync(path.join(root,'r35-e2e-result.json'),JSON.stringify(result,null,2));await recovery.screenshot({path:path.join(root,'restored-e2e.png'),fullPage:true});
  process.stdout.write(`R35 packaged backup/restore PASS: admin encrypted snapshot, new-root restore, original key/accounts/file SHA/pending work, dual-owner isolation; ${root}\n`);
})().finally(async()=>{if(app)await app.close();}).catch(error=>{console.error(error);process.exitCode=1;});
