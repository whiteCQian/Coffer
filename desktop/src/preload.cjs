'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');
// Closed methods only: no channel, filesystem path, shell command, launch token or ipcRenderer export.
contextBridge.exposeInMainWorld('cofferDesktop',Object.freeze({
  status:()=>ipcRenderer.invoke('coffer:status'),
  getTheme:()=>ipcRenderer.invoke('coffer:get-theme'),
  getRecentTags:()=>ipcRenderer.invoke('coffer:get-recent-tags'),
  saveRecentTags:names=>ipcRenderer.invoke('coffer:save-recent-tags',names),
  saveTheme:value=>ipcRenderer.invoke('coffer:save-theme',value),
  getPaneWidths:()=>ipcRenderer.invoke('coffer:get-pane-widths'),
  savePaneWidth:(key,value)=>ipcRenderer.invoke('coffer:save-pane-width',key,value),
  chooseDataDirectory:()=>ipcRenderer.invoke('coffer:choose-data'),
  chooseEmptyLibrary:()=>ipcRenderer.invoke('coffer:choose-empty-library'),
  cancelEmptyLibrary:()=>ipcRenderer.invoke('coffer:cancel-empty-library'),
  manageLibrary:()=>ipcRenderer.invoke('coffer:manage-library'),
  chooseNewLocation:()=>ipcRenderer.invoke('coffer:choose-new-location'),
  useDefaultLocation:()=>ipcRenderer.invoke('coffer:use-default-location'),
  showLibraryLocation:()=>ipcRenderer.invoke('coffer:show-library-location'),
  chooseLibraryDirectory:()=>ipcRenderer.invoke('coffer:choose-library'),
  start:options=>ipcRenderer.invoke('coffer:start',options),
  chooseBackupDestination:()=>ipcRenderer.invoke('coffer:choose-backup-destination'),
  createBackup:options=>ipcRenderer.invoke('coffer:create-backup',options),
  chooseRestore:()=>ipcRenderer.invoke('coffer:choose-restore'),
  restoreBackup:password=>ipcRenderer.invoke('coffer:restore-backup',password),
  selectFiles:()=>ipcRenderer.invoke('coffer:select-files'),
  previewDroppedFiles:files=>{
    if(!Array.isArray(files) || files.length<1 || files.length>20) return Promise.reject(new Error('一次最多选择 20 个文件'));
    const paths=files.map(file=>webUtils.getPathForFile(file));
    if(paths.some(path=>!path))return Promise.reject(new Error('请拖入本机文件'));
    return ipcRenderer.invoke('coffer:preview-drops',paths);
  },
  commitInbox:confirmed=>ipcRenderer.invoke('coffer:commit-inbox',confirmed),
  openWorkCopy:id=>ipcRenderer.invoke('coffer:open-work-copy',id),
  openFile:(id,revision)=>ipcRenderer.invoke('coffer:open-file',id,revision)
}));
