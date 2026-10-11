'use strict';
const {_electron:electron}=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const desktop=path.resolve(__dirname,'..'),packaged=Boolean(process.env.COFFER_DESKTOP_EXE);
const executable=process.env.COFFER_DESKTOP_EXE || path.join(desktop,'node_modules/electron/dist/electron.exe');
const root=path.resolve(desktop,'../.test-tmp','new-library-'+crypto.randomUUID());fs.mkdirSync(root,{recursive:true});
const original=path.join(root,'original'),profile=path.join(root,'shell'),target=path.join(root,'new-empty'),failedTarget=path.join(root,'failed-new');
const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
let application;const errors=[],hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const settings=()=>fs.readFileSync(path.join(profile,'desktop-settings.json'),'utf8');
async function launch(){
  application=await electron.launch({executablePath:executable,args:[...(packaged?[]:[desktop]),'--coffer-data-directory='+original,'--coffer-shell-directory='+profile],env,timeout:60000});
  const page=await application.firstWindow();page.on('pageerror',error=>errors.push(error.message));
  await application.evaluate(({BrowserWindow})=>{for(const window of BrowserWindow.getAllWindows())window.hide();});return page;
}
async function close(){if(application){await application.close();application=null;}}
async function ready(page){await page.waitForURL(url=>url.pathname!=='/__desktop/start',{timeout:90000});await page.locator('.desktop-sidebar').waitFor({timeout:30000});}
async function select(directory,canceled=false){await application.evaluate(({dialog},value)=>{dialog.showOpenDialog=async()=>({canceled:value.canceled,filePaths:value.canceled?[]:[value.directory]});},{directory,canceled});}
async function state(page){return page.evaluate(()=>window.cofferDesktop.status());}
async function manage(page){await page.goto(new URL(page.url()).origin+'/settings');await page.getByRole('button',{name:'管理文件库',exact:true}).click();await page.locator('#existing').waitFor({state:'visible'});}
async function choose(page,directory,button='重新选择文件库位置'){await select(directory);await page.getByRole('button',{name:button,exact:true}).click();await page.locator('#wizard').waitFor({state:'visible'});}
(async()=>{
  let page=await launch();await page.getByRole('button',{name:'下一步',exact:true}).click();await page.getByRole('button',{name:'创建并进入',exact:true}).click();await ready(page);
  await manage(page);
  const oldSettings=settings(),oldKey=hash(path.join(original,'.coffer-encryption-key')),oldDatabase=hash(path.join(original,'database/coffer.mv.db'));
  await select(null,true);await page.getByRole('button',{name:'重新选择文件库位置',exact:true}).click();
  assert.equal(settings(),oldSettings);assert.equal((await state(page)).boundDataDirectory,original);
  fs.mkdirSync(target);await choose(page,target);
  assert.equal((await state(page)).newLibraryCreation,true);assert.equal((await state(page)).dataDirectory,target);
  assert.equal(settings(),oldSettings);assert.equal(fs.existsSync(path.join(target,'database')),false);
  await page.getByRole('button',{name:'下一步',exact:true}).click();
  await page.getByRole('button',{name:'取消新建，返回原库',exact:true}).click();await page.locator('#existing').waitFor({state:'visible'});
  assert.equal(settings(),oldSettings);assert.equal(hash(path.join(original,'database/coffer.mv.db')),oldDatabase);

  const nested=path.join(original,'nested-empty');fs.mkdirSync(nested);await select(nested);
  await page.getByRole('button',{name:'重新选择文件库位置',exact:true}).click();
  await page.getByText('新空库的位置必须与原库分开，请选择原库之外的空文件夹。',{exact:true}).waitFor();assert.equal(settings(),oldSettings);

  // Inject only a startup failure to exercise commit boundaries; successful creation uses real Java/API.
  fs.mkdirSync(failedTarget);await choose(page,failedTarget,'新建空文件库');
  await application.evaluate(({app})=>{
    const load=process.getBuiltinModule('module').createRequire(app.getAppPath()+'/package.json');
    const supervisor=load('./src/backend-supervisor.cjs').BackendSupervisor;
    globalThis.savedNewLibraryStart=supervisor.prototype.start;
    supervisor.prototype.start=async function(directory){process.getBuiltinModule('fs').writeFileSync(process.getBuiltinModule('path').join(directory,'retain-partial.txt'),'retain');throw new Error('BACKEND_SPAWN_FAILED');};
  });
  await page.getByRole('button',{name:'下一步',exact:true}).click();await page.getByRole('button',{name:'创建并进入',exact:true}).click();
  await page.waitForFunction(()=>document.getElementById('wizard').hidden===false && document.getElementById('message').textContent.length>0);
  assert.equal(settings(),oldSettings);assert.equal(fs.readFileSync(path.join(failedTarget,'retain-partial.txt'),'utf8'),'retain');
  assert.equal(hash(path.join(original,'.coffer-encryption-key')),oldKey);assert.equal(hash(path.join(original,'database/coffer.mv.db')),oldDatabase);
  await application.evaluate(({app})=>{process.getBuiltinModule('module').createRequire(app.getAppPath()+'/package.json')('./src/backend-supervisor.cjs').BackendSupervisor.prototype.start=globalThis.savedNewLibraryStart;});
  await page.getByRole('button',{name:'取消新建，返回原库',exact:true}).click();await page.locator('#existing').waitFor({state:'visible'});
  assert.equal(settings(),oldSettings);
  console.log('PASS: empty-folder selection, cancellation, overlap protection, startup failure and original binding/key/database preservation.');

  await choose(page,target);await page.getByRole('button',{name:'下一步',exact:true}).click();
  await page.screenshot({path:path.join(root,'new-library-confirm.png'),fullPage:true,animations:'disabled'});
  await page.getByRole('button',{name:'创建并进入',exact:true}).click();await page.locator('#busy').waitFor({state:'visible'});
  assert.equal(JSON.parse(settings()).dataDirectory,original,'Keep original binding until the new backend is healthy');
  await ready(page);
  assert.equal(JSON.parse(settings()).dataDirectory,target);assert.equal((await state(page)).boundDataDirectory,target);
  assert.notEqual(hash(path.join(target,'.coffer-desktop.json')),hash(path.join(original,'.coffer-desktop.json')));
  assert.notEqual(hash(path.join(target,'.coffer-encryption-key')),oldKey);
  assert.equal(hash(path.join(original,'.coffer-encryption-key')),oldKey);assert.equal(hash(path.join(original,'database/coffer.mv.db')),oldDatabase);
  const files=await page.evaluate(async()=>{const response=await fetch('/api/files');return response.json();});assert.equal(files.data.totalElements,0);
  const newKey=hash(path.join(target,'.coffer-encryption-key'));await close();
  page=await launch();await ready(page); // Same old launcher arguments must not override the saved new-library choice.
  assert.equal((await state(page)).dataDirectory,target);assert.equal(hash(path.join(target,'.coffer-encryption-key')),newKey);
  await manage(page);await select(original);await page.getByRole('button',{name:'重新选择文件库位置',exact:true}).click();
  await page.getByRole('button',{name:'打开文件库',exact:true}).click();await ready(page);
  assert.equal((await state(page)).dataDirectory,original);assert.equal(hash(path.join(original,'.coffer-encryption-key')),oldKey);
  assert.equal(hash(path.join(target,'.coffer-encryption-key')),newKey);
  assert.deepEqual(errors,[]);await close();
  fs.writeFileSync(path.join(root,'result.json'),JSON.stringify({passed:true,packaged,emptyFolderCreatesDraft:true,cancelPreservesBinding:true,failurePreservesBindingAndData:true,overlapRefused:true,partialDirectoryRetained:true,realNewLibraryCreated:true,newLibraryEmpty:true,distinctKeyAndIdentity:true,originalKeyAndDatabaseUnchanged:true,savedChoiceSurvivesOriginalLauncherArgs:true,canReopenOriginal:true},null,2));
  console.log('PASS: real empty-library creation, distinct identity/key, original data preservation, saved choice on restart and reopening the original library.');
  console.log('Artifacts: '+root);
})().finally(close).catch(error=>{console.error(error);process.exitCode=1;});
