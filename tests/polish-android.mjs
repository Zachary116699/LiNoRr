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
async function tap(label){let all=nodes();touch(all.find(n=>n['content-desc']===label)||all.find(n=>n.text===label||n.text===label+' ✓'));await pause(400);}
function state(){return JSON.parse(adb('exec-out','run-as','com.lnreader.app','cat','files/library/library.json'));}
function shot(name){mkdirSync('artifacts',{recursive:true});writeFileSync('artifacts/android-0.9.7-'+name+'.png',adb('exec-out','screencap','-p'));}
async function launch(){adb('shell','am','force-stop','com.lnreader.app');adb('shell','am','start','-n','com.lnreader.app/.NativeActivity');await pause(700);}
async function shelf(name){await tap('打开书架抽屉');await tap(name);}
const visibleTitles=()=>nodes().filter(n=>n['content-desc']?.endsWith('封面')).map(n=>n['content-desc'].slice(0,-2));
await launch();
assert.ok(!nodes().some(n=>n['content-desc']==='搜索书名或文件名'),'Tools start collapsed');
touch(nodes().find(n=>n.text==='LNReader'));await pause(250);assert.ok(!nodes().some(n=>n.text==='新增书架'),'Title must not open the drawer');
// Short swipe starts well away from the screen edge. Vertical and left swipes must not open.
for(const [x1,y1,x2,y2,opens] of [[650,900,650,700,false],[650,900,590,900,false],[650,900,705,900,true]]){
  adb('shell','input','swipe',String(x1),String(y1),String(x2),String(y2),'160');await pause(300);
  assert.equal(nodes().some(n=>n.text==='新增书架'),opens);
}
await tap('全部');await tap('展开或收起筛选排序');assert.ok(nodes().some(n=>n['content-desc']==='搜索书名或文件名'));
shot('shelf-tools');
// Actual sort menu, both directions and persistence across shelf changes/restarts.
await tap('书籍排序');await tap('文件名');await tap('书籍排序');
let toggle=nodes().find(n=>n.class==='android.widget.Switch');if(toggle.checked==='false'){touch(toggle);await pause(300);}await tap('×');
assert.equal(state().shelfViews.all.sort,'filename');assert.equal(state().shelfViews.all.ascending,true);
let titles=visibleTitles();assert.ok(titles.indexOf('QA700-010')>=0 && titles.indexOf('QA700-011')>titles.indexOf('QA700-010'));
await tap('切换列表或网格显示');assert.equal(state().shelfViews.all.list,true);
await shelf('最近阅读');assert.equal(state().shelfViews.recent.list,false,'Untouched recent shelf keeps its original grid mode');await tap('书籍排序');await tap('书籍名称');await shelf('全部');assert.equal(state().shelfViews.all.list,true);
await launch();assert.ok(!nodes().some(n=>n['content-desc']==='搜索书名或文件名'));await shelf('全部');
assert.equal(state().shelfViews.all.sort,'filename');assert.equal(state().shelfViews.all.list,true);
assert.equal(state().shelfViews.recent.sort,'title');await tap('展开或收起筛选排序');
for(const [label,key] of [['文件大小','size'],['导入时间','added'],['书籍名称','title']]){await tap('书籍排序');await tap(label);assert.equal(state().shelfViews.all.sort,key);assert.equal(visibleTitles()[0],'QA700-010','Ascending '+key+' orders the fixture');}
await tap('书籍排序');toggle=nodes().find(n=>n.class==='android.widget.Switch');touch(toggle);await pause(350);await tap('×');assert.equal(state().shelfViews.all.ascending,false);
assert.equal(visibleTitles()[0],'蛊真人','Descending title order');
await tap('切换列表或网格显示');
await tap('展开或收起筛选排序');assert.ok(!nodes().some(n=>n['content-desc']==='搜索书名或文件名'));shot('shelf-collapsed');
await tap('书架设置');const path=nodes().find(n=>n.text?.includes('AA-LNReader-QA-097'));assert.ok(path);const b=path.bounds.match(/\d+/g).map(Number);assert.ok(b[2]-b[0]>600,'Backup path occupies a full row');shot('backup-settings');await tap('×');
// Photo picker opens the gallery UI rather than DocumentsUI.
await shelf('最近阅读');const book=nodes().find(n=>n['content-desc']?.startsWith('管理'));assert.ok(book);touch(book);await pause(400);
for(let i=0;i<4&&!nodes().some(n=>n.text==='导入图片作为封面');i++)adb('shell','input','swipe','550','1800','550','1100','250');
await tap('导入图片作为封面');const photo=nodes();assert.ok(photo.some(n=>/photopicker|providers.media/.test(n.package)),'System gallery picker');assert.ok(!photo.some(n=>n.package==='com.google.android.documentsui'));shot('photo-picker');adb('shell','input','keyevent','4');
console.log('PASS: collapsed shelf tools, short full-width swipe, title behavior, four sort modes, direction, per-shelf persisted layout, full-width backup path and gallery picker');
