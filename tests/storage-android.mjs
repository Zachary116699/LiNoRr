import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { join } from 'node:path';

const adbPath = process.env.ADB || join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb');
const serial = execFileSync(adbPath, ['devices']).toString().split('\n').map(s => s.split(/\s+/)[0]).find(s => /^emulator-\d+$/.test(s));
assert.ok(serial, 'Use a dedicated emulator');
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], { timeout: 30000, maxBuffer: 10e6 });
assert.match(adb('emu', 'avd', 'name').toString(), /^LNReader_QA/, 'This test only resets the isolated QA emulator');
const pause = ms => new Promise(r => setTimeout(r, ms));
const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));
function nodes() { adb('shell', 'uiautomator', 'dump', '/sdcard/storage-window.xml'); const xml = adb('exec-out', 'cat', '/sdcard/storage-window.xml').toString(); return [...xml.matchAll(/<node\s+([^>]+)>?/g)].map(m => Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a => [a[1], decode(a[2])]))); }
const bounds = n => n.bounds.match(/\d+/g).map(Number);
async function tap(label, partial = false) { for (let i = 0; i < 6; i++) { const n = nodes().find(n => partial ? n.text?.includes(label) || n['content-desc']?.includes(label) : n.text === label || n['content-desc'] === label); if (n) { const b = bounds(n); adb('shell', 'input', 'tap', String((b[0] + b[2]) >> 1), String((b[1] + b[3]) >> 1)); await pause(500); return; } await pause(300); } throw Error('Missing control: ' + label); }
const userdata = () => JSON.parse(adb('exec-out', 'cat', '/sdcard/Download/LNReader/userdata.json'));
function storagePermission(mode) {
  adb('shell', 'appops', 'set', 'com.lnreader.app', 'MANAGE_EXTERNAL_STORAGE', mode);
  // Settings grants this op at UID level, which survives pm clear and overrides
  // the package-level mode. Reset both so repeated runs exercise first grant.
  adb('shell', 'appops', 'set', '--uid', 'com.lnreader.app', 'MANAGE_EXTERNAL_STORAGE', mode);
}
function shot(name) { mkdirSync('artifacts', { recursive: true }); writeFileSync('artifacts/' + name, adb('exec-out', 'screencap', '-p')); }
async function launch() { adb('shell', 'am', 'start', '-n', 'com.lnreader.app/.NativeActivity'); await pause(800); }
adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
adb('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); adb('shell', 'pm', 'clear', 'com.lnreader.app');
adb('shell', 'rm', '-rf', '/sdcard/Download/LNReader');
storagePermission('deny');
await launch(); adb('shell', 'am', 'force-stop', 'com.lnreader.app');
const legacyId = '999999999999999999999999'; const legacyText = '第一章 旧书\n原来的小说内容。\n';
const legacy = { version: 1, books: [{ id: legacyId, title: '旧书', shelf: 'legacy-category', position: 8, length: legacyText.length, marks: [{ offset: 8, title: '旧书签' }], sources: ['以前的文件名.txt'], lastRead: 123 }], shelves: [{ id: 'default', name: '未分类' }, { id: 'legacy-category', name: '迁移分类' }], prefs: { size: 19, line: 1.6, paragraph: .6, readingMode: 'paged', defaultsVersion: 2 } };
for (const [name, text] of [[legacyId + '.txt', legacyText], ['library.json', JSON.stringify(legacy)]]) execFileSync(adbPath, ['-s', serial, 'shell', 'run-as', 'com.lnreader.app', 'sh', '-c', `'cat > files/library/${name}'`], { input: Buffer.from(text) });
storagePermission('allow'); await launch();
let migrated;
for (let i = 0; i < 30; i++) { try { migrated = userdata(); if (migrated.books[0]?.directory) break; } catch {} await pause(200); }
assert.equal(migrated.books[0].position, 8); assert.deepEqual(migrated.books[0].marks, legacy.books[0].marks); assert.equal(migrated.prefs.size, 19); assert.equal(migrated.prefs.line, 1.6);
assert.equal(migrated.books[0].directory, 'book/迁移分类/旧书'); assert.deepEqual(migrated.books[0].parts, ['以前的文件名.txt']);
assert.equal(adb('exec-out', 'cat', '/sdcard/Download/LNReader/book/迁移分类/旧书/以前的文件名.txt').toString(), legacyText);
console.log('PASS: legacy private library migrates into category/original filename while preserving custom settings, history and bookmarks');
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); adb('shell', 'pm', 'clear', 'com.lnreader.app'); adb('shell', 'rm', '-rf', '/sdcard/Download/LNReader');
storagePermission('allow');
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1');
adb('push', '../xs_local_html/txt/蛊真人.txt', '/sdcard/Download/LNReader_QA_fixture.txt');
const report = execFileSync(adbPath, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'guard', 'LNReader_QA', '-e', 'fixture', '/sdcard/Download/LNReader_QA_fixture.txt', 'com.lnreader.app.test/com.lnreader.app.StorageChecks'], { timeout: 180000, maxBuffer: 5e6 }).toString();
assert.match(report, /PASS: byte-preserving/); assert.ok(!report.includes('FAIL:'), report); console.log(report.trim());
await launch(); await tap('全部', true);
const initial = nodes();
assert.ok(initial.filter(n => n['content-desc']?.endsWith('封面')).length < 60, 'Only on-screen book cells are created');
const covers = initial.filter(n => n['content-desc']?.endsWith('封面')).map(bounds);
assert.ok(covers.length >= 3); assert.equal(new Set(covers.slice(0, 3).map(b => (b[2] - b[0]) + ':' + (b[3] - b[1]))).size, 1, 'Uniform cover dimensions');
shot('android-0.9.5-700-books.png');
await tap('日夜间模式切换'); assert.equal(userdata().prefs.shelfNight, true); shot('android-0.9.5-night-shelf.png'); await tap('日夜间模式切换');
await tap('搜索书名'); adb('shell', 'input', 'text', 'QA700-00'); adb('shell', 'input', 'keyevent', '4'); await pause(400);
const target = nodes().find(n => n['content-desc']?.endsWith('封面')); const b = bounds(target);
adb('shell', 'input', 'swipe', String((b[0] + b[2]) >> 1), String((b[1] + b[3]) >> 1), String((b[0] + b[2]) >> 1), String((b[1] + b[3]) >> 1), '700'); await pause(300);
await tap('全选当前筛选'); assert.ok(nodes().some(n => n.text === '已选 10 本')); shot('android-0.9.5-selection.png');
await tap('添加到书架');
const spinner = nodes().find(n => n.class === 'android.widget.Spinner'); const sb = bounds(spinner); adb('shell', 'input', 'tap', String((sb[0] + sb[2]) >> 1), String((sb[1] + sb[3]) >> 1)); await pause(200);
await tap('QA分类'); await tap('添加到所选书架'); await pause(1500);
let state = userdata(); const moved = state.books.filter(b => b.title.startsWith('QA700-00')); assert.equal(moved.length, 10); assert.ok(moved.every(b => b.shelf === 'qa-category' && b.directory.startsWith('book/QA分类/')));
const select = nodes().find(n => n['content-desc']?.endsWith('封面')); const cb = bounds(select); adb('shell', 'input', 'swipe', String((cb[0] + cb[2]) >> 1), String((cb[1] + cb[3]) >> 1), String((cb[0] + cb[2]) >> 1), String((cb[1] + cb[3]) >> 1), '700'); await pause(200);
await tap('全选当前筛选'); await tap('移除所选'); await tap('移除'); await pause(300); state = userdata(); assert.equal(state.books.length, 692); assert.equal(state.removedBooks.length, 10);
assert.ok(adb('shell', 'ls', '/sdcard/Download/LNReader/' + moved[0].directory).toString().includes('.txt'), 'Removing from shelf keeps portable originals');
await tap('书架设置'); await tap('从备份批量添加书籍'); await tap('全选'); await tap('添加所选书籍'); assert.equal(userdata().books.length, 702);
console.log('PASS: 700-book virtualization, uniform covers, shelf night mode, filtered select-all, batch categorization, removal and restore');
// Deleting all private data must still recover the complete portable library.
adb('shell', 'input', 'keyevent', '3'); await pause(400); const before = userdata(); adb('shell', 'am', 'force-stop', 'com.lnreader.app'); adb('shell', 'pm', 'clear', 'com.lnreader.app'); storagePermission('allow');
await launch(); await tap('全部', true); await tap('搜索书名'); adb('shell', 'input', 'text', 'QA'); adb('shell', 'input', 'keyevent', '4'); await pause(300);
const after = userdata(); assert.equal(after.books.length, before.books.length); assert.deepEqual(after.books.map(b => [b.id, b.position, b.marks]), before.books.map(b => [b.id, b.position, b.marks]));
assert.ok(after.books.some(b => b.title === 'QA多文件' && b.parts.length === 2 && b.cover));
console.log('PASS: a single Download/LNReader folder restores books, original multipart names, compressed covers, history and bookmarks after private-data deletion');
// Exercise the real first-run permission screen, including the empty private mirror
// written while leaving for Settings; it must never replace portable history.
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); adb('shell', 'pm', 'clear', 'com.lnreader.app');
storagePermission('deny');
await launch(); await tap('导入书籍');
let permission = nodes().find(n => n.package === 'com.android.settings' && n.checkable === 'true' && n.clickable === 'true');
assert.ok(permission, 'Import opens Android all-files access settings');
const pb = bounds(permission); adb('shell', 'input', 'tap', String((pb[0] + pb[2]) >> 1), String((pb[1] + pb[3]) >> 1));
await pause(300); adb('shell', 'input', 'keyevent', '4'); await pause(800);
// The authorized import action resumes after restoring the existing library.
await tap('×'); await tap('全部', true);
assert.equal(userdata().books.length, before.books.length);
assert.deepEqual(userdata().books.map(b => [b.id, b.position, b.marks]), before.books.map(b => [b.id, b.position, b.marks]));
console.log('PASS: first-run system permission grant restores portable data before resuming import');
// The default sync supports both the organized book tree and loose incoming TXT.
adb('shell', 'mkdir', '-p', '/sdcard/Download/LNReader/book/同步分类/同步目录书');
execFileSync(adbPath, ['-s', serial, 'shell', 'sh', '-c', "'cat > /sdcard/Download/LNReader/book/同步分类/同步目录书/EP原名.txt'"], { input: Buffer.from('EP.01 同步章节\n没有缩进的正文。\n\n\n后续正文。\n') });
execFileSync(adbPath, ['-s', serial, 'shell', 'sh', '-c', "'cat > /sdcard/Download/LNReader/散放同步.txt'"], { input: Buffer.from('Episode 1: Sync\nPlain text.\n') });
await tap('同步文件夹');
for (let i = 0; i < 60 && userdata().books.length !== before.books.length + 2; i++) await pause(500);
let synced = userdata(); assert.equal(synced.books.length, before.books.length + 2);
const folderBook = synced.books.find(b => b.title === '同步目录书'); const looseBook = synced.books.find(b => b.title === '散放同步');
assert.ok(folderBook.directory.startsWith('book/同步分类/')); assert.deepEqual(folderBook.parts, ['EP原名.txt']); assert.ok(looseBook.directory.startsWith('book/无分类/'));
await tap('同步文件夹'); await pause(1800); assert.equal(userdata().books.length, synced.books.length, 'Repeated sync keeps IDs and avoids duplicates');
console.log('PASS: default folder sync discovers organized books and loose TXT without duplicating repeated imports');
// Pick a separate source through the real DocumentsUI tree grant.
const countBeforeSource = userdata().books.length;
adb('shell', 'mkdir', '-p', '/sdcard/Download/LNReader_QA_sync');
execFileSync(adbPath, ['-s', serial, 'shell', 'sh', '-c', "'cat > /sdcard/Download/LNReader_QA_sync/SyncSource.txt'"], { input: Buffer.from('EP.1 Source\nContent.\n') });
const sourceCover = userdata().books.find(b => b.title === 'QA多文件');
adb('shell', 'cp', '/sdcard/Download/LNReader/' + sourceCover.directory + '/cover.jpg', '/sdcard/Download/LNReader_QA_sync/SyncSource.jpg');
await tap('书架设置'); await tap('设置同步文件夹');
const rootCrumb = nodes().find(n => n['resource-id']?.endsWith('breadcrumb_text'));
assert.ok(rootCrumb); const rb = bounds(rootCrumb); adb('shell', 'input', 'tap', String((rb[0] + rb[2]) >> 1), String((rb[1] + rb[3]) >> 1)); await pause(300);
await tap('Download');
await tap('LNReader_QA_sync'); await tap('USE THIS FOLDER'); await tap('ALLOW');
assert.ok(nodes().some(n => n.text === 'Download/LNReader_QA_sync'), 'Settings show the selected sync source');
shot('android-0.9.5-shelf-settings.png'); await tap('×'); await tap('同步文件夹');
for (let i = 0; i < 40 && userdata().books.length !== countBeforeSource + 1; i++) await pause(300);
const sourceBook = userdata().books.find(b => b.title === 'SyncSource'); assert.ok(sourceBook && sourceBook.cover); assert.ok(sourceBook.directory.startsWith('book/无分类/'));
execFileSync(adbPath, ['-s', serial, 'shell', 'sh', '-c', "'cat > /sdcard/Download/LNReader_QA_sync/SyncSource.txt'"], { input: Buffer.from('EP.2 Updated\nChanged source.\n') });
await tap('同步文件夹'); await pause(1500);
assert.equal(userdata().books.length, countBeforeSource + 1); assert.equal(userdata().books.find(b => b.title === 'SyncSource').id, sourceBook.id);
assert.equal(adb('exec-out', 'cat', '/sdcard/Download/LNReader/' + sourceBook.directory + '/SyncSource.txt').toString(), 'EP.2 Updated\nChanged source.\n');
await tap('书架设置'); await tap('使用默认备份目录同步'); assert.ok(nodes().some(n => n.text === 'Download/LNReader（默认）')); await tap('×');
console.log('PASS: configurable SAF sync source, same-name cover, fixed backup destination and repeat update with stable book identity');
const log = adb('logcat', '-d', '-t', '1000', '-s', 'AndroidRuntime:E').toString(); assert.ok(!log.includes('FATAL EXCEPTION'), log);
