'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http');
const {LoopbackServer}=require('../src/loopback-server.cjs');
const {dataPath}=require('../src/safe-paths.cjs');
const {verifyResources}=require('../src/resources.cjs');
function call(origin,route,headers={}){return new Promise((resolve,reject)=>{http.get(origin+route,{headers},res=>{const chunks=[];res.on('data',chunk=>chunks.push(chunk));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks).toString()}));}).on('error',reject);});}
test('ordinary local pages cannot reach static UI or proxy; only the main-owned capability forwards API',async()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-shell-test-'));fs.writeFileSync(path.join(root,'index.html'),'production-vue');
  const seen=[];const backend=http.createServer((req,res)=>{seen.push(req.headers);res.setHeader('content-type','application/json');res.end('{"code":0,"data":"owner-session-required"}');});
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));
  const bridge=new LoopbackServer(root,root,()=>({port:backend.address().port,token:'backend-private-token'}));await bridge.start();
  try{
    assert.equal((await call(bridge.origin,'/api/auth/me')).status,403);
    assert.equal((await call(bridge.origin,'/')).status,403);
    assert.equal((await call(bridge.origin,'/',{'X-Coffer-Window-Capability':'é'.repeat(64)})).status,403);
    const headers={'X-Coffer-Window-Capability':bridge.capability};
    assert.equal((await call(bridge.origin,'/api/auth/me',{...headers,Origin:'http://evil.local'})).status,403);
    assert.equal((await call(bridge.origin,'/api/desktop/native-inbox/preview',headers)).status,403);
    const result=await call(bridge.origin,'/api/auth/me',{...headers,Cookie:'COFFER_SESSION=test','X-Coffer-Native-Capability':'forged','X-Coffer-Desktop-Token':'forged'});
    assert.equal(result.status,200);assert.equal(seen.length,1);assert.equal(seen[0]['x-coffer-desktop-token'],'backend-private-token');
    assert.equal(seen[0]['x-coffer-native-capability'],undefined);assert.equal(seen[0]['x-coffer-window-capability'],undefined);assert.equal(seen[0].cookie,'COFFER_SESSION=test');
    const page=await call(bridge.origin,'/',headers);assert.equal(page.body,'production-vue');assert.match(page.headers['content-security-policy'],/frame-src 'none'/);
    assert.equal((await call(bridge.origin,'/%2e%2e%2fpackage.json',headers)).status,404);
  }finally{await bridge.close();await new Promise(resolve=>backend.close(resolve));fs.rmSync(root,{recursive:true,force:true});}
});
test('data/install overlap and junction-like symbolic paths are refused',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-path-test-'));
  try{assert.throws(()=>dataPath(path.join(root,'install','data'),path.join(root,'install')),/INSTALL_DATA_OVERLAP/);assert.throws(()=>dataPath(root,path.join(root,'install')),/INSTALL_DATA_OVERLAP/);assert.throws(()=>dataPath(root+';AUTO_SERVER=TRUE',path.join(root,'install')),/DATA_PATH_INVALID/);}
  finally{fs.rmSync(root,{recursive:true,force:true});}
});
test('resource hash verification refuses edited backend and never substitutes an unverified runtime',()=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-manifest-test-'));
  try{fs.writeFileSync(path.join(root,'manifest.json'),JSON.stringify({formatVersion:1,schemaVersion:41,files:[{path:'../outside',size:0,sha256:'a'.repeat(64)}]}));assert.throws(()=>verifyResources(root),/PACKAGE_MANIFEST_INVALID/);}
  finally{fs.rmSync(root,{recursive:true,force:true});}
});
