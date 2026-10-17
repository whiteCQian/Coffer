'use strict';
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const { regularFile, atomicJson } = require('./safe-paths.cjs');
function validateRecentTags(names) {
  if (!Array.isArray(names) || names.length > 10 || names.some(name => typeof name !== 'string' || !name.trim() || name.length > 512)) throw Error('最近标签参数无效');
  return [...new Set(names)];
}
function recentTagStorage(profileDirectory, dataDirectory, owner) {
  const identity = crypto.createHash('sha256').update(path.resolve(dataDirectory).toLowerCase() + '\0' + String(owner)).digest('hex');
  // The old recent-tags files contained tag-filter clicks, not file opens.
  const file = path.join(profileDirectory, 'recent-file-tags-' + identity + '.json');
  return {
    read() { try { return validateRecentTags(JSON.parse(fs.readFileSync(regularFile(file, 65536), 'utf8'))); } catch { return []; } },
    write(names) { atomicJson(file, validateRecentTags(names)); },
  };
}
module.exports = { recentTagStorage, validateRecentTags };
