import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { join } from 'node:path';

const adbPath = process.env.ADB || join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb');
const devices = execFileSync(adbPath, ['devices']).toString();
const serial = devices.split('\n').map(line => line.split(/\s+/)[0]).find(id => /^emulator-\d+$/.test(id));
assert.ok(serial, 'Native UI tests require an isolated Android emulator; physical devices are excluded.');
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], { maxBuffer: 8 * 1024 * 1024, timeout: 25000 });
assert.match(adb('emu', 'avd', 'name').toString(), /^LNReader_QA/, 'Use a dedicated LNReader_QA emulator for tests that reset app data.');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));
async function nodes() {
  for (let attempt = 0; ; attempt++) {
    try { adb('shell', 'uiautomator', 'dump', '/sdcard/lnreader-window.xml'); break; }
    catch (error) { if (attempt === 2) throw error; await pause(800); }
  }
  const xml = adb('exec-out', 'cat', '/sdcard/lnreader-window.xml').toString();
  return Array.from(xml.matchAll(/<node\s+([^>]+)>?/g), m => Object.fromEntries(Array.from(m[1].matchAll(/([\w-]+)="([^"]*)"/g), x => [x[1], decode(x[2])])));
}
async function tap(text, exact = true) {
  for (let i = 0; i < 8; i++) {
    const node = (await nodes()).find(n => exact ? n.text === text : n.text?.includes(text));
    if (node) {
      const nums = node.bounds.match(/\d+/g).map(Number);
      adb('shell', 'input', 'tap', String(Math.round((nums[0] + nums[2]) / 2)), String(Math.round((nums[1] + nums[3]) / 2)));
      await pause(500); return;
    }
    await pause(500);
  }
  throw new Error('Native control not found: ' + text);
}
async function contains(text) { return (await nodes()).some(n => n.text?.includes(text)); }
function screenshot(name) { mkdirSync('artifacts', { recursive: true }); writeFileSync('artifacts/' + name, adb('exec-out', 'screencap', '-p')); }
const nativeState = () => JSON.parse(adb('exec-out', 'run-as', 'com.lnreader.app', 'cat', 'files/library/library.json').toString());
function writeNative(name, value) {
  assert.match(name, /^(library\.json|[a-f0-9]{24}\.txt)$/);
  execFileSync(adbPath, ['-s', serial, 'shell', 'run-as', 'com.lnreader.app', 'sh', '-c', `'cat > files/library/${name}'`], { input: Buffer.from(value), timeout: 10000 });
}
const size = adb('shell', 'wm', 'size').toString().match(/(\d+)x(\d+)/).slice(1).map(Number);
async function zone(x) { adb('shell', 'input', 'tap', String(Math.round(size[0] * x)), String(Math.round(size[1] * .5))); await pause(350); }
async function pageDescription() { const view = (await nodes()).find(n => n.class?.endsWith('PagedReaderView')); assert.ok(view, 'Native paged view is visible'); return view['content-desc']; }
async function homeAndState() { adb('shell', 'input', 'keyevent', '3'); await pause(350); return nativeState(); }
async function launch() { adb('shell', 'am', 'start', '-n', 'com.lnreader.app/.NativeActivity'); await pause(800); }

let beforeUpgrade;
try { beforeUpgrade = nativeState(); } catch {}
adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); await launch();
if (beforeUpgrade) {
  const upgraded = await homeAndState();
  assert.equal(upgraded.books.length, beforeUpgrade.books.length);
  assert.deepEqual(upgraded.books.map(b => [b.id, b.position, b.marks]), beforeUpgrade.books.map(b => [b.id, b.position, b.marks]));
  console.log('PASS: in-place APK upgrade keeps existing books, bookmarks and reading positions');
}
adb('shell', 'pm', 'clear', 'com.lnreader.app');
await launch();
const empty = await nodes();
assert.ok(empty.some(n => n.text === 'LNReader'));
assert.ok(empty.some(n => n.text.startsWith('最近阅读')) && empty.some(n => n.text.startsWith('全部')));
assert.ok(!empty.some(n => /离线|本地阅读|调成静音|文字之间|未分类/.test(n.text)));
screenshot('android-0.9.1-empty.png');
const fresh = await homeAndState();
assert.equal(fresh.prefs.size, 20); assert.equal(fresh.prefs.line, 1.5); assert.equal(fresh.prefs.paragraph, .5);
assert.equal(fresh.prefs.readingMode, 'paged'); assert.equal(fresh.prefs.volumeKeys, true);

// Legacy 0.9 metadata also verifies the spacing migration without resetting books.
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
const sample = '第一章 开始\n\n' + Array.from({ length: 36 }, (_, i) => `段落${String(i).padStart(3, '0')}。午后的风轻轻吹过书页。阅读从这一行继续，下一页应接着上一页，没有遗漏的文字。BodyOnlyNeedle\n\n`).join('')
  + '第二章 继续\n\n' + Array.from({ length: 18 }, (_, i) => `第二章段落${i}，测试章节衔接和阅读设置。\n\n`).join('');
const books = ['Alpha', 'Beta', 'Gamma'].map((title, i) => ({ id: (i + 1).toString().repeat(24), title, shelf: 'default', length: sample.length, position: 0, marks: [], chapterCount: 2, added: i + 1, lastRead: i === 2 ? 0 : (i + 1) * 100 }));
for (const book of books) writeNative(book.id + '.txt', sample);
writeNative('library.json', JSON.stringify({ version: 1, books, shelves: [{ id: 'default', name: '未分类' }], prefs: { size: 20, line: 1.9, paragraph: .8, bg: '#f7f3e8', font: '系统默认' } }));
await launch();
const shelf = await nodes();
const names = shelf.filter(n => ['Alpha', 'Beta', 'Gamma'].includes(n.text)).map(n => n.text);
assert.equal(names[0], 'Beta'); assert.ok(names.includes('Alpha')); assert.ok(!names.includes('Gamma'));
assert.ok(!shelf.some(n => n.text.startsWith('未分类')));
console.log('PASS: default recent shelf is ordered by last read and excludes unread books');
await tap('全部', false); assert.ok(await contains('Gamma'));
const search = (await nodes()).find(n => n['content-desc'] === '搜索书名');
const bounds = search.bounds.match(/\d+/g).map(Number);
adb('shell', 'input', 'tap', String((bounds[0] + bounds[2]) >> 1), String((bounds[1] + bounds[3]) >> 1));
adb('shell', 'input', 'text', 'BodyOnlyNeedle'); await pause(300); adb('shell', 'input', 'keyevent', '4');
assert.ok(await contains('未找到书籍'));
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); await launch();
await tap('Beta');
const firstPage = await pageDescription();
assert.ok(!(await contains('目录')));
await zone(.5); assert.ok(await contains('目录')); assert.equal(await pageDescription(), firstPage);
await zone(.5); assert.ok(!(await contains('目录'))); assert.equal(await pageDescription(), firstPage);
await zone(.88); const secondPage = await pageDescription(); assert.notEqual(secondPage, firstPage);
await zone(.12); assert.equal(await pageDescription(), firstPage);
adb('shell', 'input', 'swipe', String(Math.round(size[0] * .85)), String(Math.round(size[1] * .5)), String(Math.round(size[0] * .15)), String(Math.round(size[1] * .5)), '300');
await pause(250); assert.equal(await pageDescription(), secondPage);
adb('shell', 'input', 'keyevent', '24'); await pause(250); assert.equal(await pageDescription(), firstPage);
adb('shell', 'input', 'keyevent', '25'); await pause(250); assert.equal(await pageDescription(), secondPage);
screenshot('android-0.9.1-reader.png');
console.log('PASS: horizontal paging, left/right tap zones, middle menu toggle, volume paging and stable page content');
await zone(.5); await tap('＋书签'); await tap('目录');
await tap('第二章 继续', false);
const nextChapter = await pageDescription();
assert.ok(nextChapter.includes('第二章 继续'));
adb('shell', 'input', 'keyevent', '24'); await pause(250);
assert.ok(!(await pageDescription()).includes('第二章 继续'));
adb('shell', 'input', 'keyevent', '25'); await pause(250); assert.equal(await pageDescription(), nextChapter);
await tap('设置');
const dialogNodes = await nodes();
const close = dialogNodes.find(n => n.text === '×');
const dialogRoot = dialogNodes.find(n => n.class === 'android.widget.ScrollView');
assert.ok(close && dialogRoot);
const dialogBounds = dialogRoot.bounds.match(/\d+/g).map(Number);
assert.ok(dialogBounds[0] > 0 && dialogBounds[2] < size[0], 'Floating dialog keeps side margins');
assert.ok(dialogNodes.some(n => n.text === '音量键翻页'));
await tap('左右翻页'); await tap('滚动翻页');
await tap('音量键翻页');
screenshot('android-0.9.1-settings.png');
adb('shell', 'input', 'keyevent', '4'); await pause(300);
assert.ok(!(await nodes()).some(n => n.class?.endsWith('PagedReaderView')));
await zone(.5);
const beforeScroll = nativeState().books.find(b => b.title === 'Beta').position;
adb('shell', 'input', 'swipe', String(Math.round(size[0] * .5)), String(Math.round(size[1] * .78)), String(Math.round(size[0] * .5)), String(Math.round(size[1] * .3)), '400');
await pause(700);
const scrolled = nativeState(); assert.equal(scrolled.prefs.readingMode, 'scroll'); assert.equal(scrolled.prefs.volumeKeys, false);
assert.ok(scrolled.books.find(b => b.title === 'Beta').position > beforeScroll);
const positionBeforeVolume = scrolled.books.find(b => b.title === 'Beta').position;
adb('shell', 'input', 'keyevent', '25'); await pause(500);
assert.equal(nativeState().books.find(b => b.title === 'Beta').position, positionBeforeVolume);
console.log('PASS: cross-chapter paging, floating dialogs, scroll mode and optional volume handling');
await homeAndState(); adb('shell', 'am', 'force-stop', 'com.lnreader.app'); await launch();
await tap('Beta');
assert.ok(!(await nodes()).some(n => n.class?.endsWith('PagedReaderView')));
await zone(.5); await tap('书架');
const saved = nativeState(); assert.equal(saved.books.find(b => b.title === 'Beta').marks.length, 1);
assert.equal(saved.prefs.size, 20); assert.equal(saved.prefs.line, 1.5); assert.equal(saved.prefs.paragraph, .5);
assert.equal(saved.prefs.readingMode, 'scroll'); assert.equal(saved.prefs.volumeKeys, false);
screenshot('android-0.9.1-shelf.png');
const log = adb('logcat', '-d', '-t', '1000', '-s', 'AndroidRuntime:E').toString();
assert.ok(!log.includes('FATAL EXCEPTION'), log);
console.log('PASS: 0.9.1 native UI, name-only search, legacy preference migration, bookmark and process restart persistence');
