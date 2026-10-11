'use strict';
const api=window.cofferDesktop;
const $=id=>document.getElementById(id);
const messages={
  KEY_MISSING:'原文件库的密钥缺失，请从同一套备份恢复。',KEY_MISMATCH:'密钥与文件库不匹配，请从同一套备份恢复。',
  DATABASE_MISSING:'原文件库的数据文件缺失，请重新定位原文件库或从备份恢复。',LIBRARY_MISSING:'原文件目录找不到，请连接原磁盘或在高级选项中重新选择原目录。',
  IN_USE:'文件库正在由另一个 Coffer 窗口使用，请先关闭该窗口。',
  UPGRADE_BACKUP_REQUIRED:'当前版本需要更新文件库，完成后即可继续使用。',UPGRADE_BACKUP_FAILED:'备份未完成，文件库还未更新。请检查磁盘空间、目录权限及文件占用。',
  DOWNGRADE_REFUSED:'此文件库来自更新版本的 Coffer，请使用兼容的应用版本打开。',
  PACKAGE_CHECKSUM_FAILED:'应用资源校验失败，请重新安装 Coffer。',BACKEND_START_FAILED:'文件库未能启动，请重试；详细原因记录在启动日志中。',
  BACKEND_START_TIMEOUT:'启动时间较长，请稍后重试；详细原因记录在启动日志中。',BACKEND_EXITED:'本地后台异常退出，原数据已保留。重新打开后将恢复未完成操作。',
  INITIALIZATION_REFUSED:'此位置已包含数据，请打开已有文件库或选择其他保存位置。',DIRECTORY_NOT_WRITABLE:'此位置无法写入，请选择其他保存位置。',
  PATH_LINK_REFUSED:'此位置是链接目录，请选择普通文件夹。',INSTALL_DATA_OVERLAP:'请选择应用安装目录之外的保存位置。',PROFILE_DATA_OVERLAP:'请选择应用缓存目录之外的保存位置。',
  LIBRARY_LOCATION_OVERLAP:'新空库的位置必须与原库分开，请选择原库之外的空文件夹。',
  SETTINGS_INVALID:'保存的位置记录无法读取，请重新选择原文件库或从备份恢复。',
  BINDING_INVALID:'这里的文件库不完整，请重新选择原文件库或从备份恢复。',
  NOT_INITIALIZED:'这里没有完整的原文件库，请重新选择原位置或从备份恢复。',IO_FAILED:'此位置无法读写，请检查磁盘连接、目录权限和剩余空间。',INVALID_DIRECTORY:'此位置无法用作文件库，请选择普通文件夹。',DATABASE_INVALID:'文件库的数据文件无法读取，请从备份恢复或查看启动日志。',
  REBIND_REQUIRED:'文件目录与原记录不一致，请在高级选项中选择原文件目录。',REBIND_PENDING:'文件库仍有未完成操作，请先恢复原文件目录。',LIBRARY_CONTENT_MISMATCH:'文件内容与原文件库不匹配，请重新选择原位置或从同一套备份恢复。',RESTORE_LIBRARY_BINDING_MISMATCH:'恢复后的文件位置未通过验证，请保留恢复目录并查看启动日志。',
  MIGRATION_FAILED_ROLLED_BACK:'更新失败，已恢复原文件库。请使用原版本，或查看日志后重试。',UPGRADE_RECOVERY_REQUIRED:'上次更新尚未完成，重试时会先恢复原文件库。',
  BACKUP_AUTHENTICATION_FAILED:'备份口令不正确或备份文件已损坏，请核对后重试。',RESTORE_TARGET_NOT_EMPTY:'恢复位置已有内容，请选择空的新目录。',
  DESKTOP_MULTIPLE_LEGACY_OWNERS:'旧文件库包含多个业务账号，请先备份并迁移为个人文件库。原数据已保留。',DESKTOP_LEGACY_OWNER_DISABLED:'旧文件库的业务账号已停用，请先恢复原文件库。'
};
let state=null,step=1,view='library',pending=false,refreshing=false,restoreSelected=false,creating=false;
const busyPhases=['VERIFYING','STARTING','MAINTENANCE','READY'];
function visible(id,show){$(id).hidden=!show;}
function note(id,text){$(id).textContent=text || '';visible(id,Boolean(text));}
function focusHeading(id){$(id).querySelector('h1')?.focus();}
function render(){
  if(!state)return;
  const busy=busyPhases.includes(state.phase),blocked=busy || pending;
  const updating=['UPGRADE_BACKUP_REQUIRED','UPGRADE_BACKUP_FAILED','MIGRATION_FAILED_ROLLED_BACK'].includes(state.error);
  const restoreView=view==='restore';
  visible('wizard',!busy && !restoreView && state.firstRun);
  visible('existing',!busy && !restoreView && !state.firstRun);
  visible('recovery',!busy && restoreView);
  visible('busy',busy);
  visible('recommended-option',!state.newLibraryCreation);
  visible('cancel-new-library',Boolean(state.newLibraryCreation));
  visible('first-run-entries',!state.newLibraryCreation);
  visible('new-library-cancel-actions',Boolean(state.newLibraryCreation));
  note('new-library-notice',state.newLibraryCreation?`正在新建一个独立空库。原库保留在：${state.boundDataDirectory}。取消或创建失败时，原库绑定保持不变。`:state.incompleteNewLibraryDirectory?`上次新库创建尚未完成，原库绑定未改变。请保留该目录以便核对：${state.incompleteNewLibraryDirectory}`:'');
  visible('location-step',step===1);visible('confirm-step',step===2);
  for(const [id,active] of [['step-one',step===1],['step-two',step===2]]){
    if(active)$(id).setAttribute('aria-current','step');else $(id).removeAttribute('aria-current');
  }
  $('version').textContent='v'+state.version;
  for(const id of ['data','confirm-data','existing-data'])$(id).textContent=state.dataDirectory;
  $('library').textContent=state.libraryDirectory || '';
  if(state.locationChoice==='custom')$('custom').checked=true;else if(!pending)$('recommended').checked=true;
  visible('custom-actions',$('custom').checked);
  const locationMessage=state.directoryState==='occupied'?'此位置已有其他内容，请选择另一个保存位置。':state.directoryState==='unavailable'?'此位置暂时无法访问，请连接磁盘或选择其他位置。':'';
  note('location-message',locationMessage);
  note('message',messages[state.error] || (state.error?'文件库暂时无法打开，请重试或查看启动日志。':''));
  $('status').textContent=busy?({VERIFYING:'正在检查应用…',STARTING:'正在准备文件库…',MAINTENANCE:'正在验证并恢复文件库…',READY:'正在进入文件库…'}[state.phase]):'';
  $('existing-title').textContent=updating?'更新文件库':state.phase==='FAILED' || state.directoryState==='missing' || state.directoryState==='unavailable'?'原文件库暂时无法打开':state.phase==='EXITED'?'文件库需要重新打开':'打开你的文件库';
  $('existing-intro').textContent=updating?'更新完成后，继续使用原来的文件和设置。':state.directoryState==='missing' || state.directoryState==='unavailable'?'请连接原磁盘后重试，或重新选择原文件库的位置。':'Coffer 会打开这里保存的文件和应用数据。';
  visible('upgrade-form',updating);visible('open-actions',!updating);
  $('open').textContent=state.phase==='EXITED'?'重新打开':state.phase==='FAILED' || state.directoryState==='missing' || state.directoryState==='unavailable'?'重试':'打开文件库';
  $('busy-title').textContent=state.phase==='VERIFYING'?'正在检查应用':state.phase==='MAINTENANCE'?'正在恢复文件库':state.progress==='UPGRADING'?'正在备份并更新':creating?'正在创建你的文件库':'正在打开文件库';
  $('busy-description').textContent=state.progress==='UPGRADING'?'正在生成并验证备份，然后更新文件库。请保持窗口打开。':'请保持窗口打开，完成后将自动进入首页。';
  visible('creation-progress',creating && state.phase==='STARTING' && state.progress!=='UPGRADING');
  const stages=['PREPARING','CREATING','STARTING'],index=stages.indexOf(state.progress);
  for(const item of $('creation-progress').children){const itemIndex=stages.indexOf(item.dataset.stage);item.classList.toggle('current',itemIndex===index);item.classList.toggle('complete',itemIndex<index);}
  for(const control of document.querySelectorAll('button,input'))control.disabled=blocked;
  $('next').disabled=blocked || !state.canCreate;
  $('initialize').disabled=blocked || !state.canCreate;
  $('restore').disabled=blocked || !restoreSelected;
  $('reveal-new').disabled=blocked || !state.locationExists;
  $('reveal-existing').disabled=blocked || !state.locationExists;
  $('open').disabled=blocked || state.error==='DOWNGRADE_REFUSED';
  const report=state.maintenanceReport;
  note('maintenance-result',report?.status==='RESTORED_VERIFIED'?`文件库恢复已验证：${report.files} 个文件。点击「打开文件库」即可继续使用。`:report?.status==='UPGRADE_COMMITTED'?'文件库更新已完成。':'');
}
async function refresh(){
  if(refreshing)return;
  refreshing=true;
  try{state=await api.status();render();}catch{note('error','启动器连接失败，请重新打开 Coffer。');}
  finally{refreshing=false;}
}
async function action(fn){
  if(pending)return;
  pending=true;note('error','');render();
  try{await fn();}catch(error){const message=String(error.message).replace(/^Error invoking remote method .*?: Error: /,'');note('error',messages[message] || message);}
  finally{pending=false;await refresh();render();}
}
$('next').onclick=()=>{if(!state?.canCreate)return;step=2;render();focusHeading('confirm-step');};
$('back').onclick=()=>{step=1;render();focusHeading('location-step');};
$('initialize').onclick=()=>action(async()=>{creating=true;try{await api.start({initialize:true,upgrade:false});}finally{creating=false;}});
$('recommended').onchange=()=>action(()=>api.useDefaultLocation());
$('custom').onchange=()=>action(()=>api.chooseNewLocation());
$('choose-new').onclick=()=>action(()=>api.chooseNewLocation());
for(const id of ['choose-existing-new','choose-existing'])$(id).onclick=()=>action(async()=>{await api.chooseDataDirectory();step=1;});
$('create-empty-library').onclick=()=>action(async()=>{await api.chooseEmptyLibrary();step=1;});
$('cancel-new-library').onclick=()=>action(async()=>{await api.cancelEmptyLibrary();step=1;});
for(const id of ['reveal-new','reveal-existing'])$(id).onclick=()=>action(()=>api.showLibraryLocation());
$('open').onclick=()=>action(()=>api.start({initialize:false,upgrade:false}));
$('upgrade-form').onsubmit=event=>{event.preventDefault();action(async()=>{const input=$('upgrade-password');try{await api.start({initialize:false,upgrade:true,password:input.value});}finally{input.value='';}});};
$('choose-library').onclick=()=>action(()=>api.chooseLibraryDirectory());
for(const id of ['restore-entry-new','restore-entry-existing'])$(id).onclick=()=>{view='restore';render();focusHeading('recovery');};
$('recovery-back').onclick=()=>{view='library';$('restore-password').value='';render();focusHeading(state.firstRun?'location-step':'existing');};
$('choose-restore').onclick=()=>action(async()=>{const selected=await api.chooseRestore();if(selected){restoreSelected=true;note('restore-choice',`${selected.archiveName}\n恢复到：${selected.targetDirectory}`);}});
$('restore-form').onsubmit=event=>{event.preventDefault();action(async()=>{const input=$('restore-password');try{const result=await api.restoreBackup(input.value);if(result){restoreSelected=false;view='library';input.value='';}}finally{input.value='';}});};
refresh();setInterval(refresh,750);
