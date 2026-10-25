import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { join } from 'node:path';

const adbPath = process.env.ADB || join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb');
const serial = execFileSync(adbPath, ['devices']).toString().split('\n').map(s => s.split(/\s+/)[0]).find(s => /^emulator-\d+$/.test(s));
assert.ok(serial, 'Use a dedicated emulator');
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], { timeout: 30000, maxBuffer: 16e6 });
assert.match(adb('emu', 'avd', 'name').toString(), /^LNReader_QA/, 'Only the isolated QA emulator may be reset');
const pause = ms => new Promise(r => setTimeout(r, ms));
const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));
function nodes() { adb('shell', 'uiautomator', 'dump', '/sdcard/storage-window.xml'); const xml = adb('exec-out', 'cat', '/sdcard/storage-window.xml').toString(); return [...xml.matchAll(/<node\s+([^>]+)>?/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])]))); }
const bounds = n => n.bounds.match(/\d+/g).map(Number);
const has = (n, label) => n.text === label || n['content-desc'] === label;
function touch(n, hold = false) { const b = bounds(n), x = String((b[0] + b[2]) >> 1), y = String((b[1] + b[3]) >> 1); if (hold) adb('shell','input','swipe',x,y,x,y,'700'); else adb('shell','input','tap',x,y); }
async function tap(label) { for (let i = 0; i < 5; i++) { const all=nodes(), n=all.find(n=>n['content-desc']===label)||all.find(n=>n.text===label); if (n) { touch(n); await pause(350); return; } await pause(250); } throw Error('Missing control: ' + label); }
const size = adb('shell','wm','size').toString().match(/(\d+)x(\d+)/).slice(1).map(Number);
function swipe(up = true) { adb('shell','input','swipe',String(size[0]*.5),String(size[1]*(up?.82:.32)),String(size[0]*.5),String(size[1]*(up?.30:.85)),'300'); }
async function reveal(label) { for (let i=0;i<7;i++) { if(nodes().some(n=>has(n,label))) return; swipe(); await pause(200); } throw Error('Offscreen control: '+label); }
async function type(label,value) { await reveal(label); await tap(label); adb('shell','input','keyevent','123',...Array(70).fill('67')); if(value) adb('shell','input','text',value); adb('shell','input','keyevent','4'); await pause(350); }
let backupPath = '/sdcard/Download/LNReader';
const userdata = () => JSON.parse(adb('exec-out','cat',backupPath+'/userdata.json'));
function shot(name) { mkdirSync('artifacts',{recursive:true}); writeFileSync('artifacts/'+name,adb('exec-out','screencap','-p')); }
function put(path,text) { execFileSync(adbPath,['-s',serial,'shell','sh','-c',"'cat > "+path+"'"],{input:Buffer.from(text)}); }
function storagePermission(mode) { adb('shell','appops','set','com.lnreader.app','MANAGE_EXTERNAL_STORAGE',mode); adb('shell','appops','set','--uid','com.lnreader.app','MANAGE_EXTERNAL_STORAGE',mode); }
async function launch() { adb('shell','am','start','-n','com.lnreader.app/.NativeActivity'); await pause(700); }
async function waitFor(check) { for(let i=0;i<100;i++) { try { if(check()) return; } catch {} await pause(200); } throw Error('Timed out waiting for state'); }
async function category(name) { await tap('打开书架抽屉'); await tap(name); }
async function pickFolder(name) { const crumb=nodes().find(n=>n['resource-id']?.endsWith('breadcrumb_text')); assert.ok(crumb); touch(crumb); await pause(300); await tap('Download'); await reveal(name); await tap(name); await tap('USE THIS FOLDER'); if(nodes().some(n=>n.text==='ALLOW')) await tap('ALLOW'); }
async function close() { await tap('×'); }
async function findBook(query) { if (!nodes().some(n=>n['content-desc']==='搜索书名或文件名')) await tap('展开或收起筛选排序'); await type('搜索书名或文件名',query); for(let i=0;i<3;i++) swipe(false); await pause(300); }

adb('install','-r','app/build/outputs/apk/debug/app-debug.apk');
adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
adb('shell','am','force-stop','com.lnreader.app'); adb('shell','pm','clear','com.lnreader.app');
for(const dir of ['LNReader','LNReader_QA_Moved','AA-LNReader-QA-098','AB-LNReader-QA-098']) adb('shell','rm','-rf','/sdcard/Download/'+dir);
storagePermission('deny'); await launch(); adb('shell','am','force-stop','com.lnreader.app');
const legacyId='999999999999999999999999', archivedId='ffffffffffffffffffffffff', legacyText='第一章 旧书\n原来的小说内容。\n';
const legacy={version:1,books:[{id:legacyId,title:'旧书',shelf:'legacy-category',position:8,length:legacyText.length,marks:[{offset:8,title:'旧书签'}],sources:['以前的文件名.txt'],lastRead:123}],removedBooks:[{id:archivedId,title:'旧版已移除',shelf:'default'}],shelves:[{id:'default',name:'未分类'},{id:'legacy-category',name:'迁移分类'}],prefs:{size:19,line:1.6,paragraph:.6,readingMode:'paged',defaultsVersion:2}};
for(const [name,text] of [[legacyId+'.txt',legacyText],[archivedId+'.txt','已移除的原文'],['library.json',JSON.stringify(legacy)]]) execFileSync(adbPath,['-s',serial,'shell','run-as','com.lnreader.app','sh','-c',"'cat > files/library/"+name+"'"],{input:Buffer.from(text)});
storagePermission('allow'); await launch(); await waitFor(()=>userdata().books[0]?.directory && !userdata().removedBooks);
let migrated=userdata(); assert.equal(migrated.books[0].position,8); assert.deepEqual(migrated.books[0].marks,legacy.books[0].marks); assert.equal(migrated.prefs.size,19); assert.equal(migrated.books[0].directory,'book/迁移分类/旧书');
assert.equal(adb('exec-out','cat',backupPath+'/book/迁移分类/旧书/以前的文件名.txt').toString(),legacyText);
assert.throws(()=>adb('shell','run-as','com.lnreader.app','ls','files/library/'+archivedId+'.txt'));
console.log('PASS: legacy library migration preserves history and removes previously archived backup records/files');
adb('shell','am','force-stop','com.lnreader.app'); adb('shell','pm','clear','com.lnreader.app'); adb('shell','rm','-rf',backupPath); storagePermission('allow');
adb('push','../xs_local_html/txt/蛊真人.txt','/sdcard/Download/LNReader_QA_fixture.txt');
const report=execFileSync(adbPath,['-s',serial,'shell','am','instrument','-w','-e','guard','LNReader_QA','-e','fixture','/sdcard/Download/LNReader_QA_fixture.txt','com.lnreader.app.test/com.lnreader.app.StorageChecks'],{timeout:180000,maxBuffer:5e6}).toString();
assert.ok(!report.includes('FAIL:'),report); assert.match(report,/PASS: byte-preserving/); console.log(report.trim());
await launch(); await category('全部书籍');
const first=nodes(), covers=first.filter(n=>n['content-desc']?.endsWith('封面')).map(bounds); assert.ok(covers.length>=3); assert.equal(new Set(covers.slice(0,3).map(b=>(b[2]-b[0])+':'+(b[3]-b[1]))).size,1);
assert.ok(!first.some(n=>n.text==='下一页'),'Pager is below the books'); assert.ok(!first.some(n=>n.text==='同步文件夹')); shot('android-0.9.10-700-books.png');
for(let i=0;i<5;i++) swipe(); await reveal('下一页'); shot('android-0.9.10-shelf-pagination.png'); await tap('下一页'); assert.ok(nodes().some(n=>n.text==='QA700-686'));
for(let i=0;i<5;i++) swipe(); await type('书架页码','47'); await tap('跳转'); assert.ok(nodes().some(n=>n.text==='QA700-011'));
for(let i=0;i<5;i++) swipe(); await type('书架页码','1'); await tap('跳转');
// Both drawer entry points, and the compact list cover.
adb('shell','input','swipe',String(size[0]*.15),String(size[1]*.45),String(size[0]*.80),String(size[1]*.45),'350'); await pause(300); assert.ok(nodes().some(n=>n.text==='新增书架')); shot('android-0.9.10-drawer.png'); await tap('全部书籍');
await tap('切换列表或网格显示'); shot('android-0.9.10-list.png'); await tap('切换列表或网格显示');
await tap('日夜间模式切换'); assert.equal(userdata().prefs.night,true); shot('android-0.9.10-night-shelf.png');
await tap('书架设置'); assert.ok(!nodes().some(n=>/同步|批量添加书籍/.test(n.text))); shot('android-0.9.10-night-settings.png'); await close();
await tap('导入书籍'); shot('android-0.9.10-night-import.png'); await close();
await tap('QA700-699'); await pause(350); adb('shell','input','tap',String(size[0]*.5),String(size[1]*.5)); await tap('日 / 夜'); assert.equal(userdata().prefs.night,false);
await tap('设置'); await reveal('纸白 ▾'); await tap('纸白 ▾'); await reveal('青竹'); await tap('青竹'); await waitFor(()=>userdata().prefs.bg==='#e5ebdf'); assert.ok(!nodes().some(n=>n.text==='夜读')); await close();
const layout=userdata().books.find(b=>b.title==='QA700-699').pagination.key; await tap('日 / 夜'); assert.equal(userdata().prefs.bg,'#e5ebdf'); assert.equal(userdata().prefs.night,true); await tap('返回书架'); assert.equal(userdata().books.find(b=>b.title==='QA700-699').pagination.key,layout);
await tap('日夜间模式切换'); assert.equal(userdata().prefs.night,false);
console.log('PASS: 15-book pages, bottom-only pager/jump, drawer click/swipe, list covers and global day/night with remembered day color');

// Import through DocumentsUI, rename the display title, and still search the original filename.
adb('shell','mkdir','-p','/sdcard/Download/AB-LNReader-QA-098');
put('/sdcard/Download/AB-LNReader-QA-098/RuleBook.txt','Season 1: Spring\nEP.1 Beginning\nUnindented paragraph.\n\n\n第一幕 不默认识别\n第一章 默认章节\n正文。\n');
await tap('导入书籍'); await reveal('选择文件夹（包含子目录）'); await tap('选择文件夹（包含子目录）'); await pickFolder('AB-LNReader-QA-098'); await waitFor(()=>userdata().books.length===703);
const original=userdata().books.find(b=>b.title==='RuleBook'), bookId=original.id, hash=original.contentHash;
await tap('管理RuleBook'); assert.ok(nodes().some(n=>n.text==='文件名称')); assert.ok(nodes().some(n=>n.text==='文件大小')); assert.ok(nodes().some(n=>n.text==='页码数量')); shot('android-0.9.10-book-management.png');
await type('书籍名称','UserTitle'); await reveal('保存'); await tap('保存'); await waitFor(()=>userdata().books.some(b=>b.title==='UserTitle'));
await findBook('RuleBook'); assert.ok(nodes().some(n=>n.text==='UserTitle'),'Original filename remains searchable'); assert.ok(!nodes().some(n=>n.text==='下一页'));
await tap('管理UserTitle'); await reveal('章节匹配设置'); await tap('章节匹配设置');
let chapter=nodes(); assert.equal(chapter.find(n=>n.text==='幕').checked,'false'); assert.equal(chapter.find(n=>n.text==='章').checked,'true'); await tap('章'); await reveal('EP.'); await tap('EP.');
await type('自定义章节名称','Season');
const spinner=nodes().find(n=>n.class==='android.widget.Spinner'); assert.ok(spinner); touch(spinner); await tap('篇级'); await reveal('添加匹配'); await tap('添加匹配'); await reveal('保存章节匹配'); await tap('保存章节匹配'); await waitFor(()=>userdata().books.find(b=>b.id===bookId).chapterCount===2);
const rules=userdata().books.find(b=>b.id===bookId).chapterRules; assert.ok(rules.kinds.includes('EP.')&&!rules.kinds.includes('章')); assert.deepEqual(rules.custom,[{name:'Season',level:2}]); await close();
await tap('UserTitle'); adb('shell','input','tap',String(size[0]*.5),String(size[1]*.5)); await tap('目录'); assert.ok(nodes().some(n=>n['content-desc']==='章节匹配设置')); shot('android-0.9.10-custom-catalog.png'); await tap('章节匹配设置'); shot('android-0.9.10-chapter-rules.png'); await close(); await tap('返回书架');
const deletedDirectory=userdata().books.find(b=>b.id===bookId).directory;
await tap('管理UserTitle'); await reveal('从书架移除'); await tap('从书架移除'); await tap('移除'); await waitFor(()=>!userdata().books.some(b=>b.id===bookId));
assert.ok(!userdata().removedBooks); assert.throws(()=>adb('shell','ls',backupPath+'/'+deletedDirectory));
assert.ok(adb('shell','ls','/sdcard/Download/AB-LNReader-QA-098/RuleBook.txt').toString().includes('RuleBook.txt'));
await tap('导入书籍'); await tap('选择文件夹（包含子目录）'); assert.ok(nodes().some(n=>n.text==='AB-LNReader-QA-098'),'Last import folder is remembered'); await tap('USE THIS FOLDER'); if(nodes().some(n=>n.text==='ALLOW')) await tap('ALLOW'); await waitFor(()=>userdata().books.some(b=>b.id===bookId));
assert.equal(userdata().books.find(b=>b.id===bookId).contentHash,hash); assert.equal(userdata().books.find(b=>b.id===bookId).title,'RuleBook');
console.log('PASS: real folder import, remembered folder, original-name search, per-book custom chapter UI, actual removal and stable reimport identity');

// Select the complete filtered set, categorize it, then really remove it.
await findBook('QA700-00'); touch(nodes().find(n=>n['content-desc']?.endsWith('封面')),true); await tap('全选当前筛选'); assert.ok(nodes().some(n=>n.text==='10 本书/已选10本')); shot('android-0.9.10-selection.png');
await tap('添加到书架'); await tap('分类：QA分类'); await tap('添加到所选书架'); await waitFor(()=>userdata().books.filter(b=>b.title.startsWith('QA700-00')).every(b=>b.directory.startsWith('book/QA分类/')));
const removed=userdata().books.filter(b=>b.title.startsWith('QA700-00'));
touch(nodes().find(n=>n['content-desc']?.endsWith('封面')),true); await tap('全选当前筛选'); await tap('移除所选'); await tap('移除'); await waitFor(()=>userdata().books.length===693);
assert.ok(!userdata().removedBooks); for(const b of removed) assert.throws(()=>adb('shell','ls',backupPath+'/'+b.directory));
console.log('PASS: filtered multi-selection, batch categorization and physical/JSON deletion');

// Restore portable history via the actual permission screen after private data deletion.
adb('shell','input','keyevent','3'); await pause(300); const before=userdata(); adb('shell','am','force-stop','com.lnreader.app'); adb('shell','pm','clear','com.lnreader.app'); storagePermission('deny');
await launch(); await tap('导入书籍'); const permission=nodes().find(n=>n.package==='com.android.settings'&&n.checkable==='true'&&n.clickable==='true'); assert.ok(permission); touch(permission); adb('shell','input','keyevent','4'); await pause(700); await close();
assert.equal(userdata().books.length,before.books.length); assert.deepEqual(userdata().books.map(b=>[b.id,b.position,b.marks]),before.books.map(b=>[b.id,b.position,b.marks]));
console.log('PASS: portable library restores after private data deletion and first system permission grant');

// The user selects a different backup root, then recovers that folder after reinstall/reset.
adb('shell','mkdir','-p','/sdcard/Download/AA-LNReader-QA-098'); await tap('书架设置'); await tap('选择备份文件夹'); await pickFolder('AA-LNReader-QA-098'); await waitFor(()=>{try{return JSON.parse(adb('exec-out','cat','/sdcard/Download/AA-LNReader-QA-098/userdata.json')).books.length===693;}catch{return false;}});
await waitFor(()=>{try{adb('shell','ls',backupPath+'/userdata.json');return false;}catch{return true;}}); backupPath='/sdcard/Download/AA-LNReader-QA-098';
const portable=userdata(); adb('shell','am','force-stop','com.lnreader.app'); adb('shell','pm','clear','com.lnreader.app'); storagePermission('allow'); await launch();
await tap('书架设置'); await tap('选择备份文件夹'); await pickFolder('AA-LNReader-QA-098'); await category('全部书籍'); assert.equal(userdata().books.length,portable.books.length);
assert.deepEqual(userdata().books.map(b=>[b.id,b.position,b.marks,b.chapterRules]),portable.books.map(b=>[b.id,b.position,b.marks,b.chapterRules]));
console.log('PASS: actual backup-folder picker migrates the complete library and opens that portable library after app reset');
adb('logcat','-c'); await tap('QA多文件'); await pause(700);
assert.ok(adb('logcat','-d','-s','LNReader:D').toString().includes('page_index_source=userdata'), 'Pagination survives private-data reset and root migration');
assert.ok(nodes().some(n=>n.class?.endsWith('PagedReaderView')&&n['content-desc']?.includes('\u3000\u3000')), 'The reader uses two actual full-width Chinese spaces');
shot('android-0.9.10-restored-reader.png');
assert.ok(!adb('logcat','-d','-t','1200','-s','AndroidRuntime:E').toString().includes('FATAL EXCEPTION'));
