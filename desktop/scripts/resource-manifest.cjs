'use strict';
const fs = require('node:fs'), path = require('node:path');
const { hashFile, verifyResources } = require('../src/resources.cjs');
const root = path.resolve(__dirname, '../.build/resources');
const files = [];
function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes:true})) {
    const file = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error('No links in release resources');
    if (entry.isDirectory()) walk(file);
    else if (entry.isFile() && file !== path.join(root, 'manifest.json')) files.push({path:path.relative(root, file).split(path.sep).join('/'), size:fs.statSync(file).size, sha256:hashFile(file)});
  }
}
walk(root); files.sort((a,b) => a.path.localeCompare(b.path));
const migrations = fs.readdirSync(path.resolve(__dirname, '../../backend/src/main/resources/db/migration/h2'));
const schemaVersion = Math.max(...migrations.map(name => Number(/^V(\d+)__/.exec(name)?.[1] ?? 0)));
const runtime = require('../runtime.lock.json');
fs.writeFileSync(path.join(root, 'manifest.json'), JSON.stringify({formatVersion:1, version:require('../package.json').version, schemaVersion, runtime, files}, null, 2));
verifyResources(root);
process.stdout.write(`Verified ${files.length} packaged resources, schema V${schemaVersion}, Temurin ${runtime.version}\n`);
