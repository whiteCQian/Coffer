'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const repo=path.resolve(__dirname,'../..'),esbuild=require('../../frontend/node_modules/esbuild'),vue=require('../../frontend/node_modules/@vue/compiler-sfc'),parser=require('../../frontend/node_modules/@babel/parser');
const input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.resolve(process.argv[2]||path.join(repo,'.test-tmp/web-upload-flow/stage'));
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error('Unexpected '+label+': '+a.length);return a[0];}
(async()=>{
 if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep)||fs.existsSync(output))throw Error('Use fresh staging');
 const originalJs='index-home-cleanup-5eefd0c1ca4f.js',originalCss='index-file-tags-d9f92bbf4bfb.css',original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss),'utf8');
 if(hash(original)!=='e0994ba32e018da3d6c7e9258e95f2f0da52f78846a8af31289e73b27749b2ce')throw Error('Unexpected deployed bundle');
 let code=esbuild.transformSync(original,{minify:false}).code;const ast=parser.parse(code,{sourceType:'module'}),maps=[],homes=[],mounts=[];
 walk(ast,n=>{if(n.type==='ObjectExpression'&&['defineComponent','createElementVNode','ref','watch'].every(k=>n.properties.some(p=>p.key?.name===k)))maps.push(n);if(n.type==='VariableDeclarator'&&n.init?.arguments?.[0]?.type==='ObjectExpression'&&n.init.arguments[0].properties.some(p=>p.key?.name==='__name'&&p.value?.value==='WebHomeView'))homes.push(n);if(n.type==='ExpressionStatement'&&code.slice(n.start,n.end).includes('.mount("#app")'))mounts.push(n);});
 const runtime=Object.fromEntries(one(maps,'runtime').properties.map(p=>[p.key.name,p.value.name]));
 if(runtime.ref!=='$'||runtime.defineComponent!=='ce')throw Error('Unsupported runtime');Object.assign(runtime,{onMounted:'ct',onUnmounted:'Pa',nextTick:'je',withModifiers:'Qe',withKeys:'Jt'});
 const shims={vue:Object.keys(runtime).map(k=>`export const ${k}=cofferUploadRuntime.${k};`).join('\n'),pinia:'export const defineStore=cd;',
 'vue-router':'export const useRouter=Pu;export const useRoute=Nn;', 'element-plus':'export const ElMessage=Dt;export const ElMessageBox=Oa;',
 '@element-plus/icons-vue':'export const UploadFilled=h6;export const ArrowRight=qa;export const Search=ir;',
 '@/stores/auth':'export const useAuthStore=ja;','@/stores/workspace':'export const useWorkspaceStore=bo;','@/stores/fileView':'export const useFileViewStore=bc;',
 '@/api/desktop':'export const desktopBridge=null;', '@/components/FileDetailDrawer.vue':'export default bf;', '@/components/FileTile.vue':'export default zu;', '@/layout/ChatDock.vue':'export default Pg;', '@/components/DesktopImportPanel.vue':'export default Vg;',
 '@/api/files':`export function listFiles(params){return ot.get('/files',{params});}export function uploadFile(body,key,progress){return ot.post('/files/upload',body,{headers:{'Content-Type':'multipart/form-data','Idempotency-Key':key},onUploadProgress:e=>{if(e.total)progress?.(Math.round(e.loaded/e.total*100));}});}export function saveFileOnly(body,key){return ot.post('/files/save',body,{headers:{'Idempotency-Key':key}});}`,
 '@/api/tasks':`export function getTaskProgress(id,quietError=false){return ot.get('/tasks/'+encodeURIComponent(id),{quietError});}export function getTaskFile(id){return ot.get('/tasks/'+encodeURIComponent(id)+'/file',{quietError:true});}`,
 '@/api/fileTags':`export function confirmTag(body){return ot.post('/files/tags/confirm',body);}`};
 const styles=new Map();
 const built=await esbuild.build({stdin:{contents:"export {default as Home} from '@/views/WebHomeView.vue';",loader:'ts',resolveDir:repo},bundle:true,write:false,format:'iife',globalName:'CofferWebUploads',minify:true,target:'es2020',plugins:[{name:'deployed-runtime',setup(b){
  b.onResolve({filter:/.*/},a=>Object.hasOwn(shims,a.path)?{path:a.path,namespace:'deployed'}:a.path.startsWith('@/')?{path:path.join(repo,'frontend/src',a.path.slice(2)+(path.extname(a.path)?'':'.ts'))}:undefined);
  b.onLoad({filter:/.*/,namespace:'deployed'},a=>({contents:shims[a.path],loader:'js'}));
  b.onLoad({filter:/\.vue$/},a=>{
   let source=fs.readFileSync(a.path,'utf8');
   // This deployed backend predates /files/save. Preserve its existing failure boundary.
   if(a.path.endsWith('WebHomeView.vue')){if(!source.includes('<UploadZone compact @uploaded='))throw Error('Home upload binding changed');source=source.replace('<UploadZone compact @uploaded=','<UploadZone compact :allow-save-only="false" @uploaded=');}
   const descriptor=vue.parse(source,{filename:a.path}).descriptor,id='data-v-coffer-upload-'+hash(a.path).slice(0,8);
   const compiled=vue.compileScript(descriptor,{id,genDefaultAs:'__component',inlineTemplate:true,templateOptions:{compilerOptions:{scopeId:id}}});
   const compiledStyles=descriptor.styles.map(style=>{const s=vue.compileStyle({source:style.content,id,scoped:style.scoped});if(s.errors.length)throw Error(s.errors.join('\n'));return s.code;}).join('\n');styles.set(a.path,compiledStyles);
   return{contents:compiled.content+`\n__component.__scopeId='${id}';export default __component;`,loader:'ts',resolveDir:path.dirname(a.path)};
  });
 }}]});
 const home=one(homes,'home'),mount=one(mounts,'mount');
 const insert=`const cofferUploadRuntime={${Object.entries(runtime).map(([k,v])=>k+':'+v).join(',')}};\n${built.outputFiles[0].text}\n`;
 code=code.slice(0,mount.start)+insert+code.slice(mount.start);
 code=code.slice(0,home.init.start)+`${runtime.defineComponent}({setup(props,ctx){return CofferWebUploads.Home.setup(props,ctx)}})`+code.slice(home.init.end);
 const nextCss=css+'\n'+[...styles.values()].join('\n'),stamp=hash(code+nextCss).slice(0,12),nextJs=`index-upload-flow-${stamp}.js`,nextCssName=`index-upload-flow-${hash(nextCss).slice(0,12)}.css`;
 const consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent chunk')[1],consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8'),nextConsent=`modelConsent-upload-flow-${stamp}.js`;
 if(!consentCode.includes(originalJs))throw Error('Consent import changed');code=code.replaceAll(consent,nextConsent);
 const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});
 const html=fs.readFileSync(path.join(input,'index.html'),'utf8').replaceAll(originalJs,nextJs).replaceAll(originalCss,nextCssName);
 for(const[n,v]of [['index.html',html],['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextCssName,nextCss],['assets/'+nextConsent,consentCode.replaceAll(originalJs,nextJs)]])fs.writeFileSync(path.join(web,n),v);
 const files=['index.html','assets/'+nextJs,'assets/'+nextCssName,'assets/'+nextConsent];
 const backendRoot=path.resolve(repo,'../coffer-test-release/build-artifacts/backend/BOOT-INF/classes'),classRoot=path.join(repo,'.test-tmp/web-upload-flow/classes');
 const classes=['com/coffer/task/application/TaskFileLookupService.class','com/coffer/task/api/TaskFileController.class'];
 for(const n of classes){const to=path.join(output,'build-artifacts/backend/BOOT-INF/classes',n);fs.mkdirSync(path.dirname(to),{recursive:true});fs.copyFileSync(path.join(classRoot,n),to);}
 const fingerprints=['com/coffer/file/application/FileService.class','com/coffer/file/infrastructure/persistence/FileMetadataRepository.class','com/coffer/auth/service/OwnerAuthorizationAspect.class'];
 fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-upload-feedback-2026-10-17',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),newJs:nextJs,files:files.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))})),backendClasses:classes.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(classRoot,n)))})),backendFingerprints:fingerprints.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(backendRoot,n)))}))},null,2));
 console.log(JSON.stringify({staged:true,output,files,backendClasses:classes.length}));
})().catch(e=>{console.error(e);process.exitCode=1;});
