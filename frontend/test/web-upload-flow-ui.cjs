// Isolated browser workflow. Every file, model endpoint and API response is fictional.
const {chromium}=require(process.env.COFFER_PLAYWRIGHT_MODULE||'playwright'),fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'../..'),working=path.join(repo,'.test-tmp/web-upload-flow'),source=process.argv.includes('--source'),base=source?path.join(working,'source-web'):path.resolve(repo,'../coffer-test-release/build-artifacts/web'),overlay=source?null:path.join(working,process.env.COFFER_UPLOAD_STAGE||'stage-v2','build-artifacts/web');
(async()=>{
 const server=http.createServer((req,res)=>{let relative=new URL(req.url,'http://localhost').pathname;if(!path.extname(relative))relative='/index.html';let f=path.resolve(base,'.'+relative),staged=overlay&&path.join(overlay,path.relative(base,f));if(staged&&fs.existsSync(staged))f=staged;if(!fs.existsSync(f)){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml'})[path.extname(f)]||'application/octet-stream');fs.createReadStream(f).pipe(res);});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const origin='http://127.0.0.1:'+server.address().port,browser=await chromium.launch({channel:'msedge',headless:true});
 const sample=path.join(working,'虚构上传.txt');fs.writeFileSync(sample,'纯虚构的上传流程测试，没有个人信息。');
 try{
  const context=await browser.newContext({viewport:{width:process.argv.includes('--narrow')?900:1280,height:884}});let taskStatus='PENDING',uploaded=false,failUpload=false,failTag=true,owner=1;const confirmations=[],errors=[],uploadKeys=[];
  let pending=[{tagId:11,tagName:'会议纪要'},{tagId:12,tagName:'测试文件'}],confirmed=[];
  function file(){return{id:41,fileName:'虚构上传.txt',fileType:'txt',fileSize:fs.statSync(sample).size,revision:1,uploadTime:'2026-10-18T00:10:00',status:taskStatus,archived:false,summary:'虚构会议摘要',taskState:taskStatus==='COMPLETED'?(pending.length?'AWAITING_CONFIRMATION':'READY'):'PROCESSING',confirmedTags:confirmed,pendingTags:pending,previewUrl:null,tags:[...confirmed.map(t=>({...t,confirmationStatus:'CONFIRMED'})),...pending.map(t=>({...t,confirmationStatus:'PENDING_CONFIRMATION'}))]};}
  await context.route(origin+'/api/**',async route=>{const p=new URL(route.request().url()).pathname,m=route.request().method();let data=[];
   const reply=(data,status=200,msg='success')=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:status,msg,data})});
   if(p.endsWith('/auth/csrf'))data='fictional';else if(p.endsWith('/auth/status'))data={setupRequired:false,setupAvailable:false,desktopPersonal:false};else if(p.endsWith('/auth/me'))data={id:owner,username:'fictional-uploader',role:'USER',enabled:true};
   else if(p.endsWith('/model-execution/target'))data={configurationVersion:'fictional-v1',mode:'API',targets:[{capability:'CHAT',baseUrl:'https://fictional.invalid',modelName:'fictional-model'}]};
   else if(p.endsWith('/files/upload')){uploadKeys.push(route.request().headers()['idempotency-key']);if(failUpload)return reply(null,500,'虚构上传失败');uploaded=true;data={taskId:'task-exact-file',fileName:'虚构上传.txt',fileSize:20,status:'PENDING'};}
   else if(p.endsWith('/files/save')){uploaded=true;data={taskId:'task-exact-file',fileName:'虚构上传.txt',status:'FAILED'};}
   else if(p.endsWith('/tasks/task-exact-file/file'))data=file();else if(p.endsWith('/tasks/task-exact-file'))data={taskId:'task-exact-file',fileName:'虚构上传.txt',status:taskStatus,progress:taskStatus==='PROCESSING'?35:taskStatus==='COMPLETED'?100:0,result:taskStatus==='FAILED'?'虚构模型暂时不可用':'虚构摘要'};
   else if(p.endsWith('/tasks/overview'))data={processingCount:0,pendingConfirmCount:0,failedCount:0,processing:[],pendingConfirm:[],failed:[]};
   else if(p.endsWith('/files/tags/confirm')){const body=route.request().postDataJSON();assert.equal(body.fileId,41,'Task identity must select file 41, never same-name file 99');confirmations.push(body.tagId);if(body.tagId===12&&failTag){failTag=false;return reply(null,500,'虚构标签确认失败');}const tag=pending.find(t=>t.tagId===body.tagId);if(tag)confirmed.push(tag);pending=pending.filter(t=>t.tagId!==body.tagId);}
   else if(p.endsWith('/files')||p.endsWith('/files/search'))data={content:uploaded?[file(),{...file(),id:99,fileSize:999,fileName:'虚构上传.txt'}]:[],totalElements:uploaded?2:0,totalPages:1};
   else if(p.endsWith('/files/41'))data=file();else if(p.endsWith('/files/99'))throw Error('Wrong same-name file requested');
   else if(p.includes('/tags/candidates'))data=confirmed.map(t=>({name:t.tagName,fileCount:1}));else if(p.endsWith('/desktop/capabilities'))data={workCopies:false};else if(p.endsWith('/runtime/status'))data={readiness:'UP',components:[],alerts:[]};
   else if(m!=='GET')throw Error('Unexpected write '+p);
   return reply(data);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const card=page.locator('#web-task-progress'),receipt=()=>page.locator('.upload-receipt[data-phase]').first();
  async function pick(){await page.locator('.upload-zone input[type=file]').setInputFiles(sample);await page.getByRole('button',{name:'同意并提交',exact:true}).click();}
  async function state(phase){await page.locator(`.upload-receipt[data-phase="${phase}"]`).waitFor();assert.equal(new URL(page.url()).pathname,'/','Upload workflow must stay on the workbench');}
  await page.goto(origin+'/');await page.getByRole('heading',{name:'最近上传',exact:true}).waitFor();assert.equal(await card.count(),0,'No empty task card');
  await pick();await state('PENDING');assert(await receipt().getByText('虚构上传.txt',{exact:true}).isVisible());
  taskStatus='PROCESSING';await card.getByRole('button',{name:'刷新状态',exact:true}).click();await state('PROCESSING');assert.equal(await receipt().locator('progress').getAttribute('value'),'35');
  taskStatus='COMPLETED';await card.getByRole('button',{name:'刷新状态',exact:true}).click();await state('CONFIRM');assert.equal(await receipt().locator('.suggested-tags span').count(),2);
  await receipt().getByRole('button',{name:'采用建议标签',exact:true}).click();await page.getByText('虚构标签确认失败',{exact:true}).waitFor();await receipt().getByRole('button',{name:'采用建议标签',exact:true}).waitFor();assert.equal(await receipt().locator('.suggested-tags span').count(),1);
  await receipt().getByRole('button',{name:'采用建议标签',exact:true}).click();await state('COMPLETED');assert.deepEqual(confirmations,[11,12,12]);
  await receipt().getByRole('button',{name:'查看文件',exact:true}).click();await page.locator('.drawer-head').getByRole('heading',{name:'虚构上传.txt',exact:true}).waitFor();assert.equal(new URL(page.url()).pathname,'/');await page.locator('.drawer-head .head-x').click();
  await page.reload();await state('COMPLETED');assert.equal(await page.locator('.web-home .suggest-card').count(),0);await page.screenshot({path:path.join(working,(source?'source':'compat')+'-completed.png')});await card.screenshot({path:path.join(working,(source?'source':'compat')+'-completed-card.png')});
  await card.getByRole('button',{name:'收起已完成',exact:true}).click();assert.equal(await card.count(),0);
  taskStatus='FAILED';await pick();await state('FAILED');assert(await receipt().getByRole('button',{name:'查看文件',exact:true}).isVisible());
  failUpload=true;await pick();if(source)await page.getByRole('button',{name:'返回',exact:true}).click();await state('UPLOAD_FAILED');
  if(source){await pick();await page.getByRole('button',{name:'仅保存',exact:true}).click();await state('SAVED');assert(await receipt().getByText('文件已安全保存，可以查看和下载，之后再进行 AI 分析。',{exact:true}).isVisible());}
  assert(uploadKeys.every(Boolean));owner=2;await page.reload();await page.getByRole('heading',{name:'最近上传',exact:true}).waitFor();assert.equal(await card.count(),0,'Upload receipts must be account isolated');assert.deepEqual(errors,[]);
  console.log('PASS '+(source?'source':'compatible')+': stay on workbench, idle collapse, queued/processing/complete/failure states, exact same-name file identity, partial tag confirmation recovery, in-place details, refresh restore, clear completed, account isolation');
 }finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
