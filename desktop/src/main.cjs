'use strict';
const {app,BrowserWindow,session,ipcMain,dialog,Menu,shell}=require('electron');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {dataPath,noLinks,regularFile,atomicJson}=require('./safe-paths.cjs');
const {verifyResources}=require('./resources.cjs');
const {LoopbackServer}=require('./loopback-server.cjs');
const {BackendSupervisor}=require('./backend-supervisor.cjs');
const {runMaintenance}=require('./maintenance.cjs');
const {testStartupOptions}=require('./test-startup.cjs');
const {startupPath,inspectDirectory,newLibraryLocation,requireEmptyWritableDirectory,requireSeparateEmptyLibrary}=require('./startup-directory.cjs');
const argument=name=>process.argv.find(value=>value.startsWith(name+'='))?.slice(name.length+1);
const installRoot=app.isPackaged?path.dirname(app.getPath('exe')):path.resolve(__dirname,'..');
const profileDirectory=dataPath(argument('--coffer-shell-directory') || path.join(app.getPath('appData'),'Coffer-shell'),installRoot);
noLinks(profileDirectory,true);fs.mkdirSync(profileDirectory,{recursive:true});noLinks(profileDirectory);
app.setPath('userData',profileDirectory);app.setAppUserModelId('com.coffer.desktop');app.enableSandbox();
if(!app.requestSingleInstanceLock()){app.quit();}else{
  let window,webSession,server,supervisor,manifest,phase='VERIFYING',failure=null,quitting=false,progress=null,creationDraft=null;
  const resourceRoot=app.isPackaged?path.join(process.resourcesPath,'coffer'):path.resolve(__dirname,'../.build/resources');
  const settingsFile=path.join(profileDirectory,'desktop-settings.json');
  const paneWidthsFile=path.join(profileDirectory,'pane-widths.json');
  const themeFile=path.join(profileDirectory,'theme.json');
  let theme=null;
  try{const saved=JSON.parse(fs.readFileSync(regularFile(themeFile,4096),'utf8'));if(['light','dark'].includes(saved?.theme))theme=saved.theme;}catch{}
  const paneLimits={'coffer:sidebar-width':[180,360],'coffer:side-panel-width':[280,600]};
  const paneWidths={};
  try{
    const saved=JSON.parse(fs.readFileSync(regularFile(paneWidthsFile,4096),'utf8'));
    for(const [key,[min,max]] of Object.entries(paneLimits))if(Number.isFinite(saved?.[key]) && saved[key]>=min && saved[key]<=max)paneWidths[key]=saved[key];
  }catch{}
  const newLibraryAttemptFile=path.join(profileDirectory,'new-library-attempt.json');
  const defaultDataDirectory=argument('--coffer-data-directory') || path.join(process.env.LOCALAPPDATA || app.getPath('home'),'Coffer');
  let settings={dataDirectory:defaultDataDirectory,initialized:false};
  if(fs.existsSync(settingsFile)){
    try{settings={...settings,...JSON.parse(fs.readFileSync(regularFile(settingsFile,65536),'utf8'))};}catch{failure='SETTINGS_INVALID';}
  }
  // A saved user choice takes precedence over a shortcut/test launcher's default data directory.
  try{settings.dataDirectory=startupPath(settings.dataDirectory,installRoot,profileDirectory);}catch(error){failure=error.message;}
  const selected=new Map();
  let backupDestination=null,restoreSelection=null,maintenanceReport=null;
  const upgradeJournal=()=>path.join(path.dirname(settings.dataDirectory),path.basename(settings.dataDirectory)+'.coffer-upgrade.json');
  const maintenance=request=>runMaintenance(resourceRoot,{dataDirectory:settings.dataDirectory,libraryDirectory:settings.libraryDirectory || path.join(settings.dataDirectory,'library'),...request});
  const requirePassphrase=password=>{if(typeof password!=='string' || password.length<12 || password.length>1024)throw new Error('备份口令须为 12–1024 个字符');};
  const log=text=>fs.appendFileSync(path.join(profileDirectory,'launcher.log'),String(text),{mode:0o600});
  function reportTestStartup(){
    if(!app.isPackaged && argument('--coffer-test-autostart')==='true' && argument('--coffer-data-directory') && argument('--coffer-shell-directory'))
      atomicJson(path.join(profileDirectory,'test-startup-state.json'),{phase,error:failure,dataDirectory:settings.dataDirectory,frontendPid:process.pid,backendPid:supervisor?.child?.pid || null,backendPort:supervisor?.port || null,frontendOrigin:server?.origin || null,version:manifest?.version || null});
  }
  const sourceValid=event=>{
    try{const source=new URL(event.senderFrame.url);return window && !window.isDestroyed() && event.sender===window.webContents && event.senderFrame===window.webContents.mainFrame && source.origin===server.origin && !source.pathname.startsWith('/api/') && !source.pathname.startsWith('/assets/');}
    catch{return false;}
  };
  const startupOnly=()=>{
    if(!window || new URL(window.webContents.mainFrame.url).pathname!=='/__desktop/start' || ['STARTING','READY','MAINTENANCE','VERIFYING'].includes(phase))throw new Error('请在启动页完成数据目录设置');
  };
  function handle(channel,fn){ipcMain.handle(channel,async(event,...args)=>{if(!sourceValid(event))throw new Error('IPC 来源验证失败');return fn(...args);});}
  handle('coffer:get-pane-widths',()=>({...paneWidths}));
  handle('coffer:get-theme',()=>theme);
  handle('coffer:save-theme',value=>{
    if(!['light','dark'].includes(value))throw new Error('主题参数无效');
    atomicJson(themeFile,{theme:value});theme=value;
  });
  handle('coffer:save-pane-width',(key,value)=>{
    if(typeof key!=='string' || !Object.hasOwn(paneLimits,key) || !Number.isFinite(value))throw new Error('面板宽度参数无效');
    const [min,max]=paneLimits[key];if(value<min || value>max)throw new Error('面板宽度超出范围');
    const next={...paneWidths,[key]:Math.round(value)};atomicJson(paneWidthsFile,next);Object.assign(paneWidths,next);
  });
  function status(){
    const effective=creationDraft?.candidate || settings;
    const directory=inspectDirectory(effective.dataDirectory,effective);
    const interrupted=effective.creationStarted && directory.kind!=='empty';
    const firstRun=Boolean(creationDraft) || !settings.initialized && !interrupted && directory.kind!=='existing' && !fs.existsSync(upgradeJournal()) && failure!=='SETTINGS_INVALID';
    let incompleteNewLibraryDirectory=null;
    try{const attempt=JSON.parse(fs.readFileSync(regularFile(newLibraryAttemptFile,65536),'utf8'));if(attempt.dataDirectory!==settings.dataDirectory)incompleteNewLibraryDirectory=attempt.dataDirectory;}catch{}
    return {phase,error:failure,dataDirectory:effective.dataDirectory,boundDataDirectory:settings.dataDirectory,newLibraryCreation:Boolean(creationDraft),incompleteNewLibraryDirectory,defaultDataDirectory,locationChoice:creationDraft?'custom':effective.dataDirectory===defaultDataDirectory?'recommended':'custom',directoryState:interrupted && directory.kind==='occupied'?'missing':directory.kind,locationExists:directory.exists,firstRun,canCreate:firstRun && !effective.initialized && directory.kind==='empty' && Boolean(manifest),progress,libraryDirectory:effective.libraryDirectory || null,version:app.getVersion(),initialized:effective.initialized,nonempty:directory.nonempty,maintenanceReport};
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
    if(response.status!==200 || response.data?.data?.role!=='USER')throw new Error('个人文件库尚未就绪');
    return response.data.data;
  }
  async function libraryReady(){return user();}
  async function nativeApi(route,body){
    const csrf=await supervisor.api('/api/auth/csrf',{headers:{Cookie:await cookieHeader()}});await synchronizeCookies(csrf.headers);
    const cookies=await webSession.cookies.get({url:server.origin,name:'XSRF-TOKEN'});
    if(!cookies[0])throw new Error('桌面请求凭据未就绪，请重新打开窗口');
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
    const draft=creationDraft;
    if(draft && !options.initialize)throw new Error('请先完成或取消新库创建。');
    const effective=draft?.candidate || settings;
    effective.dataDirectory=startupPath(effective.dataDirectory,installRoot,profileDirectory);
    if(effective.libraryDirectory)effective.libraryDirectory=startupPath(effective.libraryDirectory,installRoot,profileDirectory);
    const current=status();
    if(options.initialize && !current.canCreate)throw new Error('初始化仅允许未绑定的空目录；已有数据请打开或恢复');
    if(options.initialize)requireEmptyWritableDirectory(effective.dataDirectory);
    if(draft)requireSeparateEmptyLibrary(effective.dataDirectory,settings);
    if(options.upgrade)requirePassphrase(options.password);
    // The wizard's "创建并进入" is the final confirmation for a new empty library.
    if(options.upgrade){
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:'更新文件库',message:'程序会先生成并验证加密备份，再更新文件库。更新或启动失败时自动恢复原数据。请先关闭正在编辑的文件。',buttons:['取消','备份并更新'],defaultId:0,cancelId:0,noLink:true});
      if(confirm.response!==1)return status();
    }
    phase='STARTING';progress='PREPARING';failure=null;selected.clear();
    try{
      await supervisor.stop();
      // Persist the selected root before creating anything so a failed first start can be located again.
      if(draft)atomicJson(newLibraryAttemptFile,{dataDirectory:effective.dataDirectory});
      else{
        if(current.directoryState==='existing')settings.initialized=true;
        if(options.initialize)settings.creationStarted=true;
        atomicJson(settingsFile,settings);
        if(fs.existsSync(upgradeJournal()))maintenanceReport=await maintenance({action:'recover-upgrade'});
      }
      let upgraded=null;
      if(options.upgrade){progress='UPGRADING';upgraded=await maintenance({action:'upgrade',password:options.password,targetSchema:manifest.schemaVersion});maintenanceReport=upgraded;if(upgraded.status!=='MIGRATED_AWAITING_HEALTH')throw new Error('MIGRATION_FAILED_ROLLED_BACK');}
      progress=options.initialize?'CREATING':'STARTING';
      try{await supervisor.start(effective.dataDirectory,{initialize:options.initialize,libraryDirectory:effective.libraryDirectory,upgrade:false,upgradeProbeId:upgraded?.backupId,onProgress:()=>{progress='STARTING';}});}
      catch(error){await supervisor.stop();if(!draft && fs.existsSync(upgradeJournal())){maintenanceReport=await maintenance({action:'recover-upgrade'});throw new Error('MIGRATION_FAILED_ROLLED_BACK');}throw error;}
      if(upgraded)await maintenance({action:'commit-upgrade'});
      if(draft)await webSession.clearStorageData();
      const committed={...effective,initialized:true,version:manifest.version,schemaVersion:manifest.schemaVersion};delete committed.creationStarted;
      atomicJson(settingsFile,committed);settings=committed;creationDraft=null;
      if(draft){try{regularFile(newLibraryAttemptFile,65536);fs.unlinkSync(newLibraryAttemptFile);}catch{}}
      phase='READY';progress=null;await window.loadURL(server.origin+'/');reportTestStartup();
    }catch(error){
      phase='FAILED';progress=null;failure=error.message;await supervisor.stop();
      if(draft){draft.candidate.initialized=inspectDirectory(effective.dataDirectory).nonempty;}
      else if(options.initialize){
        if(inspectDirectory(settings.dataDirectory).nonempty)settings.initialized=true;else delete settings.creationStarted;
        atomicJson(settingsFile,settings);
      }
      reportTestStartup();
    }
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
    window=new BrowserWindow({width:1280,height:850,minWidth:900,minHeight:650,title:'Coffer',icon:path.join(__dirname,'app-icon.png'),show:false,webPreferences:{preload:path.join(__dirname,'preload.cjs'),session:webSession,nodeIntegration:false,nodeIntegrationInWorker:false,nodeIntegrationInSubFrames:false,contextIsolation:true,sandbox:true,webSecurity:true,allowRunningInsecureContent:false,webviewTag:false,devTools:!app.isPackaged}});
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
    handle('coffer:start',options=>start(options));
    handle('coffer:manage-library',async()=>{
      if(!manifest || !['READY','IDLE','FAILED','EXITED'].includes(phase))throw new Error('请等待当前操作完成。');
      phase='MAINTENANCE';await supervisor.stop();selected.clear();phase='IDLE';failure=null;await window.loadURL(server.origin+'/__desktop/start');return status();
    });
    handle('coffer:choose-backup-destination',async()=>{await libraryReady();const chosen=await dialog.showOpenDialog(window,{title:'选择备份介质目录（独立备份需不同物理设备）',properties:['openDirectory','createDirectory']});if(chosen.canceled)return null;backupDestination=noLinks(chosen.filePaths[0]);return {directory:backupDestination};});
    handle('coffer:create-backup',async options=>{
      await libraryReady();if(!options || Object.keys(options).some(key=>!['password','localSnapshot'].includes(key)) || typeof options.localSnapshot!=='boolean' || !backupDestination)throw new Error('请先选择备份目录');requirePassphrase(options.password);
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:'整套加密备份',message:`将短暂停止后台，校验并加密个人文件库的正文、配置、台账和原密钥。请关闭外部编辑应用。\n目标：${backupDestination}\n${options.localSnapshot?'这是本机快照，不能标记为独立介质备份。':'目标必须与数据库/文件库位于不同物理设备。'}`,buttons:['取消','生成并验证'],defaultId:0,cancelId:0});if(confirm.response!==1)return null;
      await libraryReady();phase='MAINTENANCE';selected.clear();let result,error;
      try{await supervisor.stop();result=await maintenance({action:options.localSnapshot?'snapshot':'backup',destinationDirectory:backupDestination,password:options.password,configuration:{applicationVersion:app.getVersion()}});maintenanceReport=result;}
      catch(failed){error=failed;}
      finally{try{await supervisor.start(settings.dataDirectory,{libraryDirectory:settings.libraryDirectory});phase='READY';}catch(failed){phase='FAILED';failure=failed.message;await window.loadURL(server.origin+'/__desktop/start');}}
      if(error)throw error;return result;
    });
    handle('coffer:choose-restore',async()=>{
      if(creationDraft)throw new Error('请先取消新库创建，再恢复备份。');
      startupOnly();const source=await dialog.showOpenDialog(window,{title:'选择加密备份包',filters:[{name:'Coffer 加密备份',extensions:['cofferbackup']}],properties:['openFile']});if(source.canceled)return null;
      const target=await dialog.showOpenDialog(window,{title:'选择空的新恢复目录，不能覆盖已有数据',properties:['openDirectory','createDirectory']});if(target.canceled)return null;
      const destination=startupPath(target.filePaths[0],installRoot,profileDirectory);if(fs.readdirSync(destination).length)throw new Error('恢复只允许空目录');
      restoreSelection={archive:regularFile(source.filePaths[0]),target:destination};return {archiveName:path.basename(restoreSelection.archive),targetDirectory:destination};
    });
    handle('coffer:restore-backup',async password=>{
      if(creationDraft)throw new Error('请先取消新库创建，再恢复备份。');
      startupOnly();requirePassphrase(password);if(!restoreSelection)throw new Error('请先选择备份包与空恢复目录');
      const confirm=await dialog.showMessageBox(window,{type:'warning',title:'验证并恢复整套数据',message:`校验个人文件库、文件摘要、台账、未完成操作和原密钥后恢复到：\n${restoreSelection.target}\n原数据目录不会被覆盖。恢复后打开个人文件库。`,buttons:['取消','验证并恢复'],defaultId:0,cancelId:0});if(confirm.response!==1)return null;
      phase='MAINTENANCE';failure=null;
      try{await supervisor.stop();const result=await maintenance({action:'restore',archive:restoreSelection.archive,targetDirectory:restoreSelection.target,password});settings.dataDirectory=restoreSelection.target;settings.libraryDirectory=null;settings.initialized=true;atomicJson(settingsFile,settings);maintenanceReport=result;restoreSelection=null;await webSession.clearStorageData();phase='IDLE';return result;}
      catch(error){phase='FAILED';failure=error.message;throw error;}
    });
    const beginEmptyLibrary=directory=>{
      startupOnly();
      const target=requireSeparateEmptyLibrary(startupPath(directory,installRoot,profileDirectory),settings);
      const previous=creationDraft?.previous || {phase,failure,maintenanceReport};
      creationDraft={candidate:{dataDirectory:target,libraryDirectory:null,initialized:false},previous};
      selected.clear();phase='IDLE';failure=null;maintenanceReport=null;return status();
    };
    const chooseData=async emptyOnly=>{
      startupOnly();const result=await dialog.showOpenDialog(window,{title:emptyOnly?'选择空文件夹，新建文件库':'选择已有文件库或空文件夹新建',properties:['openDirectory','createDirectory']});
      if(result.canceled)return status();
      startupOnly();
      let directory=startupPath(result.filePaths[0],installRoot,profileDirectory);
      let kind=inspectDirectory(directory).kind;
      if(kind==='occupied'){
        const child=startupPath(path.join(directory,'Coffer'),installRoot,profileDirectory);
        const childKind=inspectDirectory(child).kind;
        if(childKind!=='empty' && childKind!=='existing')throw new Error('所选位置及其 Coffer 子目录都不是可用的空文件库位置。请选择空文件夹或已有文件库。');
        directory=child;kind=childKind;
      }
      if(kind==='empty')return beginEmptyLibrary(directory);
      if(kind!=='existing' || emptyOnly)throw new Error('此位置无法创建空库，请选择空文件夹。');
      if(directory!==settings.dataDirectory)await webSession.clearStorageData();
      startupOnly();
      const chosen={dataDirectory:directory,libraryDirectory:null,initialized:true};atomicJson(settingsFile,chosen);
      selected.clear();creationDraft=null;settings=chosen;failure=null;phase='IDLE';maintenanceReport=null;return status();
    };
    handle('coffer:choose-data',()=>chooseData(false));
    handle('coffer:choose-empty-library',()=>chooseData(true));
    handle('coffer:cancel-empty-library',()=>{
      startupOnly();if(creationDraft){const previous=creationDraft.previous;creationDraft=null;phase=previous.phase;failure=previous.failure;maintenanceReport=previous.maintenanceReport;}return status();
    });
    const chooseFresh=directory=>{
      startupOnly();
      if(!status().firstRun)throw new Error('请先重新定位或恢复原文件库。');
      const target=startupPath(directory,installRoot,profileDirectory);
      if(creationDraft)return beginEmptyLibrary(target);
      delete settings.creationStarted;settings.dataDirectory=target;settings.libraryDirectory=null;failure=null;phase='IDLE';return status();
    };
    handle('coffer:choose-new-location',async()=>{
      startupOnly();if(!status().firstRun)throw new Error('请先重新定位或恢复原文件库。');
      const result=await dialog.showOpenDialog(window,{title:'选择保存位置，程序会在其中创建 Coffer 文件夹',properties:['openDirectory','createDirectory']});
      if(result.canceled)return status();
      return chooseFresh(newLibraryLocation(result.filePaths[0],installRoot,profileDirectory));
    });
    handle('coffer:use-default-location',()=>{startupOnly();if(creationDraft)throw new Error('请为新空库选择保存位置。');return chooseFresh(defaultDataDirectory);});
    handle('coffer:show-library-location',async()=>{
      startupOnly();if(!status().locationExists)throw new Error('此位置尚未创建，创建文件库后可在文件资源管理器中查看。');
      const error=await shell.openPath(noLinks(creationDraft?.candidate.dataDirectory || settings.dataDirectory));if(error)throw new Error('无法打开文件资源管理器，请稍后重试。');
    });
    handle('coffer:choose-library',async()=>{
      if(creationDraft)throw new Error('请先取消新库创建，再定位原文件目录。');
      startupOnly();const result=await dialog.showOpenDialog(window,{title:'选择原文件库目录（绑定必须一致）',properties:['openDirectory']});
      if(!result.canceled){startupOnly();selected.clear();settings.libraryDirectory=startupPath(result.filePaths[0],installRoot,profileDirectory);atomicJson(settingsFile,settings);}return status();
    });
    handle('coffer:select-files',async()=>{await user();const result=await dialog.showOpenDialog(window,{title:'选择要交给个人文件库管理的本地文件',properties:['openFile','multiSelections']});return result.canceled?[]:previewPaths(result.filePaths);});
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
        try{if((await user()).id!==entry.ownerId)throw new Error('个人文件库已变化');await nativeApi('/api/desktop/native-inbox/commit',{sourcePath:entry.sourcePath,preview:entry.preview});selected.delete(id);results.push({name:entry.preview.name,targetPath:entry.preview.targetPath,ok:true});}
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
    const automaticTest=testStartupOptions({packaged:app.isPackaged,requested:argument('--coffer-test-autostart')==='true',explicitData:argument('--coffer-data-directory'),explicitProfile:argument('--coffer-shell-directory'),initialized:settings.initialized,hasBinding:fs.existsSync(path.join(settings.dataDirectory,'.coffer-desktop.json')),hasUpgradeJournal:fs.existsSync(upgradeJournal()),nonempty:status().nonempty});
    if(!failure && automaticTest)await start(automaticTest);
    // Normal packaged startup never initializes missing roots automatically.
    else if(!failure && (settings.initialized || fs.existsSync(upgradeJournal()) || fs.existsSync(path.join(settings.dataDirectory,'.coffer-desktop.json'))))await start({initialize:false,upgrade:false});
  }).catch(error=>{log('Launcher failed: '+error.message+'\n');dialog.showErrorBox('Coffer 启动失败','请检查安装资源、目录权限和启动日志，用户数据没有被自动删除。');app.quit();});
}
