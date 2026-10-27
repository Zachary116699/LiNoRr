package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static com.lnreader.app.Library.*;

/** End-to-end native storage regression for the portable SQLite catalogue. QA emulator only. */
final class FunctionalChecks {
    private final Instrumentation test;
    private NativeActivity activity;
    private FunctionalChecks(Instrumentation test){this.test=test;}
    static String run(Instrumentation test)throws Exception{return new FunctionalChecks(test).run();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object get(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
    private static void put(Object o,String name,Object v){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new RuntimeException(e);}}
    private Object call(String name,Class<?>[] types,Object...args){try{Method m=NativeActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    private void main(Runnable action){test.runOnMainSync(action);}
    private boolean onMain(BooleanSupplier value){boolean[] result={false};main(()->result[0]=value.getAsBoolean());return result[0];}
    private void until(BooleanSupplier value)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(!value.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("Timed out");Thread.sleep(30);}}
    private void idle()throws Exception{until(()->onMain(()->!(Boolean)get(activity,"importing")&&!(Boolean)get(activity,"backgroundImport")));test.waitForIdleSync();}
    private Library library(){return (Library)get(activity,"lib");}
    private Object entry(File file,String original)throws Exception{Class<?> type=Class.forName("com.lnreader.app.NativeActivity$Entry");Constructor<?> ctor=type.getDeclaredConstructor(Uri.class,String.class,long.class);ctor.setAccessible(true);return ctor.newInstance(Uri.fromFile(file),original,file.length());}
    private void importFiles(List<Object> entries,String title,boolean merge)throws Exception{
        main(()->{put(activity,"grouping",merge?"merged":"separate");put(activity,"importPrivate",false);put(activity,"cancelImport",false);call("startImportProgress",new Class<?>[]{});((Dialog)get(activity,"importProgressDialog")).dismiss();});
        ((ExecutorService)get(activity,"io")).submit(()->call("importEntries",new Class<?>[]{List.class,String.class,String.class,JSONObject.class},entries,title,"",null)).get();idle();
    }
    private void organize()throws Exception{main(()->call("organizeAndRefresh",new Class<?>[]{}));idle();}
    private void open(JSONObject book)throws Exception{main(()->call("openBook",new Class<?>[]{JSONObject.class,int.class},book,-1));until(()->onMain(()->get(activity,"current")!=null&&get(activity,"pagedReader")!=null&&((PagedReaderView)get(activity,"pagedReader")).ready()&&!(Boolean)get(activity,"importing")));until(()->onMain(()->get(activity,"pageTotals")!=null));}
    private void back()throws Exception{main(()->call("back",new Class<?>[]{}));test.waitForIdleSync();}
    private void leavePrivacyWithBlockedQuery(JSONObject privateBook)throws Exception{
        until(()->onMain(()->((List<?>)get(activity,"pageBooks")).contains(privateBook)));
        CountDownLatch counted=new CountDownLatch(1);main(()->call("loadShelfCounts",new Class<?>[]{Runnable.class},(Runnable)counted::countDown));check(counted.await(10,TimeUnit.SECONDS),"Privacy counts did not finish");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ((ExecutorService)get(activity,"queryIo")).execute(()->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});check(entered.await(10,TimeUnit.SECONDS),"Query blocker did not start");
        try{
            main(()->call("togglePrivacy",new Class<?>[]{}));test.waitForIdleSync();
            check(onMain(()->!(Boolean)get(activity,"privacyMode")&&((List<?>)get(activity,"pageBooks")).isEmpty()&&((GridView)get(activity,"bookGrid")).getAdapter().getCount()==0&&(Integer)get(activity,"shelfTotal")==0),"Private rows/count remained visible before replacement query");
            main(()->{try{Object adapter=get(activity,"shelfAdapter");ViewGroup parent=(ViewGroup)get(activity,"bookGrid");Class<?> type=Class.forName("com.lnreader.app.NativeActivity$BookCell");Constructor<?> ctor=type.getDeclaredConstructor(NativeActivity.class,boolean.class);ctor.setAccessible(true);View cell=(View)ctor.newInstance(activity,false);Method bind=type.getDeclaredMethod("bind",JSONObject.class);bind.setAccessible(true);bind.invoke(cell,privateBook);check(cell.getVisibility()==View.INVISIBLE&&((TextView)get(cell,"title")).length()==0,"Card binding exposed a private title");cell.performClick();cell.performLongClick();check(!(Boolean)get(activity,"selectionMode"),"Hidden card remained selectable");JSONObject publicBook=library().store.page(new BookStore.Filter("all","","added",false,false),0,1).get(0);bind.invoke(cell,publicBook);check(cell.getVisibility()==View.VISIBLE&&((TextView)get(cell,"title")).getText().toString().equals(publicBook.optString("title")),"Recycled card did not restore public content");}catch(Exception e){throw new RuntimeException(e);}});
        }finally{release.countDown();}
        until(()->onMain(()->!((List<?>)get(activity,"pageBooks")).isEmpty()));
        main(()->{for(Object row:(List<?>)get(activity,"pageBooks"))check(!((JSONObject)row).optBoolean("private"),"Replacement query exposed a private book");});
    }
    private String run()throws Exception{
        Context context=test.getTargetContext();check(Environment.isExternalStorageManager(),"QA storage permission missing");
        File backup=new File(context.getCacheDir(),"functional-backup"),target=new File(context.getCacheDir(),"functional-moved"),inputs=new File(context.getCacheDir(),"functional-input");
        for(File f:new File[]{backup,target,inputs})BackupFiles.deleteTree(f);BackupFiles.mkdir(backup);BackupFiles.mkdir(inputs);
        BackupFiles.write(new File(backup,"userdata.json"),json("version",1,"books",new JSONArray(),"prefs",defaultPrefs(),"shelves",new JSONArray().put(json("id","default","name","无分类"))).toString().getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("storage",0).edit().putString("backupPath",backup.getPath()).commit();
        activity=(NativeActivity)test.startActivitySync(new Intent(context,NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));until(()->get(activity,"lib")!=null);idle();
        String one="EP.01 第一段\r\n"+"第一段原文。\r\n".repeat(80),two="EP.02 第二段\n"+"第二段原文。\n".repeat(80);
        byte[] utf16=("\uFEFF"+one).getBytes(StandardCharsets.UTF_16LE),gb=two.getBytes(Charset.forName("GB18030"));File a=new File(inputs,"一.txt"),b=new File(inputs,"二.txt");BackupFiles.write(a,utf16);BackupFiles.write(b,gb);
        List<Object> pair=Arrays.asList(entry(a,"第一原名.txt"),entry(b,"第二原名.txt"));importFiles(pair,"合集",true);
        Library lib=library();check(lib.store.count()==1,"Merged import failed");JSONObject book=lib.books.get(0);String id=book.optString("id");
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);Thread diskStall=new Thread(()->{try{lib.store.transaction(()->{writing.countDown();release.await(5,TimeUnit.SECONDS);});}catch(Exception e){throw new RuntimeException(e);}});diskStall.start();check(writing.await(5,TimeUnit.SECONDS),"Write stall setup");long updateStarted=SystemClock.elapsedRealtime();try{main(()->set(book,"position",7));check(SystemClock.elapsedRealtime()-updateStarted<1000,"Reading progress waited for another book's disk transaction");}finally{release.countDown();diskStall.join();}lib.store.flush();
        check(Arrays.equals(utf16,java.nio.file.Files.readAllBytes(new File(lib.directory(book),"第一原名.txt").toPath())),"Original UTF16 bytes changed");check(Arrays.equals(gb,java.nio.file.Files.readAllBytes(new File(lib.directory(book),"第二原名.txt").toPath())),"Original GB18030 bytes changed");
        String expected=BookParser.decode(utf16)+BookParser.decode(gb);check(lib.document(book).text.equals(expected)&&lib.document(book).toc.isEmpty(),"Merged source or default EP grammar changed");
        importFiles(Arrays.asList(entry(a,"Renamed-A.txt"),entry(b,"Renamed-B.txt")),"不同书名",true);check(lib.store.count()==1,"Names broke content deduplication");
        main(()->book.remove("contentHash"));lib.store.flush();importFiles(pair,"散列补齐",true);check(lib.store.count()==1&&!book.optString("contentHash").isEmpty(),"Missing legacy fingerprint was not backfilled");
        a.delete();b.delete();check(lib.document(book).text.equals(expected),"Deleting source prevents reading");
        main(()->set(book,"chapterRules",json("kinds",new JSONArray().put("EP."),"custom",new JSONArray())));check(lib.document(book).toc.size()==2,"Per-book EP matching");
        JSONObject first=json("id","first","name","分类一"),second=json("id","second","name","分类二");
        main(()->{lib.shelves.add(first);lib.shelves.add(second);setShelfIds(book,Arrays.asList("first","second"));});organize();check(book.optString("directory").startsWith("book/分类一/")&&shelfIds(book).size()==2,"Multi-category primary folder");
        main(()->set(first,"name","分类改名"));organize();check(book.optString("directory").startsWith("book/分类改名/")&&lib.document(book).text.equals(expected),"Category rename lost source or path");
        main(()->setShelfIds(book,Collections.singletonList("second")));organize();check(book.optString("directory").startsWith("book/分类二/"),"Removing first membership did not relocate book");
        open(book);main(()->{call("jump",new Class<?>[]{int.class},expected.length()/2);call("addMark",new Class<?>[]{});});check(book.optJSONArray("marks").length()==1,"Bookmark add");back();lib.store.flush();check(book.optJSONObject("pagination")!=null,"Persistent pagination missing");
        Library reopened=new Library(context,true);try{JSONObject restored=reopened.book(id);check(restored.optJSONObject("pagination")!=null&&restored.optJSONArray("marks").length()==1,"Reopen lost pagination/bookmark");check(reopened.document(restored).text.equals(expected),"Portable reopen changed text");}finally{reopened.store.close();}
        File index=new File(new File(lib.cache,id),"chapters.index");String signature;try(DataInputStream in=new DataInputStream(new FileInputStream(index))){in.readInt();signature=in.readUTF();}
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(index))){out.writeInt(BookParser.VERSION);out.writeUTF(signature);out.writeInt(1);out.writeUTF("损坏的目录");out.writeInt(-10);out.writeInt(3);out.writeInt(999999);}
        lib.releaseDocument();check(lib.document(book).toc.size()==2,"Invalid chapter offsets were accepted");
        lib.releaseDocument();BackupFiles.deleteTree(lib.cache);BackupFiles.mkdir(lib.cache);open(book);check("userdata".equals(get(activity,"pageIndexSource")),"Cache clear recalculated valid pagination");back();
        List<Object> segments=new ArrayList<>();for(int i=0;i<66;i++){File f=new File(inputs,"segment"+i+".txt");BackupFiles.write(f,("第"+i+"章 段落\n载荷 "+i+"\n").getBytes(StandardCharsets.UTF_8));segments.add(entry(f,"卷/segment"+i+".txt"));}
        importFiles(segments,"多文件",true);JSONObject many=lib.store.page(new BookStore.Filter("all","多文件","added",true,false),0,15).get(0);
        check(many.optInt("_partCount")==66&&lib.partFiles(many).size()==66,"Large part lists were truncated");check(lib.store.count(new BookStore.Filter("all","segment65","title",true,false))==1,"Search missed non-preview original names");check(lib.document(many).text.endsWith("载荷 65\n"),"Many-part natural order changed");
        List<Object> separate=new ArrayList<>();for(int i=0;i<32;i++){File f=new File(inputs,"Part"+i+".txt");BackupFiles.write(f,("第一章 排序 "+i+"\n正文 "+i+"\n").getBytes(StandardCharsets.UTF_8));separate.add(entry(f,f.getName()));}
        importFiles(separate,"分本",false);BookStore.Filter sorted=new BookStore.Filter("all","Part","filename",true,true);check(lib.store.count(sorted)==32&&lib.store.page(sorted,0,15).get(2).optString("title").equals("Part2"),"Natural filename sorting");
        Set<String> separateIds=lib.store.ids(sorted);List<File> separateDirs=new ArrayList<>();for(String selected:separateIds)separateDirs.add(lib.directory(lib.book(selected)));
        main(()->{put(activity,"active","all");put(activity,"query","");call("showShelf",new Class<?>[]{});});until(()->onMain(()->(Integer)get(activity,"shelfTotal")==34&&((List<?>)get(activity,"pageBooks")).size()==15));
        main(()->{put(activity,"selectionMode",true);call("selectFilteredBooks",new Class<?>[]{});});until(()->onMain(()->((Set<?>)get(activity,"selectedBooks")).size()==34));
        main(()->{put(activity,"privacyMode",true);put(activity,"active","second");call("showShelf",new Class<?>[]{});call("openDrawer",new Class<?>[]{});});until(()->onMain(()->get(activity,"drawer")!=null&&((Dialog)get(activity,"drawer")).isShowing()));main(()->{((Dialog)get(activity,"drawer")).dismiss();call("changePrivacy",new Class<?>[]{Set.class,boolean.class},Collections.singleton(id),true);});idle();check(lib.store.count(new BookStore.Filter("all","合集","title",false,false))==0,"Privacy filter exposes hidden book");
        leavePrivacyWithBlockedQuery(book);check(onMain(()->get(activity,"active").equals("all")),"Stale counts exposed private-only shelf title after leaving privacy");main(()->call("openDrawer",new Class<?>[]{}));until(()->onMain(()->get(activity,"drawer")!=null&&((Dialog)get(activity,"drawer")).isShowing()));
        main(()->{Dialog drawer=(Dialog)get(activity,"drawer");ListView list=findList(drawer.getWindow().getDecorView());for(int i=0;i<list.getAdapter().getCount();i++)check(!((JSONObject)list.getAdapter().getItem(i)).optString("id").equals("second"),"Private-only category visible");drawer.dismiss();put(activity,"privacyMode",true);call("changePrivacy",new Class<?>[]{Set.class,boolean.class},Collections.singleton(id),false);});idle();
        Set<String> recentFixtures=new HashSet<>();lib.store.transaction(()->{for(int i=0;i<100;i++){String fake=Library.id("recent-fixture-"+i);recentFixtures.add(fake);lib.store.put(json("id",fake,"title","Recent"+i,"lastRead",i+1,"directory","book/无分类/Recent"+i,"parts",new JSONArray().put("original.txt")));}});
        check(lib.store.count(new BookStore.Filter("recent","","lastRead",true,false))==90,"Recent limit");check(lib.store.count(new BookStore.Filter("recent","Recent0","lastRead",true,false))==0,"Search bypasses recent limit");lib.removeBooks(null,recentFixtures);
        main(()->call("performRemove",new Class<?>[]{Set.class,Runnable.class},separateIds,null));idle();check(lib.store.count()==2,"Batch deletion lost retained books");for(File f:separateDirs)check(!f.exists(),"Removed backup directory remains");check(new File(inputs,"Part0.txt").isFile(),"Removal deleted original input");
        main(()->{for(Dialog d:new ArrayList<>(((Map<Dialog,?>)get(activity,"dialogShells")).keySet()))d.dismiss();for(int i=0;i<20;i++){Dialog d=(Dialog)call("sheet",new Class<?>[]{String.class,LinearLayout.class},"弹窗回归",new LinearLayout(activity));d.dismiss();}});test.waitForIdleSync();check(((Map<?,?>)get(activity,"dialogShells")).isEmpty(),"Dismissed dialogs remain strongly retained");
        JSONObject payload=json("text","字".repeat(32767)+"😀"+"文".repeat(6000));lib.store.journal("chunk-test","import","x","y",payload);check(new JSONObject(lib.store.operation("chunk-test").getString("payload")).getString("text").equals(payload.getString("text")),"Chunked journal splits Unicode");
        lib.store.db.execSQL("CREATE TRIGGER qa_finish_failure BEFORE DELETE ON operations WHEN OLD.id='chunk-test' BEGIN SELECT RAISE(ABORT,'injected'); END");boolean rolledBack=false;try{lib.store.finish("chunk-test");}catch(android.database.sqlite.SQLiteException e){rolledBack=true;}finally{lib.store.db.execSQL("DROP TRIGGER qa_finish_failure");}check(rolledBack&&new JSONObject(lib.store.operation("chunk-test").getString("payload")).getString("text").equals(payload.getString("text")),"Partial journal cleanup destroyed its payload");lib.store.finish("chunk-test");check(lib.store.operation("chunk-test")==null,"Journal cleanup incomplete");
        File moved=new File(lib.directory(book).getParentFile(),"恢复移动");JSONObject beforeMove=new JSONObject(book.toString());set(beforeMove,"directory",lib.relative(moved));set(beforeMove,"directoryName",moved.getName());set(beforeMove,"organizedTitle",book.optString("title"));set(beforeMove,"position",10);set(beforeMove,"marks",new JSONArray());lib.store.journal("move-test","move",book.optString("directory"),lib.relative(moved),beforeMove);
        main(()->{set(book,"position",200);set(book,"marks",new JSONArray().put(json("offset",200,"title","移动失败后新增的书签")));});lib.store.flush();lib.cleanupPending();check(book.optInt("position")==200&&book.optJSONArray("marks").length()==1&&lib.directory(book).equals(moved.getCanonicalFile()),"Move recovery overwrote later progress/bookmarks");
        JSONObject oldSplit=new JSONObject(book.toString());set(oldSplit,"padding","字".repeat(40000));lib.store.journal("old-split","move","book/无分类/旧路径",book.optString("directory"),oldSplit);lib.store.db.delete("operation_payload","operation=?",new String[]{"old-split"});lib.cleanupPending();check(lib.store.operation("old-split")==null&&book.optInt("position")==200,"Completed old split cleanup still blocks imports or overwrites progress");
        main(()->call("changeBackupFolder",new Class<?>[]{File.class},target));idle();check(lib.backup.equals(target.getCanonicalFile())&&lib.store.count()==2,"Backup relocation lost catalogue");check(!new File(backup,"book").exists()&&lib.document(lib.book(id)).text.equals(expected),"Backup relocation lost raw files");
        File after=new File(inputs,"after.txt");BackupFiles.write(after,"第一章 迁移后\n迁移后的独立正文\n".getBytes(StandardCharsets.UTF_8));importFiles(Collections.singletonList(entry(after,"after.txt")),"迁移后",false);check(lib.store.count()==3,"Staging still points to old backup root");
        File corrupt=new File(context.getCacheDir(),"functional-corrupt");BackupFiles.deleteTree(corrupt);BackupFiles.mkdir(corrupt);byte[] broken=new byte[4096];Arrays.fill(broken,(byte)97);File brokenDb=new File(corrupt,BookStore.FILE);BackupFiles.write(brokenDb,broken);boolean rejected=false;try(BookStore invalid=new BookStore(corrupt)){}catch(Exception e){rejected=true;}check(rejected&&Arrays.equals(broken,java.nio.file.Files.readAllBytes(brokenDb.toPath())),"Corrupt database was silently replaced");BackupFiles.deleteTree(corrupt);
        return "PASS: v2 native storage, original encodings, merged dedup/backfill, multi-category moves, source independence, persistent pagination/bookmarks, cache recovery, many-part filenames, natural sorting, cross-page selection, privacy, recent limit, physical batch removal, bounded dialogs, chunked journals and backup relocation\n";
    }
    private static ListView findList(View view){if(view instanceof ListView)return (ListView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ListView match=findList(((ViewGroup)view).getChildAt(i));if(match!=null)return match;}return null;}
}
