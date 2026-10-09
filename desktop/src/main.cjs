'use strict';
const {app,BrowserWindow,session,ipcMain,dialog,Menu}=require('electron');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {dataPath,noLinks,regularFile,atomicJson}=require('./safe-paths.cjs');
const {verifyResources}=require('./resources.cjs');
const {LoopbackServer}=require('./loopback-server.cjs');
const {BackendSupervisor}=require('./backend-supervisor.cjs');
const {runMaintenance}=require('./maintenance.cjs');
const argument=name=>process.argv.find(value=>value.startsWith(name+'='))?.slice(name.length+1);
const installRoot=app.isPackaged?path.dirname(app.getPath('exe')):path.resolve(__dirname,'..');
const profileDirectory=dataPath(argument('--coffer-shell-directory') || path.join(app.getPath('appData'),'Coffer-shell'),installRoot);
noLinks(profileDirectory,true);fs.mkdirSync(profileDirectory,{recursive:true});noLinks(profileDirectory);
app.setPath('userData',profileDirectory);app.setAppUserModelId('com.coffer.desktop');app.enableSandbox();
if(!app.requestSingleInstanceLock()){app.quit();}else{
  let window,webSession,server,supervisor,manifest,phase='VERIFYING',failure=null,quitting=false;
  const resourceRoot=app.isPackaged?path.join(process.resourcesPath,'coffer'):path.resolve(__dirname,'../.build/resources');
  const settingsFile=path.join(profileDirectory,'desktop-settings.json');
  let settings={dataDirectory:argument('--coffer-data-directory') || path.join(process.env.LOCALAPPDATA || app.getPath('home'),'Coffer'),initialized:false};
  if(fs.existsSync(settingsFile)){
    try{settings={...settings,...JSON.parse(fs.readFileSync(regularFile(settingsFile,65536),'utf8'))};}catch{failure='SETTINGS_INVALID';}
  }
  if(argument('--coffer-data-directory'))settings.dataDirectory=argument('--coffer-data-directory');
  try{settings.dataDirectory=dataPath(settings.dataDirectory,installRoot);}catch(error){failure=error.message;}
  const selected=new Map();
  let backupDestination=null,restoreSelection=null,maintenanceReport=null;
  const upgradeJournal=()=>path.join(path.dirname(settings.dataDirectory),path.basename(settings.dataDirectory)+'.coffer-upgrade.json');
  const maintenance=request=>runMaintenance(resourceRoot,{dataDirectory:settings.dataDirectory,libraryDirectory:settings.libraryDirectory || path.join(settings.dataDirectory,'library'),...request});
  const requirePassphrase=password=>{if(typeof password!=='string' || password.length<12 || password.length>1024)throw new Error('备份口令须为 12–1024 个字符');};
  const log=text=>fs.appendFileSync(path.join(profileDirectory,'launcher.log'),String(text),{mode:0o600});
  const sourceValid=event=>{
    try{const source=new URL(event.senderFrame.url);return window && !window.isDestroyed() && event.sender===window.webContents && event.senderFrame===window.webContents.mainFrame && source.origin===server.origin && !source.pathname.startsWith('/api/') && !source.pathname.startsWith('/assets/');}
    catch{return false;}
  };
  const startupOnly=()=>{
    if(!window || new URL(window.webContents.mainFrame.url).pathname!=='/__desktop/start' || ['STARTING','READY','MAINTENANCE','VERIFYING'].includes(phase))throw new Error('请在启动页完成数据目录设置');
  };
  function handle(channel,fn){ipcMain.handle(channel,async(event,...args)=>{if(!sourceValid(event))throw new Error('IPC 来源验证失败');return fn(...args);});}
  function status(){
    let nonempty=true;try{nonempty=fs.existsSync(settings.dataDirectory) && fs.readdirSync(noLinks(settings.dataDirectory)).some(name=>name!=='.coffer-desktop.lock');}catch{}
    return {phase,error:failure,dataDirectory:settings.dataDirectory,libraryDirectory:settings.libraryDirectory || null,version:app.getVersion(),initialized:settings.initialized,nonempty,maintenanceReport};
  }
  async function cookieHeader(){return (await webSession.cookies.get({url:server.origin})).filter(cookie=>['COFFER_SESSION','JSESSIONID','XSRF-TOKEN'].includes(cookie.name)).map(cookie=>`${cookie.name}=${cookie.value}`).join('; ');}
  async function synchronizeCookies(headers){
    for(const raw of headers['set-cookie'] || []){
      const first=raw.split(';')[0],equals=first.indexOf('=');if(equals<1)continue;
      const name=first.slice(0,equals);if(!['COFFER_SESSION','JSESSIONID','XSRF-TOKEN'].includes(name))continue;
      await webSession.cookies.set({url:server.origin,name,value:first.slice(equals+1),path:'/',httpOnly:/;\s*HttpOnly/i.test(raw),sameSite:'lax'});
    }
  }
  async function user(){
    const response=await supervisor.api('/api/auth/me',{headers:{Cookie:await cookieHeader()}});
    if(response.status!==200 || response.data?.data?.role!=='USER')throw new Error('请使用业务账号登录后选择文件');
    return response.data.data;
  }
  async function administrator(){const response=await supervisor.api('/api/auth/me',{headers:{Cookie:await cookieHeader()}});if(response.status!==200 || response.data?.data?.role!=='ADMIN')throw new Error('整套备份仅向已登录管理员开放');return response.data.data;}
  async function nativeApi(route,body){
    const csrf=await supervisor.api('/api/auth/csrf',{headers:{Cookie:await cookieHeader()}});await synchronizeCookies(csrf.headers);
    const cookies=await webSession.cookies.get({url:server.origin,name:'XSRF-TOKEN'});
    if(!cookies[0])throw new Error('登录授权已变化，请重新登录');
    const response=await supervisor.api(route,{method:'POST',headers:{Cookie:await cookieHeader(),'X-XSRF-TOKEN':decodeURIComponent(cookies[0].value),'X-Coffer-Native-Capability':supervisor.nativeToken},body});
    if(response.status!==200 || response.data?.code!==0)throw new Error(response.data?.msg || '本地文件操作失败，来源文件已保留');
    return response.data.data;
  }
  async function previewPaths(paths){
    if(!Array.isArray(paths) || paths.length<1 || paths.length>20 || paths.some(value=>typeof value!=='string' || !path.isAbsolute(value) || value.length>4096 || /[\r\n\0]/.test(value)))throw new Error('本机文件选择参数无效');
    const owner=await user(), previews=[];
    for(const value of paths){
      const preview=await nativeApi('/api/desktop/native-inbox/preview',{sourcePath:value});
      const id=crypto.randomUUID();selected.set(id,{ownerId:owner.id,sourcePath:value,preview,expires:Date.now()+10*60*1000});
      previews.push({id,name:preview.name,size:preview.size,targetPath:preview.targetPath,sha256:preview.sha256});
    }
    for(const [id,entry] of selected)if(entry.expires<Date.now())selected.delete(id);
    if(selected.size>200)selected.clear();
    return previews;
  }
  async function start(options){
    startupOnly();
    if(!options || typeof options!=='object' || Array.isArray(options) || Object.keys(options).some(key=>!['initialize','upgrade','password'].includes(key)) || typeof options.initialize!=='boolean' || typeof options.upgrade!=='boolean' || options.initialize && options.upgrade)throw new Error('启动参数无效');
    if(!manifest)throw new Error('安装资源尚未通过校验，请重新安装');
    settings.dataDirectory=dataPath(settings.dataDirectory,installRoot);
    if(settings.libraryDirectory)settings.libraryDirectory=dataPath(settings.libraryDirectory,installRoot);
    const current=status();
    if(options.initialize && (current.initialized || current.nonempty))throw new Error('初始化仅允许未绑定的空目录；已有数据请打开或恢复');
    if(options.upgrade)requirePassphrase(options.password);
    if(options.initialize || options.upgrade){
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:options.initialize?'初始化本机文件库':'数据库升级',message:options.initialize?`在所选空目录初始化账号、数据库、文件库和本机密钥：\n${settings.dataDirectory}`:'后台停止后生成经验证的整套加密回滚包，再迁移数据库。迁移/健康检查失败自动恢复整套旧数据。请先关闭外部应用。本机回滚包不能代替独立介质备份。',buttons:['取消',options.initialize?'初始化空目录':'加密备份并升级'],defaultId:0,cancelId:0,noLink:true});
      if(confirm.response!==1)return status();
    }
    phase='STARTING';failure=null;selected.clear();
    try{
      await supervisor.stop();
      if(fs.existsSync(upgradeJournal()))maintenanceReport=await maintenance({action:'recover-upgrade'});
      let upgraded=null;
      if(options.upgrade){upgraded=await maintenance({action:'upgrade',password:options.password,targetSchema:manifest.schemaVersion});maintenanceReport=upgraded;if(upgraded.status!=='MIGRATED_AWAITING_HEALTH')throw new Error('MIGRATION_FAILED_ROLLED_BACK');}
      try{await supervisor.start(settings.dataDirectory,{initialize:options.initialize,libraryDirectory:settings.libraryDirectory,upgrade:false,upgradeProbeId:upgraded?.backupId});}
      catch(error){await supervisor.stop();if(fs.existsSync(upgradeJournal())){maintenanceReport=await maintenance({action:'recover-upgrade'});throw new Error('MIGRATION_FAILED_ROLLED_BACK');}throw error;}
      if(upgraded)await maintenance({action:'commit-upgrade'});
      settings.initialized=true;settings.version=manifest.version;settings.schemaVersion=manifest.schemaVersion;atomicJson(settingsFile,settings);
      phase='READY';await window.loadURL(server.origin+'/');
    }catch(error){phase='FAILED';failure=error.message;await supervisor.stop();}
    return status();
  }
  app.on('second-instance',()=>{if(window){if(window.isMinimized())window.restore();window.focus();}});
  app.on('window-all-closed',()=>app.quit());
  app.on('before-quit',event=>{
    if(quitting)return;event.preventDefault();quitting=true;selected.clear();
    Promise.resolve(supervisor?.stop()).then(()=>server?.close()).finally(()=>app.quit());
  });
  app.whenReady().then(async()=>{
    Menu.setApplicationMenu(null);
    server=new LoopbackServer(path.join(resourceRoot,'web'),__dirname,()=>phase==='READY' && supervisor.port?{port:supervisor.port,token:supervisor.token}:null);await server.start();
    webSession=session.fromPartition('persist:coffer',{cache:false});
    webSession.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));webSession.setPermissionCheckHandler(()=>false);
    webSession.on('will-download',event=>event.preventDefault());
    window=new BrowserWindow({width:1280,height:850,minWidth:900,minHeight:650,title:'Coffer',show:false,webPreferences:{preload:path.join(__dirname,'preload.cjs'),session:webSession,nodeIntegration:false,nodeIntegrationInWorker:false,nodeIntegrationInSubFrames:false,contextIsolation:true,sandbox:true,webSecurity:true,allowRunningInsecureContent:false,webviewTag:false,devTools:!app.isPackaged}});
    webSession.webRequest.onBeforeRequest((details,callback)=>{
      let allowed=false;try{allowed=details.webContentsId===window.webContents.id && new URL(details.url).origin===server.origin && (!details.frame || !details.frame.parent);}catch{}
      if(details.resourceType==='mainFrame' && new URL(details.url).pathname.startsWith('/api/'))allowed=false;
      callback({cancel:!allowed});
    });
    webSession.webRequest.onBeforeSendHeaders((details,callback)=>{
      const headers={...details.requestHeaders};for(const key of Object.keys(headers))if(/^x-coffer-(?:window-capability|desktop-token|native-capability)$/i.test(key))delete headers[key];
      if(details.webContentsId===window.webContents.id && new URL(details.url).origin===server.origin)headers['X-Coffer-Window-Capability']=server.capability;
      callback({requestHeaders:headers});
    });
    window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',(event,url)=>{try{const target=new URL(url);if(target.origin!==server.origin || target.pathname.startsWith('/api/'))event.preventDefault();}catch{event.preventDefault();}});
    window.webContents.on('will-attach-webview',event=>event.preventDefault());
    window.webContents.on('render-process-gone',()=>{if(!quitting && !window.isDestroyed())window.loadURL(server.origin+'/__desktop/start').catch(()=>{});});
    supervisor=new BackendSupervisor(resourceRoot,{},()=>{
      if(phase==='READY' && !quitting){phase='EXITED';failure='BACKEND_EXITED';window.loadURL(server.origin+'/__desktop/start').catch(()=>{});}
    },log);
    handle('coffer:status',status);
    handle('coffer:start',start);
    handle('coffer:choose-backup-destination',async()=>{await administrator();const chosen=await dialog.showOpenDialog(window,{title:'选择备份介质目录（独立备份需不同物理设备）',properties:['openDirectory','createDirectory']});if(chosen.canceled)return null;backupDestination=noLinks(chosen.filePaths[0]);return {directory:backupDestination};});
    handle('coffer:create-backup',async options=>{
      await administrator();if(!options || Object.keys(options).some(key=>!['password','localSnapshot'].includes(key)) || typeof options.localSnapshot!=='boolean' || !backupDestination)throw new Error('请先选择备份目录');requirePassphrase(options.password);
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:'整套加密备份',message:`将短暂停止后台，校验并加密所有账号、正文、配置、台账和原密钥。请关闭外部编辑应用。\n目标：${backupDestination}\n${options.localSnapshot?'这是本机快照，不能标记为独立介质备份。':'目标必须与数据库/文件库位于不同物理设备。'}`,buttons:['取消','生成并验证'],defaultId:0,cancelId:0});if(confirm.response!==1)return null;
      await administrator();phase='MAINTENANCE';selected.clear();let result,error;
      try{await supervisor.stop();result=await maintenance({action:options.localSnapshot?'snapshot':'backup',destinationDirectory:backupDestination,password:options.password,configuration:{applicationVersion:app.getVersion()}});maintenanceReport=result;}
      catch(failed){error=failed;}
      finally{try{await supervisor.start(settings.dataDirectory,{libraryDirectory:settings.libraryDirectory});phase='READY';}catch(failed){phase='FAILED';failure=failed.message;await window.loadURL(server.origin+'/__desktop/start');}}
      if(error)throw error;return result;
    });
    handle('coffer:choose-restore',async()=>{
      startupOnly();const source=await dialog.showOpenDialog(window,{title:'选择加密备份包',filters:[{name:'Coffer 加密备份',extensions:['cofferbackup']}],properties:['openFile']});if(source.canceled)return null;
      const target=await dialog.showOpenDialog(window,{title:'选择空的新恢复目录，不能覆盖已有数据',properties:['openDirectory','createDirectory']});if(target.canceled)return null;
      const destination=dataPath(target.filePaths[0],installRoot);if(fs.readdirSync(destination).length)throw new Error('恢复只允许空目录');
      restoreSelection={archive:regularFile(source.filePaths[0]),target:destination};return {archiveName:path.basename(restoreSelection.archive),targetDirectory:destination};
    });
    handle('coffer:restore-backup',async password=>{
      startupOnly();requirePassphrase(password);if(!restoreSelection)throw new Error('请先选择备份包与空恢复目录');
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:'验证并恢复整套数据',message:`校验账号、文件摘要、台账、未完成操作和原密钥后恢复到：\n${restoreSelection.target}\n原数据目录不会被覆盖。恢复后必须重新登录。`,buttons:['取消','验证并恢复'],defaultId:0,cancelId:0});if(confirm.response!==1)return null;
      phase='MAINTENANCE';failure=null;
      try{await supervisor.stop();const result=await maintenance({action:'restore',archive:restoreSelection.archive,targetDirectory:restoreSelection.target,password});settings.dataDirectory=restoreSelection.target;settings.libraryDirectory=null;settings.initialized=true;atomicJson(settingsFile,settings);maintenanceReport=result;restoreSelection=null;await webSession.clearStorageData();phase='IDLE';return result;}
      catch(error){phase='FAILED';failure=error.message;throw error;}
    });
    handle('coffer:choose-data',async()=>{
      startupOnly();const result=await dialog.showOpenDialog(window,{title:'选择空的新数据目录或已有原数据目录',properties:['openDirectory','createDirectory']});
      if(!result.canceled){selected.clear();settings.dataDirectory=dataPath(result.filePaths[0],installRoot);settings.libraryDirectory=null;settings.initialized=fs.existsSync(path.join(settings.dataDirectory,'.coffer-desktop.json'));atomicJson(settingsFile,settings);}return status();
    });
    handle('coffer:choose-library',async()=>{
      startupOnly();const result=await dialog.showOpenDialog(window,{title:'选择原文件库目录（绑定必须一致）',properties:['openDirectory']});
      if(!result.canceled){selected.clear();settings.libraryDirectory=dataPath(result.filePaths[0],installRoot);atomicJson(settingsFile,settings);}return status();
    });
    handle('coffer:setup-token',async()=>{
      const result=await supervisor.api('/api/auth/status');
      if(result.status!==200 || !result.data?.data?.setupRequired || !result.data.data.setupAvailable)throw new Error('管理员初始化已结束或不可用');
      return fs.readFileSync(regularFile(path.join(settings.dataDirectory,'coffer-initial-admin-token.txt'),512),'utf8').trim();
    });
    handle('coffer:select-files',async()=>{await user();const result=await dialog.showOpenDialog(window,{title:'选择要复制到当前用户收件箱的本地文件',properties:['openFile','multiSelections']});return result.canceled?[]:previewPaths(result.filePaths);});
    handle('coffer:preview-drops',previewPaths);
    handle('coffer:commit-inbox',async items=>{
      const owner=await user();if(!Array.isArray(items) || items.length<1 || items.length>20)throw new Error('导入确认参数无效');
      const receipts=items.map(item=>{
        if(!item || Object.keys(item).some(key=>!['id','targetPath'].includes(key)))throw new Error('导入确认参数无效');
        const entry=selected.get(item.id);if(!entry || entry.expires<Date.now() || entry.ownerId!==owner.id || item.targetPath!==entry.preview.targetPath)throw new Error('导入预览已过期、归属变化或路径不一致，请重新选择');
        return [item.id,entry];
      });
      const results=[];
      for(const [id,entry] of receipts){
        try{if((await user()).id!==entry.ownerId)throw new Error('登录账号已变化');await nativeApi('/api/desktop/native-inbox/commit',{sourcePath:entry.sourcePath,preview:entry.preview});selected.delete(id);results.push({name:entry.preview.name,targetPath:entry.preview.targetPath,ok:true});}
        catch(error){results.push({name:entry.preview.name,targetPath:entry.preview.targetPath,ok:false,error:error.message});}
      }
      return results;
    });
    handle('coffer:open-work-copy',async id=>{
      await user();if(typeof id!=='string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id))throw new Error('工作副本 ID 无效');
      return nativeApi(`/api/desktop/work-copies/${id}/open`);
    });
    await window.loadURL(server.origin+'/__desktop/start');window.show();
    try{manifest=verifyResources(resourceRoot);supervisor.manifest=manifest;phase=failure?'FAILED':'IDLE';}
    catch(error){phase='FAILED';failure=error.message;log('Resource verification failed: '+failure+'\n');return;}
    // Existing settings or a recognized old data directory open normally; missing roots never initialize themselves.
    if(!failure && (settings.initialized || fs.existsSync(upgradeJournal()) || fs.existsSync(path.join(settings.dataDirectory,'.coffer-desktop.json'))))await start({initialize:false,upgrade:false});
  }).catch(error=>{log('Launcher failed: '+error.message+'\n');dialog.showErrorBox('Coffer 启动失败','请检查安装资源、目录权限和启动日志，用户数据没有被自动删除。');app.quit();});
}
