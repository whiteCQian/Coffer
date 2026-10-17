'use strict';
// Exercise the actual Axios interceptor and consent module in current and V44 browser builds.
const {chromium}=require(process.env.COFFER_PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'..'),patch=path.resolve(process.argv[2]);
const oldWeb=path.join(repo,'desktop/dist/win-unpacked/resources/coffer/web');
const sourceWeb=path.join(repo,'.test-tmp/chat-consent-web');
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    for(const variant of ['source','v44']){
      const web=variant==='source'?sourceWeb:oldWeb;
      const server=http.createServer((req,res)=>{
        const relative=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
        let file=path.resolve(web,'.'+relative);
        if(!file.startsWith(web+path.sep))file=path.join(web,'index.html');
        if(variant==='v44'){
          const staged=path.resolve(patch,'web','.'+relative);
          if(staged.startsWith(path.join(patch,'web')+path.sep)&&fs.existsSync(staged)&&fs.statSync(staged).isFile())file=staged;
        }
        if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(web,'index.html');
        res.setHeader('Content-Type',({'.js':'text/javascript','.css':'text/css','.html':'text/html','.svg':'image/svg+xml'})[path.extname(file)]||'application/octet-stream');
        fs.createReadStream(file).pipe(res);
      });
      await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
      const origin='http://127.0.0.1:'+server.address().port;
      const context=await browser.newContext(),page=await context.newPage();
      let owner=1,version='configuration-one',chats=0,failed=false;
      const approved=new Set();
      const errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.route(origin+'/api/**',async route=>{
        const request=route.request(),url=new URL(request.url()),p=url.pathname;
        let data=[],status=200,code=0,msg='success';
        if(p.endsWith('/auth/csrf'))data='fixture-csrf';
        else if(p.endsWith('/auth/status'))data={setupRequired:false,desktopPersonal:true};
        else if(p.endsWith('/auth/me'))data={id:owner,username:'fictional-chat',role:'USER',enabled:true};
        else if(p.endsWith('/runtime/status'))data={readiness:'UP',components:[],alerts:[]};
        else if(p.endsWith('/tasks/overview'))data={processingCount:0,pendingConfirmCount:0,failedCount:0,processing:[],pendingConfirm:[],failed:[]};
        else if(p.endsWith('/settings/runtime/config'))data=[{mode:'LOCAL',capability:'CHAT',configured:true}];
        else if(p.endsWith('/settings/runtime'))data={activeMode:'LOCAL',currentModeValidated:true};
        else if(p.endsWith('/model-execution/target') || p.endsWith('/model-execution/chat-target'))data={configurationVersion:version,mode:'LOCAL',targets:[{capability:'CHAT',baseUrl:'http://fictional.invalid/v1',modelName:'fictional-model'}],chatConsentApproved:approved.has(`${owner}:${version}`)};
        else if(p.endsWith('/chat/send')){
          assert.equal(request.headers()['x-coffer-model-version'],version);
          assert.equal(request.headers()['x-coffer-allow-sensitive'],'true');chats++;approved.add(`${owner}:${version}`);
          if(failed){status=503;code=503;msg='虚构服务暂不可用';}else data={reply:'虚构回复',sessionId:'fictional-session',citations:[]};
        }
        await route.fulfill({status,contentType:'application/json',body:JSON.stringify({code,msg,data})});
      });
      try{
        await page.goto(origin+'/search');await page.locator('.chat-input-field').waitFor();
        async function send(text){await page.locator('.chat-input-field').fill(text);await page.locator('.chat-input-field').press('Enter');}
        async function waitChats(n){await page.waitForFunction(()=>!document.querySelector('.chat-input-field')?.disabled);assert.equal(chats,n);await page.locator('.el-message-box:visible').waitFor({state:'hidden'});}
        await send('虚构问题一');await page.getByRole('button',{name:'允许并记住此配置',exact:true}).waitFor();assert.equal(chats,0);
        await page.getByRole('button',{name:'允许并记住此配置',exact:true}).click();await waitChats(1);
        await send('虚构问题二');await waitChats(2);
        await page.reload();await page.locator('.chat-input-field').waitFor();await send('重开页面的虚构问题');await waitChats(3);
        failed=true;await send('服务失败的虚构问题');await waitChats(4);failed=false;await send('重试虚构问题');await waitChats(5);
        version='configuration-two';await send('更换配置后的虚构问题');await page.getByText(/模型配置或授权范围已变化/).waitFor();assert.equal(chats,5);
        await page.locator('.el-message-box').getByRole('button',{name:'取消',exact:true}).click();await page.waitForFunction(()=>!document.querySelector('.chat-input-field')?.disabled);assert.equal(chats,5);
        await send('再次确认虚构问题');await page.getByRole('button',{name:'允许并记住此配置',exact:true}).click();await waitChats(6);
        owner=2;await page.reload();await page.locator('.chat-input-field').waitFor();await send('另一账号的虚构问题');await page.getByRole('button',{name:'允许并记住此配置',exact:true}).waitFor();assert.equal(chats,6);
        await page.getByRole('button',{name:'允许并记住此配置',exact:true}).click();await waitChats(7);
        await page.evaluate(()=>localStorage.clear());await page.reload();await page.locator('.chat-input-field').waitFor();await send('清理客户端缓存后继续虚构对话');await waitChats(8);
        const indexName=/src="\/assets\/([^"/]+\.js)"/.exec(fs.readFileSync(path.join(web,'index.html'),'utf8'))[1];
        const indexCode=fs.readFileSync(variant==='v44'?path.join(patch,'web/assets',indexName):path.join(web,'assets',indexName),'utf8');
        const moduleName=/import\("\.\/(modelConsent-[^"/]+\.js)"\)/.exec(indexCode)[1];
        await page.evaluate(name=>{window.genericConsent=import('/assets/'+name).then(m=>m.confirmModelTarget()).catch(()=>null);},moduleName);
        await page.getByText('确认 AI 内容发送目标',{exact:true}).waitFor();await page.locator('.el-message-box').getByRole('button',{name:'取消',exact:true}).click();
        assert.deepEqual(errors,[]);
        console.log(`PASS ${variant}: one grant for multiple/reopened/retried messages; changed configuration/account re-prompts; cancellation and non-chat permissions preserved`);
      }finally{await context.close();await new Promise(resolve=>server.close(resolve));}
    }
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
