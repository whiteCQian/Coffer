'use strict';
// Layout-only backport for the shipped V44 desktop; preserve its backend and API bindings.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { verifyResources, hashFile } = require('../src/resources.cjs');
const { noLinks } = require('../src/safe-paths.cjs');
const repo = path.resolve(__dirname, '../..');
const root = path.join(repo, 'desktop/dist/win-unpacked/resources/coffer');
const output = path.resolve(process.argv[2] || path.join(repo, '.test-tmp/operation-scroll-v44-' + crypto.randomUUID()));
const installing = process.argv.includes('--install');
if (!output.startsWith(path.join(repo, '.test-tmp') + path.sep)) throw Error('Use repository-local test output');
noLinks(root); noLinks(output, true);
const marker = 'coffer-operation-scroll-v44-2026-10-16';
if (!installing) {
  if (fs.existsSync(output)) throw Error('Use a fresh output directory');
  const manifest = verifyResources(root);
  if (manifest.schemaVersion !== 44) throw Error('Only shipped V44 is supported');
  const html = fs.readFileSync(path.join(root, 'web/index.html'), 'utf8');
  const links = [...html.matchAll(/href="(\/assets\/[^"/]+\.css)"/g)];
  if (links.length !== 1) throw Error('Unexpected stylesheet layout');
  const name = 'web' + links[0][1];
  const original = fs.readFileSync(path.join(root, name), 'utf8');
  if (!original.includes('.operations-page[data-v-a034ad81]')) throw Error('Unexpected operations component');
  if (original.includes(marker)) throw Error('Repair already installed');
  const patch = `\n/* ${marker} */
.operations-page[data-v-a034ad81]{display:block;height:100%;max-height:100%;min-height:0;min-width:0;overflow-x:hidden;overflow-y:auto;scrollbar-gutter:stable}
.operations-page[data-v-a034ad81]>*{margin-bottom:var(--gap,18px)}
.operations-page[data-v-a034ad81]>.pg-head{flex-wrap:wrap;gap:12px}
.ledger-layout[data-v-a034ad81]{flex:none;align-items:start;min-width:0}
.batch-panel[data-v-a034ad81],.detail-panel[data-v-a034ad81]{min-width:0;min-height:0;overflow:visible}
.batch-list[data-v-a034ad81],.detail-list[data-v-a034ad81]{flex:none;overflow:visible}
.batch-row-head[data-v-a034ad81] code{min-width:0;flex:1;overflow-wrap:anywhere}
.batch-row-meta[data-v-a034ad81],.detail-head[data-v-a034ad81]{flex-wrap:wrap}
.detail-head[data-v-a034ad81]{align-items:flex-start;flex:none}
.panel-heading[data-v-a034ad81]>div,.detail-head[data-v-a034ad81]>div,.detail-item-head[data-v-a034ad81]>div{min-width:0}
.panel-title[data-v-a034ad81]{overflow-wrap:anywhere}
.path-grid[data-v-a034ad81] code{white-space:normal;overflow-wrap:anywhere}
`;
  fs.mkdirSync(path.dirname(path.join(output, name)), { recursive: true });
  fs.writeFileSync(path.join(output, name), original + patch);
  const entry = manifest.files.find(e => e.path === name);
  if (!entry) throw Error('CSS is not in resource manifest');
  entry.size = fs.statSync(path.join(output, name)).size;
  entry.sha256 = hashFile(path.join(output, name));
  manifest.operationScrollRepair = marker;
  fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify(manifest, null, 2));
  fs.writeFileSync(path.join(output, 'patch.json'), JSON.stringify({ files: [name, 'manifest.json'],
    originals: Object.fromEntries([name, 'manifest.json'].map(file => [file, hashFile(path.join(root, file))])) }, null, 2));
  console.log(JSON.stringify({ output, staged: true, changedFiles: [name, 'manifest.json'] }));
} else {
  verifyResources(root);
  const patch = JSON.parse(fs.readFileSync(path.join(output, 'patch.json'), 'utf8'));
  const staged = JSON.parse(fs.readFileSync(path.join(output, 'manifest.json'), 'utf8'));
  if (staged.operationScrollRepair !== marker || patch.files.length !== 2 || patch.files[1] !== 'manifest.json' || !/^web\/assets\/index-[\w-]+\.css$/.test(patch.files[0])) throw Error('Invalid staged repair');
  for (const name of patch.files) {
    if (hashFile(path.join(root, name)) !== patch.originals[name]) throw Error('Resources changed after staging');
    noLinks(path.join(output, name));
    const target = path.join(output, 'backup', name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(root, name), target);
  }
  try {
    for (const name of patch.files) fs.copyFileSync(path.join(output, name), path.join(root, name));
    const verified = verifyResources(root);
    fs.writeFileSync(path.join(output, 'verification.json'), JSON.stringify({ resourcesVerified: verified.files.length,
      changedFiles: patch.files, repair: marker }, null, 2));
    console.log(JSON.stringify({ installed: true, resourcesVerified: verified.files.length, backup: path.join(output, 'backup') }));
  } catch (error) {
    for (const name of patch.files) fs.copyFileSync(path.join(output, 'backup', name), path.join(root, name));
    throw error;
  }
}
