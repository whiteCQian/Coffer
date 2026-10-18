'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const repo=path.resolve(__dirname,'../..'),input=path.resolve(repo,'../coffer-test-release/build-artifacts/web'),output=path.join(repo,'.test-tmp/chat-drawn-icons/stage');
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
if(fs.existsSync(output))throw Error('Use fresh staging');
const originalJs='index-eight-tags-009fa1f840aa.js',originalCss='index-chat-size-e839dd6a35bc.css',js=fs.readFileSync(path.join(input,'assets',originalJs)),css=fs.readFileSync(path.join(input,'assets',originalCss));
if(hash(js)!=='c2520e3b92b02d2782ac717be90d6a4aa2aa45a4f227206a6eb87f889e2d4c30'||hash(css)!=='e839dd6a35bc1ab0f149919a1728b99cb467227a8978bb9ba24390bf9e97acc7')throw Error('Deployed release changed');
const web=path.join(output,'build-artifacts/web');fs.mkdirSync(path.join(web,'assets'),{recursive:true});let rules=fs.readFileSync(path.join(repo,'frontend/src/styles/chat-expand-controls.css'),'utf8');const out=['index.html'];
for(const mode of ['expand','collapse']){
  const filename=mode+'-generated-v2.png',bytes=fs.readFileSync(path.join(repo,'frontend/src/assets/chat-controls',filename)),asset=mode+'-generated-v2-'+hash(bytes).slice(0,12)+'.png',url='../assets/chat-controls/'+filename;
  if(rules.split(url).length!==2)throw Error('Icon reference changed');rules=rules.replace(url,'./'+asset);fs.writeFileSync(path.join(web,'assets',asset),bytes);out.push('assets/'+asset);
}
const marker='/* Two outward arrows enlarge the panel; two inward arrows restore it. */',parts=css.toString().split(marker);if(parts.length!==2)throw Error('Previous icon rules changed');
const nextCss=parts[0]+rules,nextCssName=`index-chat-drawn-${hash(nextCss).slice(0,12)}.css`,html=fs.readFileSync(path.join(input,'index.html'),'utf8');if(!html.includes(originalJs)||!html.includes(originalCss))throw Error('HTML changed');
fs.writeFileSync(path.join(web,'assets',nextCssName),nextCss);fs.writeFileSync(path.join(web,'index.html'),html.replaceAll(originalCss,nextCssName));out.push('assets/'+nextCssName);
fs.writeFileSync(path.join(output,'patch.json'),JSON.stringify({repair:'web-chat-drawn-icons-2026-10-18',originalJs,originalCss,originalJsHash:hash(js),originalCssHash:hash(css),files:out.map(n=>({path:n,sha256:hash(fs.readFileSync(path.join(web,n)))}))},null,2));console.log(JSON.stringify({staged:true,files:out}));
