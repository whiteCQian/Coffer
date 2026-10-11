'use strict';
// Runs the built executable and bundled JRE against new, isolated data. No application APIs are mocked.
const { _electron: electron }=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto'),{spawn,execFileSync}=require('node:child_process');
const executable=path.resolve(process.env.COFFER_DESKTOP_EXE || path.join(__dirname,'../dist/win-unpacked/Coffer.exe'));
const fixtureRoot=path.resolve(process.env.COFFER_DESKTOP_TEST_ROOT || path.join(__dirname,'../.test-tmp',crypto.randomUUID()));
fs.mkdirSync(fixtureRoot,{recursive:true});
const sourceFile=path.join(fixtureRoot,'本地安装验证.txt');fs.writeFileSync(sourceFile,'Public report for desktop package verification. This file contains no personal information.');
const env={};for(const key of ['SystemRoot','WINDIR','COMSPEC','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP'])if(process.env[key])env[key]=process.env[key];
env.PATH=path.join(process.env.SystemRoot || 'C:\\Windows','System32');
let application,modelServer;
async function api(page,route,method='GET',body,extra={}){
  return page.evaluate(async({route,method,body,extra})=>{
    await fetch('/api/auth/csrf');const cookie=document.cookie.split('; ').find(value=>value.startsWith('XSRF-TOKEN='));
    const headers={...extra};if(cookie)headers['X-XSRF-TOKEN']=decodeURIComponent(cookie.slice('XSRF-TOKEN='.length));
    if(body!==undefined)headers['Content-Type']='application/json';
    const result=await fetch('/api'+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
    const text=await result.text();return {status:result.status,body:text?JSON.parse(text):null};
  },{route,method,body,extra});
}
function plain(port,route,headers={}){return new Promise((resolve,reject)=>{http.get({hostname:'127.0.0.1',port,path:route,headers},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));}).on('error',reject);});}
async function wait(page,condition,timeout=60000){const until=Date.now()+timeout;let last;while(Date.now()<until){last=await condition();if(last)return last;await page.waitForTimeout(250);}throw new Error('Condition timeout: '+JSON.stringify(last));}
async function state(){
  const directory=path.join(fixtureRoot,'data/.coffer-runtime');
  const ready=fs.readdirSync(directory).filter(name=>/^ready-.*\.json$/.test(name)).sort((a,b)=>fs.statSync(path.join(directory,b)).mtimeMs-fs.statSync(path.join(directory,a)).mtimeMs)[0];
  const rendezvous=JSON.parse(fs.readFileSync(path.join(directory,ready),'utf8'));
  const page=await application.firstWindow();return {origin:new URL(page.url()).origin,backendPort:rendezvous.port,backendPid:rendezvous.pid};
}
async function configureModel(page,baseUrl){
  for(const capability of ['CHAT','VISION','EMBEDDING'])assert.equal((await api(page,`/settings/runtime/config/LOCAL/${capability}`,'PUT',{baseUrl,modelName:'coffer-isolated-fixture',apiKey:''})).status,200);
  const validated=await api(page,'/settings/runtime/validate','POST',{mode:'LOCAL'});assert.equal(validated.status,200);assert.equal(validated.body.data.validationSuccess,true,JSON.stringify(validated.body));
  assert.equal((await api(page,'/settings/runtime/mode','PUT',{mode:'LOCAL'})).status,200);
}
(async()=>{
  modelServer=http.createServer(async(req,res)=>{
    const chunks=[];for await(const chunk of req)chunks.push(chunk);const request=JSON.parse(Buffer.concat(chunks).toString() || '{}');
    const prompt=JSON.stringify(request.messages || []);const content=prompt.includes('Reply with OK only')?'OK':prompt.includes('category')?JSON.stringify({category:'报告',tags:['桌面验证','本地资料','安装测试']}):'Public sample report for desktop verification.';
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({id:'fixture-'+crypto.randomUUID(),object:'chat.completion',created:Math.floor(Date.now()/1000),model:'coffer-isolated-fixture',choices:[{index:0,message:{role:'assistant',content},finish_reason:'stop'}],usage:{prompt_tokens:10,completion_tokens:10,total_tokens:20}}));
  });
  for(const port of [1234,8000,11434]) {
    try {await new Promise((resolve,reject)=>{modelServer.once('error',reject);modelServer.listen(port,'127.0.0.1',resolve);});break;}
    catch(error){if(error.code!=='EADDRINUSE')throw error;}
  }
  if(!modelServer.listening)throw new Error('All approved local model fixture ports are occupied');
  application=await electron.launch({executablePath:executable,args:[`--coffer-data-directory=${path.join(fixtureRoot,'data')}`,`--coffer-shell-directory=${path.join(fixtureRoot,'shell')}`],env,timeout:45000});
  const page=await application.firstWindow();const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.getByRole('button',{name:'下一步',exact:true}).waitFor();
  await page.getByRole('button',{name:'下一步',exact:true}).click();
  await page.getByRole('button',{name:'创建并进入',exact:true}).click();
  await page.waitForURL(url=>url.pathname!=='/__desktop/start',{timeout:90000});
  await wait(page,async()=> (await api(page,'/auth/status')).body.data?.desktopPersonal===true);
  assert.equal(await page.evaluate(()=>typeof window.require),'undefined');assert.equal(await page.evaluate(()=>typeof window.process),'undefined');
  const preferences=await application.evaluate(({BrowserWindow})=>{const value=BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences();return {nodeIntegration:value.nodeIntegration,contextIsolation:value.contextIsolation,sandbox:value.sandbox};});
  assert.deepEqual(preferences,{nodeIntegration:false,contextIsolation:true,sandbox:true});
  assert.equal((await api(page,'/auth/me')).body.data?.username,'个人文件库');
  assert.equal((await api(page,'/admin/users','POST',{username:'forbidden',password:'Unused-password'})).status,403);
  assert.equal((await api(page,'/auth/login','POST',{username:'forbidden',password:'Unused-password'})).status,403);
  assert.equal((await api(page,'/auth/logout','POST')).status,403);
  await page.goto(new URL(page.url()).origin+'/admin');await page.waitForURL(url=>url.pathname==='/');
  const ownerA=(await api(page,'/auth/me')).body.data.id;
  await configureModel(page,`http://127.0.0.1:${modelServer.address().port}/v1`);
  const ports=await state();assert.notEqual(ports.backendPort,8080);assert.ok(ports.backendPid>0);
  const sessionCookie=await application.evaluate(async({session},origin)=>(await session.fromPartition('persist:coffer').cookies.get({url:origin})).map(cookie=>`${cookie.name}=${cookie.value}`).join('; '),ports.origin);
  assert.equal(await plain(ports.backendPort,'/api/auth/me'),403);assert.equal(await plain(new URL(ports.origin).port,'/api/auth/me'),403);
  assert.equal(await plain(ports.backendPort,'/api/files',{Cookie:sessionCookie}),403);assert.equal(await plain(new URL(ports.origin).port,'/api/files',{Cookie:sessionCookie}),403);
  assert.equal((await api(page,'/desktop/native-inbox/preview','POST',{sourcePath:sourceFile})).status,403);
  await application.evaluate(({dialog},file)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[file]});},sourceFile);
  await page.goto(ports.origin+'/files');await page.getByRole('button',{name:'选择本地文件',exact:true}).click();
  await page.getByRole('button',{name:'确认接管（入库后删除源文件）',exact:true}).waitFor();
  assert.match(await page.locator('.desktop-import').innerText(),new RegExp(`users/${ownerA}/inbox/`));
  await page.getByRole('button',{name:'确认接管（入库后删除源文件）',exact:true}).click();
  const inbox=await wait(page,async()=>{const response=await api(page,'/inbox-imports/progress');return response.body.data.items?.find(item=>item.status==='AWAITING_CONFIRMATION');},90000);
  const target=(await api(page,'/model-execution/target')).body.data;
  assert.ok(fs.existsSync(sourceFile),'Unconfirmed source must remain');
  assert.equal((await api(page,`/inbox-imports/${inbox.id}/confirm`,'POST',{targetPath:inbox.targetPath},{'X-Coffer-Model-Version':target.configurationVersion,'X-Coffer-Allow-Sensitive':'true'})).status,200);
  const file=await wait(page,async()=>{const response=await api(page,'/files');return response.body.data.content?.find(file=>file.status==='COMPLETED');},90000);
  assert.equal(fs.existsSync(sourceFile),false,'Source must be removed after verified formal registration');
  const beforeRevision=(await api(page,`/files/${file.id}`)).body.data.revision;
  const copy=(await api(page,'/desktop/work-copies','POST',{fileId:file.id})).body.data;assert.equal(copy.status,'READY');
  await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='coffer-fixture-file';document.body.appendChild(input);});
  const dropFile=path.join(fixtureRoot,'drop-preview.txt');fs.writeFileSync(dropFile,'Unconfirmed drop remains outside the library.');
  await page.locator('#coffer-fixture-file').setInputFiles(dropFile);
  const dropped=await page.evaluate(()=>window.cofferDesktop.previewDroppedFiles(Array.from(document.querySelector('#coffer-fixture-file').files)));
  assert.equal(dropped.length,1);assert.match(dropped[0].targetPath,new RegExp(`users/${ownerA}/inbox/`));
  const original=fs.readFileSync(dropFile);fs.writeFileSync(copy.workPath,'Public edited desktop report. The managed body retains its earlier revision.');assert.deepEqual(fs.readFileSync(dropFile),original);
  const saved=await api(page,`/desktop/work-copies/${copy.id}/save`,'POST',undefined,{'X-Coffer-Model-Version':target.configurationVersion,'X-Coffer-Allow-Sensitive':'true'});assert.equal(saved.body.data.status,'SAVED');
  const detail=await wait(page,async()=>{const response=await api(page,`/files/${file.id}`);return response.body.data.status==='COMPLETED'?response.body.data:null;},90000);assert.ok(detail.revision>beforeRevision);
  const content=await page.evaluate(async url=>{const result=await fetch(url);return {status:result.status,text:await result.text()};},detail.previewUrl);
  assert.equal(content.status,200);assert.equal(content.text,'Public edited desktop report. The managed body retains its earlier revision.');
  assert.equal((await api(page,`/desktop/work-copies/${copy.id}/close`,'POST',{action:'CLOSE',sha256:saved.body.data.sha256})).body.data.status,'CLOSED');
  const staleError=await page.evaluate(async confirmed=>{try{await window.cofferDesktop.commitInbox(confirmed);return null;}catch(error){return error.message;}},dropped.map(item=>({id:item.id,targetPath:item.targetPath+'-tampered'})));
  assert.match(staleError,/路径不一致/);assert.deepEqual(fs.readFileSync(dropFile),original);
  assert.equal(await page.evaluate(()=>typeof window.cofferDesktop.setupToken),'undefined');
  const ipcRejected=await application.evaluate(async({app,BrowserWindow})=>{
    const preload=app.getAppPath()+'/src/preload.cjs';
    const attacker=new BrowserWindow({show:false,webPreferences:{preload,nodeIntegration:false,contextIsolation:true,sandbox:true,partition:'coffer-test-untrusted'}});
    try{await attacker.loadURL('data:text/html,<title>untrusted</title>');return await attacker.webContents.executeJavaScript('typeof window.cofferDesktop === "undefined" ? "BRIDGE_NOT_EXPOSED" : window.cofferDesktop.status().then(()=>false,error=>error.message)');}finally{attacker.close();}
  });assert.match(ipcRejected,/IPC 来源验证失败/);
  const sameArgs=[`--coffer-data-directory=${path.join(fixtureRoot,'data')}`,`--coffer-shell-directory=${path.join(fixtureRoot,'shell')}`];
  const second=spawn(executable,sameArgs,{env,windowsHide:true,stdio:'ignore'});
  const secondCode=await Promise.race([new Promise(resolve=>second.once('exit',resolve)),new Promise(resolve=>setTimeout(()=>resolve('timeout'),5000))]);
  assert.notEqual(secondCode,'timeout');assert.equal((await state()).backendPid,ports.backendPid);
  const keyPath=path.join(fixtureRoot,'data/.coffer-encryption-key'),keyHash=crypto.createHash('sha256').update(fs.readFileSync(keyPath)).digest('hex');
  const browserPid=await application.evaluate(({app})=>app.getAppMetrics().find(metric=>metric.type==='Browser').pid);
  execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','$ownedPid=[int]$env:COFFER_PROBE_PID; $owned=Get-CimInstance Win32_Process -Filter ("ProcessId="+$ownedPid); $parentMatches=$owned -and $owned.ParentProcessId -eq [int]$env:COFFER_PROBE_PARENT; $jarMatches=$owned -and $owned.CommandLine -and $owned.CommandLine.Replace("/","\\").Contains($env:COFFER_PROBE_JAR.Replace("/","\\")); if(-not $parentMatches -or -not $jarMatches){throw ("Backend ownership mismatch: actualParent="+$owned.ParentProcessId+", expectedParent="+$env:COFFER_PROBE_PARENT+", jarMatches="+$jarMatches)}; Stop-Process -Id $ownedPid -Force'],{env:{...process.env,COFFER_PROBE_PID:String(ports.backendPid),COFFER_PROBE_PARENT:String(browserPid),COFFER_PROBE_JAR:path.join(path.dirname(executable),'resources/coffer/backend/coffer-backend.jar')},windowsHide:true});
  await page.getByText(/本地后台异常退出/).waitFor({timeout:15000});
  await page.getByRole('button',{name:'重新打开',exact:true}).click();
  await page.waitForURL(url=>url.pathname!=='/__desktop/start',{timeout:90000});
  assert.equal((await api(page,'/auth/me')).body.data.id,ownerA);
  const preserved=(await api(page,`/files/${file.id}`)).body.data;assert.equal(preserved.revision,detail.revision);
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(keyPath)).digest('hex'),keyHash);
  const restarted=await state();
  await page.screenshot({path:path.join(fixtureRoot,'installed-e2e.png'),fullPage:true});
  const parentApplication=application;
  execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command','$ownedPid=[int]$env:COFFER_PROBE_PARENT; $owned=Get-CimInstance Win32_Process -Filter ("ProcessId="+$ownedPid); if(-not $owned -or $owned.ExecutablePath -ne $env:COFFER_PROBE_EXE -or -not $owned.CommandLine.Contains($env:COFFER_PROBE_WORKSPACE)){throw "Launcher ownership mismatch"}; Stop-Process -Id $ownedPid -Force'],{env:{...process.env,COFFER_PROBE_PARENT:String(browserPid),COFFER_PROBE_EXE:executable,COFFER_PROBE_WORKSPACE:fixtureRoot},windowsHide:true});
  const until=Date.now()+20000;let childClosed=false;
  while(Date.now()<until){try{await plain(restarted.backendPort,'/actuator/health');}catch{childClosed=true;break;}await new Promise(resolve=>setTimeout(resolve,200));}
  assert.ok(childClosed,'Backend must close automatically when the owning launcher is force-killed');
  await parentApplication.close().catch(()=>{});application=null;
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(keyPath)).digest('hex'),keyHash);
  assert.ok(errors.every(error=>error==='cancel'),JSON.stringify(errors));
  fs.writeFileSync(path.join(fixtureRoot,'e2e-result.json'),JSON.stringify({passed:true,packagedExecutable:executable,fileId:file.id,backendPid:ports.backendPid,backendPort:ports.backendPort,sourceDeletedAfterRegistration:true,unconfirmedSourceRetained:true,personalWithoutLogin:true,accountManagementBlocked:true,nodeIntegration:false,externalPageStatus:403,workSaveRevision:detail.revision,singleInstance:true,untrustedIpcRejected:true,backendCrashVisible:true,parentCrashClosesBackend:true,keyRetained:true},null,2));
  process.stdout.write(`Packaged E2E PASS: personal library without login, native handoff/removal, drop preview, real pipeline/work version, web/IPC rejection, single instance, backend restart, parent crash lifecycle; artifacts ${fixtureRoot}\n`);
})().finally(async()=>{if(application)await application.close();if(modelServer)await new Promise(resolve=>modelServer.close(resolve));}).catch(error=>{console.error(error);process.exitCode=1;});
