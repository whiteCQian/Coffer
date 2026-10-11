'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const argument=name=>process.argv.find(value=>value.startsWith(name+'='))?.slice(name.length+1);
const volume=argument('--volume'),receipt=argument('--receipt');
if(!volume || !receipt || !path.isAbsolute(volume) || !path.isAbsolute(receipt))throw new Error('Absolute volume and receipt paths are required');
if(!fs.statSync(volume).isDirectory())throw new Error('MinIO volume is unavailable');
function sample(){
  const stage=receipt+'.'+crypto.randomUUID()+'.tmp';
  try{
    const space=fs.statfsSync(volume,{bigint:true});
    const totalBytes=Number(space.blocks*space.bsize),freeBytes=Number(space.bavail*space.bsize);
    if(!Number.isSafeInteger(totalBytes) || !Number.isSafeInteger(freeBytes) || totalBytes<=0 || freeBytes<0 || freeBytes>totalBytes)throw new Error('Invalid volume sample');
    fs.writeFileSync(stage,JSON.stringify({checkedAt:new Date().toISOString(),totalBytes,freeBytes}),{flag:'wx',mode:0o600});
    fs.renameSync(stage,receipt);
  }finally{if(fs.existsSync(stage))fs.unlinkSync(stage);}
}
sample();
if(!process.argv.includes('--once'))setInterval(()=>{try{sample();}catch{process.stderr.write('MINIO_CAPACITY_SAMPLE_UNAVAILABLE\n');}},10000);
