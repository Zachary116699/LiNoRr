package com.lnreader.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable, incremental catalogue. Only a bounded set of book JSON objects is resident. */
final class BookStore implements AutoCloseable {
    interface Work {void run() throws Exception;}
    static final String FILE = "library.sqlite";
    final File file;
    final SQLiteDatabase db;
    private final LinkedHashMap<String, Record> cache = new LinkedHashMap<>(128, .75f, true);
    private final java.util.concurrent.ConcurrentHashMap<String,Record> dirty=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed;
    java.util.function.BiConsumer<JSONObject,String> changed = (b,k) -> {};
    private static final int CACHE_SIZE = 160;
    final class Record extends JSONObject {
        private boolean attached;
        private long version;
        private final BookStore owner=BookStore.this;
        Record(String value) throws JSONException { super(value); attached = true; }
        private void mark(String key){if(!closed){version++;dirty.put(optString("id"),this);revision.incrementAndGet();changed.accept(this,key);}}
        @Override public synchronized JSONObject put(String key,Object value)throws JSONException {
            Object old=opt(key);super.put(key,value);if(attached&&!Objects.equals(old,value))mark(key);return this;
        }
        @Override public synchronized Object remove(String key){Object old=super.remove(key);if(attached&&old!=null)mark(key);return old;}
        @Override public synchronized String toString(){return super.toString();}
    }
    BookStore(File directory) throws IOException {
        BackupFiles.mkdir(directory); file = new File(directory, FILE);
        db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.CREATE_IF_NECESSARY,broken->{throw new android.database.sqlite.SQLiteDatabaseCorruptException("书库数据库损坏；原文件已保留，请使用备份恢复");});db.enableWriteAheadLogging();
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY, hash TEXT, title TEXT, filename TEXT, title_sort TEXT, filename_sort TEXT, size INTEGER, added INTEGER, lastRead INTEGER, private INTEGER, hiddenRecent INTEGER, directory TEXT, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        if(!"2".equals(meta("hashIndexVersion"))){db.execSQL("DROP INDEX IF EXISTS book_hash");db.execSQL("CREATE INDEX book_hash ON books(hash)");meta("hashIndexVersion","2");}
        for (String column : new String[]{"title_sort", "filename_sort", "size", "added", "lastRead", "directory"}) db.execSQL("CREATE INDEX IF NOT EXISTS book_" + column + " ON books(" + column + ",id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS membership(book TEXT NOT NULL,shelf TEXT NOT NULL,PRIMARY KEY(shelf,book))");
        db.execSQL("CREATE INDEX IF NOT EXISTS membership_book ON membership(book)");
        db.execSQL("CREATE TABLE IF NOT EXISTS extra_parts(book TEXT NOT NULL,ordinal INTEGER NOT NULL,filename TEXT NOT NULL,source TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(book,ordinal))");
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,kind TEXT NOT NULL,source TEXT,target TEXT,payload TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS operation_payload(operation TEXT NOT NULL,chunk INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(operation,chunk))");
    }
    long revision() { return revision.get(); }
    synchronized void transaction(Work work)throws Exception{db.beginTransaction();try{work.run();db.setTransactionSuccessful();}finally{db.endTransaction();}}
    synchronized Set<String> dirtyIds() { return new HashSet<>(dirty.keySet()); }
    void touch(JSONObject book){if(book instanceof BookStore.Record){Record b=(Record)book;if(b.owner==this)synchronized(b){b.mark("progress");}}}
    private static final class Frozen{final BookStore.Record record;final long version;final JSONObject value;Frozen(BookStore.Record r,long v,JSONObject value){record=r;version=v;this.value=value;}}
    synchronized void flush() throws Exception {
        if (dirty.isEmpty()) return;
        List<Frozen> writes=new ArrayList<>();for(Record b:dirty.values())synchronized(b){writes.add(new Frozen(b,b.version,new JSONObject(b.toString())));}
        db.beginTransaction();
        try{for(Frozen item:writes)write(item.value);db.setTransactionSuccessful();}finally{db.endTransaction();}
        for(Frozen item:writes)synchronized(item.record){String id=item.record.optString("id");if(item.record.version==item.version)dirty.remove(id,item.record);synchronized(cache){cache.put(id,item.record);}}
        trim();
    }
    private void trim(){synchronized(cache){Iterator<Map.Entry<String,Record>> it=cache.entrySet().iterator();while(cache.size()>CACHE_SIZE&&it.hasNext()){Map.Entry<String,Record> entry=it.next();if(!dirty.containsKey(entry.getKey()))it.remove();}}}
    Record peek(String id){Record b=dirty.get(id);if(b!=null)return b;synchronized(cache){return cache.get(id);}}
    JSONObject get(String id) {
        Record b=peek(id);if(b!=null)return b;
        synchronized(this){b=peek(id);if(b!=null)return b;try(Cursor c=db.rawQuery("SELECT json FROM books WHERE id=?",new String[]{id})){return c.moveToFirst()?record(id,c.getString(0)):null;}}
    }
    private Record record(String id,String json) {
        try{Record b=peek(id);if(b!=null)return b;Record parsed=new Record(json);if(!id.equals(parsed.optString("id")))throw new JSONException("书籍编号不一致");synchronized(cache){b=dirty.get(id);if(b==null)b=cache.get(id);if(b==null){b=parsed;cache.put(id,b);}}trim();return b;}
        catch (JSONException e) { throw new IllegalStateException("书籍记录损坏", e); }
    }
    synchronized void put(JSONObject b) throws Exception {
        db.beginTransaction();try{write(b);db.setTransactionSuccessful();}finally{db.endTransaction();}String id=b.getString("id");synchronized(cache){cache.remove(id);}dirty.remove(id);revision.incrementAndGet();
    }
    private void write(JSONObject b) throws JSONException {
        String id = b.getString("id"); if (!id.matches("[a-f0-9]{24}")) throw new JSONException("无效书籍编号");
        JSONObject row=b;JSONArray parts=b.optJSONArray("parts"),sources=b.optJSONArray("sources");
        if(parts!=null){db.delete("extra_parts","book=?",new String[]{id});if(parts.length()>64){row=new JSONObject();for(Iterator<String> keys=b.keys();keys.hasNext();){String key=keys.next();if(!key.equals("parts")&&!key.equals("sources"))row.put(key,b.opt(key));}StringBuilder preview=new StringBuilder();for(int i=0;i<parts.length();i++){String source=sources==null?parts.optString(i):sources.optString(i,parts.optString(i));ContentValues p=new ContentValues();p.put("book",id);p.put("ordinal",i);p.put("filename",parts.optString(i));p.put("source",source);String name=new File(source).getName();p.put("name",name.toLowerCase(Locale.ROOT));db.insertOrThrow("extra_parts",null,p);if(i<4){if(i>0)preview.append('\n');preview.append(name);}}row.put("_partCount",parts.length());row.put("_filePreview",preview.toString());}}
        ContentValues v = new ContentValues(); v.put("id", id); v.put("hash", b.optString("contentHash")); v.put("title", b.optString("title").toLowerCase(Locale.ROOT));
        String filename=Library.fileNames(row).toLowerCase(Locale.ROOT);v.put("filename",filename);v.put("title_sort",sortKey(b.optString("title")));v.put("filename_sort",sortKey(filename));
        v.put("size", b.optLong("fileSize")); v.put("added", b.optLong("added")); v.put("lastRead", b.optLong("lastRead")); v.put("private", b.optBoolean("private") ? 1 : 0); v.put("hiddenRecent", b.optBoolean("hiddenRecent") ? 1 : 0); v.put("directory", b.optString("directory")); v.put("json", row.toString());
        if(db.insertWithOnConflict("books",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("无法写入书籍记录");
        db.delete("membership", "book=?", new String[]{id});
        for (String shelf : Library.shelfIds(b)) { ContentValues m = new ContentValues(); m.put("book", id); m.put("shelf", shelf); db.insertOrThrow("membership", null, m); }
    }
    /** Numeric runs compare naturally without regex allocations inside a comparator. */
    static String sortKey(String value) {
        String s = value.toLowerCase(Locale.ROOT); StringBuilder key = new StringBuilder();
        for (int i = 0; i < s.length();) { char c = s.charAt(i); if (c >= '0' && c <= '9') { int end = i + 1; while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') end++; int start = i; while (start + 1 < end && s.charAt(start) == '0') start++; String n = s.substring(start, end),length=Integer.toString(n.length());key.append('\u0001');for(int pad=length.length();pad<8;pad++)key.append('0');key.append(length).append(n).append('\u0002');i = end; } else { key.append(c); i++; } }
        return key.toString();
    }
    static final class Filter {
        final String shelf, query, sort; final boolean privacy, ascending;
        Filter(String shelf, String query, String sort, boolean privacy, boolean ascending) { this.shelf = shelf; this.query = query.trim().toLowerCase(Locale.ROOT); this.sort = sort; this.privacy = privacy; this.ascending = ascending; }
    }
    private String source(Filter f, List<String> args) {
        String privacy = f.privacy ? "1" : "private=0";
        String from;
        if (f.shelf.equals("recent")) from = "(SELECT * FROM books WHERE " + privacy + " AND lastRead>0 AND hiddenRecent=0 ORDER BY lastRead DESC,id LIMIT 90) b WHERE 1";
        else {
            from = "books b WHERE " + privacy;
            if (f.shelf.equals("private")) from += " AND private=1";
            else if (f.shelf.equals("default")) from += " AND NOT EXISTS(SELECT 1 FROM membership m WHERE m.book=b.id)";
            else if (!f.shelf.equals("all")) { from = "membership m JOIN books b ON b.id=m.book WHERE m.shelf=? AND " + privacy; args.add(f.shelf); }
        }
        if (!f.query.isEmpty()) { from += " AND (instr(title,?)>0 OR instr(filename,?)>0 OR EXISTS(SELECT 1 FROM extra_parts p WHERE p.book=b.id AND instr(p.name,?)>0))"; args.add(f.query); args.add(f.query);args.add(f.query); }
        return from;
    }
    synchronized int count() { try (Cursor c = db.rawQuery("SELECT count(*) FROM books", null)) { c.moveToFirst(); return c.getInt(0); } }
    synchronized int count(Filter f) { List<String> args = new ArrayList<>(); String from = source(f, args); try (Cursor c = db.rawQuery("SELECT count(*) FROM " + from, args.toArray(new String[0]))) { c.moveToFirst(); return c.getInt(0); } }
    synchronized List<JSONObject> page(Filter f, int offset, int limit) {
        List<String> args = new ArrayList<>(); String from = source(f, args);
        String column = f.sort.equals("title") ? "title_sort" : f.sort.equals("filename") ? "filename_sort" : f.sort.equals("size") ? "size" : f.sort.equals("lastRead") ? "lastRead" : "added";
        List<JSONObject> result = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id,json FROM " + from + " ORDER BY " + column + (f.ascending ? " ASC" : " DESC") + ",id LIMIT " + Math.max(0,limit) + " OFFSET " + Math.max(0,offset), args.toArray(new String[0]))) { while (c.moveToNext()) result.add(record(c.getString(0),c.getString(1))); }
        return result;
    }
    synchronized Set<String> ids(Filter f) {
        List<String> args = new ArrayList<>(); String from = source(f,args); Set<String> ids = new LinkedHashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM " + from, args.toArray(new String[0]))) { while (c.moveToNext()) ids.add(c.getString(0)); } return ids;
    }
    synchronized Map<String,int[]> counts() {
        Map<String,int[]> result = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT m.shelf,count(*),sum(CASE WHEN b.private=0 THEN 1 ELSE 0 END) FROM membership m JOIN books b ON b.id=m.book GROUP BY m.shelf",null)) { while (c.moveToNext()) result.put(c.getString(0),new int[]{c.getInt(1),c.getInt(2)}); }
        return result;
    }
    synchronized boolean hasHash(String hash) { try(Cursor c=db.rawQuery("SELECT 1 FROM books WHERE hash=?",new String[]{hash})) {return c.moveToFirst();} }
    synchronized List<String> missingHashes(long size) { List<String> ids=new ArrayList<>(); try(Cursor c=db.rawQuery("SELECT id FROM books WHERE hash='' AND size=?",new String[]{Long.toString(size)})) {while(c.moveToNext())ids.add(c.getString(0));} return ids; }
    synchronized String meta(String key) { try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{key})) {return c.moveToFirst()?c.getString(0):null;} }
    synchronized void meta(String key,String value) {ContentValues v=new ContentValues();v.put("key",key);v.put("value",value);if(db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new android.database.sqlite.SQLiteException("无法写入书库设置");}
    synchronized void journal(String id,String kind,String source,String target,JSONObject payload) {
        String json=payload==null?"":payload.toString();ContentValues v=new ContentValues();v.put("id",id);v.put("kind",kind);v.put("source",source);v.put("target",target);v.put("payload",json.length()>32768?"@chunks":json);
        db.beginTransaction();try{db.insertOrThrow("operations",null,v);if(json.length()>32768){int chunk=0;for(int start=0;start<json.length();){int end=Math.min(json.length(),start+32768);if(end<json.length()&&Character.isHighSurrogate(json.charAt(end-1)))end--;ContentValues p=new ContentValues();p.put("operation",id);p.put("chunk",chunk++);p.put("text",json.substring(start,end));db.insertOrThrow("operation_payload",null,p);start=end;}}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    synchronized List<JSONObject> operations() {List<JSONObject> rows=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id,kind,source,target,payload FROM operations ORDER BY CASE WHEN kind='stage' THEN 1 ELSE 0 END",null)){while(c.moveToNext())rows.add(Library.json("id",c.getString(0),"kind",c.getString(1),"source",c.getString(2),"target",c.getString(3),"payload",c.getString(4)));}return rows;}
    synchronized List<String> operationIds(){List<String> ids=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id FROM operations ORDER BY CASE WHEN kind='stage' THEN 1 ELSE 0 END,id",null)){while(c.moveToNext())ids.add(c.getString(0));}return ids;}
    synchronized JSONObject operation(String id){try(Cursor c=db.rawQuery("SELECT kind,source,target,payload FROM operations WHERE id=?",new String[]{id})){if(!c.moveToFirst())return null;String payload=c.getString(3);if("@chunks".equals(payload)){StringBuilder joined=new StringBuilder();try(Cursor parts=db.rawQuery("SELECT text FROM operation_payload WHERE operation=? ORDER BY chunk",new String[]{id})){while(parts.moveToNext())joined.append(parts.getString(0));}payload=joined.toString();}return Library.json("id",id,"kind",c.getString(0),"source",c.getString(1),"target",c.getString(2),"payload",payload);}}
    synchronized Set<String> importSources(){Set<String> paths=new HashSet<>();try(Cursor c=db.rawQuery("SELECT source FROM operations WHERE kind='import'",null)){while(c.moveToNext())paths.add(c.getString(0));}return paths;}
    synchronized void finish(String operation){db.beginTransaction();try{db.delete("operation_payload","operation=?",new String[]{operation});db.delete("operations","id=?",new String[]{operation});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    synchronized void remove(String id) {db.beginTransaction();try{db.delete("membership","book=?",new String[]{id});db.delete("extra_parts","book=?",new String[]{id});db.delete("books","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}synchronized(cache){cache.remove(id);}dirty.remove(id);revision.incrementAndGet();}
    synchronized List<String> partNames(String id){List<String> names=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT filename FROM extra_parts WHERE book=? ORDER BY ordinal",new String[]{id})){while(c.moveToNext())names.add(c.getString(0));}return names;}
    synchronized List<String> sourceNames(String id){List<String> names=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT source FROM extra_parts WHERE book=? ORDER BY ordinal",new String[]{id})){while(c.moveToNext())names.add(new File(c.getString(0)).getName());}return names;}
    synchronized List<String> allIds() {List<String> ids=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id FROM books ORDER BY id",null)){while(c.moveToNext())ids.add(c.getString(0));}return ids;}
    synchronized int directoryReferences(String directory) {try(Cursor c=db.rawQuery("SELECT count(*) FROM books WHERE directory=?",new String[]{directory})){c.moveToFirst();return c.getInt(0);}}
    synchronized JSONObject atDirectory(String directory){try(Cursor c=db.rawQuery("SELECT id,json FROM books WHERE directory=? LIMIT 2",new String[]{directory})){if(!c.moveToFirst())return null;String id=c.getString(0),json=c.getString(1);return c.moveToNext()?null:record(id,json);}}
    /** Legacy JSON is read one book at a time; no whole-library string or 32 MB ceiling. */
    synchronized JSONObject importJson(File input) throws Exception {
        JSONObject state = new JSONObject(); db.beginTransaction();
        try (JsonReader r = new JsonReader(new InputStreamReader(new android.util.AtomicFile(input).openRead(),StandardCharsets.UTF_8))) {
            r.beginObject(); while(r.hasNext()) {Library.interrupted();String name=r.nextName(); if(name.equals("books")) {r.beginArray();while(r.hasNext()){Library.interrupted();JSONObject b=(JSONObject)value(r,0);write(b);}r.endArray();}else if(name.equals("removedBooks")){r.beginArray();while(r.hasNext()){JSONObject b=(JSONObject)value(r,0);String id=UUID.randomUUID().toString();journal(id,"delete",b.optString("directory"),"",b);}r.endArray();}else state.put(name,value(r,0));}r.endObject();
            meta("state",state.toString());meta("initialized","1");db.setTransactionSuccessful();
        } finally {db.endTransaction();}return state;
    }
    private static Object value(JsonReader r,int depth) throws Exception {
        Library.interrupted();
        if(depth>32)throw new IOException("JSON 层级过深");
        switch(r.peek()) {
            case BEGIN_OBJECT: JSONObject o=new JSONObject();r.beginObject();while(r.hasNext())o.put(r.nextName(),value(r,depth+1));r.endObject();return o;
            case BEGIN_ARRAY: JSONArray a=new JSONArray();r.beginArray();while(r.hasNext())a.put(value(r,depth+1));r.endArray();return a;
            case STRING:return r.nextString();case BOOLEAN:return r.nextBoolean();case NULL:r.nextNull();return JSONObject.NULL;
            case NUMBER:String n=r.nextString();try{return Long.parseLong(n);}catch(NumberFormatException e){return Double.parseDouble(n);}
            default:throw new IOException("无效 JSON");
        }
    }
    synchronized void checkpoint(){flushUnchecked();try(Cursor c=db.rawQuery("PRAGMA wal_checkpoint(FULL)",null)){if(!c.moveToFirst()||c.getInt(0)!=0||c.getLong(1)!=c.getLong(2))throw new android.database.sqlite.SQLiteException("数据库仍在写入，请稍后重试迁移");}}
    synchronized void verify()throws IOException{try(Cursor c=db.rawQuery("PRAGMA quick_check",null)){if(!c.moveToFirst()||!"ok".equals(c.getString(0)))throw new IOException("书库完整性检查未通过，原书库已保留");}}
    private void flushUnchecked(){try{flush();}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public synchronized void close(){if(!db.isOpen())return;try{flushUnchecked();}finally{closed=true;db.close();synchronized(cache){cache.clear();}dirty.clear();}}
}
