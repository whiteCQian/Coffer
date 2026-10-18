'use strict';
// Targeted UI backport for the deployed test server; no backend/schema changes.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const repo=path.resolve(__dirname,'../..'),esbuild=require('../../frontend/node_modules/esbuild'),vue=require('../../frontend/node_modules/@vue/compiler-sfc'),parser=require('../../frontend/node_modules/@babel/parser');
const input=path.resolve(process.argv[2]||path.join(repo,'../coffer-test-release/build-artifacts/web')),output=path.resolve(process.argv[3]||path.join(repo,'.test-tmp/server-recent-tags/stage'));
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error('Unexpected '+label+': '+a.length);return a[0];}
(async()=>{
 if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep)||fs.existsSync(output))throw Error('Use fresh repository-local staging');
 const originalJs='index-Uobh9XEM.js',originalCss='index-BO0us_qQ.css';
 const original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss),'utf8');
 if(hash(original)!=='ca64f76a6cec3c7df05f59c57f662dcf5dd6cc9114bf2cd86dace4094cdabcae'||hash(css)!=='82904f6ee024695f12c414e792103c375df4d5a529d1e73a94873cadb0140c30')throw Error('Unsupported deployed release');
 let code=esbuild.transformSync(original,{minify:false}).code;
 const ast=parser.parse(code,{sourceType:'module'}),vars=new Map(),edits=[],text=n=>code.slice(n.start,n.end);
 walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.id.type==='Identifier')vars.set(n.id.name,n);});
 function component(name){const a=[];walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.init?.arguments?.[0]?.type==='ObjectExpression'&&n.init.arguments[0].properties.some(p=>p.key?.name==='__name'&&p.value?.value===name))a.push(n);});return one(a,name);}
 const rail=component('IconRail'),drawer=component('FileDetailDrawer'),files=component('FilesView');
 const runtime={defineComponent:'ce',computed:'_',ref:'M',watch:'pe',unref:'i',resolveComponent:'Et',openBlock:'C',createElementBlock:'N',createElementVNode:'w',createVNode:'q',toDisplayString:'j',createBlock:'de',Fragment:'Te',renderList:'st',createCommentVNode:'X',normalizeClass:'P',withCtx:'te',withDirectives:'Ze',vModelText:'dn',createTextVNode:'Fe'};
 const shims={vue:Object.keys(runtime).map(k=>`export const ${k}=cofferWebRecentRuntime.${k};`).join('\n'),pinia:'export const defineStore=Fm;',
  'vue-router':'export const useRoute=rl;','@element-plus/icons-vue':'export const PriceTag=Jk;',
  '@/stores/workspace':'export const useWorkspaceStore=Ei;','@/stores/auth':'export const useAuthStore=na;',
  '@/api/desktop':'export const desktopBridge=null;','element-plus':'export const ElMessage=xt;',
  '@/api/files':`export function getFileDetail(id,revision){return rt.get('/files/'+id,{params:{revision}});}export function fileDownloadUrl(id,revision){const p=new URLSearchParams({download:'true'});if(revision!=null)p.set('revision',String(revision));return '/api/files/'+id+'/content?'+p;}`};
 const built=await esbuild.build({stdin:{contents:"export {default as SidebarTags} from '@/components/SidebarTags.vue';export {useOpenFile} from '@/composables/useOpenFile';",resolveDir:repo,loader:'ts'},bundle:true,write:false,format:'iife',globalName:'CofferWebRecentFiles',minify:true,target:'es2020',plugins:[{name:'server-runtime',setup(b){
  b.onResolve({filter:/.*/},a=>Object.hasOwn(shims,a.path)?{path:a.path,namespace:'server'}:a.path.startsWith('@/')?{path:path.join(repo,'frontend/src',a.path.slice(2)+(path.extname(a.path)?'':'.ts'))}:undefined);
  b.onLoad({filter:/.*/,namespace:'server'},a=>({contents:shims[a.path],loader:'js'}));
  b.onLoad({filter:/\.vue$/},a=>{const descriptor=vue.parse(fs.readFileSync(a.path,'utf8'),{filename:a.path}).descriptor;return{contents:vue.compileScript(descriptor,{id:'coffer-recent-tags',inlineTemplate:true,templateOptions:{compilerOptions:{scopeId:'data-v-coffer-recent-tags'}}}).content,loader:'ts',resolveDir:path.dirname(a.path)};});
 }}]});
 const tags=[];walk(rail,n=>{if(n.type==='ConditionalExpression'&&text(n.test)==='!i(u).isAdmin && i(s).tags.length')tags.push(n);});
 const oldTags=one(tags,'old sidebar');edits.push({start:oldTags.start,end:oldTags.end,value:"!i(u).isAdmin?q(CofferWebRecentFiles.SidebarTags,{filePath:'/files',class:'web-tags',onNavigate:()=>a('navigate')}):X('',true)"});
 const setups=n=>n.init.arguments[0].properties.find(p=>p.key?.name==='setup');
 const ds=setups(drawer),fsu=setups(files);
 edits.push({start:ds.body.start+1,end:ds.body.start+1,value:'const cofferOpen=CofferWebRecentFiles.useOpenFile();'});
 edits.push({start:fsu.body.start+1,end:fsu.body.start+1,value:`const cofferOpen=CofferWebRecentFiles.useOpenFile();let cofferInspectTimer;qn(()=>clearTimeout(cofferInspectTimer));function cofferOpenRow(id,type,event){clearTimeout(cofferInspectTimer);if(event?.target instanceof Element&&event.target.closest('input,button,a,label'))return;A.value=null;void cofferOpen.openFile(id,undefined,type??re.value.find(item=>item.id===id)?.fileType);}`});
 function classOf(n){const p=n.arguments?.[1],o=p?.type==='Identifier'?vars.get(p.name)?.init:p;return o?.properties?.find(p=>p.key?.name==='class')?.value?.value;}
 const actions=[],previewLinks=[];
 walk(drawer,n=>{if(n.type==='CallExpression'){if(classOf(n)==='drawer-actions')actions.push(n);if(classOf(n)==='preview-link')previewLinks.push(n);}});
 const children=one(actions,'drawer actions').arguments[2];edits.push({start:children.start+1,end:children.start+1,value:`w('button',{class:'act-btn is-download',disabled:cofferOpen.isOpening(c.value.id),onClick:()=>cofferOpen.openFile(c.value.id,n.revision,c.value.fileType)},cofferOpen.isOpening(c.value.id)?'正在打开…':'打开文件',9,['disabled','onClick']),`});
 for(const n of previewLinks){const props=n.arguments[1];edits.push({start:props.end-1,end:props.end-1,value:',onClick:event=>{event.preventDefault();void cofferOpen.openFile(c.value.id,n.revision,c.value.fileType);}'});}
 const targets=[],menus=[];walk(files,n=>{if(n.type==='CallExpression'&&['tr','article'].includes(n.arguments[0]?.value)&&n.arguments[1]?.properties?.some(p=>p.key?.name==='onContextmenu'))targets.push(n);if(n.type==='CallExpression'&&classOf(n)==='file-context-menu')menus.push(n);});
 if(targets.length!==2)throw Error('Expected list and grid');
 for(const n of targets){const props=n.arguments[1],click=props.properties.find(p=>p.key?.name==='onClick'),ctx=props.properties.find(p=>p.key?.name==='onContextmenu'),id=ctx.value.body.arguments[1],row=text(id.object);
  edits.push({start:click.value.start,end:click.value.end,value:`event=>{clearTimeout(cofferInspectTimer);if(event.detail>1)return;cofferInspectTimer=setTimeout(()=>V(${text(id)}),500);}`});
  edits.push({start:props.end-1,end:props.end-1,value:`,onDblclick:event=>cofferOpenRow(${text(id)},${row}.fileType,event)`});
  const dynamic=vars.get(n.arguments.at(-1).name)?.init;if(dynamic?.type!=='ArrayExpression')throw Error('Missing dynamic event names');edits.push({start:dynamic.end-1,end:dynamic.end-1,value:',"onDblclick"'});
  walk(props.properties.find(p=>p.key?.name==='onKeydown'),a=>{if(a.type==='CallExpression'&&a.callee.name==='V')edits.push({start:a.start,end:a.end,value:`cofferOpenRow(${text(id)},${row}.fileType)`});});
 }
 const menu=one(menus,'file menu').arguments[2];edits.push({start:menu.start+1,end:menu.start+1,value:"w('button',{disabled:cofferOpen.isOpening(A.value.id),onClick:()=>cofferOpenRow(A.value.id)},'打开文件',8,['disabled']),"});
 walk(files,n=>{if(n.type==='BinaryExpression'&&n.operator==='-'&&text(n.left)==='window.innerHeight'&&n.right.value===160)edits.push({start:n.right.start,end:n.right.end,value:'210'});});
 const mounts=[];walk(ast,n=>{if(n.type==='ExpressionStatement'&&text(n).includes('.mount("#app")'))mounts.push(n);});
 const mount=one(mounts,'mount');edits.push({start:mount.start,end:mount.start,value:`const cofferWebRecentRuntime={${Object.entries(runtime).map(([k,v])=>k+':'+v).join(',')}};\n${built.outputFiles[0].text}\nCofferWebRecentFiles.SidebarTags.__scopeId='data-v-coffer-recent-tags';\n`});
 edits.sort((a,b)=>b.start-a.start);for(let i=0;i<edits.length;i++){if(i&&edits[i].end>edits[i-1].start)throw Error('Overlapping edits');const e=edits[i];code=code.slice(0,e.start)+e.value+code.slice(e.end);}
 const pool='(c.value.data.data ?? []).slice(0, 12)';if(code.split(pool).length!==2)throw Error('Unexpected candidate truncation');code=code.replace(pool,'(c.value.data.data ?? [])');
 code=code.replaceAll('单击查看详情 · 右键查看更多操作','单击查看详情 · 双击打开文件 · 右键查看更多操作').replaceAll('\\u5355\\u51FB\\u67E5\\u770B\\u8BE6\\u60C5 \\xB7 \\u53F3\\u952E\\u67E5\\u770B\\u66F4\\u591A\\u64CD\\u4F5C','单击查看详情 · 双击打开文件 · 右键查看更多操作').replaceAll('\\u4E5F\\u53EF\\u4EE5\\u53CC\\u51FB\\u6587\\u4EF6\\u67E5\\u770B\\u8BE6\\u60C5','双击文件可直接打开');
 const sfc=vue.parse(fs.readFileSync(path.join(repo,'frontend/src/components/SidebarTags.vue'),'utf8')).descriptor;
 const styles=vue.compileStyle({source:sfc.styles[0].content,id:'data-v-coffer-recent-tags',scoped:true});if(styles.errors.length)throw Error(styles.errors.join('\n'));
 const nextCss=css+'\n'+styles.code+'\n.web-tags.sidebar-tag-list{overflow-y:auto;min-height:0}';
 const stamp=hash(code+nextCss).slice(0,12),nextJs=`index-file-tags-${stamp}.js`,nextCssName=`index-file-tags-${hash(nextCss).slice(0,12)}.css`;
 const consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent module')[1],consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8');
 if(!consentCode.includes('./'+originalJs))throw Error('Unexpected consent module import');
 const nextConsent=`modelConsent-file-tags-${stamp}.js`;
 code=code.replaceAll(consent,nextConsent);const linkedConsent=consentCode.replaceAll(originalJs,nextJs);
 const html=fs.readFileSync(path.join(input,'index.html'),'utf8').replaceAll(originalJs,nextJs).replaceAll(originalCss,nextCssName);
 fs.mkdirSync(path.join(output,'build-artifacts/web/assets'),{recursive:true});
 const outWeb=path.join(output,'build-artifacts/web');
 for(const[n,v]of [['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextCssName,nextCss],['assets/'+nextConsent,linkedConsent],['index.html',html]])fs.writeFileSync(path.join(outWeb,n),v);
 const filesOut=['index.html','assets/'+nextJs,'assets/'+nextCssName,'assets/'+nextConsent];
 fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-recent-opened-file-tags-2026-10-17',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),newJs:nextJs,files:filesOut.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(outWeb,n)))}))},null,2));
 console.log(JSON.stringify({staged:true,output,files:filesOut}));
})().catch(e=>{console.error(e);process.exitCode=1;});
