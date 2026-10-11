'use strict';
// One vector master for every product surface; PNG/ICO are derived app-icon formats.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..');
const symbol=fs.readFileSync(path.join(root,'assets/brand/coffer.svg'),'utf8');
const shape=/<path[^>]*d="([^"]+)"/.exec(symbol)?.[1];if(!shape)throw new Error('Brand symbol path missing');
const appIcon=`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><defs><linearGradient id="app-teal" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#347f90"/><stop offset="1" stop-color="#246879"/></linearGradient></defs><rect x="4" y="4" width="248" height="248" rx="58" fill="url(#app-teal)"/><path fill="#fffaf0" fill-rule="evenodd" transform="translate(38 51) scale(.74) translate(-80 -14)" d="${shape}"/></svg>\n`;
const targets=['frontend/public/logo.svg','desktop/src/logo.svg'];
for(const target of targets){const file=path.join(root,target);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,symbol);}
fs.writeFileSync(path.join(root,'desktop/build/icon.svg'),appIcon);
fs.writeFileSync(path.join(root,'frontend/public/app-icon.svg'),appIcon);
const sharp=require(process.env.COFFER_SHARP_MODULE || 'sharp');
(async()=>{
  await sharp(Buffer.from(appIcon)).resize(256,256).png().toFile(path.join(root,'desktop/src/app-icon.png'));
  const sizes=[16,24,32,48,64,128,256],images=await Promise.all(sizes.map(size=>sharp(Buffer.from(appIcon)).resize(size,size).png().toBuffer()));
  const header=Buffer.alloc(6+16*sizes.length);header.writeUInt16LE(1,2);header.writeUInt16LE(sizes.length,4);
  let offset=header.length;
  sizes.forEach((size,index)=>{const entry=6+16*index;header[entry]=size===256?0:size;header[entry+1]=header[entry];header.writeUInt16LE(1,entry+4);header.writeUInt16LE(32,entry+6);header.writeUInt32LE(images[index].length,entry+8);header.writeUInt32LE(offset,entry+12);offset+=images[index].length;});
  fs.writeFileSync(path.join(root,'desktop/build/icon.ico'),Buffer.concat([header,...images]));
  console.log('Generated shared Coffer SVG, app PNG and seven-size Windows ICO from the brand master.');
})().catch(error=>{console.error(error);process.exitCode=1;});
