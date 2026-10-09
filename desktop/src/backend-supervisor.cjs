'use strict';
const {spawn}=require('node:child_process'), fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),http=require('node:http');
const {regularFile,noLinks}=require('./safe-paths.cjs');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function request(port, token, route, options={}) {
  return new Promise((resolve,reject)=>{
    const headers={...options.headers,'X-Coffer-Desktop-Token':token};
    const body=options.body===undefined?null:Buffer.from(JSON.stringify(options.body));
    if(body){headers['Content-Type']='application/json';headers['Content-Length']=body.length;}
    const req=http.request({hostname:'127.0.0.1',port,path:route,method:options.method || 'GET',headers,timeout:options.timeout || 30000},res=>{
      let size=0; const chunks=[]; res.on('data',chunk=>{size+=chunk.length;if(size>2*1024*1024)res.destroy(new Error('RESPONSE_TOO_LARGE'));else chunks.push(chunk);});
      res.on('error',reject);res.on('end',()=>{let data;try{data=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{data=null;}resolve({status:res.statusCode,headers:res.headers,data});});
    });
    req.on('error',reject);req.on('timeout',()=>req.destroy(new Error('BACKEND_TIMEOUT')));if(body)req.write(body);req.end();
  });
}
class BackendSupervisor {
  constructor(resourceRoot,manifest,onExit,log) {this.root=resourceRoot;this.manifest=manifest;this.onExit=onExit;this.log=log;this.port=null;this.stopping=false;}
  async start(dataDirectory,{initialize=false,libraryDirectory,upgrade=false,upgradeProbeId}={}) {
    if(this.child && this.child.exitCode===null)throw new Error('BACKEND_ALREADY_OWNED');
    this.instance=crypto.randomUUID();this.token=crypto.randomBytes(32).toString('hex');this.nativeToken=crypto.randomBytes(32).toString('hex');this.stopping=false;this.port=null;
    const env={};
    for(const key of ['SystemRoot','WINDIR','COMSPEC','USERPROFILE','LOCALAPPDATA','APPDATA','TEMP','TMP','USERNAME','USERDOMAIN']) if(process.env[key])env[key]=process.env[key];
    env.PATH=path.join(this.root,'runtime/bin')+path.delimiter+path.join(process.env.SystemRoot || 'C:\\Windows','System32');
    Object.assign(env,{COFFER_DESKTOP_USER_DATA_DIR:dataDirectory,COFFER_DESKTOP_INSTANCE_ID:this.instance,COFFER_DESKTOP_LAUNCH_TOKEN:this.token,COFFER_DESKTOP_NATIVE_TOKEN:this.nativeToken,COFFER_DESKTOP_PARENT_PID:String(process.pid),COFFER_DESKTOP_SCHEMA_VERSION:String(this.manifest.schemaVersion)});
    if(upgradeProbeId)env.COFFER_UPGRADE_HEALTH_CHECK_ID=upgradeProbeId;
    const args=['-jar',path.join(this.root,'backend/coffer-backend.jar'),'--spring.profiles.active=desktop','--coffer.desktop.shell=true','--server.port=0',`--coffer.desktop.initialize=${initialize}`,`--coffer.desktop.upgrade-with-backup=${upgrade}`];
    if(libraryDirectory)args.push(`--coffer.desktop.library-directory=${libraryDirectory}`);
    this.errorText='';this.child=spawn(regularFile(path.join(this.root,'runtime/bin/java.exe')),args,{env,cwd:this.root,windowsHide:true,stdio:['pipe','pipe','pipe']});
    const child=this.child, started=Date.now();
    child.stdin.on('error',()=>{});
    const collect=data=>{const text=data.toString();this.errorText=(this.errorText+text).slice(-24000);this.log(text.replaceAll(this.token,'[redacted]').replaceAll(this.nativeToken,'[redacted]'));};
    child.stdout.on('data',collect);child.stderr.on('data',collect);
    this.spawnError=false;child.on('error',()=>{this.errorText+=' BACKEND_SPAWN_FAILED';this.spawnError=true;});
    child.once('exit',(code,signal)=>{this.port=null;if(!this.stopping)this.onExit(code,signal);});
    const ready=path.join(dataDirectory,'.coffer-runtime',`ready-${this.instance}.json`);
    while(Date.now()-started<180000){
      if(this.spawnError)throw new Error('BACKEND_SPAWN_FAILED');
      if(child.exitCode!==null || child.signalCode!==null)throw new Error(this.diagnostic());
      try{
        const info=fs.statSync(regularFile(ready,4096));
        const body=JSON.parse(fs.readFileSync(ready,'utf8'));
        if(body.instanceId!==this.instance || body.pid!==child.pid || !Number.isInteger(body.port) || body.port<1 || body.port>65535 || info.mtimeMs<started-2000)throw new Error('RENDEZVOUS_INVALID');
        const health=await request(body.port,this.token,'/actuator/health',{timeout:1500});
        if(health.status===200 && health.data?.status==='UP'){this.port=body.port;return;}
      }catch(error){if(error.message==='RENDEZVOUS_INVALID')throw error;}
      await delay(250);
    }
    await this.stop();throw new Error('BACKEND_START_TIMEOUT');
  }
  diagnostic(){
    const known=['UPGRADE_BACKUP_REQUIRED','UPGRADE_BACKUP_FAILED','DOWNGRADE_REFUSED','KEY_MISSING','KEY_MISMATCH','LIBRARY_MISSING','DATABASE_MISSING','IN_USE','INITIALIZATION_REFUSED','BINDING_INVALID'];
    for(const code of known)if(this.errorText.includes(code))return code;
    if(this.errorText.includes('升级备份失败'))return 'UPGRADE_BACKUP_FAILED';
    if(this.errorText.includes('不能降级写入'))return 'DOWNGRADE_REFUSED';
    for(const [text,code] of [['主密钥缺失','KEY_MISSING'],['正在被另一进程使用','IN_USE'],['文件库根目录缺失','LIBRARY_MISSING'],['桌面数据库缺失','DATABASE_MISSING'],['初始化只允许空目录','INITIALIZATION_REFUSED'],['数据库结构与安装包不一致','UPGRADE_BACKUP_REQUIRED'],['无法验证数据库绑定','KEY_MISMATCH']])if(this.errorText.includes(text))return code;
    return 'BACKEND_START_FAILED';
  }
  async api(route,options={}) {if(!this.port)throw new Error('BACKEND_NOT_READY');return request(this.port,this.token,route,options);}
  async stop(){
    this.stopping=true;this.port=null;const child=this.child;if(!child)return;
    if(child.exitCode===null && child.signalCode===null){
      child.stdin.end('COFFER_SHUTDOWN\n');
      await Promise.race([new Promise(resolve=>child.once('exit',resolve)),delay(12000)]);
      if(child.exitCode===null && child.signalCode===null){child.kill();await Promise.race([new Promise(resolve=>child.once('exit',resolve)),delay(3000)]);}
    }
    this.child=null;
  }
}
module.exports={BackendSupervisor,request};
