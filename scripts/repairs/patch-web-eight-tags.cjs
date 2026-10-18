'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),esbuild=require('../../frontend/node_modules/esbuild'),parser=require('../../frontend/node_modules/@babel/parser');
const repo=path.resolve(__dirname,'../..'),input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.join(repo,'.test-tmp/web-eight-tags/stage');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error(label+': '+a.length);return a[0];}
if(fs.existsSync(output))throw Error('Use fresh staging');
const originalJs='index-pane-layout-e68cb912922e.js',originalCss='index-pane-layout-555e68d855f5.css',original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss));
if(hash(original)!=='c20479fb5cd293286832c9b31bc6aa068decba15ed35a2e68f1573177f0644e7'||hash(css)!=='555e68d855f5dd187a6adc09fdea8c2e0c865c2f7e5c04fa4f8819b5665d3fa3')throw Error('Deployed release changed');
let code=esbuild.transformSync(original,{minify:false}).code;const ast=parser.parse(code,{sourceType:'module'}),normalizers=[];
walk(ast,n=>{if(n.type==='FunctionDeclaration'){const body=code.slice(n.start,n.end);if(body.includes('new Set(')&&body.includes('.trim()')&&body.includes('<= 512')&&body.includes('.slice(0, 10)'))normalizers.push(n);}});
const normalizer=one(normalizers,'recent tag normalizer'),limits=[];walk(normalizer,n=>{if(n.type==='CallExpression'&&n.callee.type==='MemberExpression'&&n.callee.property.name==='slice'&&n.arguments[0]?.value===0&&n.arguments[1]?.value===10)limits.push(n.arguments[1]);});
const limit=one(limits,'recent tag cap');code=code.slice(0,limit.start)+'8'+code.slice(limit.end);
const stamp=hash(code).slice(0,12),nextJs=`index-eight-tags-${stamp}.js`,consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent module')[1],consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8'),nextConsent=`modelConsent-eight-tags-${stamp}.js`;
if(!consentCode.includes(originalJs))throw Error('Consent linkage changed');code=code.replaceAll(consent,nextConsent);
const html=fs.readFileSync(path.join(input,'index.html'),'utf8');if(!html.includes(originalJs)||!html.includes(originalCss))throw Error('HTML changed');const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});
for(const[n,v]of [['index.html',html.replaceAll(originalJs,nextJs)],['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextConsent,consentCode.replaceAll(originalJs,nextJs)]])fs.writeFileSync(path.join(web,n),v);
const out=['index.html','assets/'+nextJs,'assets/'+nextConsent];fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-eight-recent-tags-2026-10-18',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),files:out.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))}))},null,2));console.log(JSON.stringify({staged:true,files:out}));
