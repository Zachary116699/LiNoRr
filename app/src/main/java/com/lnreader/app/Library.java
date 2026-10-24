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
    final List<JSONObject> books = new ArrayList<>(), shelves = new ArrayList<>();
    Library(Context context) throws Exception { this(context, false); }
    Library(Context context, boolean preferPortable) throws Exception {
        this.context = context;
        root = new File(context.getFilesDir(), "library"); if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建书库");
        cache = new File(context.getCacheDir(), "books"); BackupFiles.mkdir(cache);
        backup = new File(context.getSharedPreferences("storage", 0).getString("backupPath", new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LNReader").getPath()));
        File metadata = new File(root, "library.json");
        File portable = new File(backup, "userdata.json");
        if (canWrite() && portable.isFile()) { if (preferPortable || !metadata.isFile() || portable.lastModified() >= metadata.lastModified()) metadata = portable; portableReady = true; }
        state = metadata.exists() ? new JSONObject(new String(read(new AtomicFile(metadata).openRead(), 32_000_000), StandardCharsets.UTF_8)) : json();
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
        if (!prefs.has("night")) { set(prefs, "night", prefs.optBoolean("shelfNight") || prefs.optString("bg").equalsIgnoreCase("#20231f")); if (prefs.optString("bg").equalsIgnoreCase("#20231f")) set(prefs, "bg", "#f7f3e8"); prefs.remove("shelfNight"); }
        JSONObject defaults = defaultPrefs(); for (Iterator<String> keys = defaults.keys(); keys.hasNext();) { String key = keys.next(); if (!prefs.has(key)) set(prefs, key, defaults.opt(key)); }
        if (state.optJSONArray("removedBooks") != null && state.optJSONArray("removedBooks").length() == 0) state.remove("removedBooks");
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
    synchronized JSONObject prepareBackup(JSONObject detached) throws Exception {
        if (!canWrite()) throw new IOException("尚未授权文件读写权限"); BackupFiles.mkdir(backup);
        String before = detached.toString(); boolean ready = portableReady; List<File> created = new ArrayList<>(); Map<File,File> moved = new LinkedHashMap<>();
        try {
            JSONArray categories = detached.optJSONArray("shelves"), items = detached.optJSONArray("books");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject b = items.getJSONObject(i); String shelf = "无分类";
                if (categories != null) for (int j = 0; j < categories.length(); j++) { JSONObject s = categories.getJSONObject(j); if (s.optString("id").equals(b.optString("shelf")) && !s.optString("id").equals("default")) shelf = s.optString("name"); }
                File existing = null; if (!b.optString("directory").isEmpty()) { existing = directory(b); if (!existing.isDirectory()) existing = null; }
                if (existing == null) {
                    File legacy = textFile(b.optString("id")); if (!legacy.isFile()) throw new IOException("找不到正文：" + b.optString("title"));
                    File dir = createDirectory(shelf, b.optString("title")); created.add(dir); String filename = b.optString("title") + ".txt";
                    JSONArray names = b.optJSONArray("sources"); if (names != null && names.length() == 1) filename = new File(names.optString(0)).getName(); filename = BackupFiles.name(filename);
                    BackupFiles.copy(legacy, new File(dir, filename)); File oldCover = new File(root, b.optString("id") + ".jpg"); if (oldCover.isFile()) BackupFiles.copy(oldCover, new File(dir, "cover.jpg"));
                    set(b, "directory", relative(dir)); set(b, "parts", new JSONArray().put(filename));
                } else {
                    File category = new File(new File(backup, "book"), BackupFiles.name(shelf)); BackupFiles.mkdir(category);
                    if (!existing.getParentFile().equals(category) || !existing.getName().equals(b.optString("directoryName", existing.getName())) || !b.optString("organizedTitle", b.optString("title")).equals(b.optString("title"))) {
                        File target = BackupFiles.available(category, b.optString("title")); java.nio.file.Files.move(existing.toPath(), target.toPath()); moved.put(existing, target); set(b, "directory", relative(target));
                    }
                }
                set(b, "organizedTitle", b.optString("title")); set(b, "directoryName", new File(b.optString("directory")).getName());
            }
            portableReady = true; saveSnapshot(detached.toString());
        } catch (Exception error) {
            for (Map.Entry<File,File> pair : moved.entrySet()) if (pair.getValue().exists()) java.nio.file.Files.move(pair.getValue().toPath(), pair.getKey().toPath());
            for (File file : created) BackupFiles.deleteTree(file);
            portableReady = ready; atomic(new File(root, "library.json"), before.getBytes(StandardCharsets.UTF_8)); throw error;
        }
        pruneEmptyCategories(backup); return detached;
    }
    private static void pruneEmptyCategories(File backup) { File[] categories = new File(backup, "book").listFiles(); if (categories != null) for (File category : categories) { String[] children = category.list(); if (category.isDirectory() && children != null && children.length == 0) category.delete(); } }
    void applyPaths(JSONObject detached) {
        JSONArray updated = detached.optJSONArray("books");
        if (updated != null) for (int i = 0; i < updated.length(); i++) { JSONObject copy = updated.optJSONObject(i), b = book(copy.optString("id")); if (b != null) for (String key : new String[]{"directory", "parts", "organizedTitle", "directoryName"}) if (copy.has(key)) set(b, key, copy.opt(key)); }
        if (detached.has("removedBooks")) set(state, "removedBooks", detached.opt("removedBooks"));
        portableReady = true;
    }
    /** Called on the serial save queue; pending older snapshots finish first. */
    synchronized JSONObject removeBooks(JSONObject detached, Set<String> ids) throws Exception {
        if (!canWrite()) throw new IOException("请先授权文件读写权限");
        maintenanceWarning = "";
        JSONArray kept = new JSONArray(), oldBooks = detached.optJSONArray("books"), archived = detached.optJSONArray("removedBooks");
        List<JSONObject> deleting = new ArrayList<>();
        if (oldBooks != null) for (int i = 0; i < oldBooks.length(); i++) { JSONObject b = oldBooks.getJSONObject(i); if (ids.contains(b.optString("id"))) deleting.add(b); else kept.put(b); }
        if (archived != null) for (int i = 0; i < archived.length(); i++) deleting.add(archived.getJSONObject(i));
        Map<File, File> staged = new LinkedHashMap<>(); String before = detached.toString();
        try {
            for (JSONObject b : deleting) {
                if (!b.optString("directory").startsWith("book/")) continue;
                boolean shared = false; for (int i = 0; i < kept.length(); i++) if (kept.getJSONObject(i).optString("directory").equals(b.optString("directory"))) shared = true;
                File directory = directory(b); if (shared || !directory.isDirectory()) continue;
                // Resolve only a managed book directory; never recurse into source URIs.
                BackupFiles.child(new File(backup, "book"), b.optString("directory").substring(5));
                File hidden = new File(backup, ".removed-" + UUID.randomUUID()); java.nio.file.Files.move(directory.toPath(), hidden.toPath()); staged.put(directory, hidden);
            }
            set(detached, "books", kept); detached.remove("removedBooks"); saveSnapshot(detached.toString());
        } catch (Exception error) {
            for (Map.Entry<File,File> pair : staged.entrySet()) if (pair.getValue().exists()) java.nio.file.Files.move(pair.getValue().toPath(), pair.getKey().toPath());
            atomic(new File(root, "library.json"), before.getBytes(StandardCharsets.UTF_8)); throw error;
        }
        for (File directory : staged.values()) try { BackupFiles.deleteTree(directory); } catch (IOException ignored) { maintenanceWarning = "书籍已移除，部分临时文件未能清理，将在下次启动重试"; }
        for (JSONObject b : deleting) {
            String id = b.optString("id"); if (!id.matches("[a-f0-9]{24}")) continue;
            try { java.nio.file.Files.deleteIfExists(textFile(id).toPath()); java.nio.file.Files.deleteIfExists(new File(root, id + ".jpg").toPath()); } catch (IOException ignored) {}
            File[] cached = cache.listFiles(file -> file.getName().startsWith(id)); if (cached != null) for (File file : cached) try { BackupFiles.deleteTree(file); } catch (IOException ignored) {}
        }
        warm = null; pruneEmptyCategories(backup); return detached;
    }
    synchronized JSONObject relocate(File requested, JSONObject detached) throws Exception {
        if (!canWrite()) throw new IOException("请先授权文件读写权限");
        File target = requested.getCanonicalFile(), oldRoot = backup.getCanonicalFile();
        if (target.equals(oldRoot)) return detached;
        if (target.getPath().startsWith(oldRoot.getPath() + File.separator) || oldRoot.getPath().startsWith(target.getPath() + File.separator)) throw new IOException("请选择与当前书库互不包含的文件夹");
        BackupFiles.mkdir(target); if (new File(target, "userdata.json").exists()) throw new IOException("目标文件夹已有书库，请选择空文件夹，避免覆盖");
        List<File> created = new ArrayList<>(), originals = new ArrayList<>(); JSONArray books = detached.optJSONArray("books");
        try {
            if (books != null) for (int i = 0; i < books.length(); i++) {
                JSONObject b = books.getJSONObject(i); File original = directory(b); String category = original.getParentFile().getName();
                File parent = new File(new File(target, "book"), category); BackupFiles.mkdir(parent); File dest = BackupFiles.available(parent, original.getName()); BackupFiles.mkdir(dest); created.add(dest);
                BackupFiles.copyTree(original, dest); File cover = coverFile(b); if (cover.isFile() && !new File(dest, "cover.jpg").isFile()) BackupFiles.copy(cover, new File(dest, "cover.jpg"));
                set(b, "directory", target.toPath().relativize(dest.toPath()).toString().replace(File.separatorChar, '/')); set(b, "directoryName", dest.getName()); originals.add(original);
            }
            atomic(new File(target, "userdata.json"), detached.toString().getBytes(StandardCharsets.UTF_8));
            if (!context.getSharedPreferences("storage", 0).edit().putString("backupPath", target.getPath()).commit()) throw new IOException("无法保存备份路径");
        } catch (Exception error) { for (File dir : created) BackupFiles.deleteTree(dir); java.nio.file.Files.deleteIfExists(new File(target, "userdata.json").toPath()); throw error; }
        backup = target; portableReady = true; maintenanceWarning = "";
        try { saveSnapshot(detached.toString()); } catch (IOException error) { maintenanceWarning = "已迁移至新文件夹，应用内元数据镜像未能更新"; }
        // Only files managed by this library are removed from the previous root.
        for (File directory : originals) try { BackupFiles.deleteTree(directory); } catch (IOException error) { maintenanceWarning = "已迁移至新文件夹，旧目录有文件未能清理"; }
        try { java.nio.file.Files.deleteIfExists(new File(oldRoot, "userdata.json").toPath()); } catch (IOException error) { maintenanceWarning = "已迁移至新文件夹，旧目录有文件未能清理"; } pruneEmptyCategories(oldRoot); return detached;
    }
    void cleanupPending() { if (!canWrite()) return; File[] files = backup.listFiles(f -> f.getName().matches("\\.removed-[a-f0-9-]{36}")); if (files != null) for (File file : files) try { BackupFiles.deleteTree(file); } catch (IOException ignored) {} }
    static BookParser.Rules chapterRules(JSONObject book) {
        JSONObject config = book.optJSONObject("chapterRules"); if (config == null) return BookParser.Rules.defaults();
        List<String> enabled = new ArrayList<>(); JSONArray kinds = config.optJSONArray("kinds"); if (kinds != null) for (int i = 0; i < kinds.length(); i++) enabled.add(kinds.optString(i));
        List<BookParser.Rule> custom = new ArrayList<>(); JSONArray names = config.optJSONArray("custom");
        if (names != null) for (int i = 0; i < names.length(); i++) { JSONObject r = names.optJSONObject(i); if (r != null) try { custom.add(new BookParser.Rule(r.optString("name"), r.optInt("level", 3))); } catch (IllegalArgumentException ignored) {} }
        return new BookParser.Rules(enabled, custom);
    }
    static String fileNames(JSONObject book) { JSONArray names = book.optJSONArray("sources"); if (names == null || names.length() == 0) names = book.optJSONArray("parts"); List<String> result = new ArrayList<>(); if (names != null) for (int i = 0; i < names.length(); i++) result.add(new File(names.optString(i)).getName()); return String.join("\n", result); }
    List<File> partFiles(JSONObject book) throws IOException {
        List<File> files = new ArrayList<>(); JSONArray parts = book.optJSONArray("parts");
        if (!book.optString("directory").isEmpty() && parts != null) { File dir = directory(book); for (int i = 0; i < parts.length(); i++) files.add(BackupFiles.child(dir, parts.optString(i))); }
        else files.add(textFile(book.optString("id"))); return files;
    }
    long fileSize(JSONObject book) { if (!canWrite() && !book.optString("directory").isEmpty()) return book.optLong("fileSize"); try { long size = 0; for (File f : partFiles(book)) size += f.length(); return size; } catch (Exception ignored) { return book.optLong("fileSize"); } }
    static final class Document {
        final String id, signature, ruleKey, sourceStamp, contentHash, text; final long fileSize; final List<BookParser.Chapter> toc, sections;
        Document(String id, String signature, String ruleKey, String sourceStamp, String contentHash, long fileSize, String text, List<BookParser.Chapter> toc) { this.id = id; this.signature = signature; this.ruleKey = ruleKey; this.sourceStamp = sourceStamp; this.contentHash = contentHash; this.fileSize = fileSize; this.text = text; this.toc = toc; sections = BookParser.sections(text, toc); }
    }
    synchronized Document document(JSONObject b) throws Exception {
        String id = b.optString("id"); if (!id.matches("[a-f0-9]{24}")) throw new IOException("无效书籍编号");
        List<File> files = partFiles(b); StringBuilder stats = new StringBuilder(); long size = 0;
        for (File f : files) { if (!f.isFile()) throw new IOException("备份正文缺失：" + f.getName()); stats.append(f.getName()).append(':').append(f.length()).append(':').append(f.lastModified()).append('|'); size += f.length(); }
        String sourceStamp = id(stats.toString()), rules = b.optJSONObject("chapterRules") == null ? "defaults" : b.optJSONObject("chapterRules").toString();
        Document cached = warm;
        if (cached != null && cached.id.equals(id) && cached.sourceStamp.equals(sourceStamp) && cached.ruleKey.equals(rules)) return cached;
        File canonical = new File(cache, id + ".txt"), stamp = new File(cache, id + ".stamp"), index = new File(cache, id + ".index");
        JSONObject info = json(); if (stamp.isFile()) try { info = new JSONObject(new String(read(new FileInputStream(stamp), 100000), StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        boolean valid = canonical.isFile() && sourceStamp.equals(info.optString("sourceStamp")) && !info.optString("contentHash").isEmpty();
        String text, contentHash;
        if (valid) { text = fastText(canonical); contentHash = info.optString("contentHash"); }
        else {
            StringBuilder all = new StringBuilder(); for (File file : files) { if (all.length() > 0 && all.charAt(all.length() - 1) != '\n') all.append('\n'); all.append(BookParser.decode(read(new FileInputStream(file), 90_000_000))); }
            text = all.toString(); contentHash = BookIdentity.fingerprint(files);
            atomic(canonical, text.getBytes(StandardCharsets.UTF_8)); atomic(stamp, json("sourceStamp", sourceStamp, "contentHash", contentHash).toString().getBytes(StandardCharsets.UTF_8));
        }
        String signature = id("v" + BookParser.VERSION + ":" + contentHash + ":" + rules); List<BookParser.Chapter> toc = null;
        if (index.isFile()) try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(index)))) {
            if (in.readInt() == BookParser.VERSION && in.readUTF().equals(signature)) { int count = in.readInt(); if (count < 0 || count > 500000) throw new IOException("目录缓存无效"); toc = new ArrayList<>(); for (int i = 0; i < count; i++) { BookParser.Chapter c = new BookParser.Chapter(in.readUTF(), in.readInt(), in.readInt()); c.end = in.readInt(); toc.add(c); } }
        } catch (Exception ignored) { toc = null; }
        if (toc == null) { toc = BookParser.parse(text, chapterRules(b)); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(BookParser.VERSION); out.writeUTF(signature); out.writeInt(toc.size()); for (BookParser.Chapter c : toc) { out.writeUTF(c.title); out.writeInt(c.start); out.writeInt(c.level); out.writeInt(c.end); } } atomic(index, bytes.toByteArray()); }
        StringBuilder boundaries = new StringBuilder("layout6:").append(contentHash); for (BookParser.Chapter heading : toc) boundaries.append(':').append(heading.start);
        Document result = new Document(id, id(boundaries.toString()), rules, sourceStamp, contentHash, size, text, toc); warm = result; return result;
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
