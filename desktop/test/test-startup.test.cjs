'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const {testStartupOptions}=require('../src/test-startup.cjs');
const explicit={packaged:false,requested:true,explicitData:'test-data',explicitProfile:'test-profile',initialized:false,hasBinding:false,hasUpgradeJournal:false,nonempty:false};
test('explicit source test starts both desktop and backend on an empty test directory',()=>{
  assert.deepEqual(testStartupOptions(explicit),{initialize:true,upgrade:false});
});
test('packaged releases and unspecified directories never gain automatic initialization',()=>{
  for(const override of [{packaged:true},{requested:false},{explicitData:''},{explicitProfile:''}])assert.equal(testStartupOptions({...explicit,...override}),null);
});
test('existing, partial or previously initialized test directories are opened, never reset',()=>{
  for(const override of [{initialized:true},{hasBinding:true},{hasUpgradeJournal:true},{nonempty:true}])assert.deepEqual(testStartupOptions({...explicit,...override}),{initialize:false,upgrade:false});
});
