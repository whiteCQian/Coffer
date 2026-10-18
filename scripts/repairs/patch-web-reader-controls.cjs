'use strict';
// Target only the reader controls and recent-upload scrollbar in the shipped UI.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),esbuild=require('../../frontend/node_modules/esbuild'),parser=require('../../frontend/node_modules/@babel/parser');
const repo=path.resolve(__dirname,'../..'),input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.join(repo,'.test-tmp/web-reader-ui/stage');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error(label+': '+a.length);return a[0];}
if(fs.existsSync(output))throw Error('Use fresh staging');
const originalJs='index-upload-flow-9f2b4370ed9a.js',originalCss='index-content-layout-caa977a876a5.css',original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss),'utf8');
if(hash(original)!=='db83f930c2926066c1dd00d403503bd5c36fb270d67d888abc54a8971cdcfc4e'||hash(css)!=='caa977a876a58aab9eef97417d43127a8ea3569e39efa3eb1de1d9832b40bbb7')throw Error('Live release differs');
let code=esbuild.transformSync(original,{minify:false}).code;const ast=parser.parse(code,{sourceType:'module'}),edits=[],text=n=>code.slice(n.start,n.end),components={};
walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.init?.arguments?.[0]?.properties?.some(p=>p.key?.name==='__name'&&['FileDetailDrawer','FilesView'].includes(p.value?.value)))components[n.init.arguments[0].properties.find(p=>p.key?.name==='__name').value.value]=n;});
if(!components.FileDetailDrawer||!components.FilesView)throw Error('Missing components');
// This release's drawer uses d.value for the displayed file; verify the opening arguments.
const buttons=[],links=[];walk(components.FileDetailDrawer,n=>{if(n.type==='CallExpression'&&n.arguments[0]?.value==='button'&&text(n).includes('.openFile('))buttons.push(n);if(n.type==='CallExpression'&&n.arguments[1]?.properties?.some(p=>p.key?.name==='class'&&p.value?.value==='preview-link'))links.push(n);});
const button=one(buttons,'drawer open control');if(!text(button).includes('a.openFile(d.value.id, l.revision, d.value.fileType)'))throw Error('Drawer binding changed');
edits.push({start:button.start,end:button.end,value:`cofferWebReader.supportsOnlineReading(d.value.fileType)?${text(button)}:X("",true)`});
const link=one(links,'preview link');edits.push({start:link.start,end:link.end,value:`cofferWebReader.supportsOnlineReading(d.value.fileType)?${text(link)}:X("",true)`});
// Restrict the whole preview-link fragment, including its punctuation.
const conditions=[];walk(components.FileDetailDrawer,n=>{if(n.type==='ConditionalExpression'&&text(n.test)==='T.value && !s(pa)')conditions.push(n.test);});const test=one(conditions,'preview condition');edits.push({start:test.start,end:test.end,value:'T.value && !s(pa) && cofferWebReader.supportsOnlineReading(d.value.fileType)'});
const note=[];walk(components.FileDetailDrawer,n=>{if(n.type==='StringLiteral'&&n.value==='该类型暂不支持内嵌预览')note.push(n);});const oldNote=one(note,'preview note');edits.push({start:oldNote.start,end:oldNote.end,value:'(cofferWebReader.supportsOnlineReading(d.value.fileType)?"可打开文件在线阅读。":"该类型暂不支持在线阅读，请下载到本地查看。")'});
const menuButtons=[],openCalls=[];walk(components.FilesView,n=>{if(n.type==='CallExpression'&&n.arguments[0]?.value==='button'&&n.arguments[2]?.value==='打开文件')menuButtons.push(n);if(n.type==='CallExpression'&&text(n).startsWith('t.openFile('))openCalls.push(n);});
const menu=one(menuButtons,'menu open control');edits.push({start:menu.start,end:menu.end,value:`cofferWebReader.supportsOnlineReading(P.value.find(file=>file.id===V.value.id)?.fileType)?${text(menu)}:X("",true)`});
const open=one(openCalls,'row opener');if(text(open)!=='t.openFile(Re, void 0, Ae ?? P.value.find((rt) => rt.id === Re)?.fileType)')throw Error('Row opener changed');edits.push({start:open.start,end:open.end,value:`cofferWebReader.supportsOnlineReading(Ae ?? P.value.find(file=>file.id===Re)?.fileType)?${text(open)}:j(Re)`});
// Guard the shared opener as well, so non-preview formats cannot trigger a download indirectly.
const openerGuards=[];walk(ast,n=>{if(n.type==='FunctionDeclaration'&&n.async&&n.params.length===3&&text(n).includes('about:blank')&&text(n).includes('method: "HEAD"'))openerGuards.push(n);});
const guard=one(openerGuards,'shared opener');if(guard.params.length!==3)throw Error('Open arguments changed');edits.push({start:guard.body.start+1,end:guard.body.start+1,value:`if(!cofferWebReader.supportsOnlineReading(${text(guard.params[2]).split('=')[0].trim()}))return;`});
for(const e of edits.sort((a,b)=>b.start-a.start)){code=code.slice(0,e.start)+e.value+code.slice(e.end);}
// Compile the same small utilities used by source builds; no second Vue runtime.
const utilities=esbuild.buildSync({stdin:{contents:"export {supportsOnlineReading} from './frontend/src/utils/onlineReading';import {installRecentUploadScrollbar} from './frontend/src/utils/recentUploadScrollbar';installRecentUploadScrollbar();",resolveDir:repo,loader:'ts'},bundle:true,write:false,format:'iife',globalName:'cofferWebReader',minify:true,target:'es2020'}).outputFiles[0].text;
code=utilities+'\n'+code;
const previousHint='单击查看详情 · 双击打开文件 · 右键查看更多操作';walk(parser.parse(code,{sourceType:'module'}),()=>{});
// Replace its escaped literal as generated by esbuild, keeping all other UI text intact.
const hintCode=esbuild.transformSync(JSON.stringify(previousHint),{minify:false}).code.trim().replace(/;$/,'');
code=code.replace(hintCode,JSON.stringify('单击查看详情 · 双击预览支持的文件 · 右键查看更多操作'));
const nextCss=css+'\n'+fs.readFileSync(path.join(repo,'frontend/src/styles/web-content-layout.css'),'utf8'),stamp=hash(code+nextCss).slice(0,12),nextJs=`index-reader-controls-${stamp}.js`,nextCssName=`index-reader-controls-${hash(nextCss).slice(0,12)}.css`;
const consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent module')[1],consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8'),nextConsent=`modelConsent-reader-controls-${stamp}.js`;
if(!consentCode.includes(originalJs))throw Error('Consent linkage changed');code=code.replaceAll(consent,nextConsent);
const html=fs.readFileSync(path.join(input,'index.html'),'utf8');if(!html.includes(originalJs)||!html.includes(originalCss))throw Error('HTML changed');
const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});
for(const[n,v]of [['index.html',html.replaceAll(originalJs,nextJs).replaceAll(originalCss,nextCssName)],['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextCssName,nextCss],['assets/'+nextConsent,consentCode.replaceAll(originalJs,nextJs)]])fs.writeFileSync(path.join(web,n),v);
const files=['index.html','assets/'+nextJs,'assets/'+nextCssName,'assets/'+nextConsent];fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-reader-controls-2026-10-18',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),files:files.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))}))},null,2));console.log(JSON.stringify({staged:true,files}));
