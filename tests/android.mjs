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
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1');
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
  if (['全部', '最近阅读'].includes(text)) {
    const shown = await nodes();
    if (!shown.some(n => n.text === '书架')) { const logo = shown.find(n => n['content-desc'] === '打开书架抽屉'); if (logo) { const b = logo.bounds.match(/\d+/g).map(Number); adb('shell', 'input', 'tap', String((b[0] + b[2]) >> 1), String((b[1] + b[3]) >> 1)); await pause(300); } }
  }
  for (let i = 0; i < 8; i++) {
    const node = (await nodes()).find(n => exact ? n.text === text || n['content-desc'] === text : n.text?.includes(text) || n['content-desc']?.includes(text));
    if (node) {
      const nums = node.bounds.match(/\d+/g).map(Number);
      adb('shell', 'input', 'tap', String(Math.round((nums[0] + nums[2]) / 2)), String(Math.round((nums[1] + nums[3]) / 2)));
      await pause(500); return;
    }
    await pause(500);
  }
  throw new Error('Native control not found: ' + text);
}
async function contains(text) { return (await nodes()).some(n => n.text?.includes(text) || n['content-desc']?.includes(text)); }
async function settingsTap(text) {
  for (let i = 0; i < 6; i++) {
    if (await contains(text)) return tap(text, false);
    adb('shell', 'input', 'swipe', String(size[0] * .5), String(size[1] * .82), String(size[0] * .5), String(size[1] * .48), '300'); await pause(250);
  }
  throw new Error('Settings item not visible: ' + text);
}
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
adb('shell', 'appops', 'set', 'com.lnreader.app', 'MANAGE_EXTERNAL_STORAGE', 'deny');
adb('shell', 'appops', 'set', '--uid', 'com.lnreader.app', 'MANAGE_EXTERNAL_STORAGE', 'deny');
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
assert.ok(empty.some(n => n.text === '最近阅读')); assert.ok(!empty.some(n => n.text === '全部'), 'Categories live in the drawer');
assert.ok(!empty.some(n => /离线|本地阅读|调成静音|文字之间|未分类/.test(n.text)));
screenshot('android-0.9.6-empty.png');
const fresh = await homeAndState();
assert.equal(fresh.prefs.size, 18); assert.equal(fresh.prefs.line, 1.4); assert.equal(fresh.prefs.paragraph, .4);
assert.equal(fresh.prefs.readingMode, 'paged'); assert.equal(fresh.prefs.volumeKeys, true);
assert.equal(fresh.prefs.pageAnimation, 'slide');

// Legacy 0.9 metadata also verifies the spacing migration without resetting books.
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
const sample = '第一章 开始\n\n' + Array.from({ length: 36 }, (_, i) => `段落${String(i).padStart(3, '0')}。午后的风轻轻吹过书页。阅读从这一行继续，下一页应接着上一页，没有遗漏的文字。BodyOnlyNeedle\n\n`).join('')
  + '第二章 继续\n\n' + Array.from({ length: 18 }, (_, i) => `继续段落${i}，测试章节衔接和阅读设置。\n\n`).join('');
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
const search = (await nodes()).find(n => n['content-desc'] === '搜索书名或文件名');
const bounds = search.bounds.match(/\d+/g).map(Number);
adb('shell', 'input', 'tap', String((bounds[0] + bounds[2]) >> 1), String((bounds[1] + bounds[3]) >> 1));
adb('shell', 'input', 'text', 'BodyOnlyNeedle'); await pause(300); adb('shell', 'input', 'keyevent', '4');
assert.ok(await contains('未找到书籍'));
adb('shell', 'am', 'force-stop', 'com.lnreader.app'); await launch();
await tap('Beta');
if (await contains('Got it')) await tap('Got it');
const firstPage = await pageDescription();
const firstQuietProgress = (await nodes()).find(n => /^\d+\/\d+ 页/.test(n.text))?.text;
assert.ok((await nodes()).some(n => /\d+\/\d+ 页 · \d+%/.test(n.text)), 'Immersive page count and percentage');
const immersiveNodes = await nodes();
assert.ok(immersiveNodes.some(n => /\d+:\d+.*电量/.test(n['content-desc'])), 'Live clock and battery');
const statusNode = immersiveNodes.find(n => n['content-desc']?.startsWith('时间 '));
assert.ok(Number(statusNode.bounds.match(/\d+/g)[3]) > size[1] * .97, 'Bottom information sits near the physical screen edge');
assert.ok(!immersiveNodes.some(n => /本章|\d+\s*\/\s*\d+\s*章/.test(n.text)), 'No chapter-based progress appears');
assert.ok((await nodes()).some(n => n['content-desc'] === '返回书架'));
function systemBarFlags() {
  const windows = adb('shell', 'dumpsys', 'window', 'windows').toString().split(/  Window #/);
  const app = windows.find(w => w.includes('ty=BASE_APPLICATION') && w.includes('package=com.lnreader.app'));
  assert.ok(app); return app.match(/vsysui=([^\n]+)/)[1].trim().split(/\s+/);
}
assert.ok(systemBarFlags().includes('FULLSCREEN') && systemBarFlags().includes('HIDE_NAVIGATION'));
assert.ok(!(await contains('目录')));
await zone(.5); assert.ok(await contains('目录')); assert.equal(await pageDescription(), firstPage);
const headerButtons = (await nodes()).filter(n => n.class === 'android.widget.ImageButton');
assert.ok(headerButtons.some(n => n['content-desc'] === '返回书架' && n.text === ''));
assert.ok(headerButtons.some(n => n['content-desc'] === '书签' && n.text === ''));
assert.ok(!(await nodes()).some(n => n.text === '书签' || n.text === '＋书签' || n.text === '书架'));
screenshot('android-0.9.6-controls.png');
assert.ok(!systemBarFlags().includes('FULLSCREEN') && !systemBarFlags().includes('HIDE_NAVIGATION'));
await zone(.5); assert.ok(!(await contains('目录'))); assert.equal(await pageDescription(), firstPage);
await zone(.88); const secondPage = await pageDescription(); assert.notEqual(secondPage, firstPage);
await zone(.12); assert.equal(await pageDescription(), firstPage);
adb('shell', 'input', 'swipe', String(Math.round(size[0] * .85)), String(Math.round(size[1] * .5)), String(Math.round(size[0] * .15)), String(Math.round(size[1] * .5)), '300');
await pause(250); assert.equal(await pageDescription(), secondPage);
adb('shell', 'input', 'keyevent', '24'); await pause(250); assert.equal(await pageDescription(), firstPage);
adb('shell', 'input', 'keyevent', '25'); await pause(250); assert.equal(await pageDescription(), secondPage);
adb('shell', 'input', 'keyevent', '24'); await pause(250);
const density = Number(adb('shell', 'wm', 'density').toString().match(/Physical density: (\d+)/)[1]) / 160;
const shortStart = Math.round(size[0] * .5), shortEnd = shortStart - Math.round(20 * density), middleY = Math.round(size[1] * .5);
adb('shell', 'input', 'swipe', String(shortStart), String(middleY), String(shortEnd), String(middleY), '160'); await pause(200);
assert.equal(await pageDescription(), secondPage, 'A short 20dp horizontal swipe turns a page');
adb('shell', 'input', 'swipe', String(shortEnd), String(middleY), String(shortStart), String(middleY), '160'); await pause(200);
assert.equal(await pageDescription(), firstPage);
// Keep a transition active while injecting a burst: every input must advance, even before the prior animation ends.
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '3');
adb('shell', 'input', 'keyevent', '25', '25', '25'); await pause(600);
assert.match(await pageDescription(), /正文，第4\//, 'Rapid repeated inputs are not discarded during animation');
adb('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1');
adb('shell', 'input', 'keyevent', '24', '24', '24'); await pause(300); assert.equal(await pageDescription(), firstPage);
adb('shell', 'input', 'keyevent', '25', '25', '24', '25'); await pause(300);
assert.match(await pageDescription(), /正文，第3\//, 'A rapid direction change preserves every turn');
adb('shell', 'input', 'keyevent', '24'); await pause(250);
screenshot('android-0.9.6-reader.png');
console.log('PASS: horizontal paging, left/right tap zones, middle menu toggle, volume paging and stable page content');
await zone(.5); await tap('下一章'); assert.ok((await pageDescription()).includes('第二章 继续'));
await tap('上一章'); assert.equal(await pageDescription(), firstPage);
await tap('书签'); await tap('添加当前位置书签');
assert.ok(await contains('我的书签')); assert.equal(nativeState().books.find(b => b.title === 'Beta').marks.length, 1);
await tap('添加当前位置书签'); assert.equal(nativeState().books.find(b => b.title === 'Beta').marks.length, 1);
await tap('删除书签第一章 开始'); assert.ok(await contains('还没有书签'));
await tap('添加当前位置书签'); screenshot('android-0.9.6-bookmarks.png');
adb('shell', 'input', 'keyevent', '4'); await tap('目录');
await tap('第二章 继续', false);
assert.ok(await contains('返回阅读'), 'Selecting a chapter keeps the catalog open');
assert.ok((await nodes()).some(n => n['content-desc'] === '第二章 继续，当前章节'));
assert.ok((await nodes()).some(n => n.selected === 'true'), 'Current catalog entry exposes its selected state');
await tap('返回阅读');
const nextChapter = await pageDescription();
assert.ok(firstQuietProgress);
assert.equal(Number(firstQuietProgress.match(/\/(\d+)/)[1]), Number(firstPage.match(/\/(\d+)页/)[1]) + Number(nextChapter.match(/\/(\d+)页/)[1]), 'Whole-book page count uses the exact reader geometry');
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
assert.ok(dialogNodes.some(n => n.text === '平移'));
await tap('平移'); await tap('无动画'); assert.equal(nativeState().prefs.pageAnimation, 'none');
await tap('无动画'); await tap('平移'); assert.equal(nativeState().prefs.pageAnimation, 'slide');
await tap('左右翻页'); await tap('滚动翻页');
const volumeSwitch = (await nodes()).find(n => n.class === 'android.widget.Switch');
const switchBounds = volumeSwitch.bounds.match(/\d+/g).map(Number);
adb('shell', 'input', 'tap', String((switchBounds[0] + switchBounds[2]) >> 1), String((switchBounds[1] + switchBounds[3]) >> 1));
screenshot('android-0.9.6-settings.png');
adb('shell', 'input', 'keyevent', '4'); await pause(300);
assert.ok(!(await nodes()).some(n => n.class?.endsWith('PagedReaderView')));
await zone(.5);
const beforeScroll = nativeState().books.find(b => b.title === 'Beta').position;
adb('shell', 'input', 'swipe', String(Math.round(size[0] * .5)), String(Math.round(size[1] * .78)), String(Math.round(size[0] * .5)), String(Math.round(size[1] * .3)), '400');
// Native fling can outlive the gesture; persistence is debounced until it settles.
await pause(1800);
const scrolled = nativeState(); assert.equal(scrolled.prefs.readingMode, 'scroll'); assert.equal(scrolled.prefs.volumeKeys, false);
assert.ok(scrolled.books.find(b => b.title === 'Beta').position > beforeScroll, `Scroll progress should advance from ${beforeScroll} to ${scrolled.books.find(b => b.title === 'Beta').position}`);
const positionBeforeVolume = scrolled.books.find(b => b.title === 'Beta').position;
adb('shell', 'input', 'keyevent', '25'); await pause(500);
assert.equal(nativeState().books.find(b => b.title === 'Beta').position, positionBeforeVolume);
console.log('PASS: cross-chapter paging, floating dialogs, scroll mode and optional volume handling');
await homeAndState(); adb('shell', 'am', 'force-stop', 'com.lnreader.app'); await launch();
await tap('Beta');
assert.ok(!(await nodes()).some(n => n.class?.endsWith('PagedReaderView')));
await zone(.5); await tap('返回书架');
const saved = nativeState(); assert.equal(saved.books.find(b => b.title === 'Beta').marks.length, 1);
assert.equal(saved.prefs.size, 18); assert.equal(saved.prefs.line, 1.4); assert.equal(saved.prefs.paragraph, .4);
assert.equal(saved.prefs.readingMode, 'scroll'); assert.equal(saved.prefs.volumeKeys, false);
screenshot('android-0.9.6-shelf.png');
// Hierarchy and the expandable, live native RGB picker.
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
const hierarchical = '第一卷 风起\n卷的正文。\n第一章 初见\n章的正文。\n第一节 远行\n' + '节的正文，测试阅读。\n'.repeat(60) + '第二章 归来\n正文。\n第二卷 风止\n正文。\n第三章 尾声\n正文。';
const treeBook = { id: '9'.repeat(24), title: 'Hierarchy', shelf: 'default', length: hierarchical.length, position: 0, marks: [], chapterCount: 6, added: 99, lastRead: Date.now() };
saved.books.push(treeBook); saved.prefs.readingMode = 'paged';
writeNative(treeBook.id + '.txt', hierarchical); writeNative('library.json', JSON.stringify(saved));
await launch(); await tap('Hierarchy'); await zone(.5); await tap('目录');
assert.ok(!(await contains('第一章 初见')), 'Opening catalog at a volume does not expand its descendants');
await tap('全部收起'); assert.ok(!(await contains('第一节 远行'))); assert.ok(await contains('第一卷 风起'));
await tap('展开第一卷 风起'); assert.ok(await contains('第一章 初见')); assert.ok(!(await contains('第一节 远行')));
await tap('展开第一章 初见'); assert.ok(await contains('第一节 远行'));
await tap('第一节 远行');
assert.ok(await contains('返回阅读'));
assert.ok((await nodes()).some(n => n['content-desc'] === '第一节 远行，当前章节'));
assert.ok(!(await nodes()).some(n => n.text.startsWith('●')));
await tap('返回阅读');
await tap('目录');
assert.ok(await contains('第一章 初见')); assert.ok(await contains('第一节 远行')); assert.ok(!(await contains('第三章 尾声')), 'Only ancestors of the current section open automatically');
await tap('全部展开'); assert.ok(await contains('第三章 尾声')); adb('shell', 'input', 'keyevent', '4');
await tap('目录'); assert.ok(!(await contains('第三章 尾声')), 'Reopening resets unrelated expanded branches');
screenshot('android-0.9.6-catalog.png'); adb('shell', 'input', 'keyevent', '4');
await zone(.5);
assert.ok(await contains('第一节 远行')); screenshot('android-0.9.6-immersive.png');
await zone(.5); await tap('设置');
assert.ok((await nodes()).some(n => n['content-desc'] === '字号数值' && n.text === '18'));
await tap('字号数值'); adb('shell', 'input', 'text', '19'); adb('shell', 'input', 'keyevent', '66'); await pause(400);
assert.equal(nativeState().prefs.size, 19, 'Editable numeric settings apply on keyboard completion');
await tap('字号数值'); adb('shell', 'input', 'text', '18'); adb('shell', 'input', 'keyevent', '66'); await pause(400);
assert.equal(nativeState().prefs.size, 18);
await settingsTap('纸白 ▾'); await settingsTap('青竹');
assert.equal(nativeState().prefs.bg, '#e5ebdf');
assert.ok((await nodes()).some(n => n['content-desc'] === '青竹，已选择' && n.selected === 'true'));
assert.ok(!(await nodes()).some(n => n['content-desc'] === 'R颜色滑条'));
await settingsTap('自定义');
// Reveal lower controls in the floating settings sheet.
adb('shell', 'input', 'swipe', String(size[0] * .5), String(size[1] * .84), String(size[0] * .5), String(size[1] * .37), '400'); await pause(350);
const red = (await nodes()).find(n => n['content-desc'] === 'R颜色滑条'); assert.ok(red);
const rb = red.bounds.match(/\d+/g).map(Number);
adb('shell', 'input', 'swipe', String(rb[0] + 15), String((rb[1] + rb[3]) >> 1), String((rb[0] + rb[2]) >> 1), String((rb[1] + rb[3]) >> 1), '500'); await pause(400);
assert.notEqual(nativeState().prefs.bg, '#e5ebdf');
assert.equal(nativeState().prefs.customBackground, true);
await tap('G颜色数值'); adb('shell', 'input', 'text', '64'); adb('shell', 'input', 'keyevent', '4'); await pause(400);
assert.equal(nativeState().prefs.bg.slice(3, 5), '40', 'RGB numerical input updates the background live');
screenshot('android-0.9.6-rgb.png');
adb('shell', 'input', 'keyevent', '4'); await pause(200); await zone(.5);
const quiet = await nodes();
assert.ok(quiet.some(n => n.text === '第一节 远行'));
assert.ok(!quiet.some(n => /本章/.test(n.text)), 'Whole-book page index finishes in background');
adb('shell', 'dumpsys', 'battery', 'set', 'level', '37'); await pause(300);
assert.ok(await contains('电量 37%'), 'Battery information responds to the system broadcast');
adb('shell', 'dumpsys', 'battery', 'reset');
console.log('PASS: hierarchical collapse/expand, immersive information, chapter controls, selected themes and live RGB sliders');
const log = adb('logcat', '-d', '-t', '1000', '-s', 'AndroidRuntime:E').toString();
assert.ok(!log.includes('FATAL EXCEPTION'), log);
console.log('PASS: 0.9.6 native UI, rapid repeated paging, short swipes, header icons, bookmark management and persistent highlighted catalog');
