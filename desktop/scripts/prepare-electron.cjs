'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { regularFile } = require('../src/safe-paths.cjs');

function prepareElectron(root, { platform = process.platform, arch = process.arch } = {}) {
  if (platform !== 'win32' || arch !== 'x64') throw new Error('Windows installer requires a Windows x64 Node runtime');
  const project = JSON.parse(fs.readFileSync(regularFile(path.join(root, 'package.json')), 'utf8'));
  const version = project.devDependencies?.electron;
  if (!/^\d+\.\d+\.\d+(?:[-+][\w.-]+)?$/.test(version || '')) throw new Error('Pin an exact Electron version before preparing the installer');
  const moduleRoot = path.join(root, 'node_modules', 'electron');
  let installed, installer;
  try {
    installed = JSON.parse(fs.readFileSync(regularFile(path.join(moduleRoot, 'package.json')), 'utf8'));
    installer = regularFile(path.join(moduleRoot, 'install.js'));
  } catch (error) {
    throw new Error('Electron npm package is missing or invalid; run npm ci --prefix desktop', { cause: error });
  }
  if (installed.version !== version) throw new Error('Installed Electron npm package does not match the pinned version');
  if (process.env.ELECTRON_OVERRIDE_DIST_PATH) throw new Error('Unset ELECTRON_OVERRIDE_DIST_PATH for a reproducible installer');
  const env = { ...process.env, ELECTRON_INSTALL_PLATFORM: 'win32', ELECTRON_INSTALL_ARCH: 'x64', electron_config_cache: path.join(root, '.build', 'cache', 'electron') };
  // Keep the checksums from the locked npm package even when a mirror is configured.
  delete env.electron_use_remote_checksums;
  delete env.npm_config_electron_use_remote_checksums;
  const result = spawnSync(process.execPath, [installer], { cwd: root, env, stdio: 'inherit', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error('Locked Electron binary download/extraction failed; installer build stopped', { cause: result.error });
  let executable;
  try {
    const binaryVersion = fs.readFileSync(regularFile(path.join(moduleRoot, 'dist', 'version')), 'utf8').trim().replace(/^v/, '');
    const binaryName = fs.readFileSync(regularFile(path.join(moduleRoot, 'path.txt')), 'utf8').trim();
    if (binaryVersion !== version || binaryName !== 'electron.exe') throw new Error('Electron binary version/platform mismatch');
    executable = regularFile(path.join(moduleRoot, 'dist', 'electron.exe'));
    const fd = fs.openSync(executable, 'r');
    try {
      const dos = Buffer.alloc(64), pe = Buffer.alloc(6);
      if (fs.readSync(fd, dos, 0, dos.length, 0) !== dos.length || dos.toString('ascii', 0, 2) !== 'MZ') throw new Error('Invalid Electron executable');
      const offset = dos.readUInt32LE(60);
      if (offset < 64 || fs.readSync(fd, pe, 0, pe.length, offset) !== pe.length || pe.readUInt32LE(0) !== 0x00004550 || pe.readUInt16LE(4) !== 0x8664) throw new Error('Electron executable is not Windows x64');
    } finally { fs.closeSync(fd); }
  } catch (error) {
    throw new Error('Electron binary is missing, invalid or does not match the pinned Windows x64 release', { cause: error });
  }
  return { version, executable };
}

if (require.main === module) {
  try {
    const prepared = prepareElectron(path.resolve(__dirname, '..'));
    process.stdout.write(`Prepared locked Electron ${prepared.version} for Windows x64\n`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { prepareElectron };
