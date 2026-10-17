'use strict';
// Backport the two UX rules to V44 without replacing its APIs, import semantics or model fixes.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const esbuild=require('../../frontend/node_modules/esbuild');
const vue=require('../../frontend/node_modules/@vue/compiler-sfc');
const parser=require('../../frontend/node_modules/@babel/parser');
const asar=require('@electron/asar');
const {verifyResources,hashFile}=require('../src/resources.cjs');
const {noLinks}=require('../src/safe-paths.cjs');
const repo=path.resolve(__dirname,'../..'),resources=path.join(repo,'desktop/dist/win-unpacked/resources');
const root=path.join(resources,'coffer');
const output=path.resolve(process.argv[2]||path.join(repo,'.test-tmp/sidebar-batch-'+crypto.randomUUID()));
const marker='recent-ten-tags-current-batch-v44-2026-10-16';
const install=process.argv.includes('--install');
if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep))throw Error('Use repository-local staging');
noLinks(resources);noLinks(output,true);
function walk(node,fn){if(!node||typeof node!=='object')return; if(node.type)fn(node);for(const [key,value]of Object.entries(node)){if(key==='loc')continue;if(Array.isArray(value))value.forEach(n=>walk(n,fn));else if(value&&typeof value==='object')walk(value,fn);}}
function one(list,label){if(list.length!==1)throw Error('Unexpected '+label+': '+list.length);return list[0];}
(async()=>{
if(install){
  verifyResources(root);
  const plan=JSON.parse(fs.readFileSync(path.join(output,'patch.json'),'utf8'));
  if(plan.marker!==marker||plan.files.some(n=>n!=='app.asar'&&n!=='coffer/manifest.json'&&!/^coffer\/web\/assets\/index-[\w-]+\.(js|css)$/.test(n)))throw Error('Invalid repair plan');
  const expected=JSON.parse(fs.readFileSync(path.join(output,'staged-hashes.json'),'utf8'));
  for(const name of plan.files){
    if(hashFile(path.join(resources,name))!==plan.originals[name]||hashFile(path.join(output,name))!==expected[name])throw Error('Repair or installed resources changed');
    const backup=path.join(output,'backup',name);fs.mkdirSync(path.dirname(backup),{recursive:true});fs.copyFileSync(path.join(resources,name),backup);
  }
  try{
    for(const name of plan.files)fs.copyFileSync(path.join(output,name),path.join(resources,name));
    const m=verifyResources(root);
    if(!asar.extractFile(path.join(resources,'app.asar'),'src/recent-tags.cjs').length)throw Error('Recent tag persistence missing');
    fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({repair:marker,resourcesVerified:m.files.length,changedFiles:plan.files,backup:path.join(output,'backup')},null,2));
    console.log(JSON.stringify({installed:true,resourcesVerified:m.files.length}));
  }catch(error){for(const name of plan.files)fs.copyFileSync(path.join(output,'backup',name),path.join(resources,name));throw error;}
  return;
}
if(fs.existsSync(output))throw Error('Use a fresh output directory');
const manifest=verifyResources(root);if(manifest.schemaVersion!==44)throw Error('Only V44 is supported');
if(manifest.sidebarBatchRepair===marker)throw Error('Already installed');
const html=fs.readFileSync(path.join(root,'web/index.html'),'utf8');
const jsName='web'+one([...html.matchAll(/src="(\/assets\/index-[^"/]+\.js)"/g)],'main bundle')[1];
const cssName='web'+one([...html.matchAll(/href="(\/assets\/index-[^"/]+\.css)"/g)],'stylesheet')[1];
let code=esbuild.transformSync(fs.readFileSync(path.join(root,jsName),'utf8'),{loader:'js',minify:false}).code;
if(code.includes(marker))throw Error('Already installed');
const ast=parser.parse(code,{sourceType:'module'}),edits=[];
const text=n=>code.slice(n.start,n.end);
function component(name){const found=[];walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.init?.type==='CallExpression'&&n.init.arguments[0]?.type==='ObjectExpression'&&n.init.arguments[0].properties.some(p=>p.key?.name==='__name'&&p.value?.value===name))found.push(n);});return one(found,name);}
function edit(n,value){edits.push({start:n.start,end:n.end,value});}
const desktop=component('DesktopSidebar'),web=component('IconRail'),progress=component('AiProgress'),importer=component('DesktopImportPanel');
const desktopTags=[],webTags=[];
walk(desktop,n=>{if(n.type==='ConditionalExpression'&&text(n.test)==='i(n).tags.length')desktopTags.push(n);});
walk(web,n=>{if(n.type==='ConditionalExpression'&&text(n.test)==='!i(u).isAdmin && i(s).tags.length')webTags.push(n);});
edit(one(desktopTags,'desktop tags'),`q(CofferV44SidebarTags.default,{filePath:'/',class:'sidebar-tags'})`);
edit(one(webTags,'web tags'),`!i(u).isAdmin?q(CofferV44SidebarTags.default,{filePath:'/files',class:'web-tags',onNavigate:()=>a('navigate')}):J('',true)`);
const histories=[],headers=[],summaryTests=[];
walk(progress,n=>{
  if(n.type==='CallExpression'&&n.callee.name==='w'&&['Jde','Qde','Zde'].includes(n.arguments[1]?.name))histories.push(n);
  if(n.type==='ConditionalExpression'&&text(n.test)==='(N = c.value) != null && N.enabled && !a.pendingOnly')headers.push(n);
  if(n.type==='ConditionalExpression'&&text(n.test)==='(V = c.value) != null && V.enabled && !a.pendingOnly')summaryTests.push(n.test);
});
if(histories.length!==3)throw Error('Unexpected historical progress layout');
histories.forEach(n=>edit(n,"J('',true)"));edit(one(headers,'historical header'),`J('',true)`);
edit(one(summaryTests,'inbox actions condition'),`c.value?.enabled&&!a.pendingOnly&&c.value.items.some(item=>item.status==='AWAITING_CONFIRMATION'||item.status==='MANUAL_REVIEW'||(item.status==='IMPORTED'&&item.error))`);
const setup=importer.init.arguments[0].properties.find(p=>p.key?.name==='setup');
edits.push({start:setup.body.start+1,end:setup.body.start+1,value:`const cofferBatchProgress=M(null);`});
const confirms=[],sections=[],resets=[],success=[];
walk(setup,n=>{
  if(n.type==='FunctionDeclaration'&&n.id.name==='g')confirms.push(n);
  if(n.type==='CallExpression'&&n.callee.name==='I'&&n.arguments[0]?.value==='section')sections.push(n);
  if(n.type==='ArrowFunctionExpression'&&text(n)==='() => {\n    u.value = [], c.value = [];\n  }')resets.push(n);
});
const confirm=one(confirms,'native import handler');
walk(confirm,n=>{if(n.type==='CallExpression'&&text(n.callee)==='$t.success')success.push(n.arguments[0]);});
edit(confirm,`async function g(){
  if(!Kt||r.value||!u.value.length)return;
  r.value=true;const items=u.value.slice(),generation=o.generation;
  cofferBatchProgress.value=items.length>1?{total:items.length,processed:0}:null;c.value=[];
  try{
    for(const item of items){
      if(o.generation!==generation)return;
      let receipts;
      try{receipts=await Kt.commitInbox([{id:item.id,targetPath:item.targetPath}]);}
      catch(error){receipts=[{name:item.name,targetPath:item.targetPath,ok:false,error:String(error?.message||'文件导入失败，请重试').replace(/^Error invoking remote method .*?: Error: /,'')}];}
      if(o.generation!==generation)return;
      c.value.push(...receipts);if(cofferBatchProgress.value)cofferBatchProgress.value.processed++;
    }
    if(c.value.some(item=>item.ok)){$t.success(${text(one(success,'import success message'))});d('queued');}u.value=[];
  }catch(error){f(error);}finally{cofferBatchProgress.value=null;r.value=false;}
}`);
const children=one(sections,'import section').arguments[2];if(children.type!=='ArrayExpression')throw Error('Unexpected import children');
edits.push({start:children.elements[1].start,end:children.elements[1].start,value:`cofferBatchProgress.value?q(CofferV44BatchBar,{progress:cofferBatchProgress.value}):J('',true),`});
const reset=one(resets,'identity reset');edits.push({start:reset.body.start+1,end:reset.body.start+1,value:`cofferBatchProgress.value=null;`});
edits.sort((a,b)=>b.start-a.start);
for(let i=0;i<edits.length;i++){if(i&&edits[i].end>edits[i-1].start)throw Error('Overlapping edits');code=code.slice(0,edits[i].start)+edits[i].value+code.slice(edits[i].end);}
const oldPool='(c.value.data.data ?? []).slice(0, 12)';if(code.split(oldPool).length!==2)throw Error('Unexpected tag pool');code=code.replace(oldPool,'(c.value.data.data ?? [])');
const sfcFile=path.join(repo,'frontend/src/components/SidebarTags.vue');
const descriptor=vue.parse(fs.readFileSync(sfcFile,'utf8'),{filename:sfcFile}).descriptor;
const compiled=vue.compileScript(descriptor,{id:'coffer-recent-tags',inlineTemplate:true,templateOptions:{compilerOptions:{scopeId:'data-v-coffer-recent-tags'}}});
const runtime={defineComponent:'ce',computed:'_',ref:'M',watch:'pe',unref:'i',resolveComponent:'xt',openBlock:'C',createElementBlock:'I',createElementVNode:'w',createVNode:'q',toDisplayString:'W',createBlock:'de',Fragment:'$e',renderList:'lt',createCommentVNode:'J',normalizeClass:'A',withCtx:'ae',withDirectives:'et',vModelText:'pn',createTextVNode:'Fe'};
const required=[...compiled.content.matchAll(/import\s*\{([^}]+)\}\s*from\s*["']vue["']/g)].flatMap(m=>m[1].split(',').map(s=>s.trim().split(/\s+as\s+/)[0]));
for(const name of required)if(!runtime[name])throw Error('Missing Vue runtime binding '+name);
const shims={vue:Object.entries(runtime).map(([name,value])=>`export const ${name}=cofferV44Runtime.${name};`).join('\n'),
  'vue-router':'export const useRoute=rl;', '@element-plus/icons-vue':'export const PriceTag=Qk;',
  '@/stores/workspace':'export const useWorkspaceStore=Ei;','@/stores/auth':'export const useAuthStore=na;','@/api/desktop':'export const desktopBridge=Kt;'};
const bundle=await esbuild.build({stdin:{contents:compiled.content,loader:'ts',resolveDir:path.dirname(sfcFile)},bundle:true,write:false,format:'iife',globalName:'CofferV44SidebarTags',minify:true,target:'es2020',plugins:[{name:'v44-bindings',setup(build){
  build.onResolve({filter:/.*/},args=>Object.hasOwn(shims,args.path)?{path:args.path,namespace:'v44-shim'}:args.path.startsWith('@/')?{path:path.join(repo,'frontend/src',args.path.slice(2)+'.ts')}:undefined);
  build.onLoad({filter:/.*/,namespace:'v44-shim'},args=>({contents:shims[args.path],loader:'js'}));
}}]});
const factory=`/* ${marker} */\nconst cofferV44Runtime={${Object.entries(runtime).map(([k,v])=>`${k}:${v}`).join(',')}};\n${bundle.outputFiles[0].text}\nconst CofferV44BatchBar=ce({props:['progress'],setup(props){return()=>{const p=props.progress,percent=Math.round(p.processed/p.total*100);return q('div',{class:'import-batch',role:'progressbar','aria-label':'本次批量导入','aria-valuenow':p.processed,'aria-valuemax':p.total,'aria-valuemin':0},[q('div',{class:'import-batch-head'},[q('strong',null,'本次批量导入'),q('span',null,percent+'%')]),q('div',{class:'import-batch-track'},[q('div',{class:'import-batch-fill',style:{width:percent+'%'}})]),q('p',null,'已处理 '+p.processed+' / '+p.total+' 个文件')]);}}});\n`;
if(code.split('const Oye = Poe()').length!==2)throw Error('Unexpected mount point');code=code.replace('const Oye = Poe()',factory+"CofferV44SidebarTags.default.__scopeId='data-v-coffer-recent-tags';\n"+'const Oye = Poe()');
code=esbuild.transformSync(code,{loader:'js',minify:true,target:'es2020'}).code;
const styles=descriptor.styles.map(s=>{const result=vue.compileStyle({source:s.content,id:'data-v-coffer-recent-tags',scoped:true});if(result.errors.length)throw result.errors[0];return result.code;}).join('\n');
const batchCss=`.desktop-import .import-batch{position:sticky;top:0;z-index:1;padding:12px 0;background:var(--panel)}.desktop-import .import-batch-head{display:flex;justify-content:space-between;gap:12px}.desktop-import .import-batch-track{height:6px;margin-top:8px;border-radius:6px;overflow:hidden;background:var(--line)}.desktop-import .import-batch-fill{height:100%;background:var(--accent)}.desktop-import .import-batch p{margin:7px 0 0;color:var(--text-2);font-size:12px}`;
const changes=new Map([[jsName,code],[cssName,fs.readFileSync(path.join(root,cssName),'utf8')+'\n/* '+marker+' */\n'+styles+'\n'+batchCss]]);
for(const [name,content]of changes){const target=path.join(output,'coffer',name);fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,content);const entry=manifest.files.find(e=>e.path===name);entry.size=fs.statSync(target).size;entry.sha256=hashFile(target);}
manifest.sidebarBatchRepair=marker;fs.writeFileSync(path.join(output,'coffer/manifest.json'),JSON.stringify(manifest,null,2));
const shell=path.join(output,'shell-source'),source=path.join(resources,'app.asar');
for(const name of asar.listPackage(source)){const dest=path.resolve(shell,name.replace(/^[/\\]+/,''));if(!dest.startsWith(shell+path.sep))throw Error('Unsafe archive entry');}
asar.extractAll(source,shell);
const mainPath=path.join(shell,'src/main.cjs'),preloadPath=path.join(shell,'src/preload.cjs');
let main=fs.readFileSync(mainPath,'utf8'),preload=fs.readFileSync(preloadPath,'utf8');
const requireBefore="const {verifyResources}=require('./resources.cjs');",handlerBefore="  handle('coffer:get-pane-widths',()=>({...paneWidths}));";
if(main.split(requireBefore).length!==2||main.split(handlerBefore).length!==2||preload.includes('getRecentTags'))throw Error('Unexpected shell preferences layout');
main=main.replace(requireBefore,requireBefore+"\nconst {recentTagStorage}=require('./recent-tags.cjs');").replace(handlerBefore,handlerBefore+"\n  handle('coffer:get-recent-tags',async()=>{const owner=await user();return recentTagStorage(profileDirectory,settings.dataDirectory,owner.id).read();});\n  handle('coffer:save-recent-tags',async names=>{const owner=await user();recentTagStorage(profileDirectory,settings.dataDirectory,owner.id).write(names);});");
const preloadBefore="  getTheme:()=>ipcRenderer.invoke('coffer:get-theme'),";if(preload.split(preloadBefore).length!==2)throw Error('Unexpected preload');
preload=preload.replace(preloadBefore,preloadBefore+"\n  getRecentTags:()=>ipcRenderer.invoke('coffer:get-recent-tags'),\n  saveRecentTags:names=>ipcRenderer.invoke('coffer:save-recent-tags',names),");
for(const [name,content]of [[mainPath,main],[preloadPath,preload]]){esbuild.transformSync(content,{loader:'js'});fs.writeFileSync(name,content);}
fs.copyFileSync(path.join(repo,'desktop/src/recent-tags.cjs'),path.join(shell,'src/recent-tags.cjs'));
await asar.createPackage(shell,path.join(output,'app.asar'));
if(asar.extractFile(path.join(output,'app.asar'),'src/main.cjs').toString()!==main)throw Error('Archive verification failed');
const files=[...changes.keys()].map(n=>'coffer/'+n).concat('coffer/manifest.json','app.asar');
fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({marker,files,originals:Object.fromEntries(files.map(n=>[n,hashFile(path.join(resources,n))]))},null,2));
fs.writeFileSync(path.join(output,'staged-hashes.json'),JSON.stringify(Object.fromEntries(files.map(n=>[n,hashFile(path.join(output,n))])),null,2));
console.log(JSON.stringify({staged:true,output,files}));
})().catch(error=>{console.error(error);process.exitCode=1;});
