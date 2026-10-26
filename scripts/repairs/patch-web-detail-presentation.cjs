'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const parser = require('../../frontend/node_modules/@babel/parser');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-detail-presentation-20261026/stage-v2');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  if (node.type) visit(node);
  for (const [key, value] of Object.entries(node)) {
    if (key === 'loc') continue;
    if (Array.isArray(value)) value.forEach(child => walk(child, visit));
    else if (value && typeof value === 'object') walk(value, visit);
  }
}
function one(nodes, label) {
  if (nodes.length !== 1) throw new Error(`${label}: expected one match, received ${nodes.length}`);
  return nodes[0];
}
if (fs.existsSync(output)) throw new Error('Use a fresh staging directory');
const originalJs = 'index-operations-cleanup-2a8a51fdc53a.js';
const originalCss = 'index-operations-cleanup-1d365165e07b.css';
const original = fs.readFileSync(path.join(input, 'assets', originalJs));
if (hash(original) !== '971392ac3bb9c94cd6cbf473968debfebc3bf022e8ddd2cd2680a2df21f5637a') throw new Error('Release changed');
const cssBefore = fs.readFileSync(path.join(input, 'assets', originalCss), 'utf8');
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (!html.includes(originalJs) || !html.includes(originalCss)) throw new Error('Entry changed');
let code = esbuild.transformSync(original.toString(), { minify: false }).code;
const ast = parser.parse(code, { sourceType: 'module' });
const text = node => code.slice(node.start, node.end);
const components = new Map(), bindings = new Map();
walk(ast, node => {
  if (node.type === 'VariableDeclarator' && node.id.type === 'Identifier') bindings.set(node.id.name, node.init);
  if (node.type === 'VariableDeclarator' && node.init?.arguments?.[0]?.properties) {
    const name = node.init.arguments[0].properties.find(property => property.key?.name === '__name')?.value?.value;
    if (['FileDetailDrawer', 'StorageQuotaPanel'].includes(name)) {
      if (components.has(name)) throw new Error('Duplicate component');
      components.set(name, node);
    }
  }
});
const drawer = components.get('FileDetailDrawer'), quota = components.get('StorageQuotaPanel');
if (!drawer || !quota) throw new Error('Components missing');
const setup = drawer.init.arguments[0].properties.find(property => property.key?.name === 'setup');
const props = setup.params[0].name;
const refs = [], watchers = [], ticks = [], roots = [], masks = [], panels = [], closes = [], quotaRoots = [];
function propsOf(call) {
  const arg = call.arguments[1];
  return arg?.type === 'Identifier' ? bindings.get(arg.name) : arg;
}
function hasClass(call, name) {
  return propsOf(call)?.properties?.some(property => property.key?.name === 'class' && property.value?.value === name);
}
walk(drawer, node => {
  if (node.type === 'VariableDeclarator' && node.init?.type === 'CallExpression'
      && node.init.callee.type === 'Identifier' && node.init.arguments[0]?.type === 'NullLiteral') refs.push(node.init.callee.name);
  if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.arguments[0]?.type === 'ArrowFunctionExpression'
      && node.arguments[0].body.type === 'ArrayExpression' && node.arguments.length >= 2
      && text(node.arguments[0]).includes('.modelValue') && text(node.arguments[0]).includes('.revision')) watchers.push(node.callee.name);
  if (node.type === 'AwaitExpression' && node.argument.type === 'CallExpression'
      && node.argument.callee.type === 'Identifier' && node.argument.arguments.length === 0) ticks.push(node.argument.callee.name);
  if (node.type !== 'CallExpression') return;
  if (node.arguments[0]?.value === 'div' && node.arguments[1]?.type === 'ObjectExpression'
      && node.arguments[1].properties.some(property => property.key?.name === 'class' && text(property.value).includes('"drawer-wrap"'))) roots.push(node);
  if (node.arguments[0]?.value === 'div' && hasClass(node, 'drawer-mask')) masks.push(node);
  if (node.arguments[0]?.value === 'aside' && hasClass(node, 'drawer-panel')) panels.push(node);
});
const root = one(roots, 'drawer root'), mask = one(masks, 'drawer mask'), panel = one(panels, 'main panel');
walk(panel, node => {
  if (node.type === 'CallExpression' && node.arguments[0]?.value === 'button' && hasClass(node, 'head-x')) closes.push(node);
});
const close = one(closes, 'detail close button');
const ref = one([...new Set(refs)], 'ref binding'), watch = one([...new Set(watchers)], 'watch binding'), nextTick = one([...new Set(ticks)], 'nextTick binding');
const helperSource = fs.readFileSync(path.join(repo, 'frontend/src/composables/useDetailPresentation.ts'), 'utf8')
  .replace(/^import[^\r\n]+from 'vue'\r?\n/, `const ref=${ref},watch=${watch},nextTick=${nextTick};\n`)
  .replace('export function useDetailPresentation', 'function useDetailPresentation');
const helper = esbuild.transformSync(helperSource, { loader: 'ts', target: 'es2020' }).code;
const mounts = ast.program.body.filter(node => node.type === 'ExpressionStatement' && text(node).includes('.mount("#app")'));
const mount = one(mounts, 'application mount');
const state = 'cofferDetailUi';
const rootProps = root.arguments[1];
const classMap = rootProps.properties.find(property => property.key?.name === 'class').value.arguments[0].elements.find(node => node.type === 'ObjectExpression');
if (!classMap) throw new Error('Drawer class map missing');
const maskConditions = [];
walk(drawer, node => { if (node.type === 'ConditionalExpression' && node.alternate?.start <= mask.start && node.alternate?.end >= mask.end) maskConditions.push(node); });
const maskCondition = one(maskConditions, 'mask conditional');
const oldMaskClick = mask.arguments[1].properties.find(property => property.key?.name === 'onClick').value;
const render = close.callee.name;
const expandedButton = `${render}('button',{ref:${state}.toggleButton,class:'head-expand','aria-label':${state}.expanded.value?'收起文件详情':'放大文件详情','aria-expanded':${state}.expanded.value,onClick:()=>${state}.setExpanded(!${state}.expanded.value)},${state}.expanded.value?'↙ 收起':'↗ 放大',9,['aria-label','aria-expanded'])`;
const edits = [
  { start: mount.start, end: mount.start, value: `const CofferDetailPresentation=(()=>{${helper};return {useDetailPresentation};})();\n` },
  { start: setup.body.start + 1, end: setup.body.start + 1, value: `const ${state}=CofferDetailPresentation.useDetailPresentation(()=>String(${props}.modelValue)+':'+String(${props}.revision??''));` },
  { start: classMap.end - 1, end: classMap.end - 1, value: `,'is-expanded':${state}.expanded.value` },
  { start: rootProps.end - 1, end: rootProps.end - 1, value: `,onKeydownCapture:${state}.onKeydown` },
  { start: maskCondition.test.start, end: maskCondition.test.end, value: `(${text(maskCondition.test)})&&!${state}.expanded.value` },
  { start: oldMaskClick.start, end: oldMaskClick.end, value: `()=>${state}.expanded.value?${state}.setExpanded(false):(${text(oldMaskClick)})()` },
  { start: panel.arguments[1].start, end: panel.arguments[1].end, value: `{...${text(panel.arguments[1])},ref:${state}.panel,role:${state}.expanded.value?'dialog':void 0,'aria-modal':${state}.expanded.value||void 0,'aria-label':${state}.expanded.value?'文件详情':void 0,tabindex:-1}` },
  { start: panel.end - 1, end: panel.end - 1, value: `,8,['role','aria-modal','aria-label']` },
  { start: close.start, end: close.start, value: expandedButton + ',' },
];
if (panel.arguments.length !== 3) throw new Error('Panel patch flags changed');
walk(quota, node => { if (node.type === 'CallExpression' && node.arguments[0]?.value === 'aside' && hasClass(node, 'quota-panel')) quotaRoots.push(node); });
const quotaRoot = one(quotaRoots, 'quota root');
const quotaChildren = quotaRoot.arguments[2];
const quotaRender = quotaChildren.elements.find(node => node.type === 'CallExpression' && node.arguments[0]?.value === 'button')?.callee.name;
if (!quotaRender) throw new Error('Quota render binding missing');
edits.push(
  { start: quotaRoot.arguments[0].start, end: quotaRoot.arguments[0].end, value: "'details'" },
  { start: quotaRoot.arguments[1].start, end: quotaRoot.arguments[1].end, value: `{...${text(quotaRoot.arguments[1])},class:'quota-panel is-collapsible'}` },
  { start: quotaChildren.start, end: quotaChildren.end, value: `[${quotaRender}('summary',null,'存储用量'),${quotaRender}('div',{class:'quota-details'},${text(quotaChildren)})]` },
);
for (const edit of edits.sort((a, b) => b.start - a.start)) code = code.slice(0, edit.start) + edit.value + code.slice(edit.end);
const css = cssBefore + '\n' + fs.readFileSync(path.join(repo, 'frontend/src/styles/detail-presentation.css'), 'utf8');
const stamp = hash(code + css).slice(0, 12);
const nextJs = `index-detail-presentation-${stamp}.js`, nextCss = `index-detail-presentation-${hash(css).slice(0, 12)}.css`;
const chunks = [];
for (const name of fs.readdirSync(path.join(input, 'assets'))) {
  if (!name.endsWith('.js') || name === originalJs || !code.includes(name)) continue;
  const contents = fs.readFileSync(path.join(input, 'assets', name), 'utf8');
  if (!contents.includes(originalJs)) continue;
  const nextName = `${path.basename(name, '.js')}-detail-ui-${stamp}.js`;
  code = code.replaceAll(name, nextName);
  chunks.push([`assets/${nextName}`, contents.replaceAll(originalJs, nextJs)]);
}
const assets = [['index.html', html.replaceAll(originalJs, nextJs).replaceAll(originalCss, nextCss)],
  [`assets/${nextJs}`, esbuild.transformSync(code, { minify: true, target: 'es2020' }).code], [`assets/${nextCss}`, css], ...chunks];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = { repair: 'web-detail-presentation-2026-10-26', originalJs, originalCss,
  originalJsHash: hash(original), originalCssHash: hash(cssBefore),
  files: assets.map(([name]) => ({ path: name, sha256: hash(fs.readFileSync(path.join(web, name))) })),
};
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files }));
