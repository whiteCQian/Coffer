'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { within, regularFile, noLinks } = require('./safe-paths.cjs');
function hashFile(file) {
  const hash = crypto.createHash('sha256'), fd = fs.openSync(regularFile(file), 'r'), buffer = Buffer.alloc(1024 * 1024);
  try { let count; while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, count)); }
  finally { fs.closeSync(fd); }
  return hash.digest('hex');
}
function verifyResources(root) {
  noLinks(root); const manifest = JSON.parse(fs.readFileSync(regularFile(path.join(root, 'manifest.json'), 2 * 1024 * 1024), 'utf8'));
  if (manifest.formatVersion !== 1 || !Number.isSafeInteger(manifest.schemaVersion) || !Array.isArray(manifest.files) || manifest.files.length > 10000) throw new Error('PACKAGE_MANIFEST_INVALID');
  const names = new Set();
  for (const entry of manifest.files) {
    if (typeof entry.path !== 'string' || entry.path.includes('\\') || entry.path.split('/').some(p => !p || p === '.' || p === '..') || !/^[a-f0-9]{64}$/.test(entry.sha256) || names.has(entry.path)) throw new Error('PACKAGE_MANIFEST_INVALID');
    const file = path.resolve(root, entry.path); if (!within(root, file) || fs.statSync(regularFile(file)).size !== entry.size || hashFile(file) !== entry.sha256) throw new Error('PACKAGE_CHECKSUM_FAILED');
    names.add(entry.path);
  }
  for (const required of ['backend/coffer-backend.jar', 'runtime/bin/java.exe', 'runtime/release', 'web/index.html']) if (!names.has(required)) throw new Error('PACKAGE_RESOURCE_MISSING');
  return manifest;
}
module.exports = { hashFile, verifyResources };
