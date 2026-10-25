import {execFileSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import assert from 'node:assert/strict';
const bin=join(process.env.ANDROID_HOME,'platform-tools/adb');
const serial=execFileSync(bin,['devices']).toString().match(/emulator-\d+/)?.[0];assert.ok(serial);
const adb=(...args)=>execFileSync(bin,['-s',serial,...args],{timeout:90000,maxBuffer:16e6});
assert.match(adb('emu','avd','name').toString(),/^LNReader_QA/);
mkdirSync('artifacts',{recursive:true});
for(const mode of ['allfiles','full','partial']){
 adb('shell','am','force-stop','com.lnreader.app');
 adb('shell','appops','set','com.lnreader.app','MANAGE_EXTERNAL_STORAGE',mode==='allfiles'?'allow':'deny');
 adb('shell','appops','set','--uid','com.lnreader.app','MANAGE_EXTERNAL_STORAGE',mode==='allfiles'?'allow':'deny');
 if(mode==='allfiles'){adb('shell','pm','revoke','com.lnreader.app','android.permission.READ_MEDIA_IMAGES');adb('shell','pm','revoke','com.lnreader.app','android.permission.READ_MEDIA_VISUAL_USER_SELECTED');}
 if(mode==='partial'){adb('shell','pm','revoke','com.lnreader.app','android.permission.READ_MEDIA_IMAGES');adb('shell','pm','grant','com.lnreader.app','android.permission.READ_MEDIA_VISUAL_USER_SELECTED');}
 const result=adb('shell','am','instrument','-w','-e','guard','LNReader_QA','-e','gesture','true','-e','gallery',mode,'com.lnreader.app.test/com.lnreader.app.StorageChecks').toString();
 assert.match(result,/PASS: native album/,result);console.log(mode+': '+result.trim());
 writeFileSync('artifacts/android-0.9.13-gallery-'+mode+'.png',adb('exec-out','run-as','com.lnreader.app','cat','cache/gallery-'+(mode==='partial'?'partial':'full')+'.png'));
 if(mode==='full')for(const name of ['book-cover-actions','sort-spacing'])writeFileSync('artifacts/android-0.9.13-'+name+'.png',adb('exec-out','run-as','com.lnreader.app','cat','cache/'+name+'.png'));
}
adb('shell','pm','grant','com.lnreader.app','android.permission.READ_MEDIA_IMAGES');
adb('shell','appops','set','--uid','com.lnreader.app','MANAGE_EXTERNAL_STORAGE','allow');
