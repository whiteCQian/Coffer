'use strict';
// Remove only the duplicate home-page tag card from the currently deployed web UI.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const repo=path.resolve(__dirname,'../..'),esbuild=require('../../frontend/node_modules/esbuild'),parser=require('../../frontend/node_modules/@babel/parser');
const input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.resolve(process.argv[2]||path.join(repo,'.test-tmp/web-home-cleanup/stage'));
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function walk(n,f){if(!n||typeof n!=='object')return;if(n.type)f(n);for(const[k,v]of Object.entries(n))if(k!=='loc'){if(Array.isArray(v))v.forEach(x=>walk(x,f));else if(v&&typeof v==='object')walk(v,f);}}
function one(a,label){if(a.length!==1)throw Error('Unexpected '+label+': '+a.length);return a[0];}
if(!output.startsWith(path.join(repo,'.test-tmp')+path.sep)||fs.existsSync(output))throw Error('Use fresh repository-local staging');
const originalJs='index-file-tags-573e398e809c.js',originalCss='index-file-tags-d9f92bbf4bfb.css';
const original=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss));
if(hash(original)!=='c35d874a512d9a45571453165c3d97e601033367b11f98f42777a7b90968e14a'||hash(css)!=='d9f92bbf4bfb4829d3ce7f3af9cf624330b305b31e2b8a6f1ba78fd273d8d702')throw Error('Deployed release differs');
let code=esbuild.transformSync(original,{minify:false}).code;
const ast=parser.parse(code,{sourceType:'module'}),vars=new Map(),components=[];
const text=n=>code.slice(n.start,n.end);
walk(ast,n=>{if(n.type==='VariableDeclarator'&&n.id.type==='Identifier')vars.set(n.id.name,n);if(n.type==='VariableDeclarator'&&n.init?.arguments?.[0]?.type==='ObjectExpression'&&n.init.arguments[0].properties.some(p=>p.key?.name==='__name'&&p.value?.value==='WebHomeView'))components.push(n);});
const children=[];
walk(one(components,'WebHomeView'),n=>{if(n.type!=='CallExpression')return;const props=n.arguments[1],object=props?.type==='Identifier'?vars.get(props.name)?.init:props;
 if(object?.properties?.some(p=>p.key?.name==='class'&&p.value?.value==='web-home-library'))children.push(n.arguments[2]);});
const array=one(children,'home library children');if(array.type!=='ArrayExpression'||array.elements.length!==3)throw Error('Home layout changed');
const card=array.elements[2],target=vars.get(card.arguments[0].name);if(!target)throw Error('Unexpected tag component');
// Verify the referenced component contains the tag browsing heading before removing its render call.
let tagDefinition=target;if(target.init?.arguments?.[0]?.type==='Identifier')tagDefinition=vars.get(target.init.arguments[0].name);
if(!tagDefinition||!text(tagDefinition).includes('SuggestTags'))throw Error('The third card is not SuggestTags');
code=code.slice(0,array.start)+'['+array.elements.slice(0,2).map(text).join(',')+']'+code.slice(array.end);
const stamp=hash(code).slice(0,12),nextJs=`index-home-cleanup-${stamp}.js`;
const consent=one([...code.matchAll(/import\("\.\/(modelConsent-[^"]+\.js)"\)/g)],'consent module')[1];
const consentCode=fs.readFileSync(path.join(input,'assets',consent),'utf8');if(!consentCode.includes('./'+originalJs))throw Error('Unexpected consent imports');
const nextConsent=`modelConsent-home-cleanup-${stamp}.js`;code=code.replaceAll(consent,nextConsent);
const html=fs.readFileSync(path.join(input,'index.html'),'utf8');if(!html.includes(originalJs))throw Error('Input HTML changed');
const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});
for(const[n,content]of [['index.html',html.replaceAll(originalJs,nextJs)],['assets/'+nextJs,esbuild.transformSync(code,{minify:true,target:'es2020'}).code],['assets/'+nextConsent,consentCode.replaceAll(originalJs,nextJs)]])fs.writeFileSync(path.join(web,n),content);
const files=['index.html','assets/'+nextJs,'assets/'+nextConsent];
fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-home-remove-tag-card-2026-10-17',originalJs,originalCss,originalJsHash:hash(original),originalCssHash:hash(css),newJs:nextJs,files:files.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))}))},null,2));
console.log(JSON.stringify({staged:true,output,files}));
