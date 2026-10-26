'use strict';

// Patch only the operations API and its read/error state in the existing web release.
// Backend/schema, unrelated components and CSS remain on the deployed baseline.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const parser = require('../../frontend/node_modules/@babel/parser');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-recent-operations-20261026/stage');
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
function one(values, name) {
  if (values.length !== 1) throw new Error(`${name}: expected one match, received ${values.length}`);
  return values[0];
}
if (fs.existsSync(output)) throw new Error('Use a fresh staging directory');
const originalJs = 'index-BVnoD2pp.js';
const originalCss = 'index-CqyENvXr.css';
const original = fs.readFileSync(path.join(input, 'assets', originalJs));
const css = fs.readFileSync(path.join(input, 'assets', originalCss));
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (!html.includes(originalJs) || !html.includes(originalCss)) throw new Error('Release entry changed');
let code = esbuild.transformSync(original.toString(), { minify: false }).code;
const ast = parser.parse(code, { sourceType: 'module' });
const text = node => code.slice(node.start, node.end);
const functions = [], components = [];
walk(ast, node => {
  if (node.type === 'FunctionDeclaration' && text(node).includes('"/governance/operations/recent"')) functions.push(node);
  if (node.type === 'VariableDeclarator' && node.init?.arguments?.[0]?.properties?.some(
    property => property.key?.name === '__name' && property.value?.value === 'OperationsView')) components.push(node);
});
const api = one(functions, 'recent operations API');
const component = one(components, 'operations component');
const requests = [];
walk(api, node => {
  if (node.type === 'CallExpression' && node.arguments?.[0]?.value === '/governance/operations/recent') requests.push(node);
});
const http = one(requests, 'API client').callee.object.name;
const axiosErrors = [];
walk(ast, node => {
  if (node.type === 'NewExpression' && node.callee?.type === 'MemberExpression'
      && node.callee.property.name === 'CanceledError') axiosErrors.push(node.callee.object.name);
});
const axios = one([...new Set(axiosErrors)], 'Axios binding');
if (code.includes('let recentRouteMissing')) throw new Error('Compatibility helper already present');
let helper = fs.readFileSync(path.join(repo, 'frontend/src/api/governance.ts'), 'utf8');
helper = helper.slice(helper.indexOf('type RecentQuery = '))
  .replace('export async function recentOperations', `async function ${api.id.name}`)
  .replaceAll('http.', `${http}.`).replaceAll('axios.', `${axios}.`);
helper = esbuild.transformSync(helper, { loader: 'ts', target: 'es2020' }).code;
const loaders = [];
walk(component, node => {
  if (node.type === 'FunctionDeclaration' && node.async && text(node).includes(`await ${api.id.name}(`)) loaders.push(node);
});
const loader = one(loaders, 'operations loader');
const catches = [];
walk(loader, node => { if (node.type === 'CatchClause') catches.push(node); });
const catcher = one(catches, 'load error handler');
const errorAssignment = catcher.body.body[0]?.expression;
if (errorAssignment?.type !== 'AssignmentExpression' || errorAssignment.left.property?.name !== 'value') {
  throw new Error('Error state binding changed');
}
const errorState = text(errorAssignment.left);
const timers = [], emptyChecks = [];
walk(component, node => {
  if (node.type === 'CallExpression' && node.callee?.name === 'setInterval'
      && node.arguments?.[1]?.value === 5000 && text(node.arguments[0]).includes(`${loader.id.name}()`)) timers.push(node.arguments[0]);
  if (node.type === 'ConditionalExpression' && text(node).includes('\\u6DFB\\u52A0\\u5E76\\u5F52\\u6863')
      && text(node.test).includes('.value.length')) emptyChecks.push(node.test);
});
const timer = one(timers, 'refresh interval');
const empty = one(emptyChecks, 'empty state condition');
const edits = [
  { start: api.start, end: api.end, value: helper },
  { start: catcher.start, end: catcher.end, value: `catch(error){${errorState}=error instanceof Error && error.message.startsWith('当前服务版本')?error.message:'最近操作暂时无法读取，请点击刷新重试；文件库仍可访问。';}` },
  { start: timer.start, end: timer.end, value: `()=>{if(!${errorState} && !document.hidden)void ${loader.id.name}();}` },
  { start: empty.start, end: empty.end, value: `(${text(empty)}) && !${errorState}` },
];
for (const edit of edits.sort((a, b) => b.start - a.start)) code = code.slice(0, edit.start) + edit.value + code.slice(edit.end);
const stamp = digest(code).slice(0, 12);
const nextJs = `index-recent-operations-${stamp}.js`;
const chunks = [];
// Dynamic consent chunks import the public HTTP/runtime exports of the main chunk.
// Rewrite both sides so the patch cannot instantiate a second application.
for (const name of fs.readdirSync(path.join(input, 'assets'))) {
  if (!name.endsWith('.js') || name === originalJs || !code.includes(name)) continue;
  const contents = fs.readFileSync(path.join(input, 'assets', name), 'utf8');
  if (!contents.includes(originalJs)) continue;
  const nextName = `${path.basename(name, '.js')}-recent-${stamp}.js`;
  code = code.replaceAll(name, nextName);
  chunks.push([`assets/${nextName}`, contents.replaceAll(originalJs, nextJs)]);
}
const assets = [
  ['index.html', html.replaceAll(originalJs, nextJs)],
  [`assets/${nextJs}`, esbuild.transformSync(code, { minify: true, target: 'es2020' }).code],
  ...chunks,
];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = {
  repair: 'web-recent-operations-2026-10-26', originalJs, originalCss,
  originalJsHash: digest(original), originalCssHash: digest(css),
  files: assets.map(([name]) => ({ path: name, sha256: digest(fs.readFileSync(path.join(web, name))) })),
};
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files, cssChanged: false, backendChanged: false }));
