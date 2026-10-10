'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const { prepareElectron } = require('../scripts/prepare-electron.cjs');

function fixture(options, check) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'coffer-electron-preparation-'));
  const settings = { version: '44.7.0', installedVersion: '44.7.0', binaryVersion: '44.7.0', machine: 0x8664, exitCode: 0, writeExe: true, ...options };
  try {
    fs.writeFileSync(path.join(root, 'package.json'), JSON.stringify({ devDependencies: { electron: settings.version } }));
    const moduleRoot = path.join(root, 'node_modules', 'electron');fs.mkdirSync(moduleRoot, { recursive: true });
    fs.writeFileSync(path.join(moduleRoot, 'package.json'), JSON.stringify({ version: settings.installedVersion }));
    fs.writeFileSync(path.join(moduleRoot, 'install.js'), `
      const fs=require('node:fs'),path=require('node:path'),settings=${JSON.stringify(settings)};
      fs.writeFileSync(path.resolve(__dirname,'../../preparation-context.json'),JSON.stringify({platform:process.env.ELECTRON_INSTALL_PLATFORM,arch:process.env.ELECTRON_INSTALL_ARCH,cache:process.env.electron_config_cache}));
      if(settings.exitCode)process.exit(settings.exitCode);
      const dist=path.join(__dirname,'dist');fs.mkdirSync(dist);
      fs.writeFileSync(path.join(dist,'version'),settings.binaryVersion);fs.writeFileSync(path.join(__dirname,'path.txt'),'electron.exe');
      if(settings.writeExe){const pe=Buffer.alloc(96);pe.write('MZ');pe.writeUInt32LE(64,60);pe.writeUInt32LE(0x00004550,64);pe.writeUInt16LE(settings.machine,68);fs.writeFileSync(path.join(dist,'electron.exe'),pe);}
    `);
    check(root, moduleRoot);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
}
const windows = { platform: 'win32', arch: 'x64' };

test('fresh npm package with no binary invokes its installer before verifying Windows x64', () => {
  fixture({}, (root, moduleRoot) => {
    assert.equal(fs.existsSync(path.join(moduleRoot, 'dist', 'electron.exe')), false);
    const prepared = prepareElectron(root, windows);
    assert.equal(prepared.version, '44.7.0');assert.equal(fs.existsSync(prepared.executable), true);
    const context = JSON.parse(fs.readFileSync(path.join(root, 'preparation-context.json'), 'utf8'));
    assert.equal(context.platform, 'win32');assert.equal(context.arch, 'x64');assert.equal(context.cache, path.join(root, '.build', 'cache', 'electron'));
  });
});
test('installer failure prevents packaging even when npm package installation succeeded', () => {
  fixture({ exitCode: 11 }, root => assert.throws(() => prepareElectron(root, windows), /download\/extraction failed/));
});
test('missing binary, wrong release and wrong architecture cannot pass preparation', () => {
  for (const options of [{ writeExe: false }, { binaryVersion: '43.0.0' }, { machine: 0xaa64 }]) {
    fixture(options, root => assert.throws(() => prepareElectron(root, windows), /missing, invalid or does not match/));
  }
});
test('unlocked or mismatched npm versions fail before running a package installer', () => {
  for (const options of [{ version: '^44.7.0' }, { installedVersion: '43.0.0' }]) {
    fixture(options, root => {
      assert.throws(() => prepareElectron(root, windows), /exact Electron version|does not match the pinned version/);
      assert.equal(fs.existsSync(path.join(root, 'preparation-context.json')), false);
    });
  }
});
