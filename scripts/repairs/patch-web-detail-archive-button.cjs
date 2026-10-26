'use strict';

// Remove only the duplicate action in the detail metadata; retain the group confirmation.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const parser = require('../../frontend/node_modules/@babel/parser');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-detail-single-archive-20261026/stage');
const digest = value => crypto.createHash('sha256').update(value).digest('hex');
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
if (fs.existsSync(output)) throw new Error('Use a fresh stage');
const originalJs = 'index-recent-operations-ee3467b993bd.js';
const originalCss = 'index-CqyENvXr.css';
const original = fs.readFileSync(path.join(input, 'assets', originalJs));
if (digest(original) !== 'edadd7169af9822bc0d12af057ee20db1547265823a8f8da804009e29f2b13ab') {
  throw new Error('Deployed release changed');
}
const css = fs.readFileSync(path.join(input, 'assets', originalCss));
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (!html.includes(originalJs) || !html.includes(originalCss)) throw new Error('Entry changed');
let code = esbuild.transformSync(original.toString(), { minify: false }).code;
const ast = parser.parse(code, { sourceType: 'module' });
const text = node => code.slice(node.start, node.end);
const components = [];
walk(ast, node => {
  if (node.type === 'VariableDeclarator' && node.init?.arguments?.[0]?.properties?.some(
    property => property.key?.name === '__name' && property.value?.value === 'FileDetailDrawer')) components.push(node);
});
const drawer = one(components, 'file detail drawer');
const rows = [];
walk(drawer, node => {
  if (node.type === 'CallExpression' && node.arguments?.[0]?.value === 'dd'
      && node.arguments[2]?.type === 'ArrayExpression' && node.arguments[2].elements.length === 2
      && text(node).includes('archived')) rows.push(node.arguments[2]);
});
const children = one(rows, 'archive metadata row');
const [state, action] = children.elements;
if (action.type !== 'ConditionalExpression' || !text(action.test).includes('archived')
    || !text(action.test).includes('revision') || !text(state).includes('arch is-on')) {
  throw new Error('Metadata action changed');
}
code = code.slice(0, children.start) + `[${text(state)}]` + code.slice(children.end);
const stamp = digest(code).slice(0, 12);
const nextJs = `index-detail-single-archive-${stamp}.js`;
const chunks = [];
for (const name of fs.readdirSync(path.join(input, 'assets'))) {
  if (!name.endsWith('.js') || name === originalJs || !code.includes(name)) continue;
  const contents = fs.readFileSync(path.join(input, 'assets', name), 'utf8');
  if (!contents.includes(originalJs)) continue;
  const nextName = `${path.basename(name, '.js')}-detail-${stamp}.js`;
  code = code.replaceAll(name, nextName);
  chunks.push([`assets/${nextName}`, contents.replaceAll(originalJs, nextJs)]);
}
const assets = [['index.html', html.replaceAll(originalJs, nextJs)],
  [`assets/${nextJs}`, esbuild.transformSync(code, { minify: true, target: 'es2020' }).code], ...chunks];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = {
  repair: 'web-detail-single-archive-2026-10-26', originalJs, originalCss,
  originalJsHash: digest(original), originalCssHash: digest(css),
  files: assets.map(([name]) => ({ path: name, sha256: digest(fs.readFileSync(path.join(web, name))) })),
};
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, removedDetailActions: 1, files: plan.files }));
