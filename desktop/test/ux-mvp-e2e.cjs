'use strict';
// Builds a separate SOURCE shell resource tree. Never rewrites desktop/.build or desktop/dist.
const { _electron: electron }=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),http=require('node:http'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'../..'), root=path.join(repo,'.test-tmp','ux-source-'+crypto.randomUUID()),appRoot=path.join(root,'app'), resources=path.join(appRoot,'.build/resources');
fs.mkdirSync(resources,{recursive:true});
fs.cpSync(path.join(repo,'desktop/src'),path.join(appRoot,'src'),{recursive:true});
fs.writeFileSync(path.join(appRoot,'package.json'),JSON.stringify({name:'coffer-ux-source-probe',version:'0.2.1',main:'src/main.cjs'}));
fs.cpSync(path.join(repo,'desktop/.build/resources/runtime'),path.join(resources,'runtime'),{recursive:true});
fs.cpSync(path.join(repo,'.test-tmp/ux-web-build'),path.join(resources,'web'),{recursive:true});
fs.mkdirSync(path.join(resources,'backend'));
fs.copyFileSync(path.join(repo,'backend/target/desktop/coffer-backend-0.0.1-SNAPSHOT.jar'),path.join(resources,'backend/coffer-backend.jar'));
const {hashFile,verifyResources}=require('../..//desktop/src/resources.cjs');
const entries=[];function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())walk(f);else entries.push({path:path.relative(resources,f).split(path.sep).join('/'),size:fs.statSync(f).size,sha256:hashFile(f)});}}
walk(resources);fs.writeFileSync(path.join(resources,'manifest.json'),JSON.stringify({formatVersion:1,version:'0.2.1',schemaVersion:47,files:entries}));verifyResources(resources);
let application,model;
async function api(page,route,method='GET',body){return page.evaluate(async({route,method,body})=>{
  await fetch('/api/auth/csrf');const cookie=document.cookie.split('; ').find(v=>v.startsWith('XSRF-TOKEN='));const headers={};
  if(cookie)headers['X-XSRF-TOKEN']=decodeURIComponent(cookie.slice(11));if(body!==undefined)headers['Content-Type']='application/json';
  const response=await fetch('/api'+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});return {status:response.status,body:await response.json()};
},{route,method,body});}
async function wait(check){const end=Date.now()+90000;while(Date.now()<end){const value=await check();if(value)return value;await new Promise(r=>setTimeout(r,200));}throw Error('UX source probe deadline exceeded');}
(async()=>{
  const copy=path.join(root,'虚构公园会议.txt'),move=path.join(root,'虚构移动记录.txt');
  const text='Fictional park meeting: bring water and a magnifier.';fs.writeFileSync(copy,text);fs.writeFileSync(move,'Fictional move sample.');
  model=http.createServer(async(req,res)=>{const chunks=[];for await(const c of req)chunks.push(c);const body=JSON.parse(Buffer.concat(chunks).toString() || '{}');const prompt=JSON.stringify(body.messages || []);
    const answer=prompt.includes('Reply with OK only')?'OK':prompt.includes('synthetic test image')?'red':JSON.stringify({category:'报告',tags:['活动','虚构资料']});
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({id:'fictional',object:'chat.completion',created:1,model:'fictional',choices:[{index:0,message:{role:'assistant',content:answer},finish_reason:'stop'}]}));
  });
  // The production launcher deliberately drops arbitrary environment overrides.
  // Bind only a free port from the existing local-model allowlist.
  for(const port of [11434,1234,8000]){
    try{await new Promise((resolve,reject)=>{model.once('error',reject);model.listen(port,'127.0.0.1',()=>{model.removeListener('error',reject);resolve();});});break;}
    catch(error){if(error.code!=='EADDRINUSE')throw error;}
  }
  assert(model.listening,'No free permitted local fixture port');
  const baseUrl='http://127.0.0.1:'+model.address().port+'/v1';
  try{
    application=await electron.launch({executablePath:path.join(repo,'desktop/node_modules/electron/dist/electron.exe'),args:[appRoot,'--coffer-test-autostart=true','--coffer-data-directory='+path.join(root,'data'),'--coffer-shell-directory='+path.join(root,'shell')],timeout:60000});
    const page=await application.firstWindow(),errors=[];page.on('pageerror',e=>errors.push(e.message));
    await application.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows().forEach(w=>w.hide()));
    await page.locator('.desktop-sidebar').waitFor({timeout:90000});
    for(const capability of ['CHAT','VISION']){const result=await api(page,`/settings/runtime/config/LOCAL/${capability}`,'PUT',{mode:'LOCAL',capability,baseUrl,modelName:'fictional-model'});assert.equal(result.body.code,0,result.body.msg);}
    assert.equal((await api(page,'/settings/runtime/validate','POST',{mode:'LOCAL'})).body.data.validationSuccess,true);
    assert.equal((await api(page,'/settings/runtime/mode','PUT',{mode:'LOCAL'})).body.code,0);
    async function importFile(source,moveMode){
      await application.evaluate(({dialog},source)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[source]});},source);
      await page.getByRole('button',{name:'选择本地文件',exact:true}).click();
      if(moveMode){await page.locator('.desktop-import input[type=checkbox]').check();await page.getByRole('button',{name:'移动到文件库',exact:true}).click();await page.locator('.el-message-box').getByRole('button',{name:'确认移动',exact:true}).click();}
      else await page.getByRole('button',{name:'保存一份到 Coffer',exact:true}).click();
      await page.locator('.el-message-box').getByRole('button',{name:'同意并提交',exact:true}).click();
      const saved=await wait(async()=>{const r=await api(page,'/files');return r.body.data?.content?.find(f=>f.fileName===path.basename(source)&&f.status==='COMPLETED');});
      assert.equal(fs.existsSync(source),!moveMode);
      await page.getByRole('link',{name:'全部文件',exact:true}).first().click().catch(async()=>{await page.goto(new URL('/files',page.url()).href)});
      await page.getByText(saved.fileName,{exact:true}).first().click();await page.locator('.confirmation-card').getByRole('button',{name:'确认并归档',exact:true}).click();
      const archived=await wait(async()=>{const r=await api(page,`/files/${saved.id}`);return r.body.data?.archived && r.body.data;});
      assert.equal(archived.fileName,path.basename(source));
      const bytes=await page.evaluate(async id=>Array.from(new Uint8Array(await(await fetch(`/api/files/${id}/content?download=true`)).arrayBuffer())),saved.id);
      assert.equal(Buffer.from(bytes).toString(),moveMode?'Fictional move sample.':text);
      assert.equal((await api(page,'/files?keyword='+encodeURIComponent(path.basename(source)))).body.data.totalElements,1);
      if(await page.locator('.drawer-head .head-x').isVisible())await page.locator('.drawer-head .head-x').click();
      return archived;
    }
    await importFile(copy,false);await importFile(move,true);
    assert.equal(fs.readFileSync(copy,'utf8'),text);
    await application.evaluate(({dialog},source)=>{dialog.showOpenDialog=async()=>({canceled:false,filePaths:[source]});},copy);
    await page.getByRole('button',{name:'选择本地文件',exact:true}).click();
    assert.equal(await page.locator('.desktop-import input[type=checkbox]').isChecked(),false,'Every new selection defaults to keeping the source');
    await page.locator('.desktop-import').getByRole('button',{name:'取消',exact:true}).click();
    await page.goto(new URL('/operations',page.url()).href);await page.locator('.recent-row').first().waitFor();
    for(const zoom of [1.25,1.5]){
      await application.evaluate(({BrowserWindow},zoom)=>{const w=BrowserWindow.getAllWindows()[0];w.setSize(800,600);w.webContents.setZoomFactor(zoom);},zoom);
      await page.locator('.recent-row').last().getByRole('button',{name:'撤销整理',exact:true}).scrollIntoViewIfNeeded();
      const box=await page.locator('.recent-row').last().boundingBox(),size=await page.evaluate(()=>({height:innerHeight,width:innerWidth}));assert(box.y<size.height&&box.y+box.height>0);
      console.log(`PASS actual Electron geometry at zoom ${zoom}: last record and action reachable`);
    }
    assert.deepEqual(errors,[]);console.log('PASS actual source Electron: copy -> analysis -> one confirmation -> archive -> search -> byte-identical retrieval; explicit move; recent operations at 125%/150%. Evidence: '+root);
  }finally{if(application)await application.close();await new Promise(r=>model.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
