'use strict';
// Patch only the reviewed home presentation on the existing deployed bundle.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const parser = require('../../frontend/node_modules/@babel/parser');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-home-tasks-20261026/stage-v2');
const originalJs = 'index-detail-presentation-caea4908fe22.js';
const originalCss = 'index-tag-remove-style-172e83f640a5.css';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const original = fs.readFileSync(path.join(input, 'assets', originalJs));
const cssBefore = fs.readFileSync(path.join(input, 'assets', originalCss), 'utf8');
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (hash(original) !== 'd9cd892eee8aae0a49edd98886e205c8241d0a80a5912761583f40e169abb3ac'
  || hash(cssBefore) !== '172e83f640a5103479db199c7180849eef8576f3609eaf9017fec3adaad1e313'
  || !html.includes(originalJs) || !html.includes(originalCss)) throw Error('Release baseline changed');
if (fs.existsSync(output)) throw Error('Use a fresh stage');
let code = esbuild.transformSync(original.toString(), { minify: false }).code;
const ast = parser.parse(code, { sourceType: 'module' });
const text = node => code.slice(node.start, node.end), bindings = new Map(), components = new Map(), edits = [];
function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (node.type) visit(node);
  for (const [key, value] of Object.entries(node)) {
    if (key === 'loc') continue;
    if (Array.isArray(value)) value.forEach(child => walk(child, visit));
    else if (value && typeof value === 'object') walk(value, visit);
  }
}
function one(nodes, label) { if (nodes.length !== 1) throw Error(`${label}: expected one, got ${nodes.length}`); return nodes[0]; }
function find(node, predicate, label) { const found = []; walk(node, child => { if (predicate(child)) found.push(child); }); return one(found, label); }
const prop = (node, name) => node.properties.find(p => (p.key?.name ?? p.key?.value) === name);
function argsProps(call) { const p = call.arguments[1]; return p?.type === 'Identifier' ? bindings.get(p.name) : p; }
function hasClass(call, value) { return prop(argsProps(call) ?? { properties: [] }, 'class')?.value?.value === value; }
const isElement = (node, tag) => node.type === 'CallExpression' && node.arguments[0]?.value === tag;
const insert = (at, value) => edits.push({ start: at, end: at, value });
const replace = (node, value) => edits.push({ start: node.start, end: node.end, value });
walk(ast, node => {
  if (node.type !== 'VariableDeclarator' || node.id.type !== 'Identifier') return;
  bindings.set(node.id.name, node.init);
  const obj = node.init?.arguments?.[0];
  if (obj?.properties) {
    const name = prop(obj, '__name')?.value?.value;
    if (['WebHomeView', 'WebUploadProgress', 'ChatDock'].includes(name)) components.set(name, obj);
  }
});
if (components.size !== 3) throw Error('Missing home components');
const home = components.get('WebHomeView'), progress = components.get('WebUploadProgress'), chat = components.get('ChatDock');
const hs = prop(home, 'setup'), ps = prop(progress, 'setup'), cs = prop(chat, 'setup');
// Home owns expansion; component instances remain mounted to preserve chat state.
const homeTasks = find(hs, n => n.type === 'VariableDeclarator' && n.init?.type === 'CallExpression'
  && text(n.init).includes('.processingCount') && text(n.init).includes('.failedCount'), 'task visibility');
const refCall = find(hs, n => n.type === 'VariableDeclarator' && n.init?.type === 'CallExpression'
  && n.init.arguments[0]?.type === 'NullLiteral', 'home ref');
const afterUpload = find(hs, n => n.type === 'FunctionDeclaration' && n.async && text(n).includes('.refresh()'), 'upload completion');
const hashFocus = find(hs, n => n.type === 'FunctionDeclaration' && n.async && text(n).includes('.hash'), 'task hash focus');
const hashTick = find(hashFocus, n => n.type === 'AwaitExpression', 'hash next tick');
const watchCall = find(hs, n => n.type === 'CallExpression' && n.arguments[0]?.type === 'ArrowFunctionExpression'
  && n.arguments[0].body?.property?.name === 'hash', 'route watcher');
const state = 'cofferTasksExpanded';
const stateStatement = one(hs.body.body.filter(n => n.start <= homeTasks.start && n.end >= homeTasks.end), 'home declarations');
insert(stateStatement.end, `const ${state}=${refCall.init.callee.name}(false);${watchCall.callee.name}(${homeTasks.id.name},value=>{if(!value)${state}.value=false;});`);
insert(afterUpload.body.start + 1, `${state}.value=true;`);
replace(hashTick, `(${state}.value=true,${text(hashTick)})`);
const side = find(hs, n => isElement(n, 'div') && n.arguments[1]?.type === 'ObjectExpression' && text(n.arguments[1]).includes('"web-home-side"'), 'home side');
const sideClass = prop(side.arguments[1], 'class').value;
const sideMap = one(sideClass.arguments[0].elements.filter(n => n.type === 'ObjectExpression'), 'side class map');
insert(sideMap.end - 1, `,'tasks-expanded':${state}.value`);
const childChat = side.arguments[2].elements[0], childProgress = side.arguments[2].elements[1];
if (childChat.arguments.length !== 1 || !prop(childProgress.arguments[1], 'show-empty')) throw Error('Home children changed');
replace(childChat, `${childChat.callee.name}(${text(childChat.arguments[0])},{compact:${state}.value,onRestore:()=>${state}.value=false},null,8,['compact'])`);
insert(childProgress.arguments[1].end - 1, `,expanded:${state}.value,onToggleExpand:()=>${state}.value=!${state}.value`);
insert(childProgress.arguments[4].end - 1, `,'expanded'`);
// Task expansion control.
insert(prop(progress, 'props').value.end - 1, ',expanded:{type:Boolean}');
insert(prop(progress, 'emits').value.end - 1, ",'toggle-expand'");
const pp = ps.params[0].name, pe = ps.params[1].properties[0].value.name;
const header = find(ps, n => isElement(n, 'header'), 'task header');
const render = header.callee.name;
insert(header.arguments[2].end - 1, `,${render}('button',{type:'button',class:'task-expand-button','aria-expanded':!!${pp}.expanded,'aria-controls':'web-task-receipts','aria-label':${pp}.expanded?'收起上传与处理':'展开上传与处理',onClick:()=>${pe}('toggle-expand')},${pp}.expanded?'收起':'展开',9,['aria-expanded','aria-label'])`);
const receipts = find(ps, n => isElement(n, 'ol') && hasClass(n, 'upload-receipts'), 'task receipts');
replace(receipts.arguments[1], `{...${text(receipts.arguments[1])},id:'web-task-receipts'}`);
// Chat compact presentation keeps the same input, draft and messages.
insert(prop(chat, 'props').value.end - 1, ',compact:{type:Boolean}');
insert(cs.start, "emits:['restore'],");
insert(cs.params[0].end, ',{emit:cofferChatEmit}');
const cp = cs.params[0].name;
const chatCard = find(cs, n => isElement(n, 'aside') && n.arguments[1]?.type === 'ObjectExpression' && text(n.arguments[1]).includes('"chat-card"'), 'chat card');
const chatMap = one(prop(chatCard.arguments[1], 'class').value.arguments[0].elements.filter(n => n.type === 'ObjectExpression'), 'chat classes');
const expanded = prop(chatMap, 'is-expanded').value;
insert(chatMap.end - 1, `,'is-compact':${cp}.compact&&!(${text(expanded)})`);
const chatInput = find(cs, n => isElement(n, 'div') && hasClass(n, 'chat-input'), 'chat input');
const blanks = [];
walk(cs, n => { if (n.type === 'CallExpression' && n.arguments[0]?.value === '' && n.arguments[1]?.value === true) blanks.push(n.callee.name); });
const blank = one([...new Set(blanks)], 'chat placeholder binding');
// A keyed block mirrors Vue's compiled v-if and keeps dynamic child tracking stable.
insert(chatInput.arguments[2].start + 1, `${cp}.compact&&!(${text(expanded)})?(w(),A('button',{key:'coffer-chat-restore',type:'button',class:'chat-restore','aria-label':'展开 AI 管家',title:'展开 AI 管家',onClick:()=>cofferChatEmit('restore')},'↗')):${blank}('',true),`);
const inputNode = find(chatInput, n => isElement(n, 'input'), 'chat textbox');
insert(inputNode.arguments[1].end - 1, ",'aria-label':'向 AI 管家提问'");
const send = find(cs, n => n.type === 'FunctionDeclaration' && n.async && text(n).includes('.trim()'), 'chat send');
const sendGuard = find(send, n => n.type === 'IfStatement' && n.consequent.type === 'BlockStatement' && text(n.consequent).includes('.push('), 'send guard');
insert(sendGuard.consequent.start + 1, `if(${cp}.compact)cofferChatEmit('restore');`);
for (const edit of edits.sort((a, b) => b.start - a.start)) code = code.slice(0, edit.start) + edit.value + code.slice(edit.end);
const css = cssBefore + '\n' + fs.readFileSync(path.join(repo, 'frontend/src/styles/home-task-expansion.css'), 'utf8');
const stamp = hash(code + css).slice(0, 12), nextJs = `index-home-tasks-${stamp}.js`, nextCss = `index-home-tasks-${hash(css).slice(0, 12)}.css`;
const chunks = [];
for (const name of fs.readdirSync(path.join(input, 'assets'))) {
  if (!name.endsWith('.js') || name === originalJs || !code.includes(name)) continue;
  const contents = fs.readFileSync(path.join(input, 'assets', name), 'utf8');
  if (!contents.includes(originalJs)) continue;
  const nextName = `${path.basename(name, '.js')}-home-${stamp}.js`;
  code = code.replaceAll(name, nextName);
  chunks.push([`assets/${nextName}`, contents.replaceAll(originalJs, nextJs)]);
}
const assets = [['index.html', html.replaceAll(originalJs, nextJs).replaceAll(originalCss, nextCss)],
  [`assets/${nextJs}`, esbuild.transformSync(code, { minify: true, target: 'es2020' }).code], [`assets/${nextCss}`, css], ...chunks];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = { repair: 'web-home-task-expansion-2026-10-26', originalJs, originalCss,
  originalJsHash: hash(original), originalCssHash: hash(cssBefore),
  files: assets.map(([name]) => ({ path: name, sha256: hash(fs.readFileSync(path.join(web, name))) })) };
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files }));
