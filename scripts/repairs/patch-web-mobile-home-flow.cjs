'use strict';
// CSS-only mobile layout repair on the verified current Web release.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const postcss = require('../../frontend/node_modules/postcss');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-mobile-flow-20261026/stage');
const originalJs = 'index-home-tasks-af8a42ebbb6f.js';
const originalCss = 'index-home-tasks-dd2281d1ebb4.css';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const js = fs.readFileSync(path.join(input, 'assets', originalJs));
const before = fs.readFileSync(path.join(input, 'assets', originalCss), 'utf8');
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (hash(js) !== 'd16852a6c047708d11e2f1ddd3839342b68905a7374e9f0be760b3f09551a449'
  || hash(before) !== 'dd2281d1ebb4bb9d669be7502bd9e5589fe593c63a02080718e9152620992e7f'
  || !html.includes(originalJs) || !html.includes(originalCss)) throw Error('Release baseline changed');
const source = postcss.parse(fs.readFileSync(path.join(repo, 'frontend/src/styles/web-home-viewport.css'), 'utf8'));
const mobile = [];
source.walkAtRules('media', rule => { if (rule.params.replaceAll(' ', '') === '(max-width:760px)') mobile.push(rule); });
if (mobile.length !== 1 || !mobile[0].toString().includes('grid-template-rows: auto auto')) throw Error('Mobile flow rules missing');
if (fs.existsSync(output)) throw Error('Use a fresh stage');
const after = before + '\n' + mobile[0].toString();
const nextCss = `index-mobile-home-flow-${hash(after).slice(0, 12)}.css`;
const assets = [['index.html', html.replaceAll(originalCss, nextCss)], [`assets/${nextCss}`, after]];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = { repair: 'web-mobile-home-flow-2026-10-26', originalJs, originalCss,
  originalJsHash: hash(js), originalCssHash: hash(before),
  files: assets.map(([name]) => ({ path: name, sha256: hash(fs.readFileSync(path.join(web, name))) })) };
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files }));
