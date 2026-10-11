'use strict';
const fs=require('node:fs'),path=require('node:path');
const {dataPath,noLinks,regularFile,within}=require('./safe-paths.cjs');

function startupPath(value,installRoot,profileDirectory){
  const target=dataPath(value,installRoot);
  if(within(profileDirectory,target) || within(target,profileDirectory))throw new Error('PROFILE_DATA_OVERLAP');
  return target;
}

// A missing previously used library must never become a first-run candidate.
function inspectDirectory(directory,{initialized=false}={}){
  try{
    noLinks(directory,true);
    if(!fs.existsSync(directory))return {kind:initialized?'missing':'empty',exists:false,nonempty:false};
    if(!fs.lstatSync(directory).isDirectory())return {kind:'unavailable',exists:false,nonempty:true};
    const entries=fs.readdirSync(directory).filter(name=>name!=='.coffer-desktop.lock');
    if(entries.includes('.coffer-desktop.json')){
      regularFile(path.join(directory,'.coffer-desktop.json'),4096);
      return {kind:'existing',exists:true,nonempty:true};
    }
    return {kind:initialized?'missing':entries.length?'occupied':'empty',exists:true,nonempty:entries.length>0};
  }catch{return {kind:'unavailable',exists:false,nonempty:true};}
}

function newLibraryLocation(parent,installRoot,profileDirectory){
  if(typeof parent!=='string' || !path.isAbsolute(parent) || /[;\r\n\0]/.test(parent))throw new Error('DATA_PATH_INVALID');
  const chosen=noLinks(parent);
  if(!fs.lstatSync(chosen).isDirectory())throw new Error('PATH_DIRECTORY_REQUIRED');
  // The selected parent can contain the program (e.g. D:\); only the final child must be separate.
  if(inspectDirectory(chosen).kind==='existing')return startupPath(chosen,installRoot,profileDirectory);
  return startupPath(path.join(chosen,'Coffer'),installRoot,profileDirectory);
}

function requireEmptyWritableDirectory(directory){
  if(inspectDirectory(directory).kind!=='empty')throw new Error('INITIALIZATION_REFUSED');
  let ancestor=directory;
  while(!fs.existsSync(ancestor))ancestor=path.dirname(ancestor);
  noLinks(ancestor);
  try{fs.accessSync(ancestor,fs.constants.W_OK);}catch{throw new Error('DIRECTORY_NOT_WRITABLE');}
}
function requireSeparateEmptyLibrary(directory,previous){
  const known=previous.initialized || previous.creationStarted || inspectDirectory(previous.dataDirectory).kind!=='empty';
  const library=previous.libraryDirectory || path.join(previous.dataDirectory,'library');
  const libraryState=inspectDirectory(library);
  const roots=[previous.dataDirectory];
  if(!libraryState.exists || libraryState.nonempty)roots.push(library);
  if(known)for(const root of roots){
    if(within(root,directory) || within(directory,root))throw new Error('LIBRARY_LOCATION_OVERLAP');
  }
  requireEmptyWritableDirectory(directory);
  return directory;
}
module.exports={startupPath,inspectDirectory,newLibraryLocation,requireEmptyWritableDirectory,requireSeparateEmptyLibrary};
