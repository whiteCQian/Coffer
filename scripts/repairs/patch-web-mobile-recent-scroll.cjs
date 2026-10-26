'use strict';
// CSS-only mobile recent-upload scrollbar repair on the verified current Web release.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const postcss = require('../../frontend/node_modules/postcss');
const repo = path.resolve(__dirname, '../..');
const input = path.resolve(repo, '../coffer-test-release/build-artifacts/web');
const output = path.join(repo, '.test-tmp/web-mobile-recent-scroll-20261026/stage');
const originalJs = 'index-home-tasks-af8a42ebbb6f.js';
const originalCss = 'index-mobile-home-flow-17013c810a45.css';
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const js = fs.readFileSync(path.join(input, 'assets', originalJs));
const before = fs.readFileSync(path.join(input, 'assets', originalCss), 'utf8');
const html = fs.readFileSync(path.join(input, 'index.html'), 'utf8');
if (hash(js) !== 'd16852a6c047708d11e2f1ddd3839342b68905a7374e9f0be760b3f09551a449'
  || hash(before) !== '17013c810a45c5450933192952124a7dc2479928af7d473e40de58f385010343'
  || !html.includes(originalJs) || !html.includes(originalCss)) throw Error('Release baseline changed');
const source = postcss.parse(fs.readFileSync(path.join(repo, 'frontend/src/styles/web-home-viewport.css'), 'utf8'));
const mobile = [];
source.walkAtRules('media', rule => { if (rule.params.replaceAll(' ', '') === '(max-width:760px)') mobile.push(rule); });
if (mobile.length !== 1) throw Error('Mobile rules missing');
const scrollbarRules = mobile[0].nodes.filter(rule => rule.type === 'rule' && ['html[data-desktop=\'false\'] .web-home .recent-card', 'html[data-desktop=\'false\'] .web-home .recent-list', 'html[data-desktop=\'false\'] .web-home .recent-list::-webkit-scrollbar-thumb'].includes(rule.selector) && (rule.selector.endsWith('.recent-card') ? rule.nodes.some(node => node.prop === 'max-height') : true));
if (scrollbarRules.length !== 3) throw Error('Expected three recent scrollbar rules');
const addition = postcss.atRule({ name: 'media', params: '(max-width: 760px)' });
scrollbarRules.forEach(rule => addition.append(rule.clone()));
if (fs.existsSync(output)) throw Error('Use a fresh stage');
const after = before + '\n' + addition.toString();
const nextCss = `index-mobile-recent-scroll-${hash(after).slice(0, 12)}.css`;
const assets = [['index.html', html.replaceAll(originalCss, nextCss)], [`assets/${nextCss}`, after]];
const web = path.join(output, 'build-artifacts/web');
fs.mkdirSync(path.join(web, 'assets'), { recursive: true });
for (const [name, contents] of assets) fs.writeFileSync(path.join(web, name), contents);
const plan = { repair: 'web-mobile-recent-scroll-2026-10-26', originalJs, originalCss,
  originalJsHash: hash(js), originalCssHash: hash(before),
  files: assets.map(([name]) => ({ path: name, sha256: hash(fs.readFileSync(path.join(web, name))) })) };
fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify(plan, null, 2));
fs.copyFileSync(path.join(repo, 'scripts/repairs/deploy-web-recent-file-tags.py'), path.join(output, 'install.py'));
console.log(JSON.stringify({ stage: output, files: plan.files }));
