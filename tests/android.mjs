import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
import { join } from 'node:path';

const adbPath = process.env.ADB || join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb');
const devices = execFileSync(adbPath, ['devices']).toString();
const serial = devices.split('\n').map(line => line.split(/\s+/)[0]).find(id => /^emulator-\d+$/.test(id));
assert.ok(serial, 'Native UI tests require an isolated Android emulator; physical devices are excluded.');
const adb = (...args) => execFileSync(adbPath, ['-s', serial, ...args], { maxBuffer: 8 * 1024 * 1024 });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const decode = s => s.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&lt;', '<').replaceAll('&gt;', '>');
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

adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
adb('shell', 'pm', 'clear', 'com.lnreader.app');
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
adb('shell', 'am', 'start', '-n', 'com.lnreader.app/.NativeActivity');
await pause(1500);
assert.ok(await contains('我的书架'));
screenshot('android-shelf-empty.png');
await tap('先读一段，感受一下');
await tap('目录');
await tap('第二章 在路上', false);
assert.ok(await contains('第二章 在路上'));
await tap('＋书签');
await tap('书签');
assert.ok(await contains('第二章 在路上'));
adb('shell', 'input', 'keyevent', '4');
await tap('日 / 夜');
screenshot('android-reader-night.png');
await tap('Aa 设置');
assert.ok(await contains('应用 RGB 背景色'));
await tap('青竹');
adb('shell', 'input', 'keyevent', '4');
screenshot('android-reader.png');
await tap('‹ 书架');
assert.ok(await contains('继续上次的故事'));
const library = JSON.parse(adb('exec-out', 'run-as', 'com.lnreader.app', 'cat', 'files/library/library.json').toString());
assert.equal(library.books.length, 1);
assert.ok(library.books[0].position > 0);
assert.equal(library.books[0].marks.length, 1);
assert.equal(library.prefs.bg, '#e5ebdf');
await tap('＋');
assert.ok(await contains('新建书架'));
adb('shell', 'input', 'keyevent', '4');
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
adb('shell', 'am', 'start', '-n', 'com.lnreader.app/.NativeActivity');
await pause(1200);
assert.ok(await contains('继续上次的故事'));
await tap('继续 ↗');
assert.ok(await contains('第二章 在路上'));
await tap('‹ 书架');
screenshot('android-shelf.png');
const log = adb('logcat', '-d', '-t', '1000', '-s', 'AndroidRuntime:E').toString();
assert.ok(!log.includes('FATAL EXCEPTION'), log);
console.log('PASS: Android native launch, real TextView reader, virtualized chapter catalog, bookmark, night/green modes, RGB controls, shelf creation dialog and persistence after process restart');
