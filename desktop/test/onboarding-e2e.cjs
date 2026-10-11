'use strict';
// Real Electron and bundled Java, isolated libraries only; no AI or user files.
const { _electron: electron }=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process');
const desktop=path.resolve(__dirname,'..');
const packaged=Boolean(process.env.COFFER_DESKTOP_EXE);
const executable=process.env.COFFER_DESKTOP_EXE || path.join(desktop,'node_modules/electron/dist/electron.exe');
const resources=packaged?path.join(path.dirname(executable),'resources/coffer'):path.join(desktop,'.build/resources');
const root=path.resolve(desktop,'../.test-tmp','onboarding-'+crypto.randomUUID());
fs.mkdirSync(root,{recursive:true});
const defaultData=path.join(root,'recommended'),shell=path.join(root,'shell'),customParent=path.join(root,'个人资料 with spaces'),data=path.join(customParent,'Coffer');
const screenshots=[],errors=[];let application;
const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
async function launch(directory=defaultData,profile=shell){
  application=await electron.launch({executablePath:executable,args:[...(packaged?[]:[desktop]),'--coffer-data-directory='+directory,'--coffer-shell-directory='+profile],env,timeout:60000});
  const page=await application.firstWindow();page.on('pageerror',error=>errors.push(error.message));
  await application.evaluate(({BrowserWindow})=>{for(const window of BrowserWindow.getAllWindows())window.hide();});
  return page;
}
async function close(){if(application){await application.close();application=null;}}
async function selection(directory,canceled=false){await application.evaluate(({dialog},value)=>{dialog.showOpenDialog=async()=>({canceled:value.canceled,filePaths:value.canceled?[]:[value.directory]});},{directory,canceled});}
async function state(page){return page.evaluate(()=>window.cofferDesktop.status());}
async function idle(page){await page.waitForFunction(()=>['wizard','existing','recovery'].some(id=>!document.getElementById(id).hidden) && document.getElementById('busy').hidden);}
async function ready(page){await page.waitForURL(url=>url.pathname!=='/__desktop/start',{timeout:90000});await page.locator('.desktop-sidebar').waitFor({timeout:30000});}
async function shot(page,name){const target=path.join(root,name+'.png');await page.screenshot({path:target,fullPage:true,animations:'disabled'});screenshots.push(target);}
async function forbiddenInitialization(page){
  const error=await page.evaluate(async()=>{try{await window.cofferDesktop.start({initialize:true,upgrade:false});return null;}catch(error){return error.message;}});
  assert.match(error,/初始化仅允许未绑定的空目录/);
}
function ownedRename(source,target){
  for(const value of [source,target])assert.ok(path.resolve(value).startsWith(root+path.sep),'Move must stay inside this test fixture');
  fs.renameSync(source,target);
}
(async()=>{
  let page=await launch();await idle(page);
  assert.equal((await state(page)).firstRun,true);assert.equal(await page.locator('#next').isEnabled(),true);
  assert.equal(await page.locator('#recommended').isChecked(),true);assert.equal(fs.existsSync(defaultData),false);
  await shot(page,'01-location');
  await page.getByRole('button',{name:'下一步',exact:true}).click();
  assert.equal(await page.getByRole('heading',{name:'准备好创建文件库了'}).isVisible(),true);
  await page.getByRole('button',{name:'上一步',exact:true}).click();assert.equal(fs.existsSync(defaultData),false);
  await page.getByRole('button',{name:'从备份恢复',exact:true}).click();
  assert.equal(await page.locator('#restore-form').isVisible(),true);assert.equal(await page.locator('#upgrade-form').isVisible(),false);
  await page.getByRole('button',{name:'返回',exact:true}).click();
  await selection(null,true);await page.locator('#custom').click();
  await page.waitForFunction(()=>document.getElementById('recommended').checked);assert.equal((await state(page)).dataDirectory,defaultData);
  const occupiedParent=path.join(root,'occupied');fs.mkdirSync(path.join(occupiedParent,'Coffer'),{recursive:true});fs.writeFileSync(path.join(occupiedParent,'Coffer','keep.txt'),'preserve unrelated file');
  await selection(occupiedParent);await page.locator('#custom').check();
  await page.getByText('此位置已有其他内容，请选择另一个保存位置。',{exact:true}).waitFor();
  assert.equal(await page.locator('#next').isDisabled(),true);assert.equal(fs.readFileSync(path.join(occupiedParent,'Coffer','keep.txt'),'utf8'),'preserve unrelated file');
  const beforeInvalid=(await state(page)).dataDirectory;
  await selection(occupiedParent);await page.getByRole('button',{name:'打开已有文件库',exact:true}).click();
  await page.getByText('所选位置及其 Coffer 子目录都不是可用的空文件库位置。请选择空文件夹或已有文件库。',{exact:true}).waitFor();
  assert.equal((await state(page)).dataDirectory,beforeInvalid);
  fs.mkdirSync(customParent);await selection(customParent);await page.getByRole('button',{name:'选择保存位置',exact:true}).click();
  await page.waitForFunction(directory=>document.getElementById('data').textContent===directory,data);
  assert.equal(fs.existsSync(data),false);assert.equal(fs.existsSync(path.join(shell,'desktop-settings.json')),false);
  await page.getByRole('button',{name:'下一步',exact:true}).click();await shot(page,'02-confirm');
  await application.evaluate(({dialog})=>{globalThis.onboardingDialogs=0;dialog.showMessageBox=async()=>{globalThis.onboardingDialogs++;throw new Error('Unexpected duplicate initialization dialog');};});
  await page.getByRole('button',{name:'创建并进入',exact:true}).click();
  await page.locator('#busy').waitFor({state:'visible'});await shot(page,'03-creating');await ready(page);
  assert.equal(await application.evaluate(()=>globalThis.onboardingDialogs),0);
  assert.equal(fs.existsSync(defaultData),false);assert.equal(fs.existsSync(path.join(data,'database/coffer.mv.db')),true);
  const identityHash=hash(path.join(data,'.coffer-desktop.json')),keyHash=hash(path.join(data,'.coffer-encryption-key'));
  await close();page=await launch(data);await ready(page);
  assert.equal(hash(path.join(data,'.coffer-desktop.json')),identityHash);assert.equal(hash(path.join(data,'.coffer-encryption-key')),keyHash);await close();
  console.log('PASS: first-run steps, custom/canceled/occupied locations, creation without duplicate dialog, automatic reopen.');

  // Exercise the separate upgrade flow against a fixture reverted to the actual V43 schema.
  assert.equal(JSON.parse(fs.readFileSync(path.join(resources,'manifest.json'))).schemaVersion,44,'Update the legacy fixture when a new migration is added');
  const jar=await require('unzipper').Open.file(path.join(resources,'backend/coffer-backend.jar'));
  const h2Entry=jar.files.find(file=>/^BOOT-INF\/lib\/h2-.*\.jar$/.test(file.path));assert.ok(h2Entry);
  const h2File=path.join(root,'h2.jar');fs.writeFileSync(h2File,await h2Entry.buffer());
  const sqlOutput=execFileSync(path.join(resources,'runtime/bin/java.exe'),['-cp',h2File,'org.h2.tools.Shell','-url','jdbc:h2:file:'+path.join(data,'database/coffer').replaceAll('\\','/')+';IFEXISTS=TRUE','-user','sa','-password','','-sql','DROP TABLE desktop_native_transfer; DELETE FROM "flyway_schema_history" WHERE "version"=\'44\';'],{windowsHide:true,encoding:'utf8',timeout:15000});
  assert.doesNotMatch(sqlOutput,/Error|Exception/);
  page=await launch(data);await page.getByRole('heading',{name:'更新文件库',exact:true}).waitFor({timeout:60000});
  assert.equal(await page.locator('#wizard').isVisible(),false);assert.equal(await page.locator('#upgrade-password').isVisible(),true);assert.equal(await page.locator('#restore-password').isVisible(),false);
  await shot(page,'04-upgrade');
  await application.evaluate(({dialog})=>{dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});});
  await page.locator('#upgrade-password').fill('Isolated-onboarding-passphrase');
  await page.getByRole('button',{name:'备份并更新',exact:true}).click();await ready(page);
  assert.equal(hash(path.join(data,'.coffer-encryption-key')),keyHash);assert.equal(hash(path.join(data,'.coffer-desktop.json')),identityHash);
  assert.ok(fs.readdirSync(path.join(data,'.upgrade-backups')).some(name=>name.endsWith('.cofferbackup')));
  const upgradedSettings=JSON.parse(fs.readFileSync(path.join(shell,'desktop-settings.json')));assert.equal(upgradedSettings.schemaVersion,44);
  console.log('PASS: encrypted V43 to V44 upgrade, original key and library identity retained.');

  // A real encrypted snapshot is restored through the new, independent recovery screen.
  const snapshots=path.join(root,'snapshots');fs.mkdirSync(snapshots);
  await application.evaluate(({dialog},directory)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[directory]});},snapshots);
  const backup=await page.evaluate(async()=>{await window.cofferDesktop.chooseBackupDestination();return window.cofferDesktop.createBackup({password:'Isolated-onboarding-passphrase',localSnapshot:true});});
  assert.ok(backup.packagePath);await close();
  const restored=path.join(root,'restored');fs.mkdirSync(restored);
  const unused=path.join(root,'unused');page=await launch(unused,path.join(root,'restore-shell'));await idle(page);
  await page.getByRole('button',{name:'从备份恢复',exact:true}).click();
  await application.evaluate(({dialog},selected)=>{let index=0;dialog.showOpenDialog=async()=>({canceled:false,filePaths:[index++===0?selected.archive:selected.target]});dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});},{archive:backup.packagePath,target:restored});
  await page.getByRole('button',{name:'选择备份和恢复位置',exact:true}).click();
  await page.locator('#restore-password').fill('Isolated-onboarding-passphrase');await shot(page,'05-restore');
  await page.getByRole('button',{name:'验证并恢复',exact:true}).click();await page.getByText(/文件库恢复已验证/).waitFor({timeout:90000});
  assert.equal(fs.existsSync(unused),false);assert.equal(hash(path.join(restored,'.coffer-encryption-key')),keyHash);
  await page.getByRole('button',{name:'打开文件库',exact:true}).click();await ready(page);await close();
  console.log('PASS: encrypted snapshot restored through the separate recovery screen and opened.');

  // Explicitly opening an existing library never initializes the initially suggested directory.
  page=await launch(unused,path.join(root,'open-shell'));await idle(page);await selection(data);
  await page.getByRole('button',{name:'打开已有文件库',exact:true}).click();
  await page.getByRole('heading',{name:'打开你的文件库',exact:true}).waitFor();assert.equal((await state(page)).canCreate,false);
  await forbiddenInitialization(page);await page.getByRole('button',{name:'打开文件库',exact:true}).click();await ready(page);await close();
  assert.equal(fs.existsSync(unused),false);

  // A detached disk, missing database and missing key all retain the recovery flow.
  const detached=path.join(customParent,'detached-library');ownedRename(data,detached);
  try{
    page=await launch(data);await idle(page);assert.equal((await state(page)).firstRun,false);
    assert.equal(await page.locator('#wizard').isVisible(),false);await forbiddenInitialization(page);assert.equal(fs.existsSync(data),false);
    await shot(page,'06-missing-location');await close();
  }finally{await close();ownedRename(detached,data);}
  for(const name of ['.coffer-encryption-key','database/coffer.mv.db']){
    const original=path.join(data,name),parked=path.join(root,path.basename(name)+'.parked');ownedRename(original,parked);
    try{
      page=await launch(data);await idle(page);assert.equal((await state(page)).firstRun,false);assert.equal(await page.locator('#wizard').isVisible(),false);
      await forbiddenInitialization(page);assert.equal(fs.existsSync(original),false);await close();
    }finally{await close();ownedRename(parked,original);}
  }
  assert.equal(hash(path.join(data,'.coffer-encryption-key')),keyHash);

  // A launcher interrupted during its first creation cannot hide partial data behind a new welcome screen.
  const partial=path.join(root,'partial'),partialShell=path.join(root,'partial-shell');fs.mkdirSync(partial);fs.mkdirSync(partialShell);
  fs.writeFileSync(path.join(partial,'partial-file'),'retain');fs.writeFileSync(path.join(partialShell,'desktop-settings.json'),JSON.stringify({dataDirectory:partial,initialized:false,creationStarted:true}));
  page=await launch(partial,partialShell);await idle(page);assert.equal((await state(page)).firstRun,false);await forbiddenInitialization(page);
  assert.equal(fs.readFileSync(path.join(partial,'partial-file'),'utf8'),'retain');await close();
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(root,'result.json'),JSON.stringify({passed:true,packaged,twoSteps:true,customLocation:true,cancelRetainsSelection:true,occupiedLocationRetained:true,noDuplicateConfirmation:true,automaticReopen:true,encryptedUpgrade:true,encryptedRestore:true,existingLibraryOpened:true,missingLocationDatabaseAndKeyProtected:true,partialCreationProtected:true,screenshots},null,2));
  console.log('PASS: two-step wizard, custom/canceled/occupied selections, real creation/reopen, encrypted upgrade/restore, existing-library opening, missing location/database/key and interrupted creation protection.');
  console.log('Artifacts: '+root);
})().finally(async()=>{await close();}).catch(error=>{console.error(error);process.exitCode=1;});
