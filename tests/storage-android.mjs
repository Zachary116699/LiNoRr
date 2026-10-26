import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import assert from 'node:assert/strict';

// All destructive setup is guarded by the dedicated emulator name.
const bin = process.env.ADB || join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb');
const serial = process.env.ANDROID_SERIAL || execFileSync(bin, ['devices']).toString().match(/emulator-\d+/)?.[0];
assert.match(serial || '', /^emulator-\d+$/, 'Use the dedicated QA emulator');
const adb = (...args) => execFileSync(bin, ['-s', serial, ...args], { timeout: 60000, maxBuffer: 16e6 });
assert.match(adb('emu', 'avd', 'name').toString(), /^LNReader_QA/, 'Only an isolated QA emulator may be reset');
const scale = process.argv.includes('--scale'), resume = process.argv.includes('--resume'), gesture = process.argv.includes('--gesture');
assert.ok(!resume || scale, '--resume is only valid for the scale checkpoint');
adb('shell', 'am', 'force-stop', 'com.lnreader.app');
if (!resume) adb('shell', 'pm', 'clear', 'com.lnreader.app');
adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk');
adb('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
adb('shell', 'appops', 'set', '--uid', 'com.lnreader.app', 'MANAGE_EXTERNAL_STORAGE', 'allow');
const flags = ['-e', 'guard', 'LNReader_QA'];
if (scale) flags.push('-e', 'scale', 'true');
if (resume) flags.push('-e', 'resume', 'true');
if (gesture) flags.push('-e', 'gesture', 'true');
const report = execFileSync(bin, ['-s', serial, 'shell', 'am', 'instrument', '-w', ...flags, 'com.lnreader.app.test/com.lnreader.app.StorageChecks'], { timeout: scale ? 2400000 : 240000, maxBuffer: 8e6 }).toString();
mkdirSync('artifacts', { recursive: true });
writeFileSync('artifacts/android-0.9.20-' + (scale ? 'scale' : gesture ? 'gestures' : 'storage') + '.log', report);
console.log(report.trim());
assert.ok(!/FAIL:|AssertionError|Process crashed/.test(report), report);
assert.match(report, /PASS:/, report);
