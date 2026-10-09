'use strict';
const api=window.cofferDesktop;
const labels={IDLE:'请选择打开已有数据，或明确初始化一个空目录',STARTING:'正在启动本地后台并检查健康状态…',MAINTENANCE:'正在校验整套数据并执行加密备份/恢复，请保持窗口打开…',FAILED:'本地后台启动失败，数据已保留',EXITED:'本地后台异常退出，原数据已保留；重启将恢复未完成操作',READY:'启动完成',VERIFYING:'正在校验安装资源…'};
const messages={KEY_MISSING:'主密钥缺失，请恢复原密钥；不会生成替代密钥。',KEY_MISMATCH:'密钥与数据库不匹配，请恢复同一套备份。',DATABASE_MISSING:'原数据库缺失，请恢复原数据库。',LIBRARY_MISSING:'原文件库缺失，请恢复或选择原库。',IN_USE:'数据正由另一进程使用，请关闭已有实例。',UPGRADE_BACKUP_REQUIRED:'数据库版本与安装包不同。请先关闭外部应用，再选择备份后升级；较新数据库请使用兼容安装包。',DOWNGRADE_REFUSED:'数据库来自较新版本，不能降级写入，请使用匹配版本。',UPGRADE_BACKUP_FAILED:'升级备份失败，数据库未迁移。请检查占用、空间和目录权限。',PACKAGE_CHECKSUM_FAILED:'安装资源校验失败，请重新安装受信安装包。',BACKEND_START_FAILED:'后台启动失败。请检查数据目录、权限与启动日志后重试。',INITIALIZATION_REFUSED:'初始化只允许空目录；已有数据请打开或恢复。'};
Object.assign(messages,{MIGRATION_FAILED_ROLLED_BACK:'升级失败，整套旧数据已验证恢复。请使用原版本或核对迁移原因，不要初始化替代空库。',UPGRADE_RECOVERY_REQUIRED:'检测到未完成升级，重启将先恢复整套旧数据。',BACKUP_AUTHENTICATION_FAILED:'备份口令错误或文件认证失败，未创建替代库。',RESTORE_TARGET_NOT_EMPTY:'恢复目标不是空目录，现有数据保持不变。',BACKUP_INDEPENDENT_MEDIA_REQUIRED:'此目标不在独立物理设备上，不能标记为独立备份。'});
async function refresh(){
  try{const state=await api.status();document.getElementById('status').textContent=labels[state.phase] || state.phase;
    document.getElementById('message').textContent=messages[state.error] || '';
    document.getElementById('data').textContent=state.dataDirectory;document.getElementById('library').textContent=state.libraryDirectory || '用户数据目录内的 library';document.getElementById('version').textContent=state.version;
    const blocked=['STARTING','VERIFYING','READY','MAINTENANCE'].includes(state.phase);
    for(const id of ['initialize','open','upgrade','choose','choose-library','choose-restore','restore'])document.getElementById(id).disabled=blocked;
    document.getElementById('initialize').disabled=blocked || state.initialized || state.nonempty;
    if(state.maintenanceReport)document.getElementById('maintenance-result').textContent=state.maintenanceReport.status==='RESTORED_VERIFIED'?`整套恢复已验证：${state.maintenanceReport.users} 个账号、${state.maintenanceReport.files} 个正式文件、${state.maintenanceReport.ledgerRows} 条台账/任务。请打开数据并重新登录。`:state.maintenanceReport.status;
  }catch(error){document.getElementById('error').textContent='启动器连接失败，请重新打开应用。';}
}
async function action(fn){try{document.getElementById('error').textContent='';await fn();}catch(error){document.getElementById('error').textContent=String(error.message).replace(/^Error invoking remote method .*?: Error: /,'');}await refresh();}
document.getElementById('initialize').onclick=()=>action(()=>api.start({initialize:true,upgrade:false}));
document.getElementById('open').onclick=()=>action(()=>api.start({initialize:false,upgrade:false}));
document.getElementById('upgrade').onclick=()=>action(async()=>{const input=document.getElementById('backup-password');try{return await api.start({initialize:false,upgrade:true,password:input.value});}finally{input.value='';}});
document.getElementById('choose').onclick=()=>action(()=>api.chooseDataDirectory());
document.getElementById('choose-library').onclick=()=>action(()=>api.chooseLibraryDirectory());
document.getElementById('choose-restore').onclick=()=>action(async()=>{const selected=await api.chooseRestore();if(selected)document.getElementById('restore-choice').textContent=`${selected.archiveName} → ${selected.targetDirectory}`;});
document.getElementById('restore').onclick=()=>action(async()=>{const input=document.getElementById('backup-password');try{return await api.restoreBackup(input.value);}finally{input.value='';}});
refresh();setInterval(refresh,1000);
