'use strict';
// Overlay only the recent-tag UI and open hook; keep the installed V44 API and other repairs.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const esbuild=require('../../frontend/node_modules/esbuild'),vue=require('../../frontend/node_modules/@vue/compiler-sfc'),parser=require('../../frontend/node_modules/@babel/parser'),asar=require('@electron/asar');
const {verifyResources,hashFile}=require('../src/resources.cjs'),{noLinks}=require('../src/safe-paths.cjs');
const repo=path.resolve(__dirname,'../..'),resources=path.join(repo,'desktop/dist/win-unpacked/resources'),root=path.join(resources,'coffer');
const output=path.resolve(process.argv[2]||path.join(repo,'.test-tmp/recent-file-tags/stage-'+crypto.randomUUID()));
const marker='recent-opened-file-tags-v44-2026-10-17';
if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep))throw Error('Use repository-local staging');
noLinks(resources);noLinks(output,true);
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error('Unexpected '+label+': '+a.length);return a[0];}
(async()=>{
 if(process.argv.includes('--install')){
  verifyResources(root);const plan=JSON.parse(fs.readFileSync(path.join(output,'patch.json'),'utf8'));
  if(plan.marker!==marker||plan.files.some(n=>n!=='app.asar'&&n!=='coffer/manifest.json'&&!/^coffer\/web\/assets\/index-[\w-]+\.js$/.test(n)))throw Error('Invalid repair plan');
  for(const n of plan.files){if(hashFile(path.join(resources,n))!==plan.originals[n]||hashFile(path.join(output,n))!==plan.staged[n])throw Error('Resources changed after staging');const target=path.join(output,'backup',n);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(resources,n),target);}
  try{for(const n of plan.files)fs.copyFileSync(path.join(output,n),path.join(resources,n));const m=verifyResources(root);fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({repair:marker,resourcesVerified:m.files.length,backup:path.join(output,'backup')},null,2));console.log(JSON.stringify({installed:true,resourcesVerified:m.files.length}));}
  catch(e){for(const n of plan.files)fs.copyFileSync(path.join(output,'backup',n),path.join(resources,n));throw e;}return;
 }
 if(fs.existsSync(output))throw Error('Use fresh staging output');
 const manifest=verifyResources(root);if(manifest.schemaVersion!==44||!manifest.fileOpenRepair||manifest.recentFileTagsRepair)throw Error('Expected V44 with file-open repair');
 const jsName='web'+one([...fs.readFileSync(path.join(root,'web/index.html'),'utf8').matchAll(/src="(\/assets\/index-[^"/]+\.js)"/g)],'bundle')[1];
 let code=esbuild.transformSync(fs.readFileSync(path.join(root,jsName),'utf8'),{minify:false}).code;
 const ast=parser.parse(code,{sourceType:'module'}),edits=[],runtimeMaps=[];
 const source=n=>code.slice(n.start,n.end);
 walk(ast,n=>{if(n.type==='ObjectExpression'&&['defineComponent','createElementVNode','ref','watch'].every(k=>n.properties.some(p=>p.key?.name===k)))runtimeMaps.push(n);});
 const runtime=Object.fromEntries(runtimeMaps.at(-1).properties.map(p=>[p.key.name,p.value.name]));
 // Fingerprint this exact installed bundle, failing closed if a different release is present.
 if(runtime.ref!=='T'||runtime.createVNode!=='q'||!code.includes('var zg = (() =>')||!code.includes('const t = _k.useOpenFile()'))throw Error('Unsupported V44 bindings');
 const shims={vue:Object.keys(runtime).map(k=>`export const ${k}=cofferRecentRuntime.${k};`).join('\n'),
  pinia:'export const defineStore=gv;', 'vue-router':'export const useRoute=Rn;',
  '@element-plus/icons-vue':'export const PriceTag=JT;', '@/stores/workspace':'export const useWorkspaceStore=wu;',
  '@/stores/auth':'export const useAuthStore=Va;', '@/api/desktop':'export const desktopBridge=aa;',
  'element-plus':'export const ElMessage=_t;',
  '@/api/files':`export function getFileDetail(id,revision){return vt.get('/files/'+id,{params:{revision}});}export function fileDownloadUrl(id,revision){const p=new URLSearchParams({download:'true'});if(revision!=null)p.set('revision',String(revision));return '/api/files/'+id+'/content?'+p;}`};
 const result=await esbuild.build({stdin:{contents:"export {default as SidebarTags} from '@/components/SidebarTags.vue';export {useOpenFile} from '@/composables/useOpenFile';",loader:'ts',resolveDir:repo},bundle:true,write:false,format:'iife',globalName:'CofferRecentFileTags',minify:true,target:'es2020',plugins:[{name:'v44-runtime',setup(b){
  b.onResolve({filter:/.*/},a=>Object.hasOwn(shims,a.path)?{path:a.path,namespace:'v44'}:a.path.startsWith('@/')?{path:path.join(repo,'frontend/src',a.path.slice(2)+(path.extname(a.path)?'':'.ts'))}:undefined);
  b.onLoad({filter:/.*/,namespace:'v44'},a=>({contents:shims[a.path],loader:'js'}));
  b.onLoad({filter:/\.vue$/},a=>{const descriptor=vue.parse(fs.readFileSync(a.path,'utf8'),{filename:a.path}).descriptor;return{contents:vue.compileScript(descriptor,{id:'coffer-recent-tags',inlineTemplate:true,templateOptions:{compilerOptions:{scopeId:'data-v-coffer-recent-tags'}}}).content,loader:'ts',resolveDir:path.dirname(a.path)};});
 }}]});
 let sidebars=0,hooks=0;const mounts=[];
 walk(ast,n=>{
  if(n.type==='MemberExpression'&&source(n)==='zg.default'&&code.slice(n.end,n.end+10)!=='.__scopeId'){edits.push({start:n.start,end:n.end,value:'CofferRecentFileTags.SidebarTags'});sidebars++;}
  if(n.type==='MemberExpression'&&source(n)==='_k.useOpenFile'){edits.push({start:n.start,end:n.end,value:'CofferRecentFileTags.useOpenFile'});hooks++;}
  if(n.type==='ExpressionStatement'&&source(n).includes('.mount("#app")'))mounts.push(n);
 });
 if(sidebars!==2||hooks!==2)throw Error('Unexpected sidebar/open hook references '+sidebars+'/'+hooks);
 const mount=one(mounts,'mount');edits.push({start:mount.start,end:mount.start,value:`const cofferRecentRuntime={${Object.entries(runtime).map(([k,v])=>k+':'+v).join(',')}};\n${result.outputFiles[0].text}\nCofferRecentFileTags.SidebarTags.__scopeId='data-v-coffer-recent-tags';\n`});
 edits.sort((a,b)=>b.start-a.start);for(let i=0;i<edits.length;i++){if(i&&edits[i].end>edits[i-1].start)throw Error('Overlapping edits');const e=edits[i];code=code.slice(0,e.start)+e.value+code.slice(e.end);}
 const js=path.join(output,'coffer',jsName);fs.mkdirSync(path.dirname(js),{recursive:true});fs.writeFileSync(js,esbuild.transformSync(code,{minify:true,target:'es2020'}).code);
 const entry=manifest.files.find(e=>e.path===jsName);entry.size=fs.statSync(js).size;entry.sha256=hashFile(js);manifest.recentFileTagsRepair=marker;
 fs.writeFileSync(path.join(output,'coffer/manifest.json'),JSON.stringify(manifest,null,2));
 const shell=path.join(output,'shell-source');for(const n of asar.listPackage(path.join(resources,'app.asar'))){if(!path.resolve(shell,n.replace(/^[/\\]+/,'')).startsWith(shell+path.sep))throw Error('Unsafe archive entry');}asar.extractAll(path.join(resources,'app.asar'),shell);
 // Use a new preference namespace: old tag-filter click history remains backed up and is not imported.
 fs.copyFileSync(path.join(repo,'desktop/src/recent-tags.cjs'),path.join(shell,'src/recent-tags.cjs'));
 await asar.createPackage(shell,path.join(output,'app.asar'));
 const names=['coffer/'+jsName,'coffer/manifest.json','app.asar'];
 fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({marker,files:names,originals:Object.fromEntries(names.map(n=>[n,hashFile(path.join(resources,n))])),staged:Object.fromEntries(names.map(n=>[n,hashFile(path.join(output,n))]))},null,2));
 console.log(JSON.stringify({staged:true,output,files:names}));
})().catch(e=>{console.error(e);process.exitCode=1;});
