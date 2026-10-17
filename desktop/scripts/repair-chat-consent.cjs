'use strict';
// Backport only chat-scoped consent reuse to the shipped V44 frontend.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const {verifyResources, hashFile} = require('../src/resources.cjs');
const {noLinks} = require('../src/safe-paths.cjs');
const repo = path.resolve(__dirname, '../..');
const resourceRoot = path.resolve(process.argv[2] || path.join(repo, 'desktop/dist/win-unpacked/resources/coffer'));
const output = path.resolve(process.argv[3] || path.join(repo, '.test-tmp/chat-consent-' + crypto.randomUUID()));
const install = process.argv.includes('--install');
if (!output.startsWith(path.join(repo, '.test-tmp') + path.sep) || fs.existsSync(output)) throw Error('Use a fresh repository-local test output');
noLinks(resourceRoot); noLinks(output, true);
const manifest = verifyResources(resourceRoot);
if (manifest.schemaVersion !== 44) throw Error('This backport only supports the shipped V44 resources');
const consentEntries = manifest.files.filter(e => /^web\/assets\/modelConsent-[^/]+\.js$/.test(e.path));
if (consentEntries.length !== 1) throw Error('Unexpected consent module layout');
const consentPath = consentEntries[0].path;
const consent = fs.readFileSync(path.join(resourceRoot, consentPath), 'utf8');
if (consent.includes('cofferChatConsentV44')) { console.log('Chat consent reuse is already installed.'); process.exit(0); }
const bindings = /^import\{u as (\w+),h as (\w+),E as (\w+)\}from"\.\/([^/]+\.js)";/.exec(consent);
if (!bindings) throw Error('Unsupported frontend bindings; refusing a blind patch');
const [, auth, http, dialog, indexName] = bindings;
const indexPath = 'web/assets/' + indexName;
const main = fs.readFileSync(path.join(resourceRoot, indexPath), 'utf8');
const before = 'await a(e.url==="/model-execution/inbox")';
if (main.split(before).length !== 2) throw Error('Expected exactly one generic model-consent interceptor');
const factory = esbuild.buildSync({entryPoints:[path.join(repo, 'frontend/src/api/chatTargetConsent.ts')],bundle:true,write:false,
  format:'iife',globalName:'CofferChatConsentV44Factory',minify:true,target:'es2020'}).outputFiles[0].text;
const appendix = `\n${factory}\nconst cofferChatConsentV44=CofferChatConsentV44Factory.createChatTargetConsent({
  identity:()=>({owner:${auth}().user?.id,epoch:${auth}().generation}),
  target:async()=>(await ${http}.get('/model-execution/chat-target')).data.data,
  confirm:message=>${dialog}.confirm(message,'允许 AI 对话查找',{confirmButtonText:'允许并记住此配置',cancelButtonText:'取消',type:'warning',customClass:'model-consent-dialog'}),
  storage:{getItem:key=>localStorage.getItem(key),setItem:(key,value)=>localStorage.setItem(key,value)}
});export{cofferChatConsentV44 as currentChatTargetVersion};\n`;
const changed = new Map([
  [consentPath, consent + appendix],
  [indexPath, main.replace(before, `await(e.url==="/chat/send"?(await import("./${path.basename(consentPath)}")).currentChatTargetVersion():a(e.url==="/model-execution/inbox"))`)],
]);
for (const code of changed.values()) esbuild.transformSync(code, {loader:'js',target:'es2020'});
fs.mkdirSync(output, {recursive:true});
const updated = structuredClone(manifest);
for (const [name, code] of changed) {
  const target = path.join(output, name); fs.mkdirSync(path.dirname(target), {recursive:true});fs.writeFileSync(target, code);
  const entry = updated.files.find(e => e.path === name);
  if (!entry) throw Error('Missing manifest entry');
  entry.size = fs.statSync(target).size;entry.sha256 = hashFile(target);
}
updated.chatConsentRepair = 'scoped-chat-consent-v44-2026-10-16';
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(updated, null, 2));
const className = 'com/coffer/model/runtime/api/ChatTargetController.class';
const classes = path.join(repo, 'backend/target/classes');
if (!fs.existsSync(path.join(classes, className))) throw Error('Compile the chat-target controller before staging');
const classStage = path.join(output, 'class-patch');
const archiveClass = 'BOOT-INF/classes/' + className;
fs.mkdirSync(path.dirname(path.join(classStage, archiveClass)), {recursive:true});
fs.copyFileSync(path.join(classes, className), path.join(classStage, archiveClass));
const jarName = 'backend/coffer-backend.jar';
fs.mkdirSync(path.join(output, 'backend'), {recursive:true});
fs.copyFileSync(path.join(resourceRoot, jarName), path.join(output, jarName));
// Append one new entry, preserving all shipped entries and their unusual ZIP descriptors.
const appendClass = 'import sys,zipfile\nwith zipfile.ZipFile(sys.argv[1],"a",compression=zipfile.ZIP_DEFLATED) as z:\n if sys.argv[2] in z.namelist(): raise RuntimeError("controller already exists")\n z.write(sys.argv[3],sys.argv[2])\n';
const updateJar = require('node:child_process').spawnSync(process.env.COFFER_PYTHON || 'python', ['-c', appendClass,
  path.join(output, jarName), archiveClass, path.join(classStage, archiveClass)], {encoding:'utf8',windowsHide:true});
if (updateJar.status !== 0) throw Error('Chat controller JAR update failed: ' + (updateJar.error?.code || updateJar.stderr || 'unknown'));
const jarEntry = updated.files.find(e=>e.path===jarName);
jarEntry.size = fs.statSync(path.join(output, jarName)).size;jarEntry.sha256 = hashFile(path.join(output, jarName));
fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(updated, null, 2));
if (install) {
  const expectedRoot = path.join(repo, 'desktop/dist/win-unpacked/resources/coffer');
  if (resourceRoot !== expectedRoot) throw Error('Installation is restricted to this repository managed desktop bundle');
  verifyResources(resourceRoot);
  const names = [...changed.keys(), jarName, 'manifest.json'];
  const backup = path.join(output, 'backup');
  for (const name of names) {
    const target = path.join(backup, name);fs.mkdirSync(path.dirname(target), {recursive:true});
    fs.copyFileSync(path.join(resourceRoot, name), target);
  }
  try {
    for (const name of names) fs.copyFileSync(path.join(output, name), path.join(resourceRoot, name));
    const verified = verifyResources(resourceRoot);
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({schemaVersion:verified.schemaVersion,
      resourcesVerified:verified.files.length,changedFiles:names,backendPatchClasses:[className]},null,2));
  } catch(error) {
    for (const name of names) fs.copyFileSync(path.join(backup, name), path.join(resourceRoot, name));
    throw error;
  }
}
console.log(JSON.stringify({output,installed:install,schemaVersion:updated.schemaVersion,changedFiles:[...changed.keys(),jarName,'manifest.json']}));
