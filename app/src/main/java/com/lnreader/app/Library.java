package com.lnreader.app;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;

final class Library {
    final File root;
    volatile File backup;
    final File cache;
    private final Context context;
    volatile boolean portableReady;
    private volatile Document warm;
    volatile String maintenanceWarning = "";
    JSONObject state;
    BookStore store;
    final List<JSONObject> shelves = new ArrayList<>();
    final Set<String> pendingPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,String> shelfNames = new HashMap<>();
    private File importRoot;
    private int stagingCreates;
    final List<JSONObject> books = new AbstractList<JSONObject>() {
        public int size() { return store.count(); }
        public JSONObject get(int i) { List<JSONObject> page = store.page(new BookStore.Filter("all","","added",true,true),i,1); if (page.isEmpty()) throw new IndexOutOfBoundsException(); return page.get(0); }
        public boolean add(JSONObject b) { try {store.put(b);return true;}catch(Exception e){throw new IllegalStateException(e);} }
        @Override public Iterator<JSONObject> iterator() { Iterator<String> ids=store.allIds().iterator();return new Iterator<JSONObject>(){public boolean hasNext(){return ids.hasNext();}public JSONObject next(){return store.get(ids.next());}}; }
    };
    Library(Context context) throws Exception { this(context, false); }
    Library(Context context, boolean preferPortable) throws Exception {
        this.context = context;
        root = new File(context.getFilesDir(), "library"); if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建书库");
        cache = new File(context.getCacheDir(), "books"); BackupFiles.mkdir(cache);
        String migrationWarning="";if(canWrite())try{BackupMigration.recover(context);}catch(Exception e){migrationWarning="上次迁移尚待清理："+e.getMessage();}
        if(canWrite())BackupMigration.verifyActive(context);
        BackupLocation.Choice location = BackupLocation.choose(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), context.getSharedPreferences("storage", 0).getString("backupPath", ""), canWrite());
        backup = location.directory; maintenanceWarning = location.warning;
        if(!migrationWarning.isEmpty())maintenanceWarning=migrationWarning;
        if (canWrite()) context.getSharedPreferences("storage", 0).edit().putString("backupPath", backup.getPath()).apply();
        File metadata = new File(root, "library.json");
        File portable = new File(backup, "userdata.json");
        if (canWrite() && portable.isFile()) { if (preferPortable || !metadata.isFile() || portable.lastModified() >= metadata.lastModified()) metadata = portable; portableReady = true; }
        if(canWrite()&&!preferPortable&&!portable.isFile()&&!new File(backup,BookStore.FILE).exists()&&new File(root,BookStore.FILE).isFile()){
            try(BookStore privateStore=new BookStore(root)){privateStore.checkpoint();}BackupFiles.mkdir(backup);BackupFiles.copy(new File(root,BookStore.FILE),new File(backup,BookStore.FILE));
        }
        store = new BookStore(canWrite() ? backup : root);
        if(!store.file.getParentFile().equals(root)&&store.meta("state")==null&&store.count()==0&&!portable.isFile()&&new File(root,BookStore.FILE).isFile()){
            store.close();try(BookStore privateStore=new BookStore(root)){privateStore.checkpoint();}BackupFiles.copy(new File(root,BookStore.FILE),new File(backup,BookStore.FILE));store=new BookStore(backup);
        }
        try {
        String saved = store.meta("state");
        if (saved == null) { state = metadata.exists() ? store.importJson(metadata) : json(); store.meta("state",state.toString()); }
        else state = new JSONObject(saved);
        portableReady = canWrite() && !"false".equals(store.meta("portable"));
        if (saved == null && metadata.exists() && !metadata.equals(portable)) portableReady = false;
        if("true".equals(store.meta("organizing")))portableReady=false;
        store.meta("portable",Boolean.toString(portableReady));
        JSONArray ss = state.optJSONArray("shelves");
        if (ss != null) for (int i = 0; i < ss.length(); i++) if (ss.optJSONObject(i) != null) shelves.add(ss.optJSONObject(i));
        if (shelves.isEmpty()) shelves.add(json("id", "default", "name", "未分类"));
        if (state.optJSONObject("prefs") == null) set(state, "prefs", defaultPrefs());
        JSONObject prefs = prefs();
        if (!prefs.has("readingMode")) {
            // Migrate previous defaults while retaining explicitly customized spacing.
            if (Math.abs(prefs.optDouble("line", 1.5) - 1.9) < .001) set(prefs, "line", 1.5);
            if (Math.abs(prefs.optDouble("paragraph", .5) - .8) < .001) set(prefs, "paragraph", .5);
            set(prefs, "readingMode", "paged");
        }
        if (!prefs.has("volumeKeys")) set(prefs, "volumeKeys", true);
        migrateDefaults(prefs);
        if (!prefs.has("pageAnimation")) set(prefs, "pageAnimation", "slide");
        if (!prefs.has("night")) { set(prefs, "night", prefs.optBoolean("shelfNight") || prefs.optString("bg").equalsIgnoreCase("#20231f")); if (prefs.optString("bg").equalsIgnoreCase("#20231f")) set(prefs, "bg", "#f7f3e8"); prefs.remove("shelfNight"); }
        JSONObject defaults = defaultPrefs(); for (Iterator<String> keys = defaults.keys(); keys.hasNext();) { String key = keys.next(); if (!prefs.has(key)) set(prefs, key, defaults.opt(key)); }
        if (state.optJSONArray("removedBooks") != null && state.optJSONArray("removedBooks").length() == 0) state.remove("removedBooks");
        for (JSONObject shelf : shelves) if (shelf.optString("id").equals("default")) set(shelf, "name", "无分类");
        Set<String> valid = new HashSet<>(); for (JSONObject shelf : shelves) if (!shelf.optString("id").equals("default")) valid.add(shelf.optString("id"));
        for (JSONObject shelf : shelves) shelfNames.put(shelf.optString("id"),shelf.optString("name"));
        store.changed = (b,key) -> { if (key.equals("title") || key.equals("shelfIds") || key.equals("shelf")) pendingPaths.add(b.optString("id")); };
        }catch(Exception e){try{store.close();}catch(Exception close){e.addSuppressed(close);}throw e;}
    }
    /** Ordered membership is portable; the first category owns the single physical copy. */
    static List<String> shelfIds(JSONObject book) {
        LinkedHashSet<String> result = new LinkedHashSet<>(); JSONArray ids = book.optJSONArray("shelfIds");
        if (ids != null) { for (int i = 0; i < ids.length(); i++) { String id = ids.optString(i); if (!id.isEmpty() && !id.equals("default")) result.add(id); } }
        else { String id = book.optString("shelf"); if (!id.isEmpty() && !id.equals("default")) result.add(id); }
        return new ArrayList<>(result);
    }
    static void setShelfIds(JSONObject book, Collection<String> ids) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(ids); unique.remove("default"); unique.remove("");
        if(book.has("shelfIds")&&new ArrayList<>(unique).equals(shelfIds(book))&&book.optString("shelf").equals(unique.isEmpty()?"default":unique.iterator().next()))return;
        set(book, "shelfIds", new JSONArray(unique)); set(book, "shelf", unique.isEmpty() ? "default" : unique.iterator().next());
    }
    static void migrateDefaults(JSONObject prefs) {
        if (prefs.optInt("defaultsVersion") >= 2) return;
        if (Math.abs(prefs.optDouble("size", 20) - 20) < .001) set(prefs, "size", 18);
        if (Math.abs(prefs.optDouble("line", 1.5) - 1.5) < .001) set(prefs, "line", 1.4);
        if (Math.abs(prefs.optDouble("paragraph", .5) - .5) < .001) set(prefs, "paragraph", .4);
        set(prefs, "defaultsVersion", 2);
    }
    static JSONObject defaultPrefs() { return json("size", 18, "line", 1.4, "paragraph", .4, "bg", "#f7f3e8", "font", "系统默认", "readingMode", "paged", "volumeKeys", true, "defaultsVersion", 2, "pageAnimation", "slide"); }
    synchronized void save() throws Exception {
        saveSnapshot(snapshot());
    }
    String snapshot() { state.remove("books"); state.remove("removedBooks"); set(state, "version", 2); set(state,"catalog",BookStore.FILE); set(state, "shelves", new JSONArray(shelves)); return state.toString(); }
    synchronized void saveSnapshot(String value) throws IOException {
        try{store.flush();}catch(Exception e){throw new IOException("无法更新书籍记录",e);}store.meta("state",value);byte[] bytes=value.getBytes(StandardCharsets.UTF_8);atomic(new File(root,"library.json"),bytes);
        if (portableReady && canWrite()) { BackupFiles.mkdir(backup); atomic(new File(backup, "userdata.json"), bytes); }
    }
    boolean canWrite() { return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED; }
    File directory(JSONObject book) throws IOException { return BackupFiles.child(backup, book.optString("directory")); }
    File coverFile(JSONObject book) { try { File file = new File(directory(book), "cover.jpg"); if (file.isFile()) return file; } catch (Exception ignored) {} return new File(root, book.optString("id") + ".jpg"); }
    File createDirectory(String shelf, String title) throws IOException {
        if (!canWrite()) throw new IOException("请先授权文件读写权限");
        File category = new File(new File(backup, "book"), BackupFiles.name(shelf)); BackupFiles.mkdir(category);
        File directory = BackupFiles.available(category, title); BackupFiles.mkdir(directory); return directory;
    }
    String relative(File file)throws IOException{java.nio.file.Path base=backup.toPath().toAbsolutePath().normalize(),child=file.toPath().toAbsolutePath().normalize();if(child.equals(base)||!child.startsWith(base))throw new IOException("无效书库路径");return base.relativize(child).toString().replace(File.separatorChar,'/');}
    /** Only changed books are organized; each filesystem change has a durable recovery record. */
    JSONObject prepareBackup(JSONObject ignored) throws Exception {
        if (!canWrite()) throw new IOException("尚未授权文件读写权限");
        store.meta("organizing","true");store.meta("state",state.toString());
        store.flush();
        Set<String> ids = portableReady ? new LinkedHashSet<>(pendingPaths) : new LinkedHashSet<>(store.allIds());
        Map<String,String> names = new HashMap<>();
        for (JSONObject shelf : shelves) {
            String id = shelf.optString("id"), name = shelf.optString("name"); names.put(id,name);
            if (!Objects.equals(name,shelfNames.get(id))) ids.addAll(store.ids(new BookStore.Filter(id,"","added",true,false)));
        }
        for (String id : ids) {
            interrupted(); JSONObject b = book(id); if (b == null) continue;
            String categoryName = names.getOrDefault(b.optString("shelf"),"无分类");
            if (b.optString("shelf").equals("default")) categoryName = "无分类";
            File category = new File(new File(backup,"book"),BackupFiles.name(categoryName)); BackupFiles.mkdir(category);
            File original = b.optString("directory").isEmpty() ? null : directory(b);
            if (original == null) {
                File legacy = textFile(id); if (!legacy.isFile()) throw new IOException("找不到正文：" + b.optString("title"));
                File stage = createDirectory(categoryName,".import-" + UUID.randomUUID());
                String filename = BackupFiles.name(b.optString("title") + ".txt");
                BackupFiles.copy(legacy,new File(stage,filename));
                File oldCover = new File(root,id + ".jpg"); if (oldCover.isFile()) BackupFiles.copy(oldCover,new File(stage,"cover.jpg"));
                JSONObject next = new JSONObject(b.toString()); set(next,"parts",new JSONArray().put(filename));
                commitImport(stage,next,categoryName);
            } else if (!original.getParentFile().equals(category) || !b.optString("organizedTitle",b.optString("title")).equals(b.optString("title"))) {
                File target = BackupFiles.available(category,b.optString("title"));
                JSONObject next = new JSONObject(b.toString()); set(next,"directory",relative(target)); set(next,"directoryName",target.getName()); set(next,"organizedTitle",b.optString("title"));
                String operation = UUID.randomUUID().toString(); store.journal(operation,"move",relative(original),relative(target),next);
                java.nio.file.Files.move(original.toPath(),target.toPath());
                for (String key : new String[]{"directory","directoryName","organizedTitle"}) set(b,key,next.opt(key));
                store.flush(); store.finish(operation);
            }
            pendingPaths.remove(id);
        }
        shelfNames.clear(); shelfNames.putAll(names); portableReady = true; store.meta("portable","true");
        saveSnapshot(snapshot());store.meta("organizing","false");pruneEmptyCategories(backup);return state;
    }
    void applyPaths(JSONObject ignored) { /* Records are updated directly by ID. */ }
    private static void pruneEmptyCategories(File backup) {
        File[] categories = new File(backup,"book").listFiles();
        if(categories!=null)for(File category:categories)if(category.isDirectory())try(java.nio.file.DirectoryStream<java.nio.file.Path> children=java.nio.file.Files.newDirectoryStream(category.toPath())){if(!children.iterator().hasNext())java.nio.file.Files.deleteIfExists(category.toPath());}catch(IOException ignored){}
    }
    interface Progress { void update(int completed, int total, String label); }
    JSONObject removeBooks(JSONObject ignored, Set<String> ids) throws Exception { return removeBooks(ignored,ids,(d,t,l)->{}); }
    JSONObject removeBooks(JSONObject ignored, Set<String> ids, Progress progress) throws Exception {
        if (!canWrite()) throw new IOException("请先授权文件读写权限");
        store.flush();maintenanceWarning="";int done=0;Iterator<String> remaining=ids.iterator();
        while(remaining.hasNext()){
            interrupted();Map<String,JSONObject> chunk=new LinkedHashMap<>();
            for(int i=0;i<64&&remaining.hasNext();i++){String id=remaining.next();JSONObject b=book(id);if(b!=null)chunk.put(UUID.randomUUID().toString(),b);}
            store.transaction(()->{for(Map.Entry<String,JSONObject> item:chunk.entrySet()){JSONObject b=item.getValue();store.journal(item.getKey(),"delete",b.optString("directory"),"",b);store.remove(b.optString("id"));pendingPaths.remove(b.optString("id"));}});
            store.transaction(()->{for(Map.Entry<String,JSONObject> item:chunk.entrySet())try{finishDelete(item.getKey(),item.getValue());}catch(IOException error){maintenanceWarning="书籍已移除，待清理的文件将在下次启动重试";}});
            done+=chunk.size();progress.update(done,ids.size(),"正在移除备份和记录…");
        }
        cleanupLegacyCache(ids); warm=null; saveSnapshot(snapshot()); pruneEmptyCategories(backup); return state;
    }
    private void finishDelete(String operation,JSONObject b) throws Exception {
        String directory=b.optString("directory"), id=b.optString("id");
        if (!directory.isEmpty() && store.directoryReferences(directory)==0) {
            if(!directory.startsWith("book/"))throw new IOException("无效的书籍路径");
            BackupFiles.deleteTree(BackupFiles.child(new File(backup,"book"),directory.substring(5)));
        }
        if(id.matches("[a-f0-9]{24}")) {
            BackupFiles.deleteTree(new File(cache,id));
            java.nio.file.Files.deleteIfExists(textFile(id).toPath());
            java.nio.file.Files.deleteIfExists(new File(root,id+".jpg").toPath());
        }
        store.finish(operation);
    }
    private void cleanupLegacyCache(Set<String> ids) {
        File[] files=cache.listFiles(); if(files==null)return;
        for(File f:files) {String name=f.getName();if(name.length()>=24 && ids.contains(name.substring(0,24)))try{BackupFiles.deleteTree(f);}catch(IOException ignored){}}
    }
    JSONObject relocate(File requested,JSONObject ignored) throws Exception {
        if(!canWrite())throw new IOException("请先授权文件读写权限");
        File target=requested.getCanonicalFile(),oldRoot=backup.getCanonicalFile();
        if(target.equals(oldRoot))return state;
        if(target.toPath().startsWith(oldRoot.toPath())||oldRoot.toPath().startsWith(target.toPath()))throw new IOException("请选择与当前书库互不包含的文件夹");
        BackupFiles.mkdir(target);saveSnapshot(snapshot());store.checkpoint();
        BackupMigration.Session migration=new BackupMigration.Session(context,oldRoot,target);BookStore replacement=null;
        try{
            for(String name:new String[]{"book",".imports"}){File source=new File(oldRoot,name);if(source.exists())BackupFiles.copyTree(source,new File(migration.stage,name));}
            store.checkpoint();BackupFiles.copy(store.file,new File(migration.stage,BookStore.FILE));
            BackupFiles.copy(new File(oldRoot,"userdata.json"),new File(migration.stage,"userdata.json"));
            try(BookStore staged=new BookStore(migration.stage)){staged.verify();if(staged.count()!=store.count())throw new IOException("迁移目录记录数量不一致");staged.meta("migrationId",migration.plan.optString("id"));staged.checkpoint();}
            migration.publish();
            replacement=new BookStore(target);replacement.verify();replacement.checkpoint();
            migration.commit();
        }catch(Exception error){
            if(replacement!=null)try{replacement.close();}catch(Exception close){error.addSuppressed(close);}
            try{migration.rollback();}catch(Exception cleanup){error.addSuppressed(cleanup);}
            throw error;
        }
        // From this point cleanup errors cannot change or misreport the committed active library.
        BookStore previous=store;store=replacement;backup=target;importRoot=null;stagingCreates=0;
        store.changed=(b,k)->{if(k.equals("title")||k.equals("shelfIds")||k.equals("shelf"))pendingPaths.add(b.optString("id"));};
        try{previous.close();BackupMigration.recover(context);}catch(Exception error){maintenanceWarning="迁移已完成，旧副本将在下次启动继续清理："+error.getMessage();}
        return state;
    }
    static boolean emptyPortable(File folder) {
        File database=new File(folder,BookStore.FILE);
        if(database.isFile())try(BookStore s=new BookStore(folder)){return s.count()==0;}catch(Exception e){return false;}
        try {JSONObject value=new JSONObject(new String(read(new FileInputStream(new File(folder,"userdata.json")),1_000_000),StandardCharsets.UTF_8));return value.optJSONArray("books")!=null&&value.optJSONArray("books").length()==0;}catch(Exception e){return false;}
    }
    /** Recovery completes only journaled work. Legacy unidentifiable .removed folders are preserved. */
    void cleanupPending() {
        if(!canWrite())return;
        if(store.meta("legacyCacheCleaned")==null)try(java.nio.file.DirectoryStream<java.nio.file.Path> files=java.nio.file.Files.newDirectoryStream(cache.toPath())){for(java.nio.file.Path f:files){String name=f.getFileName().toString();if(java.nio.file.Files.isRegularFile(f)&&name.matches("[a-f0-9]{24}(?:\\..*|-.*)"))java.nio.file.Files.deleteIfExists(f);}store.meta("legacyCacheCleaned","true");}catch(IOException ignored){}
        for(String operationID:store.operationIds())try {
            JSONObject op=store.operation(operationID);if(op==null)continue;
            interrupted(); String kind=op.optString("kind"), operation=op.getString("id");
            if(kind.equals("stage")) {File stage=BackupFiles.child(backup,op.getString("source"));if(stage.getName().startsWith(".import-"))BackupFiles.deleteTree(stage);store.finish(operation);continue;}
            if(op.optString("payload").isEmpty()){
                // Repair the old split-cleanup window only when persisted state proves completion.
                if(kind.equals("import")||kind.equals("move")){JSONObject existing=store.atDirectory(op.optString("target"));boolean complete=existing!=null;if(complete)for(File part:partFiles(existing))complete&=part.isFile();if(complete){store.finish(operation);continue;}}
                else if(kind.equals("delete")&&store.directoryReferences(op.optString("source"))==0&&!BackupFiles.child(backup,op.optString("source")).exists()){store.finish(operation);continue;}
                throw new IOException("恢复日志缺少正文，尚不能确认操作是否完成");
            }
            JSONObject b=new JSONObject(op.getString("payload"));
            if(kind.equals("delete")) {store.remove(b.optString("id"));finishDelete(operation,b);}
            else {
                if(kind.equals("move")){JSONObject latest=book(b.optString("id"));if(latest==null||!latest.optString("directory").equals(op.optString("source"))&&!latest.optString("directory").equals(op.optString("target"))){store.finish(operation);continue;}}
                File from=BackupFiles.child(backup,op.getString("source")),target=BackupFiles.child(backup,op.getString("target"));
                if(!target.exists()&&from.exists())java.nio.file.Files.move(from.toPath(),target.toPath());
                if(target.isDirectory()) {
                    if(kind.equals("move")){
                        JSONObject latest=book(b.optString("id"));
                        if(latest==null){maintenanceWarning="移动恢复找不到书籍记录："+b.optString("title");continue;}
                        // A move intent owns paths, never reading history edited after the intent.
                        for(String key:new String[]{"directory","directoryName","organizedTitle"})if(b.has(key))set(latest,key,b.opt(key));
                        store.flush();
                    }else store.put(b);
                    store.finish(operation);
                }else maintenanceWarning="部分备份任务需要检查："+b.optString("title");
            }
        }catch(Exception error){maintenanceWarning="部分备份任务未完成："+error.getMessage();}
        Set<String> protectedStages=store.importSources();
        File staging=new File(backup,".imports");if(staging.isDirectory())try(java.nio.file.DirectoryStream<java.nio.file.Path> dirs=java.nio.file.Files.newDirectoryStream(staging.toPath())){for(java.nio.file.Path path:dirs)if(!protectedStages.contains(relative(path.toFile())))BackupFiles.deleteTree(path.toFile());}catch(IOException e){maintenanceWarning="部分导入暂存文件未清理，将在下次启动重试";}
    }
    static final class PendingImport {
        final File stage;final JSONObject book;final String category,operation=UUID.randomUUID().toString();File target;
        PendingImport(File stage,JSONObject book,String category){this.stage=stage;this.book=book;this.category=category;}
    }
    File createStaging()throws IOException{if(importRoot==null||stagingCreates++%32==0){importRoot=BackupFiles.child(backup,".imports");BackupFiles.mkdir(importRoot);}File dir=new File(importRoot,UUID.randomUUID().toString());java.nio.file.Files.createDirectory(dir.toPath());return dir;}
    void commitImport(File stage,JSONObject book,String category)throws Exception{commitImports(Collections.singletonList(new PendingImport(stage,book,category)));}
    void commitImports(List<PendingImport> pending)throws Exception{
        if(pending.isEmpty())return;Set<String> reserved=new HashSet<>();Map<String,File> categories=new HashMap<>();
        for(PendingImport item:pending){File category=categories.get(item.category);if(category==null){category=BackupFiles.child(backup,"book/"+BackupFiles.name(item.category));BackupFiles.mkdir(category);categories.put(item.category,category);}item.target=BackupFiles.available(category,item.book.optString("title"),reserved);reserved.add(item.target.getAbsolutePath());set(item.book,"directory",relative(item.target));set(item.book,"directoryName",item.target.getName());set(item.book,"organizedTitle",item.book.optString("title"));set(item.book,"cover",new File(item.stage,"cover.jpg").isFile());}
        // Two group commits: durable move intents, then metadata after all renames. No per-book fsync storm.
        store.transaction(()->{for(PendingImport item:pending)store.journal(item.operation,"import",relative(item.stage),relative(item.target),item.book);});
        for(PendingImport item:pending){interrupted();java.nio.file.Files.move(item.stage.toPath(),item.target.toPath());}
        store.transaction(()->{for(PendingImport item:pending){store.put(item.book);store.finish(item.operation);}});
    }
    static void interrupted() throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("操作已暂停，已完成的书籍已保留");}
    File bookCache(String id) throws IOException { if(!id.matches("[a-f0-9]{24}"))throw new IOException("无效书籍编号");File dir=new File(cache,id);BackupFiles.mkdir(dir);return dir; }
    void trimCache(String keep) {
        File[] dirs=cache.listFiles(File::isDirectory);if(dirs==null)return;
        Arrays.sort(dirs,Comparator.comparingLong(File::lastModified));long total=0;
        for(File dir:dirs){File[] files=dir.listFiles();if(files!=null)for(File f:files)total+=f.length();}
        int retained=dirs.length;for(File dir:dirs){if(total<=128L*1024*1024&&retained<=64)break;if(dir.getName().equals(keep))continue;File[] files=dir.listFiles();if(files!=null)for(File f:files)total-=f.length();try{BackupFiles.deleteTree(dir);retained--;}catch(IOException ignored){}}
    }
    static BookParser.Rules chapterRules(JSONObject book) {
        JSONObject config = book.optJSONObject("chapterRules"); if (config == null) return BookParser.Rules.defaults();
        List<String> enabled = new ArrayList<>(); JSONArray kinds = config.optJSONArray("kinds"); if (kinds != null) for (int i = 0; i < kinds.length(); i++) enabled.add(kinds.optString(i));
        List<BookParser.Rule> custom = new ArrayList<>(); JSONArray names = config.optJSONArray("custom");
        if (names != null) for (int i = 0; i < names.length(); i++) { JSONObject r = names.optJSONObject(i); if (r != null) try { custom.add(new BookParser.Rule(r.optString("name"), r.optInt("level", 3))); } catch (IllegalArgumentException ignored) {} }
        return new BookParser.Rules(enabled, custom);
    }
    static String fileNames(JSONObject book) {JSONArray names=book.optJSONArray("sources");if(names==null||names.length()==0)names=book.optJSONArray("parts");if(names==null)return book.optString("_filePreview");List<String> result=new ArrayList<>();for(int i=0;i<names.length();i++)result.add(new File(names.optString(i)).getName());return String.join("\n",result);}
    static String displayNames(JSONObject book){String names=fileNames(book);int count=book.optInt("_partCount",book.optJSONArray("parts")==null?0:book.optJSONArray("parts").length());if(names.length()>1000)names=names.substring(0,1000)+"…";return count>4?names+"\n（共 "+count+" 个文件）":names;}
    List<File> partFiles(JSONObject book) throws IOException {
        List<File> files = new ArrayList<>(); JSONArray parts = book.optJSONArray("parts");
        if (!book.optString("directory").isEmpty() && parts != null) { File dir = directory(book); for (int i = 0; i < parts.length(); i++) files.add(partFile(dir,parts.optString(i))); }
        else if(!book.optString("directory").isEmpty()&&book.optInt("_partCount")>64){File dir=directory(book);for(String name:store.partNames(book.optString("id")))files.add(partFile(dir,name));}
        else files.add(textFile(book.optString("id"))); return files;
    }
    private static File partFile(File dir,String name)throws IOException{if(name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0)return BackupFiles.child(dir,name);File file=new File(dir,name);return java.nio.file.Files.isSymbolicLink(file.toPath())?BackupFiles.child(dir,name):file;}
    long fileSize(JSONObject book) { if (!canWrite() && !book.optString("directory").isEmpty()) return book.optLong("fileSize"); try { long size = 0; for (File f : partFiles(book)) size += f.length(); return size; } catch (Exception ignored) { return book.optLong("fileSize"); } }
    static final class Document {
        final String id, signature, ruleKey, sourceStamp, contentHash, text; final long fileSize; final List<BookParser.Chapter> toc, sections;
        Document(String id, String signature, String ruleKey, String sourceStamp, String contentHash, long fileSize, String text, List<BookParser.Chapter> toc) { this.id = id; this.signature = signature; this.ruleKey = ruleKey; this.sourceStamp = sourceStamp; this.contentHash = contentHash; this.fileSize = fileSize; this.text = text; this.toc = toc; sections = BookParser.sections(text, toc); }
    }
    Document document(JSONObject b) throws Exception {
        String id = b.optString("id"); if (!id.matches("[a-f0-9]{24}")) throw new IOException("无效书籍编号");
        List<File> files = partFiles(b); StringBuilder stats = new StringBuilder(); long size = 0;
        for (File f : files) { if (!f.isFile()) throw new IOException("备份正文缺失：" + f.getName()); stats.append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified()).append('|'); size += f.length(); }
        String sourceStamp = id(stats.toString()), rules = b.optJSONObject("chapterRules") == null ? "defaults" : b.optJSONObject("chapterRules").toString();
        Document cached = warm;
        if (cached != null && cached.id.equals(id) && cached.sourceStamp.equals(sourceStamp) && cached.ruleKey.equals(rules)) return cached;
        File bookCache = bookCache(id); bookCache.setLastModified(System.currentTimeMillis());
        File canonical = new File(bookCache, "text.txt"), stamp = new File(bookCache, "text.stamp"), index = new File(bookCache, "chapters.index");
        JSONObject info = json(); if (stamp.isFile()) try { info = new JSONObject(new String(read(new FileInputStream(stamp), 100000), StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        boolean valid = canonical.isFile() && sourceStamp.equals(info.optString("sourceStamp")) && !info.optString("contentHash").isEmpty();
        String text, contentHash;
        if (valid) { text = fastText(canonical); contentHash = info.optString("contentHash"); }
        else {
            StringBuilder all=new StringBuilder();for(File file:files){interrupted();if(all.length()>0&&all.charAt(all.length()-1)!='\n')all.append('\n');BookParser.append(file,all,textLimit());}
            text = all.toString(); contentHash = BookIdentity.fingerprint(files);
            atomicText(canonical,text);atomic(stamp,json("sourceStamp",sourceStamp,"contentHash",contentHash).toString().getBytes(StandardCharsets.UTF_8));
        }
        String signature = id("v" + BookParser.VERSION + ":" + contentHash + ":" + rules); List<BookParser.Chapter> toc = null;
        if (index.isFile()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(index)))) {
            if (in.readInt() == BookParser.VERSION && in.readUTF().equals(signature)) { int count = in.readInt(); if (count < 0 || count > 500000) throw new IOException("目录缓存无效"); toc = new ArrayList<>(); for (int i = 0; i < count; i++) { BookParser.Chapter c = new BookParser.Chapter(in.readUTF(), in.readInt(), in.readInt()); c.end = in.readInt(); toc.add(c); } }
        } catch (Exception ignored) { toc = null; }
        if(toc!=null){int previous=-1;for(BookParser.Chapter c:toc)if(c.start<=previous||c.start<0||c.start>=text.length()||c.end<=c.start||c.end>text.length()||c.level<0||c.level>5){toc=null;break;}else previous=c.start;}
        if (toc == null) { toc = BookParser.parse(text, chapterRules(b)); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(BookParser.VERSION); out.writeUTF(signature); out.writeInt(toc.size()); for (BookParser.Chapter c : toc) { out.writeUTF(c.title); out.writeInt(c.start); out.writeInt(c.level); out.writeInt(c.end); } } atomic(index, bytes.toByteArray()); }
        StringBuilder boundaries = new StringBuilder("layout6:").append(contentHash); for (BookParser.Chapter heading : toc) boundaries.append(':').append(heading.start);
        Document result = new Document(id, id(boundaries.toString()), rules, sourceStamp, contentHash, size, text, toc); warm = result; trimCache(id); return result;
    }
    static String fastText(File file) throws IOException {
        try (Reader in = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) {StringBuilder text=new StringBuilder();char[] buffer=new char[8192];int n;while((n=in.read(buffer))!=-1){interrupted();if((long)text.length()+n>textLimit())throw new IOException("正文超过当前设备的安全内存上限");text.append(buffer,0,n);}return text.toString();}
    }
    static int textLimit() {return (int)Math.min(45_000_000,Runtime.getRuntime().maxMemory()/10);}
    void releaseDocument(){warm=null;}
    void discardPrivateCatalog(){if(store.file.getParentFile().equals(root))return;try{for(String suffix:new String[]{"","-wal","-shm"})java.nio.file.Files.deleteIfExists(new File(root,BookStore.FILE+suffix).toPath());atomic(new File(root,"library.json"),snapshot().getBytes(StandardCharsets.UTF_8));}catch(IOException e){maintenanceWarning="书库已打开，旧的私有元数据镜像未能清理";}}
    JSONObject prefs() { return state.optJSONObject("prefs"); }
    JSONObject book(String id) { return store.get(id); }
    File textFile(String id) { if (!id.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("无效书籍编号"); return new File(root, id + ".txt"); }
    String text(JSONObject book) throws Exception { return new String(read(new FileInputStream(textFile(book.optString("id"))), 90_000_000), StandardCharsets.UTF_8); }
    static void set(JSONObject obj, String key, Object value) { try { obj.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(e); } }
    static JSONObject json(Object... pairs) { JSONObject obj = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) set(obj, pairs[i].toString(), pairs[i + 1]); return obj; }
    static String id(String source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)); StringBuilder value = new StringBuilder();
        for (int i = 0; i < 12; i++) value.append(String.format(Locale.ROOT, "%02x", digest[i])); return value.toString();
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) { interrupted(); if ((long)out.size() + n > limit) throw new IOException("文件超出大小限制"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    static void atomic(File file, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream output = null;
        try { output = atomic.startWrite(); output.write(bytes); atomic.finishWrite(output); }
        catch (IOException e) { if (output != null) atomic.failWrite(output); throw e; }
    }
    private static void atomicText(File file,String text)throws IOException{
        AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();Writer writer=new OutputStreamWriter(output,StandardCharsets.UTF_8);for(int start=0;start<text.length();start+=8192){interrupted();writer.write(text,start,Math.min(8192,text.length()-start));}writer.flush();atomic.finishWrite(output);}
        catch(IOException e){if(output!=null)atomic.failWrite(output);throw e;}
    }
}
