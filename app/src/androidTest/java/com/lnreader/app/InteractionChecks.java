package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import static com.lnreader.app.Library.*;

/** Real native controls against a temporary backup on the dedicated QA emulator. */
final class InteractionChecks {
    private static Object get(Object o, String key) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void put(Object o, String key, Object value) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o, value); } catch (Exception e) { throw new RuntimeException(e); } }
    private static Object call(Object o, String key, Class<?>[] types, Object... args) { try { Method m = o.getClass().getDeclaredMethod(key, types); m.setAccessible(true); return m.invoke(o, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static View find(View view, String label) { if (label.equals(view.getContentDescription()) || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found; } return null; }
    private static void click(View root, String label) { View view = find(root, label); check(view != null && view.isEnabled(), "Missing enabled control: " + label); view.performClick(); }
    private static void tap(Instrumentation t, View view) { int[] point = new int[2]; t.runOnMainSync(() -> { view.getLocationOnScreen(point); point[0] += view.getWidth() / 2; point[1] += view.getHeight() / 2; }); long at = SystemClock.uptimeMillis(); for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) { MotionEvent event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, point[0], point[1], 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); check(t.getUiAutomation().injectInputEvent(event, true), "Native tap was rejected"); event.recycle(); } }
    private static Dialog shown(NativeActivity a) { for (Dialog d : ((Map<Dialog,?>) get(a, "dialogShells")).keySet()) if (d.isShowing()) return d; throw new AssertionError("Sheet missing"); }
    private static void settle(Instrumentation t, NativeActivity a) throws Exception { for (int i = 0; i < 200; i++) { boolean[] busy = {true}; t.runOnMainSync(() -> busy[0] = (boolean) get(a, "importing")); if (!busy[0]) { t.waitForIdleSync(); Thread.sleep(100); return; } Thread.sleep(50); } throw new AssertionError("Timed out waiting for native transaction"); }
    private static void shot(Instrumentation t, NativeActivity a, String name) throws Exception { t.waitForIdleSync(); Thread.sleep(120); Bitmap image = t.getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(a.getCacheDir(), name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle(); }
    private static void shelf(Instrumentation t, NativeActivity a, String id) { t.runOnMainSync(() -> { put(a, "active", id); put(a, "query", ""); put(a, "searchDraft", ""); call(a, "showShelf", new Class<?>[]{}); }); }
    private static void select(Instrumentation t, NativeActivity a, String... ids) { t.runOnMainSync(() -> { Set<String> selected = (Set<String>) get(a, "selectedBooks"); selected.clear(); selected.addAll(Arrays.asList(ids)); put(a, "selectionMode", true); put(a, "toolsExpanded", true); call(a, "updateShelfTools", new Class<?>[]{}); call(a, "renderCards", new Class<?>[]{Set.class}, (Object) null); }); }
    static void run(Instrumentation t, NativeActivity a) throws Exception {
        settle(t, a); Library lib = (Library) get(a, "lib"); check(Environment.isExternalStorageManager(), "QA file permission required");
        check(!(boolean) get(a, "toolsExpanded"), "Cold launch expanded the search tools");
        File backup = new File(a.getCacheDir(), "interaction-backup"); if (backup.exists()) BackupFiles.deleteTree(backup); lib.backup = backup; lib.portableReady = true;
        JSONObject first = json("id", "qa-first", "name", "QA First"), second = json("id", "qa-second", "name", "QA Second");
        String content = "第一章 开始\n" + "正文测试分类和阅读菜单的操作。\n".repeat(160);
        JSONObject one = json("id", "cccccccccccccccccccccccc", "title", "QA Book A", "position", 8, "lastRead", 100, "marks", new JSONArray()), two = json("id", "bbbbbbbbbbbbbbbbbbbbbbbb", "title", "QA Book B", "position", 0, "lastRead", 200, "marks", new JSONArray());
        setShelfIds(one, Arrays.asList("qa-first", "qa-second")); setShelfIds(two, Arrays.asList("qa-first"));
        for (JSONObject book : Arrays.asList(one, two)) { File dir = new File(backup, "book/QA First/" + book.optString("title")); BackupFiles.mkdir(dir); BackupFiles.write(new File(dir, "original.txt"), content.getBytes(StandardCharsets.UTF_8)); set(book, "directory", "book/QA First/" + book.optString("title")); set(book, "parts", new JSONArray().put("original.txt")); set(book, "sources", new JSONArray().put("original.txt")); }
        t.runOnMainSync(() -> { lib.books.clear(); lib.books.add(one); lib.books.add(two); lib.shelves.clear(); lib.shelves.add(json("id", "default", "name", "未分类")); lib.shelves.add(first); lib.shelves.add(second); });
        shelf(t, a, "recent"); select(t, a, one.optString("id"));
        t.runOnMainSync(() -> click(a.getWindow().getDecorView(), "移出书架")); settle(t, a);
        check((boolean) get(a, "toolsExpanded"), "Batch completion collapsed the search tools");
        check(one.optBoolean("hiddenRecent") && one.optLong("lastRead") == 100 && one.optInt("position") == 8 && lib.directory(one).isDirectory(), "Recent removal lost history or files");
        t.runOnMainSync(() -> ((Runnable) get(a, "exportTask")).run()); ((ExecutorService) get(a, "saveIo")).submit(() -> {}).get(); JSONObject exported = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(backup, "userdata.json").toPath()), StandardCharsets.UTF_8)); check(exported.getJSONArray("books").getJSONObject(0).optBoolean("hiddenRecent"), "Recent hiding was not persisted");
        t.runOnMainSync(() -> call(a, "openBook", new Class<?>[]{JSONObject.class, int.class}, one, -1)); settle(t, a);
        check(!one.optBoolean("hiddenRecent") && (boolean) call(a, "inShelf", new Class<?>[]{JSONObject.class, String.class}, one, "recent"), "Reading did not restore the recent entry");
        t.runOnMainSync(() -> call(a, "back", new Class<?>[]{}));
        check(!(boolean) get(a, "toolsExpanded") && !(boolean) get(a, "selectionMode"), "Reader return did not collapse tools and selection");
        t.runOnMainSync(() -> click(a.getWindow().getDecorView(), "展开或收起筛选排序"));
        a.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Thread.sleep(250);
        a.startActivity(new Intent(a, NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); Thread.sleep(500);
        check((boolean) get(a, "toolsExpanded") && a.hasWindowFocus(), "Background return collapsed the search tools");
        t.runOnMainSync(() -> call(a, "openDrawer", new Class<?>[]{})); t.waitForIdleSync();
        t.runOnMainSync(() -> { Dialog d = (Dialog) get(a, "drawer"); ListView shelves = (ListView) find(d.getWindow().getDecorView(), "书架分类列表"); for (int i = 0; i < shelves.getAdapter().getCount(); i++) if (((JSONObject) shelves.getAdapter().getItem(i)).optString("id").equals("qa-first")) { shelves.performItemClick(shelves.getChildAt(i - shelves.getFirstVisiblePosition()), i, shelves.getAdapter().getItemId(i)); break; } });
        check(get(a, "active").equals("qa-first") && !(boolean) get(a, "toolsExpanded"), "Switching shelves did not collapse the search tools");
        shelf(t, a, "qa-first"); select(t, a, one.optString("id"));
        t.runOnMainSync(() -> { View root = a.getWindow().getDecorView(); click(root, "全选当前筛选"); View all = find(root, "全选当前筛选"), cancel = find(root, "取消选择"); check(all.getParent() == cancel.getParent() && ((ViewGroup) all.getParent()).indexOfChild(all) < ((ViewGroup) cancel.getParent()).indexOfChild(cancel), "Select-all is not before Cancel"); View move = find(root, "移出书架"), remove = find(root, "移除所选"); check(move.getLeft() < remove.getLeft(), "Move-out is not before Remove"); }); shot(t, a, "batch-controls");
        t.runOnMainSync(() -> click(a.getWindow().getDecorView(), "移出书架")); settle(t, a);
        check(shelfIds(one).equals(Arrays.asList("qa-second")) && shelfIds(two).isEmpty(), "Current-shelf removal damaged other memberships");
        check(one.optString("directory").startsWith("book/QA Second/") && two.optString("directory").startsWith("book/无分类/") && new File(lib.directory(one), "original.txt").isFile(), "First-membership backup did not move safely");
        shelf(t, a, "all"); select(t, a, one.optString("id"));
        t.runOnMainSync(() -> { click(a.getWindow().getDecorView(), "移出书架"); View body = shown(a).getWindow().getDecorView(); click(body, "展开或收起所属分类"); click(body, "分类：QA Second"); click(body, "移出所选书架"); }); settle(t, a); check(shelfIds(one).isEmpty(), "All-books removal did not honor selected categories");
        t.runOnMainSync(() -> { setShelfIds(one, Arrays.asList("qa-first")); call(a, "organizeAndRefresh", new Class<?>[]{}); }); settle(t, a);
        shelf(t, a, "qa-first"); t.runOnMainSync(() -> call(a, "importDialog", new Class<?>[]{}));
        Dialog importing = shown(a); View importRoot = importing.getWindow().getDecorView();
        t.runOnMainSync(() -> { check(!find(importRoot, "分类：QA First").isShown(), "Import categories start expanded"); TextView summary = (TextView) find(importRoot, "展开或收起所属分类"); check(summary.getText().toString().contains("未分类"), "Import inherited the active category"); click(importRoot, "展开或收起所属分类"); click(importRoot, "新建书架"); ((EditText) find(importRoot, "新书架名称")).setText("QA New"); click(importRoot, "创建并选择书架"); });
        t.runOnMainSync(() -> { CheckBox box = (CheckBox) find(importRoot, "分类：QA New"); check(box != null && box.isChecked(), "Quick category creation did not select the new category"); }); shot(t, a, "import-categories"); t.runOnMainSync(importing::dismiss);
        t.runOnMainSync(() -> call(a, "openDrawer", new Class<?>[]{})); Dialog drawer = (Dialog) get(a, "drawer"); View drawerRoot = drawer.getWindow().getDecorView();
        t.runOnMainSync(() -> click(drawerRoot, "编辑书架")); t.waitForIdleSync(); tap(t, find(drawerRoot, "重命名QA First")); t.waitForIdleSync(); Thread.sleep(160);
        shot(t, a, "rename-focus");
        tap(t, find(drawerRoot, "书架改名输入框")); t.waitForIdleSync();
        t.runOnMainSync(() -> { check(!find(drawerRoot, "拖动QA Second").isEnabled(), "Reordering remains enabled while renaming"); EditText input = (EditText) find(drawerRoot, "书架改名输入框"); check(input != null && input.hasFocus(), "Touching the name canceled editing"); input.setText(""); });
        t.sendStringSync("Discarded"); t.runOnMainSync(() -> check(((EditText) find(drawerRoot, "书架改名输入框")).getText().toString().equals("Discarded"), "Real keyboard input failed")); tap(t, find(drawerRoot, "取消重命名")); t.waitForIdleSync(); check(first.optString("name").equals("QA First"), "Cancel saved the draft");
        t.runOnMainSync(() -> click(drawerRoot, "重命名QA First")); t.waitForIdleSync(); Thread.sleep(120);
        t.runOnMainSync(() -> ((EditText) find(drawerRoot, "书架改名输入框")).setText("Outside"));
        int[] origin = new int[2]; drawerRoot.getLocationOnScreen(origin); long at = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) { MotionEvent event = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, origin[0] + 30, origin[1] + 80, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); t.getUiAutomation().injectInputEvent(event, true); event.recycle(); }
        t.waitForIdleSync(); t.runOnMainSync(() -> check(find(drawerRoot, "书架改名输入框") == null && first.optString("name").equals("QA First"), "Outside tap did not cancel rename"));
        t.runOnMainSync(() -> click(drawerRoot, "重命名QA First")); t.waitForIdleSync(); Thread.sleep(120);
        t.runOnMainSync(() -> { ((EditText) find(drawerRoot, "书架改名输入框")).setText("QA Second"); click(drawerRoot, "确认重命名"); check(first.optString("name").equals("QA First"), "Duplicate category name was accepted"); ((EditText) find(drawerRoot, "书架改名输入框")).setText("QA Renamed"); }); shot(t, a, "inline-rename");
        tap(t, find(drawerRoot, "确认重命名")); settle(t, a);
        check(drawer.isShowing() && first.optString("name").equals("QA Renamed") && one.optString("directory").startsWith("book/QA Renamed/") && new File(lib.directory(one), "original.txt").isFile(), "Inline commit or renamed backup migration failed");
        t.runOnMainSync(() -> { check(find(drawerRoot, "拖动QA Renamed").isEnabled(), "Drag was not restored after rename"); click(drawerRoot, "重命名QA Renamed"); }); t.waitForIdleSync();
        tap(t, find(drawerRoot, "书架改名输入框")); t.runOnMainSync(() -> ((EditText) find(drawerRoot, "书架改名输入框")).setText("QA Enter")); t.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); settle(t, a);
        check(first.optString("name").equals("QA Enter") && one.optString("directory").startsWith("book/QA Enter/"), "Hardware Enter did not commit rename"); t.runOnMainSync(drawer::dismiss);
        shelf(t, a, "all"); t.runOnMainSync(() -> { View root = a.getWindow().getDecorView(); EditText search = (EditText) find(root, "搜索书名或文件名"); if (!search.isShown()) click(root, "展开或收起筛选排序"); search.setText("NoMatches"); check(find(root, "清空搜索").isEnabled(), "Clear search remained disabled"); click(root, "搜索书籍"); }); Thread.sleep(220);
        check(((List<?>) get(a, "filteredBooks")).isEmpty(), "Search fixture did not filter"); tap(t, find(a.getWindow().getDecorView(), "清空搜索")); Thread.sleep(220);
        t.runOnMainSync(() -> { View root = a.getWindow().getDecorView(); check(((List<?>) get(a, "filteredBooks")).size() == 2 && !find(root, "清空搜索").isEnabled(), "Clear search did not reset filtering and disable itself");
            ImageView toggle = (ImageView) find(root, "切换列表或网格显示"); int color = ((android.graphics.Paint) get(toggle.getDrawable(), "paint")).getColor(); toggle.performClick(); check(((android.graphics.Paint) get(toggle.getDrawable(), "paint")).getColor() == color, "View toggle lost the active UI color"); });
        batchProgress(t, a, lib);
    }
    private static void batchProgress(Instrumentation t, NativeActivity a, Library lib) throws Exception {
        Class<?> entry = Class.forName("com.lnreader.app.NativeActivity$Entry"); Constructor<?> ctor = entry.getDeclaredConstructor(android.net.Uri.class, String.class, long.class); ctor.setAccessible(true); List<Object> entries = new ArrayList<>();
        File input = new File(a.getCacheDir(), "batch-input"); BackupFiles.mkdir(input);
        for (int i = 0; i < 20; i++) { File file = new File(input, "Batch-" + i + ".txt"); BackupFiles.write(file, ("第一章 Batch " + i + "\n" + "批量导入的正文内容。\n".repeat(4000)).getBytes(StandardCharsets.UTF_8)); entries.add(ctor.newInstance(android.net.Uri.fromFile(file), file.getName(), file.length())); }
        t.runOnMainSync(() -> { put(a, "grouping", "separate"); put(a, "importShelf", "default"); ((Set<?>) get(a, "importShelves")).clear(); call(a, "showBusy", new Class<?>[]{String.class}, "批量导入进度测试"); });
        ((ExecutorService) get(a, "io")).execute(() -> call(a, "importEntries", new Class<?>[]{List.class, String.class, String.class, JSONObject.class}, entries, "Batch", "", null));
        boolean captured = false; for (int i = 0; i < 500 && (boolean) get(a, "importing"); i++) { if (!captured && ((ProgressBar) get(a, "busyProgress")).getProgress() > 0) { shot(t, a, "batch-progress"); captured = true; } Thread.sleep(20); } settle(t, a);
        check(lib.books.size() == 22 && !((ProgressBar) get(a, "busyProgress")).isIndeterminate() && ((ProgressBar) get(a, "busyProgress")).getProgress() == 40, "Batch import did not complete determinate progress");
        Set<String> ids = new HashSet<>(); for (JSONObject book : lib.books) if (book.optString("title").startsWith("Batch-")) ids.add(book.optString("id"));
        t.runOnMainSync(() -> call(a, "performRemove", new Class<?>[]{Set.class, Runnable.class}, ids, null)); settle(t, a);
        check(lib.books.size() == 2 && ((ProgressBar) get(a, "busyProgress")).getProgress() == 100 && !((ProgressBar) get(a, "busyProgress")).isIndeterminate(), "Batch removal did not complete determinate progress");
        for (String id : ids) check(lib.book(id) == null, "Removed book remains in metadata"); BackupFiles.deleteTree(input);
    }
}
