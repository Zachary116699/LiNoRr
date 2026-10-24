package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.widget.GridView;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.lnreader.app.Library.*;

/** Standalone device checks; installed only on the guarded LNReader_QA emulator. */
public final class StorageChecks extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private Object field(Object target, String name) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch (Exception e) { throw new RuntimeException(e); } }
    private Object call(Object target, String name, Class<?>[] types, Object... args) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private void waitUntil(BooleanSupplier condition) throws Exception { long end = SystemClock.elapsedRealtime() + 20000; while (!condition.getAsBoolean()) { if (SystemClock.elapsedRealtime() > end) throw new AssertionError("Timed out"); Thread.sleep(50); } }
    private boolean onMain(BooleanSupplier condition) { boolean[] value = {false}; runOnMainSync(() -> value[0] = condition.getAsBoolean()); return value[0]; }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            check("LNReader_QA".equals(arguments.getString("guard")), "Dedicated emulator guard required");
            Context context = getTargetContext(); NativeActivity activity = (NativeActivity) startActivitySync(new Intent(context, NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitUntil(() -> onMain(() -> field(activity, "lib") != null && !(Boolean) field(activity, "importing")));
            Library library = (Library) field(activity, "lib"); check(library.canWrite(), "All-files access must be granted by guarded host test");
            File inputs = new File(context.getCacheDir(), "qa-input"); BackupFiles.mkdir(inputs);
            byte[] aText = "EP.01 第一段\r\n\r\n第一份原文。\r\n".getBytes(StandardCharsets.UTF_16LE), a = new byte[aText.length + 2]; a[0] = (byte) 0xff; a[1] = (byte) 0xfe; System.arraycopy(aText, 0, a, 2, aText.length);
            byte[] b = "EP.02 第二段\n\n第二份原文。\n".getBytes(Charset.forName("GB18030"));
            File first = new File(inputs, "第一个原名.txt"), second = new File(inputs, "第二个原名.txt"); BackupFiles.write(first, a); BackupFiles.write(second, b);
            Class<?> entryClass = Class.forName("com.lnreader.app.NativeActivity$Entry"); Constructor<?> constructor = entryClass.getDeclaredConstructor(Uri.class, String.class, long.class); constructor.setAccessible(true);
            List<Object> entries = new ArrayList<>(); entries.add(constructor.newInstance(Uri.fromFile(first), first.getName(), first.length())); entries.add(constructor.newInstance(Uri.fromFile(second), second.getName(), second.length()));
            runOnMainSync(() -> { try { Field grouping = NativeActivity.class.getDeclaredField("grouping"); grouping.setAccessible(true); grouping.set(activity, "merged"); call(activity, "showBusy", new Class<?>[]{String.class}, "正在检查原始文件备份…"); } catch (Exception e) { throw new RuntimeException(e); } });
            call(activity, "importEntries", new Class<?>[]{List.class, String.class, String.class, JSONObject.class}, entries, "QA多文件", "", null);
            waitUntil(() -> onMain(() -> !(Boolean) field(activity, "importing") && library.books.size() == 1));
            JSONObject book = library.books.get(0); File directory = library.directory(book);
            check(Arrays.equals(a, read(new FileInputStream(new File(directory, first.getName())), 10000)), "UTF16 original bytes changed");
            check(Arrays.equals(b, read(new FileInputStream(new File(directory, second.getName())), 10000)), "GB18030 original bytes changed");
            check(book.getJSONArray("parts").length() == 2, "Merged source parts missing");
            String expected = BookParser.decode(a) + BookParser.decode(b); Library.Document opened = library.document(book);
            check(opened.text.equals(expected), "Merged reader added or removed content"); check(opened.toc.size() == 2, "EP headings not detected"); first.delete(); second.delete();
            Bitmap picture = Bitmap.createBitmap(2048, 3072, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(picture); canvas.drawColor(Color.rgb(41, 89, 131)); Paint paint = new Paint(); paint.setColor(Color.rgb(219, 183, 92)); canvas.drawRect(100, 100, 1600, 2400, paint);
            File originalCover = new File(inputs, "原封面.png"); try (OutputStream stream = new FileOutputStream(originalCover)) { picture.compress(Bitmap.CompressFormat.PNG, 100, stream); } picture.recycle();
            runOnMainSync(() -> { try { Field target = NativeActivity.class.getDeclaredField("coverTarget"); target.setAccessible(true); target.set(activity, book); call(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, 54, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(originalCover))); } catch (Exception e) { throw new RuntimeException(e); } });
            waitUntil(() -> onMain(() -> !(Boolean) field(activity, "importing") && book.optBoolean("cover")));
            BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true; BitmapFactory.decodeFile(new File(directory, "cover.jpg").getPath(), size); check(Math.max(size.outWidth, size.outHeight) <= 720, "Cover not resized"); check(originalCover.isFile(), "Original cover deleted");
            runOnMainSync(() -> { set(book, "position", 18); set(book, "lastRead", 123456789L); set(book, "marks", new JSONArray().put(json("offset", 18, "title", "EP.01 第一段", "date", 123L))); call(activity, "persist", new Class<?>[]{}); });
            waitUntil(() -> { try { JSONObject data = new JSONObject(Library.fastText(new File(library.backup, "userdata.json"))); return data.getJSONArray("books").getJSONObject(0).getJSONArray("marks").length() == 1; } catch (Exception e) { return false; } });
            File[] cached = library.cache.listFiles(); if (cached != null) for (File f : cached) if (f.getName().startsWith(book.optString("id"))) f.delete();
            Library restored = new Library(context); JSONObject restoredBook = restored.book(book.getString("id")); check(restoredBook != null && restoredBook.optInt("position") == 18 && restoredBook.getJSONArray("marks").length() == 1, "Portable history restore failed"); check(restored.document(restoredBook).text.equals(expected), "Deleted source/cache prevents reading backup");
            File fixture = new File(arguments.getString("fixture")); check(fixture.isFile(), "Large fixture missing");
            File bigDirectory = library.createDirectory("无分类", "蛊真人"); BackupFiles.copy(fixture, new File(bigDirectory, "蛊真人.txt"));
            JSONObject big = json("id", "888888888888888888888888", "title", "蛊真人", "directory", library.relative(bigDirectory), "parts", new JSONArray().put("蛊真人.txt"), "shelf", "default", "position", 0, "marks", new JSONArray(), "added", System.currentTimeMillis());
            long start = SystemClock.elapsedRealtime(); Library.Document cold = library.document(big); long coldMs = SystemClock.elapsedRealtime() - start;
            Library fresh = new Library(context); start = SystemClock.elapsedRealtime(); Library.Document disk = fresh.document(big); long diskMs = SystemClock.elapsedRealtime() - start;
            start = SystemClock.elapsedRealtime(); Library.Document memory = fresh.document(big); long memoryMs = SystemClock.elapsedRealtime() - start;
            check(cold.toc.size() > 1000 && cold.text.equals(disk.text) && disk == memory, "Large document/index cache invalid");
            set(big, "length", cold.text.length()); set(big, "chapterCount", cold.toc.size()); set(big, "contentSignature", cold.signature);
            runOnMainSync(() -> { library.books.add(big); call(activity, "persist", new Class<?>[]{}); call(activity, "openBook", new Class<?>[]{JSONObject.class, int.class}, big, 0); });
            waitUntil(() -> onMain(() -> field(activity, "pagedReader") != null && ((PagedReaderView) field(activity, "pagedReader")).ready() && !(Boolean) field(activity, "importing")));
            runOnMainSync(() -> call(activity, "back", new Class<?>[]{}));
            List<JSONObject> many = new ArrayList<>(); for (int i = 0; i < 700; i++) { String title = String.format(Locale.ROOT, "QA700-%03d", i); File dir = library.createDirectory("无分类", title); BackupFiles.write(new File(dir, title + ".txt"), ("EP.1 测试\n正文。\n").getBytes(StandardCharsets.UTF_8)); many.add(json("id", String.format(Locale.ROOT, "%024x", 2000 + i), "title", title, "shelf", "default", "directory", library.relative(dir), "directoryName", dir.getName(), "organizedTitle", title, "parts", new JSONArray().put(title + ".txt"), "position", 0, "length", 20, "marks", new JSONArray(), "added", 2000 + i)); }
            long[] shelfMs = {0};
            runOnMainSync(() -> { library.books.addAll(many); library.shelves.add(json("id", "qa-category", "name", "QA分类")); try { Field active = NativeActivity.class.getDeclaredField("active"); active.setAccessible(true); active.set(activity, "all"); } catch (Exception e) { throw new RuntimeException(e); } long began = SystemClock.elapsedRealtime(); call(activity, "showShelf", new Class<?>[]{}); shelfMs[0] = SystemClock.elapsedRealtime() - began; call(activity, "persist", new Class<?>[]{}); });
            waitForIdleSync(); check(onMain(() -> ((GridView) field(activity, "bookGrid")).getChildCount() < 60), "Shelf creates views for every book");
            waitUntil(() -> { try { return new JSONObject(Library.fastText(new File(library.backup, "userdata.json"))).getJSONArray("books").length() == 702; } catch (Exception e) { return false; } });
            output.putString("stream", "PASS: byte-preserving multipart backup, compressed cover, deleted-source/cache recovery, portable history, EP recognition, 700-book recycling. cold_ms=" + coldMs + " disk_ms=" + diskMs + " memory_ms=" + memoryMs + " shelf_build_ms=" + shelfMs[0] + " toc=" + cold.toc.size() + "\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) { output.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, output); }
    }
}
