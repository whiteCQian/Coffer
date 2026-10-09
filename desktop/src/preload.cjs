'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');
// Closed methods only: no channel, filesystem path, shell command, launch token or ipcRenderer export.
contextBridge.exposeInMainWorld('cofferDesktop',Object.freeze({
  status:()=>ipcRenderer.invoke('coffer:status'),
  chooseDataDirectory:()=>ipcRenderer.invoke('coffer:choose-data'),
  chooseLibraryDirectory:()=>ipcRenderer.invoke('coffer:choose-library'),
  start:options=>ipcRenderer.invoke('coffer:start',options),
  chooseBackupDestination:()=>ipcRenderer.invoke('coffer:choose-backup-destination'),
  createBackup:options=>ipcRenderer.invoke('coffer:create-backup',options),
  chooseRestore:()=>ipcRenderer.invoke('coffer:choose-restore'),
  restoreBackup:password=>ipcRenderer.invoke('coffer:restore-backup',password),
  setupToken:()=>ipcRenderer.invoke('coffer:setup-token'),
  selectFiles:()=>ipcRenderer.invoke('coffer:select-files'),
  previewDroppedFiles:files=>{
    if(!Array.isArray(files) || files.length<1 || files.length>20) return Promise.reject(new Error('一次最多选择 20 个文件'));
    const paths=files.map(file=>webUtils.getPathForFile(file));
    if(paths.some(path=>!path))return Promise.reject(new Error('请拖入本机文件'));
    return ipcRenderer.invoke('coffer:preview-drops',paths);
  },
  commitInbox:confirmed=>ipcRenderer.invoke('coffer:commit-inbox',confirmed),
  openWorkCopy:id=>ipcRenderer.invoke('coffer:open-work-copy',id)
}));
