'use strict';
// Backport only the model-test proxy budgets to the shipped shell archive.
const fs = require('node:fs'), path = require('node:path'), asar = require('@electron/asar');
const source = path.resolve(process.argv[2]), output = path.resolve(process.argv[3]);
const testRoot = path.resolve(__dirname, '../../.test-tmp') + path.sep;
if (!output.startsWith(testRoot) || fs.existsSync(output)) throw new Error('Select a fresh build-local archive output');
const work = path.join(path.dirname(output), 'shell-source');
if (fs.existsSync(work)) throw new Error('Shell extraction output already exists');
for (const entry of asar.listPackage(source)) {
  const target = path.resolve(work, entry.replace(/^[/\\]+/, ''));
  if (!target.startsWith(work + path.sep)) throw new Error('Unsafe shell archive entry');
}
asar.extractAll(source, work);
const file = path.join(work, 'src/loopback-server.cjs');
let text = fs.readFileSync(file, 'utf8');
if (!text.includes("url.pathname==='/api/settings/runtime/validate'?220000")) {
  const before = 'const upstream=http.request({';
  const timeout = 'headers,timeout:60000}';
  if (text.split(before).length !== 2 || text.split(timeout).length !== 2) throw new Error('Unexpected V44 proxy source');
  text = text.replace(before, "const timeout=url.pathname==='/api/settings/runtime/validate'?220000:url.pathname==='/api/settings/runtime/config/test'?100000:60000;\n      " + before)
    .replace(timeout, 'headers,timeout}');
  fs.writeFileSync(file, text);
}
asar.createPackage(work, output).then(() => {
  if (asar.extractFile(output, 'src/loopback-server.cjs').toString() !== text) throw new Error('Shell archive verification failed');
  console.log('Verified model-test proxy budgets in staged shell archive.');
}).catch(error => { console.error(error.message); process.exitCode = 1; });
