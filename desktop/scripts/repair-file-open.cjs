'use strict';
// Compatible overlay for the shipped V44 bundle, retaining the previous model, sidebar and scrolling repairs.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const esbuild=require('../../frontend/node_modules/esbuild'),vue=require('../../frontend/node_modules/@vue/compiler-sfc'),parser=require('../../frontend/node_modules/@babel/parser'),asar=require('@electron/asar');
const {verifyResources,hashFile}=require('../src/resources.cjs'),{noLinks}=require('../src/safe-paths.cjs');
const repo=path.resolve(__dirname,'../..'),resources=path.join(repo,'desktop/dist/win-unpacked/resources'),root=path.join(resources,'coffer');
const output=path.resolve(process.argv[2]||path.join(repo,'.test-tmp/file-open/stage-'+crypto.randomUUID()));
const marker='one-click-view-v44-2026-10-16',install=process.argv.includes('--install');
if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep))throw Error('Use repository-local staging');noLinks(resources);noLinks(output,true);
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const [k,v]of Object.entries(n)){if(k==='loc')continue;if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error('Unexpected '+label+': '+a.length);return a[0];}
(async()=>{
 if(install){
  verifyResources(root);const plan=JSON.parse(fs.readFileSync(path.join(output,'patch.json'),'utf8'));
  if(plan.marker!==marker||plan.files.some(n=>n!=='app.asar'&&n!=='coffer/manifest.json'&&n!=='coffer/backend/coffer-backend.jar'&&!/^coffer\/web\/assets\/index-[\w-]+\.js$/.test(n)))throw Error('Invalid repair plan');
  for(const n of plan.files){if(hashFile(path.join(resources,n))!==plan.originals[n]||hashFile(path.join(output,n))!==plan.staged[n])throw Error('Resources changed after staging');const target=path.join(output,'backup',n);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(resources,n),target);}
  try{for(const n of plan.files)fs.copyFileSync(path.join(output,n),path.join(resources,n));const m=verifyResources(root);fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({repair:marker,resourcesVerified:m.files.length,backup:path.join(output,'backup'),files:plan.files},null,2));console.log(JSON.stringify({installed:true,resourcesVerified:m.files.length}));}
  catch(error){for(const n of plan.files)fs.copyFileSync(path.join(output,'backup',n),path.join(resources,n));throw error;}return;
 }
 if(fs.existsSync(output))throw Error('Use fresh staging output');
 const manifest=verifyResources(root);if(manifest.schemaVersion!==44||!manifest.sidebarBatchRepair||manifest.fileOpenRepair)throw Error('Expected V44 with sidebar/batch repair');
 const html=fs.readFileSync(path.join(root,'web/index.html'),'utf8');const jsName='web'+one([...html.matchAll(/src="(\/assets\/index-[^"/]+\.js)"/g)],'main bundle')[1];
 let code=esbuild.transformSync(fs.readFileSync(path.join(root,jsName),'utf8'),{loader:'js',minify:false}).code;
 const ast=parser.parse(code,{sourceType:'module'}),variables=new Map(),edits=[];const text=n=>code.slice(n.start,n.end);
 walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.id.type==='Identifier')variables.set(n.id.name,n);});
 function component(name){const a=[];walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.init?.type==='CallExpression'&&n.init.arguments[0]?.type==='ObjectExpression'&&n.init.arguments[0].properties.some(p=>p.key?.name==='__name'&&p.value?.value===name))a.push(n);});return one(a,name);}
 const files=component('FilesView'),drawer=component('FileDetailDrawer'),editor=component('WorkCopyPanel');
 const setup=n=>n.init.arguments[0].properties.find(p=>p.key?.name==='setup');
 const runtimeCandidates=[];walk(ast,n=>{if(n.type==='ObjectExpression'&&['defineComponent','createElementVNode','createVNode','ref','watch'].every(k=>n.properties.some(p=>p.key?.name===k)))runtimeCandidates.push(n);});
 const runtime=Object.fromEntries(one(runtimeCandidates,'Vue runtime map').properties.map(p=>[p.key.name,p.value.name]));
 // These hooks and clients are fingerprinted against the preceding installed V44 repair.
 for(const [name,value]of Object.entries({ref:'N',createVNode:'W',createElementVNode:'w'}))if(runtime[name]!==value)throw Error('Unsupported runtime binding '+name);
 Object.assign(runtime,{onMounted:'dt',onBeforeUnmount:'Gt'});
 const exports=ast.program.body.find(n=>n.type==='ExportNamedDeclaration'&&n.specifiers.some(s=>s.exported.name==='u'));
 const auth=one(exports.specifiers.filter(s=>s.exported.name==='u'),'auth export').local.name,http=one(exports.specifiers.filter(s=>s.exported.name==='h'),'http export').local.name,dialog=one(exports.specifiers.filter(s=>s.exported.name==='E'),'dialog export').local.name;
 if(auth!=='ja'||http!=='pt'||dialog!=='Ra'||!code.includes('ea.openWorkCopy')||!code.includes('Tt.success'))throw Error('Unexpected V44 client bindings');
 const shims={vue:Object.keys(runtime).map(n=>`export const ${n}=cofferOpenRuntime.${n};`).join('\n'),
  'element-plus':`export const ElMessage=Tt;export const ElMessageBox=${dialog};`,'@/api/desktop':'export const desktopBridge=ea;',
  '@/stores/auth':`export const useAuthStore=${auth};`,'./http':`export default ${http};`,
  '@/api/files':`export function fileDownloadUrl(id,revision){const p=new URLSearchParams({download:'true'});if(revision!=null)p.set('revision',String(revision));return '/api/files/'+id+'/content?'+p;}`};
 const styles=[];
 async function bundle(entry,globalName,isVue=false){
  let contents=fs.readFileSync(entry,'utf8');
  if(isVue){const descriptor=vue.parse(contents,{filename:entry}).descriptor;contents=vue.compileScript(descriptor,{id:'data-v-d6fafa51',inlineTemplate:true}).content;}
  return (await esbuild.build({stdin:{contents,loader:'ts',resolveDir:path.dirname(entry)},bundle:true,write:false,format:'iife',globalName,minify:true,target:'es2020',plugins:[{name:'existing-v44-runtime',setup(b){
   b.onResolve({filter:/.*/},a=>Object.hasOwn(shims,a.path)?{path:a.path,namespace:'v44'}:a.path.startsWith('@/')?{path:path.join(repo,'frontend/src',a.path.slice(2)+'.ts')}:undefined);
   b.onLoad({filter:/.*/,namespace:'v44'},a=>({contents:shims[a.path],loader:'js'}));
  }}]})).outputFiles[0].text;
 }
 const hook=await bundle(path.join(repo,'frontend/src/composables/useOpenFile.ts'),'CofferFileOpen');
 const editing=await bundle(path.join(repo,'frontend/src/components/WorkCopyPanel.vue'),'CofferFileEditor',true);
 const factory=`const cofferOpenRuntime={${Object.entries(runtime).map(([k,v])=>`${k}:${v}`).join(',')}};\n${hook}\n${editing}\nCofferFileEditor.default.__scopeId='data-v-d6fafa51';\n`;
 // The factory is declared after all runtime helpers, before the promise which mounts the app.
 const mounts=[];walk(ast,n=>{if(n.type==='ExpressionStatement'&&text(n).includes('.mount("#app")'))mounts.push(n);});
 const mount=one(mounts,'mount statement');edits.push({start:mount.start,end:mount.start,value:factory});
 edits.push({start:editor.init.start,end:editor.init.end,value:'CofferFileEditor.default'});
 // Component definitions reference the factories only when setup is invoked after module initialization.
 // Editor is the exception: use a lazy forwarding setup to avoid a top-level temporal dead zone.
 edits[edits.length-1].value=`${runtime.defineComponent}({setup(props,ctx){return CofferFileEditor.default.setup(props,ctx);},props:{fileId:{}},emits:['saved']})`;
 const fileSetup=setup(files);edits.push({start:fileSetup.body.start+1,end:fileSetup.body.start+1,value:`const cofferOpen=CofferFileOpen.useOpenFile();let cofferInspectTimer;${runtime.onBeforeUnmount}(()=>clearTimeout(cofferInspectTimer));function cofferOpenRow(id,type,event){clearTimeout(cofferInspectTimer);if(event?.target instanceof Element&&event.target.closest('input,button,a,label'))return;void cofferOpen.openFile(id,undefined,type);}`});
 const targets=[],contextMenus=[],actions=[],copyCalls=[],title=[];
 function classOf(n){const props=n.arguments?.[1];const p=props?.type==='Identifier'?variables.get(props.name)?.init:props;return p?.properties?.find(p=>p.key?.name==='class')?.value?.value;}
 walk(files,n=>{
  if(n.type!=='CallExpression')return;
  if(['tr','article'].includes(n.arguments[0]?.value)&&n.arguments[1]?.type==='ObjectExpression'&&n.arguments[1].properties.some(p=>p.key?.name==='onContextmenu'))targets.push(n);
  if(classOf(n)==='file-context-menu')contextMenus.push(n);
 });
 walk(files,n=>{if(n.type==='BinaryExpression'&&n.operator==='-'&&text(n.left)==='window.innerHeight'&&n.right.value===160)edits.push({start:n.right.start,end:n.right.end,value:'210'});});
 if(targets.length!==2)throw Error('Expected list and grid file rows');
 for(const node of targets){const props=node.arguments[1],context=props.properties.find(p=>p.key?.name==='onContextmenu');const id=context.value.body.arguments[1];const row=text(id.object);
  const click=props.properties.find(p=>p.key?.name==='onClick');const original=text(click.value.body);
  edits.push({start:click.value.start,end:click.value.end,value:`event=>{clearTimeout(cofferInspectTimer);if(event.detail>1)return;if(ea&&window.innerWidth<=960)cofferInspectTimer=setTimeout(()=>${original},500);else ${original};}`});
  edits.push({start:props.end-1,end:props.end-1,value:`,onDblclick:event=>cofferOpenRow(${text(id)},${row}.fileType,event)`});
  const dynamic=node.arguments.at(-1);if(dynamic.type!=='Identifier')throw Error('Unexpected row events');const list=variables.get(dynamic.name)?.init;if(list?.type!=='ArrayExpression')throw Error('Missing event list');edits.push({start:list.end-1,end:list.end-1,value:',"onDblclick"'});
 }
 const menu=one(contextMenus,'context menu'),menuChildren=menu.arguments[2];
 const inspect=menuChildren.elements.find(n=>n.arguments?.[0]?.value==='button');const inspectProps=inspect.arguments[1];const click=inspectProps.properties.find(p=>p.key?.name==='onClick').value;
 const menuArgs=[];walk(click,n=>{if(n.type==='MemberExpression'&&n.property.name==='id')menuArgs.push(n);});
 const menuId=text(one(menuArgs,'menu file identity')),menuRef=menuId.replace(/\.id$/,'');
 edits.push({start:menuChildren.start+1,end:menuChildren.start+1,value:`${runtime.createElementVNode}('button',{disabled:cofferOpen.isOpening(${menuId}),onClick:()=>{void cofferOpen.openFile(${menuId});${menuRef}=null;}},'打开文件',8,['disabled','onClick']),`});
 const drawerSetup=setup(drawer);
 edits.push({start:drawerSetup.body.start+1,end:drawerSetup.body.start+1,value:`const cofferOpen=CofferFileOpen.useOpenFile(),cofferEditorPanel=${runtime.ref}(),cofferEditorExpanded=${runtime.ref}(false),cofferEditing=${runtime.ref}(false);async function cofferEdit(){if(cofferEditing.value)return;cofferEditing.value=true;cofferEditorExpanded.value=true;try{await cofferEditorPanel.value?.startEditing();}finally{cofferEditing.value=false;}}`});
 walk(drawer,n=>{if(n.type==='CallExpression'){if(classOf(n)==='drawer-actions')actions.push(n);if(n.arguments[0]?.name==='bk')copyCalls.push(n);if(classOf(n)==='drawer-name')title.push(n);}});
 const fileName=one(title,'drawer title').arguments[2];const members=[];walk(fileName,n=>{if(n.type==='MemberExpression'&&n.property.name==='fileName')members.push(n);});const fileRef=text(one(members,'drawer file reference').object);
 const actionChildren=one(actions,'drawer action buttons').arguments[2];
 edits.push({start:actionChildren.start+1,end:actionChildren.start+1,value:`${runtime.createElementVNode}('button',{class:'act-btn is-download',disabled:cofferOpen.isOpening(${fileRef}.id),onClick:()=>cofferOpen.openFile(${fileRef}.id,${drawerSetup.params[0].name}.revision,${fileRef}.fileType)},cofferOpen.isOpening(${fileRef}.id)?'正在打开…':'打开文件',9,['disabled','onClick']),ea&&${drawerSetup.params[0].name}.revision===undefined?${runtime.createElementVNode}('button',{class:'act-btn',disabled:cofferEditing.value,onClick:cofferEdit},cofferEditing.value?'正在准备…':'编辑文件',9,['disabled']):${runtime.createCommentVNode}('',true),`});
 const copy=one(copyCalls,'drawer work-copy panel'),copyText=text(copy),copyProps=copy.arguments[1];
 const withRef=copyText.slice(0,copyProps.end-copy.start-1)+',ref:cofferEditorPanel'+copyText.slice(copyProps.end-copy.start-1);
 const wrapped=`ea&&${drawerSetup.params[0].name}.revision===undefined?${runtime.createVNode}('details',{class:'file-editor-details',open:cofferEditorExpanded.value,onToggle:event=>cofferEditorExpanded.value=event.target.open},[${runtime.createElementVNode}('summary',null,'编辑与版本保存'),${withRef}],8,['open','onToggle']):${runtime.createCommentVNode}('',true)`;
 edits.push({start:copy.start,end:copy.end,value:wrapped});
 walk(drawer,n=>{if(n.type==='StringLiteral'&&n.value==='也可以双击文件查看详情')edits.push({start:n.start,end:n.end,value:JSON.stringify('双击文件可直接打开')});if(n.type==='StringLiteral'&&n.value==='文档可通过下方的工作副本，在外部应用中查看或编辑。')edits.push({start:n.start,end:n.end,value:JSON.stringify('点击“打开文件”查看；需要修改时选择“编辑文件”。查看副本的改动不会写回文件库。')});});
 edits.sort((a,b)=>b.start-a.start);for(let i=0;i<edits.length;i++){if(i&&edits[i].end>edits[i-1].start)throw Error('Overlapping edits');const e=edits[i];code=code.slice(0,e.start)+e.value+code.slice(e.end);}
 // Rewrite only exact UI copy, preserving all existing API behavior.
 code=code.replaceAll('单击查看详情 · 右键查看更多操作','单击查看详情 · 双击打开文件 · 右键查看更多操作');
 code=code.replaceAll('\\u5355\\u51FB\\u67E5\\u770B\\u8BE6\\u60C5 \\xB7 \\u53F3\\u952E\\u67E5\\u770B\\u66F4\\u591A\\u64CD\\u4F5C','单击查看详情 · 双击打开文件 · 右键查看更多操作');
 code=esbuild.transformSync(code,{loader:'js',minify:true,target:'es2020'}).code;
 const backend=path.join(repo,'.test-tmp/file-open/backend-v44-final/backend/coffer-backend.jar');if(!fs.existsSync(backend))throw Error('Compile the viewing-only backend patch first');
 const changed=[jsName,'backend/coffer-backend.jar'];
 for(const name of changed){const target=path.join(output,'coffer',name);fs.mkdirSync(path.dirname(target),{recursive:true});if(name===jsName)fs.writeFileSync(target,code);else fs.copyFileSync(backend,target);const entry=manifest.files.find(e=>e.path===name);entry.size=fs.statSync(target).size;entry.sha256=hashFile(target);}
 manifest.fileOpenRepair=marker;fs.writeFileSync(path.join(output,'coffer/manifest.json'),JSON.stringify(manifest,null,2));
 const shell=path.join(output,'shell-source');for(const n of asar.listPackage(path.join(resources,'app.asar'))){if(!path.resolve(shell,n.replace(/^[/\\]+/,'')).startsWith(shell+path.sep))throw Error('Unsafe archive entry');}asar.extractAll(path.join(resources,'app.asar'),shell);
 const mainPath=path.join(shell,'src/main.cjs'),preloadPath=path.join(shell,'src/preload.cjs');let main=fs.readFileSync(mainPath,'utf8'),preload=fs.readFileSync(preloadPath,'utf8');
 const anchor="    handle('coffer:open-work-copy',async id=>{";if(main.split(anchor).length!==2||preload.includes('openFile:'))throw Error('Unexpected shell bridge');
 main=main.replace(anchor,"    handle('coffer:open-file',async(id,revision)=>{await user();if(!Number.isSafeInteger(id)||id<1||revision!=null&&(!Number.isSafeInteger(revision)||revision<0))throw new Error('文件参数无效');return nativeApi(`/api/desktop/files/${id}/open`,revision==null?{}:{revision});});\n"+anchor);
 const bridge="  openWorkCopy:id=>ipcRenderer.invoke('coffer:open-work-copy',id)";if(preload.split(bridge).length!==2)throw Error('Unexpected preload');preload=preload.replace(bridge,bridge+",\n  openFile:(id,revision)=>ipcRenderer.invoke('coffer:open-file',id,revision)");
 for(const [file,content]of [[mainPath,main],[preloadPath,preload]]){esbuild.transformSync(content,{loader:'js'});fs.writeFileSync(file,content);}await asar.createPackage(shell,path.join(output,'app.asar'));
 const names=changed.map(n=>'coffer/'+n).concat('coffer/manifest.json','app.asar');fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({marker,files:names,originals:Object.fromEntries(names.map(n=>[n,hashFile(path.join(resources,n))])),staged:Object.fromEntries(names.map(n=>[n,hashFile(path.join(output,n))]))},null,2));
 console.log(JSON.stringify({staged:true,output,files:names}));
})().catch(e=>{console.error(e);process.exitCode=1;});
