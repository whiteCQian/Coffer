'use strict';
const fs = require('node:fs'), path = require('node:path');
const { hashFile } = require('../src/resources.cjs');
const root = path.resolve(__dirname, '../dist');
const files = fs.readdirSync(root).filter(name => /\.exe$/.test(name)).sort();
fs.writeFileSync(path.join(root, 'SHA256SUMS.txt'), files.map(name => `${hashFile(path.join(root,name))}  ${name}`).join('\n') + '\n');
process.stdout.write(`Release checksums written for ${files.length} installer(s)\n`);
