'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const esbuild=require('../../frontend/node_modules/esbuild');
const code=esbuild.buildSync({entryPoints:[require('node:path').resolve(__dirname,'../../frontend/src/api/chatTargetConsent.ts')],bundle:true,write:false,format:'cjs',platform:'node'}).outputFiles[0].text;
const compiled={exports:{}};vm.runInNewContext(code,{module:compiled,exports:compiled.exports,Map,JSON,Error});
const {createChatTargetConsent}=compiled.exports;
function fixture(){
  const state={owner:1,epoch:0,version:'v1',asks:0,cancel:false,messages:[]},values=new Map();
  const options={identity:()=>({owner:state.owner,epoch:state.epoch}),target:async()=>({configurationVersion:state.version,mode:'LOCAL',targets:[]}),
    confirm:async message=>{state.asks++;state.messages.push(message);if(state.cancel)throw Error('cancel');},
    storage:{getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}};
  return {state,values,options,consent:createChatTargetConsent(options)};
}
test('same account/config needs one grant, including another page instance',async()=>{
  const f=fixture();assert.equal(await f.consent(),'v1');await f.consent();await createChatTargetConsent(f.options)();assert.equal(f.state.asks,1);
  assert.deepEqual([...f.values.values()].map(v=>JSON.parse(v)),[{scope:'account-file-chat-v1',version:'v1'}]);
});
test('model configuration or account changes require a distinct grant',async()=>{
  const f=fixture();await f.consent();f.state.version='v2';await f.consent();assert.match(f.state.messages[1],/配置或授权范围已变化/);
  f.state.owner=2;f.state.epoch++;await f.consent();assert.equal(f.state.asks,3);
});
test('cancel does not authorize a retry',async()=>{
  const f=fixture();f.state.cancel=true;await assert.rejects(f.consent(),/cancel/);assert.equal(f.values.size,0);
  f.state.cancel=false;await f.consent();assert.equal(f.state.asks,2);
});
test('identity changes while a dialog is open cannot store a grant',async()=>{
  const f=fixture();f.options.confirm=async()=>{f.state.epoch++;};await assert.rejects(createChatTargetConsent(f.options)(),/账号已变化/);assert.equal(f.values.size,0);
});
test('concurrent requests share one dialog',async()=>{
  const f=fixture();let finish;f.options.confirm=()=>{f.state.asks++;return new Promise(resolve=>{finish=resolve;});};
  const consent=createChatTargetConsent(f.options),a=consent(),b=consent();await new Promise(resolve=>setImmediate(resolve));finish();await Promise.all([a,b]);assert.equal(f.state.asks,1);
});
test('blocked browser storage still permits repeated messages after one grant',async()=>{
  const f=fixture();f.options.storage={getItem(){throw Error('blocked');},setItem(){throw Error('blocked');}};
  const consent=createChatTargetConsent(f.options);await consent();await consent();assert.equal(f.state.asks,1);
});
test('a durable server CHAT grant is reused without client storage or a new dialog',async()=>{
  const f=fixture();f.options.target=async()=>({configurationVersion:'v1',mode:'LOCAL',targets:[],chatConsentApproved:true});
  assert.equal(await createChatTargetConsent(f.options)(),'v1');assert.equal(f.state.asks,0);assert.equal(f.values.size,0);
});
