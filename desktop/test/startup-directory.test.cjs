'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {startupPath,inspectDirectory,newLibraryLocation,requireEmptyWritableDirectory,requireSeparateEmptyLibrary}=require('../src/startup-directory.cjs');
function fixture(t){
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-onboarding-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  return {root,install:path.join(root,'install'),profile:path.join(root,'shell'),data:path.join(root,'data')};
}
test('missing or empty fresh locations can be created without creating them during inspection',t=>{
  const {data}=fixture(t);
  assert.deepEqual(inspectDirectory(data),{kind:'empty',exists:false,nonempty:false});
  requireEmptyWritableDirectory(data);assert.equal(fs.existsSync(data),false);
  fs.mkdirSync(data);fs.writeFileSync(path.join(data,'.coffer-desktop.lock'),'');
  assert.equal(inspectDirectory(data).kind,'empty');requireEmptyWritableDirectory(data);
});
test('a missing previously used library never becomes an empty creation candidate',t=>{
  const {data}=fixture(t);
  assert.equal(inspectDirectory(data,{initialized:true}).kind,'missing');
  fs.mkdirSync(data);assert.equal(inspectDirectory(data,{initialized:true}).kind,'missing');
});
test('existing and unrelated contents refuse initialization and are retained',t=>{
  const {data}=fixture(t);fs.mkdirSync(data);
  const unrelated=path.join(data,'report.txt');fs.writeFileSync(unrelated,'keep');
  assert.equal(inspectDirectory(data).kind,'occupied');assert.throws(()=>requireEmptyWritableDirectory(data),/INITIALIZATION_REFUSED/);
  fs.writeFileSync(path.join(data,'.coffer-desktop.json'),'{}');
  assert.equal(inspectDirectory(data).kind,'existing');assert.throws(()=>requireEmptyWritableDirectory(data),/INITIALIZATION_REFUSED/);
  assert.equal(fs.readFileSync(unrelated,'utf8'),'keep');
});
test('custom selection previews an isolated Coffer child and recognizes a selected existing library',t=>{
  const {root,install,profile}=fixture(t);
  assert.equal(newLibraryLocation(root,install,profile),path.join(root,'Coffer'));
  const parent=path.join(root,'个人资料 with spaces');fs.mkdirSync(parent);fs.writeFileSync(path.join(parent,'unrelated.txt'),'keep');
  const target=newLibraryLocation(parent,install,profile);
  assert.equal(target,path.join(parent,'Coffer'));assert.equal(fs.existsSync(target),false);
  fs.mkdirSync(target);fs.writeFileSync(path.join(target,'.coffer-desktop.json'),'{}');
  assert.equal(newLibraryLocation(target,install,profile),target);
});
test('program/cache overlap, files, and junctions cannot be used as new-library locations',t=>{
  const {root,install,profile,data}=fixture(t);
  for(const target of [install,path.join(install,'data'),root])assert.throws(()=>startupPath(target,install,profile),/INSTALL_DATA_OVERLAP/);
  for(const target of [profile,path.join(profile,'data')])assert.throws(()=>startupPath(target,install,profile),/PROFILE_DATA_OVERLAP/);
  fs.writeFileSync(data,'not a directory');assert.equal(inspectDirectory(data).kind,'unavailable');
  assert.throws(()=>requireEmptyWritableDirectory(data),/INITIALIZATION_REFUSED/);
  const linked=path.join(root,'link');fs.mkdirSync(path.join(root,'target'));fs.symlinkSync(path.join(root,'target'),linked,'junction');
  assert.equal(inspectDirectory(linked).kind,'unavailable');assert.throws(()=>startupPath(linked,install,profile),/PATH_LINK_REFUSED/);
});
test('explicit new-library creation cannot overlap an existing or missing bound library',t=>{
  const {root,data}=fixture(t),previous={dataDirectory:data,initialized:true};
  for(const target of [data,path.join(data,'new-library'),root])assert.throws(()=>requireSeparateEmptyLibrary(target,previous),/LIBRARY_LOCATION_OVERLAP/);
  const separate=path.join(root,'new-empty');assert.equal(requireSeparateEmptyLibrary(separate,previous),separate);assert.equal(fs.existsSync(separate),false);
});
test('a separately configured file library is protected while an unused empty selection may be reused',t=>{
  const {root,data}=fixture(t),library=path.join(root,'external-files');fs.mkdirSync(library);fs.writeFileSync(path.join(library,'.coffer-library.json'),'{}');
  const previous={dataDirectory:data,libraryDirectory:library,initialized:true};
  assert.throws(()=>requireSeparateEmptyLibrary(path.join(library,'child'),previous),/LIBRARY_LOCATION_OVERLAP/);
  const unused=path.join(root,'unused');fs.mkdirSync(unused);
  assert.equal(requireSeparateEmptyLibrary(unused,{...previous,libraryDirectory:unused}),unused);
});
test('new-library selection refuses nonempty targets without modifying either directory',t=>{
  const {root,data}=fixture(t),target=path.join(root,'target');fs.mkdirSync(target);fs.writeFileSync(path.join(target,'keep.txt'),'keep');
  assert.throws(()=>requireSeparateEmptyLibrary(target,{dataDirectory:data,initialized:true}),/INITIALIZATION_REFUSED/);
  assert.equal(fs.readFileSync(path.join(target,'keep.txt'),'utf8'),'keep');assert.equal(fs.existsSync(data),false);
});
