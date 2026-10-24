package com.lnreader.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class Library {
    final File root;
    JSONObject state;
    final List<JSONObject> books = new ArrayList<>(), shelves = new ArrayList<>();
    Library(Context context) throws Exception {
        root = new File(context.getFilesDir(), "library"); if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建书库");
        File metadata = new File(root, "library.json");
        state = metadata.exists() ? new JSONObject(new String(read(new AtomicFile(metadata).openRead(), 8_000_000), StandardCharsets.UTF_8)) : json();
        JSONArray bs = state.optJSONArray("books"), ss = state.optJSONArray("shelves");
        if (bs != null) for (int i = 0; i < bs.length(); i++) if (bs.optJSONObject(i) != null) books.add(bs.optJSONObject(i));
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
    }
    static void migrateDefaults(JSONObject prefs) {
        if (prefs.optInt("defaultsVersion") >= 2) return;
        if (Math.abs(prefs.optDouble("size", 20) - 20) < .001) set(prefs, "size", 18);
        if (Math.abs(prefs.optDouble("line", 1.5) - 1.5) < .001) set(prefs, "line", 1.4);
        if (Math.abs(prefs.optDouble("paragraph", .5) - .5) < .001) set(prefs, "paragraph", .4);
        set(prefs, "defaultsVersion", 2);
    }
    static JSONObject defaultPrefs() { return json("size", 18, "line", 1.4, "paragraph", .4, "bg", "#f7f3e8", "font", "系统默认", "readingMode", "paged", "volumeKeys", true, "defaultsVersion", 2); }
    synchronized void save() throws Exception {
        set(state, "version", 1); set(state, "books", new JSONArray(books)); set(state, "shelves", new JSONArray(shelves));
        atomic(new File(root, "library.json"), state.toString(2).getBytes(StandardCharsets.UTF_8));
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
