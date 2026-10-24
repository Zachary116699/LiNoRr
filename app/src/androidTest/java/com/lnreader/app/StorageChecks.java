package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.lnreader.app.Library.*;

/** Device-level transactions and layout-cache checks on the guarded QA emulator. */
public final class StorageChecks extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private Object field(Object target, String name) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (Exception e) { throw new RuntimeException(e); } }
    private void field(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new RuntimeException(e); } }
    private Object call(Object target, String name, Class<?>[] types, Object... args) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private void waitUntil(BooleanSupplier condition) throws Exception { long end = SystemClock.elapsedRealtime() + 30000; while (!condition.getAsBoolean()) { if (SystemClock.elapsedRealtime() > end) throw new AssertionError("Timed out"); Thread.sleep(50); } }
    private boolean onMain(BooleanSupplier condition) { boolean[] value = {false}; runOnMainSync(() -> value[0] = condition.getAsBoolean()); return value[0]; }
    private View named(View view, String label) { if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) return view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View match = named(((ViewGroup) view).getChildAt(i), label); if (match != null) return match; } return null; }
    private void idle(NativeActivity activity) throws Exception { waitUntil(() -> onMain(() -> !(Boolean) field(activity, "importing"))); }
    private void importBook(NativeActivity activity, List<Object> entries, String name) throws Exception {
        runOnMainSync(() -> { field(activity, "grouping", "merged"); call(activity, "showBusy", new Class<?>[]{String.class}, "正在检查备份…"); });
        call(activity, "importEntries", new Class<?>[]{List.class, String.class, String.class, JSONObject.class}, entries, name, "", null); idle(activity);
    }
    private void open(NativeActivity activity, JSONObject book) throws Exception {
        runOnMainSync(() -> call(activity, "openBook", new Class<?>[]{JSONObject.class, int.class}, book, -1));
        waitUntil(() -> onMain(() -> field(activity, "pagedReader") != null && ((PagedReaderView) field(activity, "pagedReader")).ready() && !(Boolean) field(activity, "importing")));
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            check("LNReader_QA".equals(arguments.getString("guard")), "Dedicated emulator guard required");
            Context context = getTargetContext(); NativeActivity activity = (NativeActivity) startActivitySync(new Intent(context, NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitUntil(() -> onMain(() -> field(activity, "lib") != null && !(Boolean) field(activity, "importing")));
            Library library = (Library) field(activity, "lib"); check(library.canWrite(), "All-files access required");
            File inputs = new File(context.getCacheDir(), "qa-input"); BackupFiles.mkdir(inputs);
            byte[] aText = "EP.01 第一段\r\n\r\n第一份原文。\r\n".getBytes(StandardCharsets.UTF_16LE), a = new byte[aText.length + 2]; a[0] = (byte) 0xff; a[1] = (byte) 0xfe; System.arraycopy(aText, 0, a, 2, aText.length);
            byte[] b = "EP.02 第二段\n\n第二份原文。\n".getBytes(Charset.forName("GB18030"));
            File first = new File(inputs, "第一个原名.txt"), second = new File(inputs, "第二个原名.txt"); BackupFiles.write(first, a); BackupFiles.write(second, b);
            Class<?> entryClass = Class.forName("com.lnreader.app.NativeActivity$Entry"); Constructor<?> constructor = entryClass.getDeclaredConstructor(Uri.class, String.class, long.class); constructor.setAccessible(true);
            List<Object> entries = new ArrayList<>(); entries.add(constructor.newInstance(Uri.fromFile(first), first.getName(), first.length())); entries.add(constructor.newInstance(Uri.fromFile(second), second.getName(), second.length()));
            importBook(activity, entries, "QA多文件"); check(library.books.size() == 1, "Initial import failed");
            JSONObject initial = library.books.get(0); String identity = initial.optString("id"), hash = initial.optString("contentHash"); File initialDirectory = library.directory(initial);
            check(Arrays.equals(a, read(new FileInputStream(new File(initialDirectory, first.getName())), 10000)), "UTF16 original changed");
            check(Arrays.equals(b, read(new FileInputStream(new File(initialDirectory, second.getName())), 10000)), "GB18030 original changed");
            String expected = BookParser.decode(a) + BookParser.decode(b); check(library.document(initial).text.equals(expected), "Merged reader changed content");
            check(library.document(initial).toc.isEmpty(), "EP must be opt-in");
            File renamedA = new File(inputs, "OtherA.txt"), renamedB = new File(inputs, "OtherB.txt"); BackupFiles.write(renamedA, a); BackupFiles.write(renamedB, b);
            List<Object> renamed = Arrays.asList(constructor.newInstance(Uri.fromFile(renamedA), renamedA.getName(), renamedA.length()), constructor.newInstance(Uri.fromFile(renamedB), renamedB.getName(), renamedB.length()));
            importBook(activity, renamed, "另一个名字"); check(library.books.size() == 1 && library.books.get(0).optString("id").equals(identity), "Content dedup depends on file name/path");
            runOnMainSync(() -> initial.remove("contentHash")); importBook(activity, renamed, "缺少散列的旧书"); check(library.books.size() == 1 && hash.equals(initial.optString("contentHash")), "Legacy books without fingerprints did not deduplicate/backfill");
            first.delete(); second.delete();
            runOnMainSync(() -> call(activity, "performRemove", new Class<?>[]{Set.class, Runnable.class}, Collections.singleton(identity), null)); idle(activity);
            check(library.books.isEmpty() && !initialDirectory.exists(), "Remove must delete the portable book");
            check(!new JSONObject(Library.fastText(new File(library.backup, "userdata.json"))).has("removedBooks"), "Removed books retained in JSON");
            check(renamedA.isFile() && renamedB.isFile(), "Removing backup deleted outside source");
            BackupFiles.write(first, a); BackupFiles.write(second, b); importBook(activity, entries, "QA多文件");
            JSONObject book = library.books.get(0); File directory = library.directory(book);
            check(identity.equals(book.optString("id")) && BookIdentity.coverColor(hash) == BookIdentity.coverColor(book.optString("contentHash")), "Remove/reimport changed identity/color");
            check(book.optInt("position") == 0 && book.getJSONArray("marks").length() == 0, "Deleted history resurrected");
            runOnMainSync(() -> set(book, "chapterRules", json("kinds", new JSONArray().put("EP."), "custom", new JSONArray())));
            Library.Document configured = library.document(book); check(configured.toc.size() == 2, "Per-book EP rules not applied");
            JSONObject levels = new JSONObject(book.toString()); set(levels, "chapterRules", json("kinds", new JSONArray(), "custom", new JSONArray().put(json("name", "EP.", "level", 4))));
            Library.Document changedLevel = library.document(levels); check(changedLevel.toc.get(0).level == 4 && changedLevel.signature.equals(configured.signature), "Hierarchy-only change invalidated unchanged page geometry");
            Bitmap picture = Bitmap.createBitmap(2048, 3072, Bitmap.Config.ARGB_8888); new Canvas(picture).drawColor(Color.rgb(119, 154, 171));
            File originalCover = new File(inputs, "原封面.png"); try (OutputStream stream = new FileOutputStream(originalCover)) { picture.compress(Bitmap.CompressFormat.PNG, 100, stream); } picture.recycle();
            runOnMainSync(() -> { field(activity, "coverTarget", book); call(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, 54, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(originalCover))); }); idle(activity);
            BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true; BitmapFactory.decodeFile(new File(directory, "cover.jpg").getPath(), size);
            check(Math.max(size.outWidth, size.outHeight) <= 720 && originalCover.isFile(), "Cover resize or original failed");
            runOnMainSync(() -> { set(book, "position", 18); set(book, "lastRead", 123456789L); set(book, "marks", new JSONArray().put(json("offset", 18, "title", "EP.01 第一段", "date", 123L))); call(activity, "persist", new Class<?>[]{}); });
            open(activity, book); waitUntil(() -> onMain(() -> field(activity, "pageTotals") != null));
            int[] totals = ((int[]) field(activity, "pageTotals")).clone(); String layoutKey = book.getJSONObject("pagination").getString("key");
            runOnMainSync(() -> call(activity, "back", new Class<?>[]{}));
            File[] cached = library.cache.listFiles(); if (cached != null) for (File file : cached) if (file.getName().startsWith(identity)) file.delete();
            open(activity, book); waitUntil(() -> onMain(() -> field(activity, "pageTotals") != null));
            check("userdata".equals(field(activity, "pageIndexSource")) && Arrays.equals(totals, (int[]) field(activity, "pageTotals")), "Same typography recalculated pages after cache deletion");
            runOnMainSync(() -> { set(library.prefs(), "size", 19); call(activity, "buildReader", new Class<?>[]{int.class}, book.optInt("position")); });
            waitUntil(() -> onMain(() -> field(activity, "pageTotals") != null));
            check(!layoutKey.equals(book.getJSONObject("pagination").getString("key")) && book.getJSONObject("pagination").getInt("size") == 19, "Font size did not invalidate layout");
            runOnMainSync(() -> { set(library.prefs(), "size", 18); call(activity, "buildReader", new Class<?>[]{int.class}, book.optInt("position")); });
            waitUntil(() -> onMain(() -> field(activity, "pageTotals") != null)); runOnMainSync(() -> call(activity, "back", new Class<?>[]{}));
            first.delete(); second.delete();
            waitUntil(() -> { try { return new JSONObject(Library.fastText(new File(library.backup, "userdata.json"))).getJSONArray("books").getJSONObject(0).has("pagination"); } catch (Exception e) { return false; } });
            Library restored = new Library(context); check(restored.document(restored.book(identity)).text.equals(expected), "Deleted source/cache prevents reading backup");
            File note = new File(directory, "笔记.md"); BackupFiles.write(note, "用户额外文件".getBytes(StandardCharsets.UTF_8));
            File oldRoot = library.backup, moved = new File(oldRoot.getParentFile(), "LNReader_QA_Moved"); BackupFiles.mkdir(moved);
            Uri target = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/LNReader_QA_Moved");
            runOnMainSync(() -> call(activity, "changeBackupFolder", new Class<?>[]{Uri.class}, target)); idle(activity);
            check(library.backup.equals(moved) && !directory.exists() && new File(library.directory(book), "笔记.md").isFile(), "Backup migration lost extra files or retained old book");
            check(new Library(context).book(identity).has("pagination"), "Migration lost pagination/history");
            Uri original = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download/LNReader");
            runOnMainSync(() -> call(activity, "changeBackupFolder", new Class<?>[]{Uri.class}, original)); idle(activity); check(library.backup.equals(oldRoot), "Restore default root failed");
            File fixture = new File(arguments.getString("fixture")); check(fixture.isFile(), "Large fixture missing");
            File bigDirectory = library.createDirectory("无分类", "蛊真人"); BackupFiles.copy(fixture, new File(bigDirectory, "蛊真人.txt"));
            JSONObject big = json("id", "888888888888888888888888", "title", "蛊真人", "directory", library.relative(bigDirectory), "parts", new JSONArray().put("蛊真人.txt"), "shelf", "default", "position", 0, "marks", new JSONArray(), "added", System.currentTimeMillis());
            long start = SystemClock.elapsedRealtime(); Library.Document cold = library.document(big); long coldMs = SystemClock.elapsedRealtime() - start;
            Library fresh = new Library(context); start = SystemClock.elapsedRealtime(); Library.Document disk = fresh.document(big); long diskMs = SystemClock.elapsedRealtime() - start;
            check(cold.toc.size() > 2000 && cold.toc.size() < 2399 && cold.text.equals(disk.text), "Narrow default grammar/cache invalid");
            set(big, "length", cold.text.length()); set(big, "chapterCount", cold.toc.size()); set(big, "contentHash", cold.contentHash);
            runOnMainSync(() -> { library.books.add(big); call(activity, "persist", new Class<?>[]{}); });
            List<JSONObject> many = new ArrayList<>();
            for (int i = 0; i < 700; i++) {
                String title = String.format(Locale.ROOT, "QA700-%03d", i), content = "第一章 测试\n正文" + i + "。\n"; File dir = library.createDirectory("无分类", title); BackupFiles.write(new File(dir, title + ".txt"), content.getBytes(StandardCharsets.UTF_8));
                many.add(json("id", String.format(Locale.ROOT, "%024x", 2000 + i), "title", title, "shelf", "default", "directory", library.relative(dir), "directoryName", dir.getName(), "organizedTitle", title, "parts", new JSONArray().put(title + ".txt"), "position", 0, "length", content.length(), "marks", new JSONArray(), "added", 2000 + i));
            }
            runOnMainSync(() -> { library.books.addAll(many); library.shelves.add(json("id", "qa-category", "name", "QA分类")); field(activity, "active", "all"); call(activity, "showShelf", new Class<?>[]{}); call(activity, "persist", new Class<?>[]{}); });
            waitForIdleSync(); check(onMain(() -> ((GridView) field(activity, "bookGrid")).getAdapter().getCount() == 15), "Shelf page is not limited to 15 books");
            runOnMainSync(() -> { ((GridView) field(activity, "bookGrid")).getChildAt(0).performLongClick(); named(activity.getWindow().getDecorView(), "全选当前筛选").performClick(); check(((Set<?>) field(activity, "selectedBooks")).size() == 702, "Select-all stopped at the visible 15-book page"); named(activity.getWindow().getDecorView(), "取消选择").performClick(); });
            waitUntil(() -> { try { return new JSONObject(Library.fastText(new File(library.backup, "userdata.json"))).getJSONArray("books").length() == 702; } catch (Exception e) { return false; } });
            output.putString("stream", "PASS: byte-preserving backup, content dedup, actual removal, stable cover, per-book rules, persistent pagination, source independence, movable backup and 15-book pages. cold_ms=" + coldMs + " disk_ms=" + diskMs + " toc=" + cold.toc.size() + "\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) { output.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, output); }
    }
}
