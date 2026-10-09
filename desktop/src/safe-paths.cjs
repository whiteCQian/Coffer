'use strict';
const fs = require('node:fs');
const path = require('node:path');
function within(root, target) {
  const relative = path.relative(path.resolve(root), path.resolve(target));
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}
function noLinks(target, allowMissing = false) {
  const resolved = path.resolve(target), parts = [];
  for (let cursor = resolved; ; cursor = path.dirname(cursor)) { parts.unshift(cursor); if (cursor === path.dirname(cursor)) break; }
  for (const part of parts) {
    let info; try { info = fs.lstatSync(part); } catch (error) { if (allowMissing && error.code === 'ENOENT') continue; throw error; }
    if (info.isSymbolicLink()) throw new Error('PATH_LINK_REFUSED');
    if (part !== resolved && !info.isDirectory()) throw new Error('PATH_DIRECTORY_REQUIRED');
  }
  return resolved;
}
function regularFile(target, maxBytes = Number.MAX_SAFE_INTEGER) {
  const resolved = noLinks(target), info = fs.lstatSync(resolved);
  if (!info.isFile() || info.size > maxBytes) throw new Error('FILE_IDENTITY_INVALID');
  return resolved;
}
function dataPath(value, installRoot) {
  if (typeof value !== 'string' || value.length > 4096 || !path.isAbsolute(value) || /[;\r\n\0]/.test(value)) throw new Error('DATA_PATH_INVALID');
  const resolved = noLinks(value, true);
  if (within(installRoot, resolved) || within(resolved, installRoot)) throw new Error('INSTALL_DATA_OVERLAP');
  return resolved;
}
function atomicJson(target, value) {
  noLinks(path.dirname(target));
  if (fs.existsSync(target)) regularFile(target, 65536);
  const stage = target + '.' + require('node:crypto').randomUUID() + '.tmp';
  const fd = fs.openSync(stage, 'wx', 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(value)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  fs.renameSync(stage, target);
}
module.exports = { within, noLinks, regularFile, dataPath, atomicJson };
