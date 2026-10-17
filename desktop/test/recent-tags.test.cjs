'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const crypto=require('node:crypto');
const {recentTagStorage,validateRecentTags}=require('../src/recent-tags.cjs');
test('recent tags persist separately for each library and owner',()=>{
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-recent-tags-'));
  try{
    const first=recentTagStorage(profile,'D:/fictional-library',1);
    assert.deepEqual(first.read(),[]);first.write(['低频标签','会议']);
    assert.deepEqual(recentTagStorage(profile,'D:/fictional-library',1).read(),['低频标签','会议']);
    assert.deepEqual(recentTagStorage(profile,'D:/fictional-library',2).read(),[]);
    assert.deepEqual(recentTagStorage(profile,'D:/another-fictional-library',1).read(),[]);
  }finally{fs.rmSync(profile,{recursive:true,force:true});}
});
test('legacy tag-filter clicks are not treated as recently opened file tags',()=>{
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'coffer-file-tag-migration-'));
  try {
    const library='D:/fictional-library',identity=crypto.createHash('sha256').update(path.resolve(library).toLowerCase()+'\0'+1).digest('hex');
    const legacy=path.join(profile,'recent-tags-'+identity+'.json');fs.writeFileSync(legacy,JSON.stringify(['clicked-only']));
    const storage=recentTagStorage(profile,library,1);assert.deepEqual(storage.read(),[]);
    storage.write(['opened-file-tag']);assert.deepEqual(storage.read(),['opened-file-tag']);
    assert.deepEqual(JSON.parse(fs.readFileSync(legacy)),['clicked-only']);
  } finally {fs.rmSync(profile,{recursive:true,force:true});}
});
test('preferences accept at most ten distinct bounded tag names',()=>{
  assert.deepEqual(validateRecentTags(['a','b','a']),['a','b']);
  for(const input of [null,{},[''],[' '.repeat(2)],['a'.repeat(513)],Array.from({length:11},(_,i)=>String(i))])assert.throws(()=>validateRecentTags(input));
  assert.equal(validateRecentTags(Array.from({length:10},(_,i)=>String(i))).length,10);
});
