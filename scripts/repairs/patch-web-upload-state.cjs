'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),esbuild=require('../../frontend/node_modules/esbuild'),parser=require('../../frontend/node_modules/@babel/parser');
const repo=path.resolve(__dirname,'../..'),input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.join(repo,'.test-tmp/web-upload-state/stage');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error(label+': '+a.length);return a[0];}
if(fs.existsSync(output))throw Error('Use fresh staging');
const originalJs='index-reader-controls-db6c27b9f781.js',originalCss='index-home-viewport-62cccde36ba0.css',original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss));
if(hash(original)!=='f0716160622fc70381451671cd6536f548e4bf1a01455c9090d6859a6668edee'||hash(css)!=='62cccde36ba03f0f25fb1cb11d63885e5b73a72c1378626893c81b9b58be6d1d')throw Error('Deployed release changed');
let code=esbuild.transformSync(original,{minify:false}).code;const ast=parser.parse(code,{sourceType:'module'}),edits=[],recent=[],text=n=>code.slice(n.start,n.end);
walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.init?.arguments?.[0]?.properties?.some(p=>p.key?.name==='__name'&&p.value?.value==='RecentUploads'))recent.push(n);});
const component=one(recent.filter(n=>text(n).includes('refresh:')),'current recent uploads'),stateCalls=[];walk(component,n=>{if(n.type==='FunctionDeclaration'&&n.params.length===1&&n.body.body.length===1&&n.body.body[0].type==='ReturnStatement'&&n.body.body[0].argument?.type==='CallExpression')stateCalls.push(n);});
const state=one(stateCalls.filter(n=>n.body.body[0].argument.arguments.length===1&&n.body.body[0].argument.arguments[0].name===n.params[0].name),'state label wrapper');
edits.push({start:state.body.start,end:state.body.end,value:`{return cofferRecentStatus.recentUploadStateLabel(${text(state.params[0])});}`});
const classes=[];walk(component,n=>{if(n.type==='StringLiteral'&&n.value==='待确认')classes.push(n);});const label=one(classes,'awaiting class label');edits.push({start:label.start,end:label.end,value:JSON.stringify('待确认标签')});
for(const e of edits.sort((a,b)=>b.start-a.start))code=code.slice(0,e.start)+e.value+code.slice(e.end);
const helper=esbuild.buildSync({entryPoints:[path.join(repo,'frontend/src/utils/recentUploadState.ts')],bundle:true,write:false,format:'iife',globalName:'cofferRecentStatus',minify:true,target:'es2020'}).outputFiles[0].text;code=helper+'\n'+code;
const stamp=hash(code).slice(0,12),nextJs=`index-upload-state-${stamp}.js`,consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent module')[1],consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8'),nextConsent=`modelConsent-upload-state-${stamp}.js`;
if(!consentCode.includes(originalJs))throw Error('Consent linkage changed');code=code.replaceAll(consent,nextConsent);
const html=fs.readFileSync(path.join(input,'index.html'),'utf8');if(!html.includes(originalJs)||!html.includes(originalCss))throw Error('HTML changed');const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});
for(const[n,v]of [['index.html',html.replaceAll(originalJs,nextJs)],['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextConsent,consentCode.replaceAll(originalJs,nextJs)]])fs.writeFileSync(path.join(web,n),v);
const files=['index.html','assets/'+nextJs,'assets/'+nextConsent];fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-upload-state-2026-10-18',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),files:files.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))}))},null,2));console.log(JSON.stringify({staged:true,files}));
