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
    final File backup, cache;
    private final Context context;
    volatile boolean portableReady;
    private volatile Document warm;
    JSONObject state;
    final List<JSONObject> books = new ArrayList<>(), shelves = new ArrayList<>();
    Library(Context context) throws Exception { this(context, false); }
    Library(Context context, boolean preferPortable) throws Exception {
        this.context = context;
        root = new File(context.getFilesDir(), "library"); if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建书库");
        cache = new File(context.getCacheDir(), "books"); BackupFiles.mkdir(cache);
        backup = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LNReader");
        File metadata = new File(root, "library.json");
        File portable = new File(backup, "userdata.json");
        if (canWrite() && portable.isFile()) { if (preferPortable || !metadata.isFile() || portable.lastModified() >= metadata.lastModified()) metadata = portable; portableReady = true; }
        state = metadata.exists() ? new JSONObject(new String(read(new AtomicFile(metadata).openRead(), 8_000_000), StandardCharsets.UTF_8)) : json();
        JSONArray bs = state.optJSONArray("books"), ss = state.optJSONArray("shelves");
        if (bs != null) for (int i = 0; i < bs.length(); i++) if (bs.optJSONObject(i) != null) books.add(bs.optJSONObject(i));
        for (JSONObject b : books) if (b.optJSONArray("parts") == null || b.optString("directory").isEmpty()) portableReady = false;
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
        for (JSONObject shelf : shelves) if (shelf.optString("id").equals("default")) set(shelf, "name", "无分类");
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
        set(state, "version", 1); set(state, "books", new JSONArray(books)); set(state, "shelves", new JSONArray(shelves));
        atomic(new File(root, "library.json"), state.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    String snapshot() { set(state, "version", 1); set(state, "books", new JSONArray(books)); set(state, "shelves", new JSONArray(shelves)); return state.toString(); }
    synchronized void saveSnapshot(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); atomic(new File(root, "library.json"), bytes);
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
    String relative(File file) throws IOException { return backup.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar, '/'); }
    /** Work on a detached snapshot; the UI applies the resulting paths after this finishes. */
    JSONObject prepareBackup(JSONObject detached) throws Exception {
        if (!canWrite()) throw new IOException("尚未授权文件读写权限"); BackupFiles.mkdir(backup);
        JSONArray categories = detached.optJSONArray("shelves");
        for (String list : new String[]{"books", "removedBooks"}) {
            JSONArray items = detached.optJSONArray(list); if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject b = items.getJSONObject(i); String shelf = "无分类";
                if (categories != null) for (int j = 0; j < categories.length(); j++) { JSONObject s = categories.getJSONObject(j); if (s.optString("id").equals(b.optString("shelf")) && !s.optString("id").equals("default")) shelf = s.optString("name"); }
                File existing = null; if (!b.optString("directory").isEmpty()) { existing = directory(b); if (!existing.isDirectory()) existing = null; }
                if (existing == null) {
                    File legacy = textFile(b.optString("id")); if (!legacy.isFile()) throw new IOException("找不到正文：" + b.optString("title"));
                    File dir = createDirectory(shelf, b.optString("title")); String filename = b.optString("title") + ".txt";
                    JSONArray names = b.optJSONArray("sources"); if (names != null && names.length() == 1) filename = new File(names.optString(0)).getName(); filename = BackupFiles.name(filename);
                    BackupFiles.copy(legacy, new File(dir, filename)); File oldCover = new File(root, b.optString("id") + ".jpg"); if (oldCover.isFile()) BackupFiles.copy(oldCover, new File(dir, "cover.jpg"));
                    set(b, "directory", relative(dir)); set(b, "parts", new JSONArray().put(filename));
                } else {
                    File category = new File(new File(backup, "book"), BackupFiles.name(shelf)); BackupFiles.mkdir(category);
                    String desired = BackupFiles.name(b.optString("title"));
                    if (!existing.getParentFile().equals(category) || !existing.getName().equals(b.optString("directoryName", existing.getName())) || !b.optString("organizedTitle", b.optString("title")).equals(b.optString("title"))) {
                        File target = BackupFiles.available(category, desired); java.nio.file.Files.move(existing.toPath(), target.toPath()); set(b, "directory", relative(target)); existing = target;
                    }
                }
                set(b, "organizedTitle", b.optString("title")); set(b, "directoryName", new File(b.optString("directory")).getName());
            }
        }
        return detached;
    }
    void applyPaths(JSONObject detached) {
        JSONArray updated = detached.optJSONArray("books");
        if (updated != null) for (int i = 0; i < updated.length(); i++) { JSONObject copy = updated.optJSONObject(i), b = book(copy.optString("id")); if (b != null) for (String key : new String[]{"directory", "parts", "organizedTitle", "directoryName"}) if (copy.has(key)) set(b, key, copy.opt(key)); }
        if (detached.has("removedBooks")) set(state, "removedBooks", detached.opt("removedBooks"));
        portableReady = true;
    }
    static final class Document {
        final String id, signature, text; final List<BookParser.Chapter> toc, sections;
        Document(String id, String signature, String text, List<BookParser.Chapter> toc) { this.id = id; this.signature = signature; this.text = text; this.toc = toc; sections = BookParser.sections(text, toc); }
    }
    Document document(JSONObject b) throws Exception {
        String id = b.optString("id"), signature = "v" + BookParser.VERSION; if (!id.matches("[a-f0-9]{24}")) throw new IOException("无效书籍编号"); JSONArray parts = b.optJSONArray("parts"); File directory = null;
        if (canWrite() && !b.optString("directory").isEmpty() && parts != null) {
            directory = directory(b); for (int i = 0; i < parts.length(); i++) { File f = BackupFiles.child(directory, parts.getString(i)); if (!f.isFile()) throw new IOException("备份正文缺失：" + f.getName()); signature += ":" + f.getName() + ":" + f.length() + ":" + f.lastModified(); }
        } else { File f = textFile(id); signature += ":legacy:" + f.length() + ":" + f.lastModified(); }
        signature = id(signature);
        Document cached = warm; if (cached != null && cached.id.equals(id) && cached.signature.equals(signature)) return cached;
        File canonical = new File(cache, id + ".txt"), stamp = new File(cache, id + ".stamp"), index = new File(cache, id + ".index");
        boolean valid = canonical.isFile() && stamp.isFile() && new String(read(new FileInputStream(stamp), 100000), StandardCharsets.UTF_8).equals(signature);
        String text;
        if (valid) text = fastText(canonical);
        else {
            if (directory == null) text = fastText(textFile(id));
            else { StringBuilder all = new StringBuilder(); for (int i = 0; i < parts.length(); i++) { if (all.length() > 0 && all.charAt(all.length() - 1) != '\n') all.append('\n'); all.append(BookParser.decode(read(new FileInputStream(BackupFiles.child(directory, parts.getString(i))), 90_000_000))); } text = all.toString(); }
            atomic(canonical, text.getBytes(StandardCharsets.UTF_8)); atomic(stamp, signature.getBytes(StandardCharsets.UTF_8));
        }
        List<BookParser.Chapter> toc = null;
        if (valid && index.isFile()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(index)))) {
            if (in.readInt() == BookParser.VERSION && in.readUTF().equals(signature)) { int count = in.readInt(); if (count < 0 || count > 500000) throw new IOException("目录缓存无效"); toc = new ArrayList<>(); for (int i = 0; i < count; i++) { BookParser.Chapter c = new BookParser.Chapter(in.readUTF(), in.readInt(), in.readInt()); c.end = in.readInt(); toc.add(c); } }
        } catch (Exception ignored) { toc = null; }
        if (toc == null) { toc = BookParser.parse(text); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(BookParser.VERSION); out.writeUTF(signature); out.writeInt(toc.size()); for (BookParser.Chapter c : toc) { out.writeUTF(c.title); out.writeInt(c.start); out.writeInt(c.level); out.writeInt(c.end); } } atomic(index, bytes.toByteArray()); }
        Document result = new Document(id, signature, text, toc); warm = result; return result;
    }
    static String fastText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); FileChannel channel = in.getChannel()) { if (channel.size() > 90_000_000) throw new IOException("正文超过读取上限"); return channel.size() == 0 ? "" : StandardCharsets.UTF_8.decode(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())).toString(); }
    }
    JSONObject prefs() { return state.optJSONObject("prefs"); }
    JSONObject book(String id) { for (JSONObject b : books) if (id.equals(b.optString("id"))) return b; return null; }
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
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("文件超出大小限制"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    static void atomic(File file, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(file); FileOutputStream output = null;
        try { output = atomic.startWrite(); output.write(bytes); atomic.finishWrite(output); }
        catch (IOException e) { if (output != null) atomic.failWrite(output); throw e; }
    }
}
