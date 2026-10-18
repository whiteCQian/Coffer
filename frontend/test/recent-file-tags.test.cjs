const test=require('node:test'),assert=require('node:assert/strict'),path=require('node:path'),Module=require('node:module');
const esbuild=require('esbuild'),{reactive}=require('vue'),{createPinia,setActivePinia}=require('pinia');
const root=path.resolve(__dirname,'..');
const bundle=esbuild.buildSync({entryPoints:[path.join(root,'src/stores/recentFileTags.ts')],bundle:true,write:false,platform:'node',format:'cjs',external:['vue','pinia'],alias:{'@/stores/auth':path.join(__dirname,'recent-tags-auth.fixture.cjs'),'@/api/desktop':path.join(__dirname,'recent-tags-desktop.fixture.cjs'),'@/api/files':path.join(__dirname,'recent-tags-files.fixture.cjs'),'@/utils/recentTags':path.join(root,'src/utils/recentTags.ts')}}).outputFiles[0].text;
const compiled=new Module(path.join(__dirname,'compiled-recent.cjs'),module);compiled.filename=path.join(__dirname,'compiled-recent.cjs');compiled.paths=module.paths;compiled._compile(bundle,compiled.filename);
function setup(load=Promise.resolve([])){
 const state=globalThis.__recentTagTest={auth:reactive({user:{id:1},isPersonal:true,generation:1}),load,writes:[],details:new Map(),reads:[],bridge:{getRecentTags:()=>load,saveRecentTags:async names=>state.writes.push(names)}};
 setActivePinia(createPinia());const store=compiled.exports.useRecentFileTagsStore();
 return{state,store};
}
test('file groups promote once, deduplicate, cap at eight, preserve untagged history',async()=>{
 const{state,store}=setup();
 for(let i=1;i<=3;i++)state.details.set(i,{confirmedTags:Array.from({length:4},(_,j)=>({tagName:`tag${(i-1)*4+j}`}))});
 await store.recordOpened(1);assert.deepEqual([...store.names],['tag0','tag1','tag2','tag3']);
 await store.recordOpened(2);await store.recordOpened(3);
 assert.deepEqual([...store.names],['tag8','tag9','tag10','tag11','tag4','tag5','tag6','tag7']);
 await store.recordOpened(1);assert.deepEqual([...store.names],['tag0','tag1','tag2','tag3','tag8','tag9','tag10','tag11']);
 assert.equal(state.writes.length,4);
 state.details.set(4,{confirmedTags:[]});await store.recordOpened(4);assert.equal(state.writes.length,4);assert.equal(store.names.length,8);
});
test('opens during preference load merge with saved history; account changes drop old pending opens',async()=>{
 let resolve;const{state,store}=setup(new Promise(r=>resolve=r));
 state.details.set(1,{confirmedTags:[{tagName:'new'}]});const updating=store.recordOpened(1);
 resolve(['old','new']);await updating;assert.deepEqual([...store.names],['new','old']);
 let detailDone;state.details.set(2,new Promise(r=>detailDone=r));const stale=store.recordOpened(2);
 await new Promise(r=>setImmediate(r));state.auth.user={id:2};detailDone({confirmedTags:[{tagName:'wrong-owner'}]});await stale;
 assert(!store.names.includes('wrong-owner'));assert.equal(state.writes.length,1);
});
test('metadata failure is contained and does not poison later updates',async()=>{
 const{state,store}=setup();await store.recordOpened(999);
 state.details.set(1,{confirmedTags:[{tagName:'valid'}]});await store.recordOpened(1);
 assert.deepEqual([...store.names],['valid']);assert.equal(state.writes.length,1);
});
