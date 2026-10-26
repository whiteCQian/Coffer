'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const esbuild = require('../../frontend/node_modules/esbuild');
const parser = require('../../frontend/node_modules/@babel/parser');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-operations-cleanup-20261026/stage-v2');
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
if (fs.existsSync(output)) throw new Error('Use fresh staging');
const originalJs = 'index-detail-single-archive-7ba40d3debf7.js';
const originalCss = 'index-CqyENvXr.css';
const original = fs.readFileSync(path.join(input, 'assets', originalJs));
if (hash(original) !== 'e6b691280ef36f689e79e733fd56dca359e29ded3065d0d3b986daa6611b1093') throw new Error('Release changed');
const originalStyles = fs.readFileSync(path.join(input, 'assets', originalCss), 'utf8');
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (!html.includes(originalJs) || !html.includes(originalCss)) throw new Error('Entry changed');
let code = esbuild.transformSync(original.toString(), { minify: false }).code;
const ast = parser.parse(code, { sourceType: 'module' });
const text = node => code.slice(node.start, node.end);
const components = new Map();
walk(ast, node => {
  if (node.type === 'VariableDeclarator' && node.init?.arguments?.[0]?.properties) {
    const name = node.init.arguments[0].properties.find(property => property.key?.name === '__name')?.value?.value;
    if (['OperationsView', 'WebTopbar'].includes(name)) {
      if (components.has(name)) throw new Error('Duplicate component');
      components.set(name, node);
    }
  }
});
const operations = components.get('OperationsView'), topbar = components.get('WebTopbar');
if (!operations || !topbar) throw new Error('Components missing');
const rows = [], subtitles = [], badges = [], comments = [];
walk(operations, node => {
  if (node.type === 'CallExpression' && node.arguments[0]?.value === 'article'
      && node.arguments[1]?.properties?.some(property => property.key?.name === 'class' && property.value.value === 'recent-row')) rows.push(node);
  if (node.type === 'StringLiteral' && node.value === '按文件查看归档结果，处理异常或撤销整理。') subtitles.push(node);
});
walk(topbar, node => {
  if (node.type === 'CallExpression' && node.callee.type === 'Identifier'
      && node.arguments[0]?.value === '' && node.arguments[1]?.value === true) comments.push(node.callee.name);
  if (node.type !== 'CallExpression' || node.arguments[0]?.value !== 'span') return;
  let containsBadge = false;
  walk(node, child => { if (child.type === 'StringLiteral' && child.value === 'Web 文件空间') containsBadge = true; });
  if (containsBadge) badges.push(node);
});
const row = one(rows, 'recent row');
const groups = row.arguments[2].elements;
if (groups?.length !== 2) throw new Error('Recent row structure changed');
const info = groups[0].arguments[2].elements;
const name = info[0];
const actions = groups[1].arguments[2];
if (name.arguments[0]?.value !== 'strong' || actions.elements.length !== 4) throw new Error('Row actions changed');
const [link, retry, rollback, details] = actions.elements;
if (details.arguments?.[0]?.value !== 'details' || !text(link).includes('/files')) throw new Error('View link changed');
const to = link.arguments[1].properties.find(property => property.key?.name === 'to')?.value;
const slots = link.arguments[2].properties.find(property => property.key?.name === 'default')?.value;
if (!to || slots?.type !== 'CallExpression') throw new Error('Router link slots changed');
// Keep Vue Router navigation, move it to the filename, and add keyboard activation.
const filename = `${text(name.callee)}('strong',{class:'recent-file-name',role:'link',tabindex:0,title:'双击查看文件',onDblclick:navigate,onKeydown:event=>{if(event.key==='Enter'){event.preventDefault();navigate();}}},${text(name.arguments[2])},9,['onDblclick','onKeydown'])`;
const navigation = `${text(link.callee)}(${text(link.arguments[0])},{to:${text(to)},custom:true},{default:${text(slots.callee)}(({navigate})=>[${filename}]),_:1},8,['to'])`;
const subtitle = one(subtitles, 'subtitle');
const badge = one(badges, 'web space badge');
const edits = [
  { start: name.start, end: name.end, value: navigation },
  { start: actions.start, end: actions.end, value: `[${text(retry)},${text(rollback)}]` },
  { start: subtitle.start, end: subtitle.end, value: JSON.stringify('双击文件名查看文件，可撤销整理或重试失败操作。') },
  { start: badge.start, end: badge.end, value: `${one([...new Set(comments)], 'comment renderer')}('',true)` },
];
for (const edit of edits.sort((a, b) => b.start - a.start)) code = code.slice(0, edit.start) + edit.value + code.slice(edit.end);
const styleRule = one([...fs.readFileSync(path.join(repo, 'frontend/src/views/OperationsView.vue'), 'utf8').matchAll(/^\.recent-file-name[^\r\n]+/gm)], 'filename style')[0];
const css = originalStyles + '\n' + styleRule + '\n';
const stamp = hash(code + css).slice(0, 12);
const nextJs = `index-operations-cleanup-${stamp}.js`;
const nextCss = `index-operations-cleanup-${hash(css).slice(0, 12)}.css`;
const chunks = [];
for (const name of fs.readdirSync(path.join(input, 'assets'))) {
  if (!name.endsWith('.js') || name === originalJs || !code.includes(name)) continue;
  const contents = fs.readFileSync(path.join(input, 'assets', name), 'utf8');
  if (!contents.includes(originalJs)) continue;
  const nextName = `${path.basename(name, '.js')}-ops-${stamp}.js`;
  code = code.replaceAll(name, nextName);
  chunks.push([`assets/${nextName}`, contents.replaceAll(originalJs, nextJs)]);
}
const assets = [['index.html', html.replaceAll(originalJs, nextJs).replaceAll(originalCss, nextCss)],
  [`assets/${nextJs}`, esbuild.transformSync(code, { minify: true, target: 'es2020' }).code],
  [`assets/${nextCss}`, css], ...chunks];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = { repair: 'web-operations-cleanup-2026-10-26', originalJs, originalCss,
  originalJsHash: hash(original), originalCssHash: hash(originalStyles),
  files: assets.map(([name]) => ({ path: name, sha256: hash(fs.readFileSync(path.join(web, name))) })),
};
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files }));
