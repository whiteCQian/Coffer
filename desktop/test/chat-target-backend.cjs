'use strict';
// Real shipped V44 backend/JRE, isolated library and fictional local model only.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {BackendSupervisor}=require('../src/backend-supervisor.cjs');
const {verifyResources}=require('../src/resources.cjs');
const repo=path.resolve(__dirname,'../..'),patch=path.resolve(process.argv[2]),root=path.join(repo,'.test-tmp','chat-target-backend-'+crypto.randomUUID());
const resources=path.join(root,'resources');
fs.mkdirSync(root,{recursive:true});fs.cpSync(path.join(repo,'desktop/dist/win-unpacked/resources/coffer'),resources,{recursive:true});
const manifest=JSON.parse(fs.readFileSync(path.join(patch,'manifest.json'),'utf8'));
for(const name of ['web/assets/modelConsent-CujxC_VH.js','web/assets/index-DYysgfLZ.js','backend/coffer-backend.jar','manifest.json'])fs.copyFileSync(path.join(patch,name),path.join(resources,name));
verifyResources(resources);
let server,supervisor;
(async()=>{
  server=http.createServer(async(req,res)=>{
    const chunks=[];for await(const c of req)chunks.push(c);const body=JSON.parse(Buffer.concat(chunks).toString()||'{}');
    const payload=req.url.endsWith('/embeddings')?{data:[{object:'embedding',index:0,embedding:Array(1024).fill(.01)}],model:'fixture',usage:{prompt_tokens:1,total_tokens:1}}:
      {id:'fixture',object:'chat.completion',created:1,model:body.model,choices:[{index:0,message:{role:'assistant',content:'虚构测试回复'},finish_reason:'stop'}]};
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(payload));
  });
  for(const port of [11434,1234,8000]){
    try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',()=>{server.removeListener('error',reject);resolve();});});break;}
    catch(e){if(e.code!=='EADDRINUSE')throw e;}
  }
  assert(server.listening,'A permitted localhost fixture port must be free');
  supervisor=new BackendSupervisor(resources,manifest,()=>{},text=>fs.appendFileSync(path.join(root,'backend.log'),text));
  const dataDirectory=path.join(root,'data');
  const cookies=new Map();
  function remember(headers){for(const raw of headers['set-cookie']||[]){const first=raw.split(';')[0],equals=first.indexOf('=');if(equals>0)cookies.set(first.slice(0,equals),first.slice(equals+1));}}
  async function api(url,method='GET',body,headers={}){
    const cookieHeader=()=>Array.from(cookies,([name,value])=>name+'='+value).join('; ');
    if(method!=='GET'){
      const csrf=await supervisor.api('/api/auth/csrf',{headers:{Cookie:cookieHeader()}});remember(csrf.headers);
      headers={...headers,'X-XSRF-TOKEN':decodeURIComponent(cookies.get('XSRF-TOKEN')||'')};
    }
    const r=await supervisor.api('/api'+url,{method,body,headers:{...headers,Cookie:cookieHeader()}});remember(r.headers);
    assert.equal(r.status,200,r.data?.msg);assert.equal(r.data?.code,0,r.data?.msg);return r.data.data;
  }
  try{
    await supervisor.start(dataDirectory,{initialize:true});
    const baseUrl='http://127.0.0.1:'+server.address().port+'/v1';
    for(const capability of ['CHAT','VISION','EMBEDDING'])await api(`/settings/runtime/config/LOCAL/${capability}`,'PUT',{baseUrl,modelName:'fictional-model'});
    const validated=await api('/settings/runtime/validate','POST',{mode:'LOCAL'});assert.equal(validated.validationSuccess,true,validated.message);
    await api('/settings/runtime/mode','PUT',{mode:'LOCAL'});
    let target=await api('/model-execution/chat-target');assert.equal(target.chatConsentApproved,false);
    await api('/chat/send','POST',{message:'请回复一条虚构测试消息'}, {'X-Coffer-Model-Version':target.configurationVersion,'X-Coffer-Allow-Sensitive':'true'});
    target=await api('/model-execution/chat-target');assert.equal(target.chatConsentApproved,true);
    await supervisor.stop();await supervisor.start(dataDirectory,{initialize:false});
    assert.equal((await api('/model-execution/chat-target')).chatConsentApproved,true,'Consent survives backend restart and a new local origin');
    await api('/settings/runtime/config/LOCAL/CHAT','PUT',{baseUrl,modelName:'fictional-model-changed'});
    assert.equal((await api('/settings/runtime/validate','POST',{mode:'LOCAL'})).validationSuccess,true);
    assert.equal((await api('/model-execution/chat-target')).chatConsentApproved,false,'Different model configuration needs a new grant');
    fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify({schemaVersion:manifest.schemaVersion,realV44Backend:true,chatScopeRecorded:true,restartReusesConsent:true,changedModelInvalidates:true},null,2));
    console.log('PASS real V44 backend: CHAT grant recorded, reused after restart, invalidated by model change. '+root);
  }finally{if(supervisor)await supervisor.stop();if(server.listening)await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
