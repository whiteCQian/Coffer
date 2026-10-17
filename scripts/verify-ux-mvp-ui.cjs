'use strict';
// UI contract/layout checks with fictional HTTP fixtures. Not a deployed-server or packaged-desktop acceptance.
const { chromium } = require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
let origin = process.env.COFFER_UI_ORIGIN;
const fs = require('node:fs');
const output = require('node:path').resolve(__dirname, '../.test-tmp/ux-evidence');
fs.mkdirSync(output, {recursive:true});
const now = '2026-10-15T12:00:00';
const question = '周六亲子活动需要准备什么';
const fixture = id => ({id,fileName:`虚构公园记录${id}.txt`,fileSize:32,fileType:'txt',uploadTime:now,summary:'虚构活动准备清单',status:'COMPLETED',taskState:'AWAITING_CONFIRMATION',archived:false,category:'REPORT',revision:1,tagStatus:'PENDING',tags:[{tagId:1,tagName:'活动',confirmationStatus:'PENDING_CONFIRMATION'}],confirmedTags:[],pendingTags:[{tagId:1,tagName:'活动',confirmationStatus:'PENDING_CONFIRMATION'}]});
(async () => {
  let server;
  if(!origin){
    const path=require('node:path'),build=path.resolve(__dirname,'../.test-tmp/ux-web-build');
    server=require('node:http').createServer((request,response)=>{
      let file=path.resolve(build,'.'+decodeURIComponent(new URL(request.url,'http://localhost').pathname));
      if(!file.startsWith(build+path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory())file=path.join(build,'index.html');
      response.setHeader('Content-Type',({'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'})[path.extname(file)] || 'application/octet-stream');
      fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));origin='http://127.0.0.1:'+server.address().port;
  }
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    for (const mode of [{personal:false,width:640,scale:1},{personal:false,width:1024,scale:1},{personal:false,width:1440,scale:1},{personal:true,width:800,scale:1.25},{personal:true,width:800,scale:1.5}]) {
      const context = await browser.newContext({viewport:{width:mode.width,height:600},deviceScaleFactor:mode.scale});
      const page = await context.newPage(), errors=[], calls=[];
      let files=[fixture(1),fixture(2)], testOutcome='timeout';
      page.on('pageerror',e=>errors.push(e.message));
      await page.route(origin+'/api/**',async route=>{
        const request=route.request(), url=new URL(request.url()), path=url.pathname, body=request.postDataJSON?.();
        calls.push({path,method:request.method(),body});
        let data=[],code=0,msg='success';
        if(path.endsWith('/auth/csrf'))data='fixture-csrf';
        else if(path.endsWith('/auth/status'))data={setupRequired:false,setupAvailable:false,desktopPersonal:mode.personal};
        else if(path.endsWith('/auth/me'))data={id:1,username:'fictional-ux',role:'USER',enabled:true};
        else if(path.endsWith('/runtime/status'))data={readiness:'UP',components:[],alerts:[]};
        else if(path.endsWith('/tasks/overview'))data={processingCount:0,failedCount:0,pendingConfirmCount:files.filter(f=>!f.archived).length,processing:[],failed:[],pendingConfirm:[]};
        else if(path.endsWith('/files'))data={content:url.searchParams.get('keyword')===question?[]:files,totalElements:url.searchParams.get('keyword')===question?0:files.length,totalPages:1};
        else if(/\/files\/\d+$/.test(path))data=files.find(f=>f.id===Number(path.split('/').pop()));
        else if(path.endsWith('/confirm-archive')){
          const id=Number(path.split('/').at(-2));
          if(id===2){code=400;msg='虚构异常，文件已保存';}
          else {const f=files.find(f=>f.id===id);Object.assign(f,{archived:true,taskState:'ARCHIVED',revision:2,pendingTags:[],confirmedTags:(body.tags??['活动']).map((tagName,i)=>({tagId:i+1,tagName,confirmationStatus:'CONFIRMED'}))});data={batchId:'fixture-archive',status:'SUCCEEDED'};}
        }
        else if(path.endsWith('/governance/operations/recent'))data={content:Array.from({length:20},(_,i)=>({id:i+1,fileId:1,batchId:'internal-hidden-'+i,sourceFileName:'虚构公园记录1.txt',targetFileName:'虚构公园记录1.txt',createdAt:now,executionStatus:'SUCCEEDED',rollbackStatus:'NOT_REQUESTED',attempts:1})),totalPages:1,totalElements:20};
        else if(path.endsWith('/settings/runtime/config/test')){
          if(body.capability==='VISION'){await new Promise(r=>setTimeout(r,500));data={capability:'VISION',success:testOutcome==='success',message:testOutcome==='timeout'?'模型生成响应超时，尚未确认连接':testOutcome==='failure'?'模型服务暂时不可用':'连接成功'};}
          else data={capability:'CHAT',success:true,message:'连接成功'};
        }
        else if(path.endsWith('/settings/runtime/config'))data=['API','LOCAL'].flatMap(mode=>['CHAT','VISION'].map(capability=>({mode,capability,baseUrl:'http://fixture.invalid/v1',modelName:'fictional-model',configured:true,maskedApiKey:'••••fixture',source:'CUSTOM'})));
        else if(path.endsWith('/settings/runtime'))data={activeMode:'API',validatedModes:{API:false,LOCAL:false},validatedAt:{},currentModeValidated:false};
        else if(path.endsWith('/model-execution/target'))data={configurationVersion:'fictional-target',mode:'API',targets:[]};
        else if(path.endsWith('/settings/credentials/status'))data={configured:{DEEPSEEK:false}};
        await route.fulfill({contentType:'application/json',body:JSON.stringify({code,msg,data})});
      });
      await page.goto(origin+'/files');
      await page.getByText('虚构公园记录1.txt',{exact:true}).first().click();
      const card=page.locator('.confirmation-card');await card.waitFor();
      assert.equal(await card.locator('details').getAttribute('open'),null);
      await card.getByLabel('新增标签',{exact:true}).fill('虚构测试');await card.getByRole('button',{name:'添加',exact:true}).click();
      await card.locator('summary').click();
      await card.locator('textarea').fill('虚构修改摘要');
      await card.locator('summary').click();
      await card.getByRole('button',{name:'确认并归档',exact:true}).click();
      await page.locator('.state-chip').filter({hasText:'已归档'}).waitFor();
      assert.equal(calls.filter(c=>c.path.endsWith('/confirm-archive')).length,1);
      assert.equal(calls.find(c=>c.path.endsWith('/confirm-archive')).body.summary,'虚构修改摘要');
      assert.equal(calls.filter(c=>c.path==='/api/governance/previews').length,0);
      if(mode.width<=960)await page.locator('.drawer-head .head-x').click();
      files.push({...fixture(3),status:'FAILED',taskState:'SAVED_NEEDS_ATTENTION',summary:null});
      await page.getByRole('button',{name:'刷新文件库',exact:true}).click();
      await page.getByRole('checkbox',{name:'选择 虚构公园记录2.txt',exact:true}).check();
      await page.getByRole('checkbox',{name:'选择 虚构公园记录3.txt',exact:true}).check();
      await page.getByRole('button',{name:'确认并归档这 2 个文件',exact:true}).click();
      await page.getByText('已受理 1 个，1 个未受理；文件已保存，未受理项可重试。',{exact:true}).waitFor();
      assert.equal(await page.getByRole('checkbox',{name:'选择 虚构公园记录2.txt',exact:true}).isChecked(),true);
      await page.getByText('已归档，可以查找和取回',{exact:true}).waitFor();
      await page.locator('.desk-search input').first().fill(question);
      await page.getByRole('button',{name:`用 AI 查找“${question}”`,exact:true}).click();
      await page.locator('.chat-input-field').waitFor();
      assert.equal(await page.locator('.chat-input-field').inputValue(),question);
      assert.equal(calls.filter(c=>c.path.endsWith('/chat/send')).length,0);
      await page.goto(origin+'/operations');await page.locator('.recent-row').first().waitFor();
      assert.equal(await page.locator('.technical-ledger').getAttribute('open'),null);
      assert.equal(await page.getByText('internal-hidden-0',{exact:true}).isVisible().catch(()=>false),false);
      await page.locator('.recent-row').last().getByRole('button',{name:'撤销整理',exact:true}).scrollIntoViewIfNeeded();
      const box=await page.locator('.recent-row').last().boundingBox();assert(box.y<600 && box.y+box.height>0);
      await page.screenshot({path:output+`/recent-${mode.personal?'desktop':'web'}-${mode.width}-${mode.scale}.png`});
      await page.goto(origin+'/settings');
      await page.getByRole('button',{name:'测试当前模式',exact:true}).click();
      await page.locator('.chat-rag-card').getByText(/测试通过 ·/).waitFor();
      await page.locator('.endpoint-card').filter({has:page.getByRole('heading',{name:/Vision/})}).getByText(/测试超时 ·/).waitFor();
      await page.reload();await page.getByText(/测试超时 ·/).waitFor();
      testOutcome='failure';await page.getByRole('button',{name:'测试连接',exact:true}).click();await page.getByText(/测试失败 ·/).waitFor();
      testOutcome='success';await page.getByRole('button',{name:'测试连接',exact:true}).click();await page.getByText(/测试图片请求成功/).waitFor();
      const vision=page.locator('.endpoint-card').filter({has:page.getByRole('heading',{name:/Vision/})});
      await vision.locator('input[type=url]').fill('http://changed.invalid/v1');await vision.getByText('尚未测试当前配置',{exact:true}).waitFor();
      assert.deepEqual(errors,[]);console.log(`PASS UI fixture ${mode.personal?'desktop':'web'} ${mode.width}px scale ${mode.scale}`);
      await context.close();
    }
  } finally {await browser.close();if(server)await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1});
