import {execFileSync} from 'node:child_process';
import {mkdirSync, writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {join} from 'node:path';

// Run after test:storage, which provides the disposable 700-book QA library.
const bin=join(process.env.ANDROID_HOME,'platform-tools/adb');
const serial=execFileSync(bin,['devices']).toString().match(/emulator-\d+/)?.[0];
assert.ok(serial);
const adb=(...args)=>execFileSync(bin,['-s',serial,...args],{timeout:30000,maxBuffer:16e6});
assert.match(adb('emu','avd','name').toString(),/^LNReader_QA/);
const pause=ms=>new Promise(r=>setTimeout(r,ms));
function nodes(){adb('shell','uiautomator','dump','/sdcard/polish-window.xml'); return [...adb('exec-out','cat','/sdcard/polish-window.xml').toString().matchAll(/<node\s+([^>]+)>?/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a=>[a[1],a[2]])));}
function touch(n){assert.ok(n,'Missing node'); const b=n.bounds.match(/\d+/g).map(Number); adb('shell','input','tap',String((b[0]+b[2])>>1),String((b[1]+b[3])>>1));}
async function tap(label){for(let i=0;i<5;i++){let all=nodes(),node=all.find(n=>n['content-desc']===label)||all.find(n=>n.text===label||n.text===label+' ✓');if(node){touch(node);await pause(400);return;}await pause(300);}throw Error('Missing control: '+label);}
function state(){return JSON.parse(adb('exec-out','run-as','com.lnreader.app','cat','files/library/library.json'));}
function shot(name){mkdirSync('artifacts',{recursive:true});writeFileSync('artifacts/android-0.9.14-'+name+'.png',adb('exec-out','screencap','-p'));}
async function launch(){adb('shell','am','force-stop','com.lnreader.app');adb('shell','am','start','-n','com.lnreader.app/.NativeActivity');await pause(700);}
async function shelf(name){await tap('打开书架抽屉');await tap(name);}
const visibleTitles=()=>nodes().filter(n=>n['content-desc']?.endsWith('封面')).map(n=>n['content-desc'].slice(0,-2));
async function waitFor(check){for(let i=0;i<100;i++){try{if(check())return;}catch{}await pause(200);}throw Error('State timeout');}
async function reveal(label){for(let i=0;i<8;i++){if(nodes().some(n=>n.text===label||n['content-desc']===label))return;adb('shell','input','swipe','550','1850','550','1100','220');await pause(180);}throw Error('Not visible: '+label);}
async function type(label,value){await tap(label);adb('shell','input','keyevent','123',...Array(70).fill('67'));adb('shell','input','text',value);adb('shell','input','keyevent','4');await pause(250);}
async function search(value){if(!nodes().some(n=>n['content-desc']==='搜索书名或文件名'))await tap('展开或收起筛选排序');await type('搜索书名或文件名',value);await tap('搜索书籍');}
async function sort(label,order='升序'){await tap('书籍排序');await tap(label);await tap(order);assert.equal(nodes().filter(n=>n.class==='android.widget.RadioButton'&&n.checked==='true').length,2);shot('sort');await tap('完成');}
async function addShelf(name){await tap('打开书架抽屉');await tap('新增书架');await type('书架名称',name);await tap('保存书架');await waitFor(()=>state().shelves.some(s=>s.name===name));}
async function removeShelf(name){await tap('打开书架抽屉');await tap('编辑书架');await tap('删除'+name);await tap('删除');await waitFor(()=>!state().shelves.some(s=>s.name===name));}
if(!process.env.POLISH_TAIL){
const gestures=adb('shell','am','instrument','-w','-e','guard','LNReader_QA','-e','gesture','true','com.lnreader.app.test/com.lnreader.app.StorageChecks').toString();assert.ok(!gestures.includes('FAIL:'),gestures);assert.match(gestures,/PASS: short and medium/);console.log(gestures.trim());
mkdirSync('artifacts',{recursive:true});writeFileSync('artifacts/android-0.9.14-drag-half.png',adb('exec-out','run-as','com.lnreader.app','cat','cache/drag-half.png'));
await launch();
assert.ok(!nodes().some(n=>n['content-desc']==='搜索书名或文件名'));
assert.ok(nodes().some(n=>/本书$/.test(n.text)),'Book count stays visible when collapsed');
touch(nodes().find(n=>n.text==='LNReader'));await pause(200);assert.ok(!nodes().some(n=>n.text==='新增书架'));
for(const [x1,y1,x2,y2,opens] of [[650,900,650,700,false],[650,900,590,900,false],[650,900,705,900,true]]){
  adb('shell','input','swipe',String(x1),String(y1),String(x2),String(y2),'160');await pause(250);assert.equal(nodes().some(n=>n.text==='新增书架'),opens);
}
assert.ok(!nodes().some(n=>n.text==='无分类'));adb('shell','input','swipe','650','900','590','900','160');await pause(300);assert.ok(!nodes().some(n=>n.text==='新增书架'),'Left swipe closes drawer');
await shelf('全部书籍');let header=nodes();const cx=desc=>{let b=header.find(n=>n['content-desc']===desc).bounds.match(/\d+/g).map(Number);return(b[0]+b[2])/2;};assert.ok(cx('书籍排序')<cx('切换列表或网格显示')&&cx('切换列表或网格显示')<cx('展开或收起筛选排序'));
await tap('展开或收起筛选排序');shot('shelf-tools');
await sort('文件名');assert.equal(state().shelfViews.all.sort,'filename');assert.equal(state().shelfViews.all.ascending,true);assert.equal(visibleTitles()[0],'QA700-010');
await tap('切换列表或网格显示');assert.equal(state().shelfViews.all.list,true);await shelf('最近阅读');assert.equal(state().shelfViews.recent.list,false);await sort('书籍名称');await shelf('全部书籍');await launch();await shelf('全部书籍');assert.equal(state().shelfViews.all.list,true);assert.equal(state().shelfViews.all.sort,'filename');
for(const [label,key] of [['文件大小','size'],['导入时间','added'],['书籍名称','title'],['最近阅读时间','lastRead']]){await sort(label);assert.equal(state().shelfViews.all.sort,key);}
await sort('书籍名称','降序');assert.equal(visibleTitles()[0],'蛊真人');await tap('切换列表或网格显示');
await search('QA700-010');let cover=nodes().find(n=>n['content-desc']==='QA700-010封面');let b=cover.bounds.match(/\d+/g).map(Number),x=String((b[0]+b[2])>>1),y=String((b[1]+b[3])>>1);adb('shell','input','swipe',x,y,x,y,'650');await pause(300);
let selected=nodes(),count=selected.find(n=>n.text==='1 本书/已选1本'),cancel=selected.find(n=>n.text==='取消选择');assert.ok(count&&cancel);assert.ok(Math.abs(Number(count.bounds.match(/\d+/g)[1])-Number(cancel.bounds.match(/\d+/g)[1]))<30);shot('selected');
await tap('展开或收起筛选排序');assert.ok(!nodes().some(n=>n.text==='取消选择'));assert.ok(nodes().some(n=>n.text==='1 本书'));assert.ok(!nodes().some(n=>n.text.includes('已选')));shot('collapsed');
await tap('书架设置');assert.ok(nodes().some(n=>n.text==='恢复默认备份文件夹'));shot('backup-settings');await tap('×');
// Create, reorder, rename and delete real user categories.
for(const name of ['PolishA','PolishB'])if(state().shelves.some(s=>s.name===name))await removeShelf(name);
await addShelf('PolishA');await addShelf('PolishB');await tap('打开书架抽屉');await tap('编辑书架');
let edit=nodes();assert.ok(!edit.some(n=>n['content-desc']==='删除最近阅读'||n['content-desc']==='删除全部书籍'));let a=edit.find(n=>n['content-desc']==='拖动PolishA').bounds.match(/\d+/g).map(Number),d=edit.find(n=>n['content-desc']==='拖动PolishB').bounds.match(/\d+/g).map(Number);
adb('shell','input','swipe',String((a[0]+a[2])>>1),String((a[1]+a[3])>>1),String((d[0]+d[2])>>1),String((d[1]+d[3])>>1),'650');await pause(400);await waitFor(()=>state().shelves.findIndex(s=>s.name==='PolishB')<state().shelves.findIndex(s=>s.name==='PolishA'));shot('drawer-edit');await tap('完成编辑');await tap('全部书籍');
await search('QA700-010');await tap('管理QA700-010');
let info=nodes();assert.ok(!info.some(n=>n['content-desc']==='分类：PolishA'));
let infoY=label=>Number(info.find(n=>n.text===label).bounds.match(/\d+/g)[1]);assert.ok(infoY('导入日期')-infoY('文件大小')<100,'Read-only metadata rows are compact');shot('compact-book-info');
await reveal('展开或收起所属分类');await tap('展开或收起所属分类');await reveal('分类：PolishA');
let categories=nodes(), box=categories.find(n=>n['content-desc']==='分类：PolishA'),name=categories.find(n=>n.text==='PolishA');assert.ok(Number(box.bounds.match(/\d+/g)[0])>=Number(name.bounds.match(/\d+/g)[2]),'Checkbox sits to the right of the category name');
await tap('分类：PolishA');await tap('分类：PolishB');shot('book-categories');await tap('展开或收起所属分类');assert.ok(!nodes().some(n=>n['content-desc']==='分类：PolishA'));await reveal('保存');await tap('保存');
const chosen=()=>state().books.find(b=>b.title==='QA700-010');await waitFor(()=>chosen().shelfIds?.length===2&&chosen().directory.startsWith('book/PolishA/'));const id=chosen().id;
await shelf('PolishA');assert.ok(visibleTitles().includes('QA700-010'));await shelf('PolishB');assert.ok(visibleTitles().includes('QA700-010'));
await tap('打开书架抽屉');await tap('编辑书架');await tap('重命名PolishA');await type('书架改名输入框','PolishRenamed');await tap('确认重命名');await waitFor(()=>chosen().directory.startsWith('book/PolishRenamed/'));await tap('完成编辑');await tap('全部书籍');
await removeShelf('PolishRenamed');await waitFor(()=>chosen().shelfIds.length===1&&chosen().directory.startsWith('book/PolishB/'));await removeShelf('PolishB');await waitFor(()=>chosen().shelfIds.length===0&&chosen().directory.startsWith('book/无分类/'));assert.equal(chosen().id,id);
await shelf('全部书籍');await search('QA700-010');
const readerBg=state().prefs.bg;await tap('书架设置');await reveal('青绿');await tap('青绿');assert.equal(nodes().filter(n=>['青绿','雾蓝','暖沙','藕粉','石墨','藤紫'].includes(n['content-desc'])).length,6);assert.ok(!nodes().some(n=>n.text==='自定义'));shot('six-ui-themes');await tap('雾蓝');await waitFor(()=>state().uiTheme==='blue');assert.equal(state().prefs.bg,readerBg,'UI theme must not modify reader background');shot('ui-blue');
} else { await launch();await shelf('全部书籍');await search('QA700-010'); }
await tap('书架设置');await tap('恢复默认备份文件夹');await tap('恢复默认');await waitFor(()=>{try{return JSON.parse(adb('exec-out','cat','/sdcard/Download/LNReader/userdata.json')).books.length===693;}catch{return false;}});assert.equal(state().books.length,693);
adb('shell','pm','grant','com.lnreader.app','android.permission.READ_MEDIA_IMAGES');await tap('管理QA700-010');await reveal('导入封面');await tap('导入封面');assert.ok(nodes().some(n=>n['content-desc']==='选择相册'));shot('photo-picker');adb('shell','input','keyevent','4');
console.log('PASS: visible counts, collapse exits selection, compact selected cards, explicit search, two radio sort groups, bidirectional drawer, editable/reorderable user categories, multiple memberships with one backup, UI theme independence, default-folder recovery and gallery');
