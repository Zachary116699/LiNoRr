package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static com.lnreader.app.Library.*;

/** Runs only via the LNReader_QA guard. Large counts are actual DB rows, not UI placeholders. */
final class LargeLibraryChecks {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void field(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void until(BooleanSupplier condition,long millis)throws Exception{long end=SystemClock.elapsedRealtime()+millis;while(!condition.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("Timed out");Thread.sleep(30);}}
    private static JSONObject fixture(int i){String id=String.format(Locale.ROOT,"%024x",i+1),name="测试书籍"+String.format(Locale.ROOT,"%06d",i);return json("id",id,"contentHash",String.format(Locale.ROOT,"%064x",i+1),"title",name,"shelf","default","shelfIds",new JSONArray(),"private",i%5==0,"position",0,"length",500000,"marks",new JSONArray(),"added",i,"fileSize",1000000,"directory","book/无分类/"+name,"parts",new JSONArray().put(name+".txt"),"sources",new JSONArray().put(name+".txt"),"organizedTitle",name,"directoryName",name,"cover",false,"chapterCount",100,"contentSignature",id);}
    static String run(Instrumentation test,boolean resuming)throws Exception{
        Context context=test.getTargetContext();StringBuilder report=new StringBuilder();
        File backup=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"LiNoRr_QA_Scale");
        long start=SystemClock.elapsedRealtime();
        if(!resuming){
        BackupFiles.deleteTree(backup);BackupFiles.mkdir(backup);
        File legacy=new File(backup,"userdata.json");
        try(Writer out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(legacy),StandardCharsets.UTF_8))){out.write("{\"version\":1,\"shelves\":[{\"id\":\"default\",\"name\":\"无分类\"}],\"prefs\":");out.write(defaultPrefs().toString());out.write(",\"books\":[");for(int i=0;i<100000;i++){if(i>0)out.write(',');out.write(fixture(i).toString());}out.write("]}");}
        check(legacy.length()>32_000_000,"Fixture must exceed previous cap");start=SystemClock.elapsedRealtime();
        try(BookStore store=new BookStore(backup)){
            JSONObject state=store.importJson(legacy);check(!state.has("books"),"State retains all books");check(store.count()==100000,"Streaming migration lost records");
            report.append("PASS: 100,000 legacy rows / ").append(legacy.length()).append(" bytes migrated in ").append(SystemClock.elapsedRealtime()-start).append("ms\n");
            BookStore.Filter all=new BookStore.Filter("all","","title",true,true);start=SystemClock.elapsedRealtime();List<JSONObject> page=store.page(all,99975,15);check(page.size()==15,"Page size");report.append("PASS: last-page query ").append(SystemClock.elapsedRealtime()-start).append("ms\n");
            check(store.count(new BookStore.Filter("all","测试书籍000099","title",true,true))==1,"Filename/title search");check(store.count(new BookStore.Filter("all","","added",false,false))==80000,"Privacy filter");
            for(int offset=0;offset<100000;offset+=997)store.page(all,offset,15);
            check(((Map<?,?>)field(store,"cache")).size()<=160,"Unbounded loaded-record cache");
            JSONObject one=store.get(String.format(Locale.ROOT,"%024x",100000));set(one,"position",73);store.flush();check(store.count()==100000,"Incremental progress lost books");
            // Batch removal fixture without full text files; preserve 70,000 for the real import exercise.
            store.db.beginTransaction();try{for(int i=70000;i<100000;i++)store.remove(String.format(Locale.ROOT,"%024x",i+1));store.db.setTransactionSuccessful();}finally{store.db.endTransaction();}
            store.meta("portable","true");store.checkpoint();
        }
        }else check(new File(backup,BookStore.FILE).isFile(),"No scale checkpoint to resume");
        context.getSharedPreferences("storage",0).edit().putString("backupPath",backup.getPath()).commit();
        NativeActivity activity=(NativeActivity)test.startActivitySync(new Intent(context,NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->{try{return field(activity,"lib")!=null;}catch(Exception e){return false;}},30000);
        Library library=(Library)field(activity,"lib");
        JSONObject readable=library.book(String.format(Locale.ROOT,"%024x",2));File dir=library.directory(readable);BackupFiles.mkdir(dir);BackupFiles.write(new File(dir,readable.optJSONArray("parts").getString(0)),("第一章 阅读\n"+"可以在导入期间阅读已有书籍。\n".repeat(500)).getBytes(StandardCharsets.UTF_8));
        Class<?> entryClass=Class.forName("com.lnreader.app.NativeActivity$Entry");Constructor<?> ctor=entryClass.getDeclaredConstructor(Uri.class,String.class,long.class);ctor.setAccessible(true);
        List<Object> entries=new ArrayList<>();for(int i=0;i<30000;i++)entries.add(ctor.newInstance(Uri.parse("content://com.lnreader.app.test.scale/"+i),"导入"+i+".txt",100L));
        AtomicReference<Throwable> failure=new AtomicReference<>();
        if(!resuming){
        test.runOnMainSync(()->{try{field(activity,"grouping","separate");field(activity,"cancelImport",false);call(activity,"startImportProgress",new Class<?>[]{});((Dialog)field(activity,"importProgressDialog")).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
        Thread worker=new Thread(()->{try{call(activity,"importEntries",new Class<?>[]{List.class,String.class,String.class,JSONObject.class},entries,"批量","",null);}catch(Throwable e){failure.set(e);}});worker.start();
        until(()->library.store.count()>70020,60000);
        test.runOnMainSync(()->{try{call(activity,"openBook",new Class<?>[]{JSONObject.class,int.class},readable,-1);}catch(Exception e){throw new RuntimeException(e);}});
        until(()->{try{return field(activity,"current")!=null&&((PagedReaderView)field(activity,"pagedReader")).ready();}catch(Exception e){return false;}},30000);
        check(worker.isAlive(),"Reader only opened after import completed");report.append("PASS: opened existing book during import\n");
        field(activity,"cancelImport",true);worker.join(30000);check(!worker.isAlive(),"Cancellation did not stop import");check(failure.get()==null,"Import crashed: "+failure.get());int checkpoint=library.store.count();check(checkpoint>70000&&checkpoint<100000,"Partial imports not committed");
        until(()->{try{return !(Boolean)field(activity,"backgroundImport");}catch(Exception e){return false;}},10000);
        }else{
            check(library.store.count()>=70000,"Incomplete scale baseline");
            report.append("PASS: resumed existing portable catalogue with ").append(library.store.count()).append(" rows across process restart\n");
            test.runOnMainSync(()->{try{call(activity,"openBook",new Class<?>[]{JSONObject.class,int.class},readable,-1);}catch(Exception e){throw new RuntimeException(e);}});
            until(()->{try{return field(activity,"current")!=null&&((PagedReaderView)field(activity,"pagedReader")).ready();}catch(Exception e){return false;}},30000);
            test.runOnMainSync(()->{try{field(activity,"grouping","separate");call(activity,"startImportProgress",new Class<?>[]{});((Dialog)field(activity,"importProgressDialog")).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
        }
        field(activity,"cancelImport",false);start=SystemClock.elapsedRealtime();
        Thread resume=new Thread(()->{try{call(activity,"importEntries",new Class<?>[]{List.class,String.class,String.class,JSONObject.class},entries,"批量","",null);}catch(Throwable e){failure.set(e);}});resume.start();
        long deadline=SystemClock.elapsedRealtime()+1800000;while(resume.isAlive()&&SystemClock.elapsedRealtime()<deadline){resume.join(1000);if(failure.get()!=null)break;}
        check(!resume.isAlive()&&failure.get()==null,"Resume failed: "+failure.get());until(()->{try{return !(Boolean)field(activity,"backgroundImport");}catch(Exception e){return false;}},10000);
        check(library.store.count()==100000,"30,000 unique imports or deduplication failed: "+library.store.count());check(field(activity,"current")!=null,"Import closed reader");
        library.store.flush();report.append("PASS: pause/re-import resume; 30,000 distinct TXT backups, total 100,000 rows; resume ").append(SystemClock.elapsedRealtime()-start).append("ms\n");
        check(new File(backup,"userdata.json").length()<65536,"Hot settings still contain full catalogue");
        File cache=library.cache;File[] dirs=cache.listFiles(File::isDirectory);check(dirs==null||dirs.length<=64,"Import eagerly cached all text");
        // Crash recovery: a ready-to-move import plus its older staging record.
        File stage=library.createDirectory("无分类",".import-"+UUID.randomUUID());BackupFiles.write(new File(stage,"恢复.txt"),"第一章 恢复\n正文".getBytes(StandardCharsets.UTF_8));String hash=BookIdentity.fingerprint(Arrays.asList(new File(stage,"恢复.txt")));JSONObject recovered=json("id",hash.substring(0,24),"contentHash",hash,"title","恢复","directory","book/无分类/恢复","parts",new JSONArray().put("恢复.txt"));
        String older=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();library.store.journal(older,"stage",library.relative(stage),"",null);library.store.journal(operation,"import",library.relative(stage),"book/无分类/恢复",recovered);library.cleanupPending();check(library.book(recovered.optString("id"))!=null&&library.directory(recovered).isDirectory(),"Prepared import was deleted instead of recovered");
        library.store.journal("delete-fixture","delete",recovered.optString("directory"),"",recovered);library.cleanupPending();check(library.book(recovered.optString("id"))==null&&!library.directory(recovered).exists(),"Delete recovery inconsistent");
        report.append("PASS: prepared import and interrupted deletion recovery; bounded caches and incremental metadata\n");
        return report.toString();
    }
}
