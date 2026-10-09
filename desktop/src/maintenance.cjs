'use strict';
const {spawn}=require('node:child_process'),path=require('node:path');
const {regularFile}=require('./safe-paths.cjs');
function runMaintenance(root,request){
  return new Promise((resolve,reject)=>{
    const env={};for(const key of ['SystemRoot','WINDIR','COMSPEC','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP','USERNAME','USERDOMAIN'])if(process.env[key])env[key]=process.env[key];
    env.PATH=path.join(root,'runtime/bin')+path.delimiter+path.join(process.env.SystemRoot || 'C:\\Windows','System32');
    const child=spawn(regularFile(path.join(root,'runtime/bin/java.exe')),['-jar',path.join(root,'backend/coffer-backend.jar'),'--coffer-desktop-maintenance'],{cwd:root,env,windowsHide:true,stdio:['pipe','pipe','pipe']});
    let stdout='';child.stdout.on('data',bytes=>{stdout=(stdout+bytes.toString()).slice(-256*1024);});child.stderr.resume();child.stdin.on('error',()=>{});
    child.on('error',()=>reject(new Error('维护进程无法启动')));
    child.once('exit',code=>{
      const line=stdout.split(/\r?\n/).reverse().find(value=>value.startsWith('{"code":'));
      let response;try{response=JSON.parse(line);}catch{return reject(new Error('维护未完成，数据与事务已保留，请核对后重试'));}
      if(code!==0 || response.code!==0)return reject(new Error(response.error || 'MAINTENANCE_FAILED'));
      resolve(response.data);
    });
    // This pipe is the only place a backup passphrase crosses the process boundary.
    child.stdin.end(JSON.stringify(request));
  });
}
module.exports={runMaintenance};
