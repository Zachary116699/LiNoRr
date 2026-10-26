package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.lnreader.app.Library.*;

/** Session visibility, real title taps, portable metadata and private import on the QA emulator. */
final class PrivacyChecks {
    private static Object get(Object o, String key) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void put(Object o, String key, Object value) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o, value); } catch (Exception e) { throw new RuntimeException(e); } }
    private static Object call(Object o, String key, Class<?>[] types, Object... args) { try { Method m = o.getClass().getDeclaredMethod(key, types); m.setAccessible(true); return m.invoke(o, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static View find(View v, String s) { if (s.equals(v.getContentDescription()) || v instanceof TextView && s.contentEquals(((TextView) v).getText())) return v; if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View hit = find(((ViewGroup) v).getChildAt(i), s); if (hit != null) return hit; } return null; }
    private static void click(View root, String s) { View v = find(root, s); check(v != null && v.isEnabled(), "Missing control: " + s); v.performClick(); }
    private static Dialog shown(NativeActivity a) { for (Dialog d : ((Map<Dialog,?>) get(a, "dialogShells")).keySet()) if (d.isShowing()) return d; throw new AssertionError("Missing dialog"); }
    private static boolean anyDialog(NativeActivity a) { for (Dialog d : ((Map<Dialog,?>) get(a, "dialogShells")).keySet()) if (d.isShowing()) return true; return false; }
    private static Set<String> drawerIds(View root) { ListView list = (ListView) find(root, "书架分类列表"); Set<String> ids = new HashSet<>(); for (int i = 0; i < list.getAdapter().getCount(); i++) ids.add(((JSONObject) list.getAdapter().getItem(i)).optString("id")); return ids; }
    private static void settle(Instrumentation t, NativeActivity a) throws Exception { for (int i = 0; i < 200; i++) { boolean[] busy = {true}; t.runOnMainSync(() -> busy[0] = (boolean) get(a, "importing")); if (!busy[0]) { t.waitForIdleSync(); Thread.sleep(120); return; } Thread.sleep(50); } throw new AssertionError("Operation timed out"); }
    private static void shelf(Instrumentation t, NativeActivity a, String id) { t.runOnMainSync(() -> { put(a, "active", id); put(a, "query", ""); put(a, "searchDraft", ""); call(a, "showShelf", new Class<?>[]{}); }); t.waitForIdleSync(); }
    private static boolean in(NativeActivity a, JSONObject book, String id) { return (boolean) call(a, "inShelf", new Class<?>[]{JSONObject.class, String.class}, book, id); }
    private static void tap(Instrumentation t, View v) { int[] p = new int[2]; t.runOnMainSync(() -> { v.getLocationOnScreen(p); p[0] += v.getWidth() / 2; p[1] += v.getHeight() / 2; }); long down = SystemClock.uptimeMillis(); for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) { MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p[0], p[1], 0); e.setSource(InputDevice.SOURCE_TOUCHSCREEN); check(t.getUiAutomation().injectInputEvent(e, true), "Tap failed"); e.recycle(); } }
    private static void doubleTitle(Instrumentation t, NativeActivity a) throws Exception { View title = find(a.getWindow().getDecorView(), "LiNoRr"); tap(t, title); Thread.sleep(65); tap(t, title); t.waitForIdleSync(); Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 80); check(!anyDialog(a), "Double tap opened About"); }
    private static void photo(Instrumentation t, NativeActivity a, String name) throws Exception { t.waitForIdleSync(); Thread.sleep(180); Bitmap b = t.getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(a.getCacheDir(), name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); } b.recycle(); }
    static void run(Instrumentation t, NativeActivity a) throws Exception {
        settle(t, a); check(!(boolean) get(a, "privacyMode"), "Cold launch restored privacy mode");
        Library lib = (Library) get(a, "lib"); check(Environment.isExternalStorageManager(), "QA file permission required");
        File backup = new File(a.getCacheDir(), "privacy-backup"); if (backup.exists()) BackupFiles.deleteTree(backup); BackupFiles.mkdir(backup); lib.backup = backup; lib.portableReady = true;
        a.getSharedPreferences("storage", 0).edit().putString("backupPath", backup.getPath()).commit();
        JSONObject publicBook = json("id", "aaaaaaaaaaaaaaaaaaaaaaaa", "title", "Public QA", "position", 0, "lastRead", 100, "marks", new JSONArray());
        JSONObject secret = json("id", "bbbbbbbbbbbbbbbbbbbbbbbb", "title", "Hidden QA", "private", true, "position", 0, "lastRead", 200, "marks", new JSONArray());
        setShelfIds(publicBook, Arrays.asList("mixed")); setShelfIds(secret, Arrays.asList("hidden-only", "mixed"));
        for (JSONObject book : Arrays.asList(publicBook, secret)) { String path = "book/" + book.optString("title"); File dir = new File(backup, path); BackupFiles.mkdir(dir); BackupFiles.write(new File(dir, "original.txt"), ("第一章 测试\n" + book.optString("title") + "\n正文。\n").getBytes(StandardCharsets.UTF_8)); set(book, "directory", path); set(book, "parts", new JSONArray().put("original.txt")); set(book, "sources", new JSONArray().put(book.optString("title") + ".txt")); }
        t.runOnMainSync(() -> { lib.books.clear(); lib.books.add(publicBook); lib.books.add(secret); lib.shelves.clear(); lib.shelves.add(json("id", "default", "name", "未分类")); lib.shelves.add(json("id", "hidden-only", "name", "Hidden Shelf")); lib.shelves.add(json("id", "mixed", "name", "Mixed Shelf")); lib.shelves.add(json("id", "empty", "name", "Empty Shelf")); });
        shelf(t, a, "all"); String theme = lib.state.optString("uiTheme"); int day = (int) call(a, "shelfBg", new Class<?>[]{});
        t.runOnMainSync(() -> {
            check(in(a, publicBook, "all") && !in(a, secret, "all") && !in(a, secret, "recent") && !in(a, secret, "mixed"), "Private book visible in ordinary shelves");
            check(((List<?>) get(a, "filteredBooks")).size() == 1, "Ordinary count includes private books");
            check(find(a.getWindow().getDecorView(), "退出隐私模式") == null && find(a.getWindow().getDecorView(), "添加到隐私") == null, "Ordinary controls reveal privacy");
            call(a, "bookEditor", new Class<?>[]{JSONObject.class}, secret); check(!anyDialog(a), "Stale private book can open editor");
            call(a, "openDrawer", new Class<?>[]{}); Dialog d = (Dialog) get(a, "drawer"); View root = d.getWindow().getDecorView(); Set<String> visible = drawerIds(root); check(!visible.contains("hidden-only") && !visible.contains("private") && visible.contains("mixed") && visible.contains("empty"), "Drawer leaks private-only shelves or hides empty/mixed shelf"); d.dismiss();
            call(a, "importDialog", new Class<?>[]{}); d = shown(a); root = d.getWindow().getDecorView(); check(find(root, "添加到隐私") == null && find(root, "分类：Hidden Shelf") == null, "Ordinary import exposes privacy"); d.dismiss();
            put(a, "query", "Hidden QA"); call(a, "scheduleSearch", new Class<?>[]{});
        }); Thread.sleep(220); t.runOnMainSync(() -> check(((List<?>) get(a, "filteredBooks")).isEmpty(), "Search reveals hidden title"));
        shelf(t, a, "all"); tap(t, find(a.getWindow().getDecorView(), "LiNoRr")); Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 160);
        t.runOnMainSync(() -> { Dialog d = shown(a); View root = d.getWindow().getDecorView(); check(find(root, "关于 LiNoRr") != null && find(root, "0.9.16") != null && find(root, "超绝可爱大水笔 & ChatGPT 6.1 Sol") != null, "About metadata missing"); d.dismiss(); });
        doubleTitle(t, a); check((boolean) get(a, "privacyMode"), "Double tap did not enter privacy");
        t.runOnMainSync(() -> { check(find(a.getWindow().getDecorView(), "退出隐私模式") != null, "Eye missing"); check((int) call(a, "shelfBg", new Class<?>[]{}) != day, "Privacy palette unchanged"); check(in(a, secret, "all") && in(a, secret, "private") && !in(a, publicBook, "private"), "Private builtin filter incorrect"); call(a, "openDrawer", new Class<?>[]{}); Dialog d = (Dialog) get(a, "drawer"); check(drawerIds(d.getWindow().getDecorView()).containsAll(Arrays.asList("private", "hidden-only")), "Private drawer incomplete"); click(d.getWindow().getDecorView(), "编辑书架"); });
        t.waitForIdleSync(); t.runOnMainSync(() -> { Dialog d = (Dialog) get(a, "drawer"); click(d.getWindow().getDecorView(), "管理隐私：Mixed Shelf"); click(shown(a).getWindow().getDecorView(), "添加到隐私"); check(publicBook.optBoolean("private") && secret.optBoolean("private"), "Shelf privacy action omitted a membership"); call(a, "changePrivacy", new Class<?>[]{Set.class, boolean.class}, Collections.singleton(publicBook.optString("id")), false); });
        shelf(t, a, "private"); photo(t, a, "privacy-shelf");
        t.runOnMainSync(() -> { call(a, "bookEditor", new Class<?>[]{JSONObject.class}, secret); click(shown(a).getWindow().getDecorView(), "移出隐私"); check(!secret.optBoolean("private"), "Book management did not remove privacy"); });
        shelf(t, a, "all"); t.runOnMainSync(() -> { ((Set<String>) get(a, "selectedBooks")).add(secret.optString("id")); put(a, "selectionMode", true); put(a, "toolsExpanded", true); call(a, "renderCards", new Class<?>[]{Set.class}, (Object) null); click(a.getWindow().getDecorView(), "添加到隐私"); check(secret.optBoolean("private"), "Batch add failed"); ((Set<String>) get(a, "selectedBooks")).add(secret.optString("id")); click(a.getWindow().getDecorView(), "移出隐私"); check(!secret.optBoolean("private"), "Batch remove failed"); call(a, "changePrivacy", new Class<?>[]{Set.class, boolean.class}, Collections.singleton(secret.optString("id")), true); });
        IntentFilter fileFilter = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT); fileFilter.addCategory(Intent.CATEGORY_OPENABLE); fileFilter.addDataType("*/*");
        Instrumentation.ActivityMonitor picker = t.addMonitor(fileFilter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        t.runOnMainSync(() -> { call(a, "importDialog", new Class<?>[]{}); View root = shown(a).getWindow().getDecorView(); ((CheckBox) find(root, "添加到隐私")).setChecked(true); click(root, "选择 TXT 文件（支持多选）"); });
        check((boolean) get(a, "importPrivate"), "Import checkbox was not captured"); check(picker.getHits() == 1, "Document intent missing; monitor hits=" + picker.getHits()); t.removeMonitor(picker); t.waitForIdleSync();
        File input = new File(a.getCacheDir(), "Private import QA.txt"); BackupFiles.write(input, "第一章 导入\n隐私导入测试专用正文，内容唯一。\n".getBytes(StandardCharsets.UTF_8));
        Class<?> entryClass = Class.forName("com.lnreader.app.NativeActivity$Entry"); Constructor<?> constructor = entryClass.getDeclaredConstructor(Uri.class, String.class, long.class); constructor.setAccessible(true); Object entry = constructor.newInstance(Uri.fromFile(input), input.getName(), input.length());
        t.runOnMainSync(() -> call(a, "showBusy", new Class<?>[]{String.class}, "QA 导入"));
        ((ExecutorService) get(a, "io")).submit(() -> call(a, "importEntries", new Class<?>[]{List.class, String.class, String.class, JSONObject.class}, Collections.singletonList(entry), "QA", "", null)).get(); settle(t, a);
        JSONObject imported = lib.books.stream().filter(b -> b.optString("title").equals("Private import QA")).findFirst().orElseThrow(); check(imported.optBoolean("private"), "Imported privacy flag missing"); check(lib.partFiles(imported).get(0).isFile(), "Private import did not back up text");
        input.delete(); check(!lib.document(imported).text.isEmpty(), "Deleted original broke private backup");
        t.runOnMainSync(() -> { set(lib.prefs(), "night", true); call(a, "showShelf", new Class<?>[]{}); }); photo(t, a, "privacy-night");
        t.runOnMainSync(() -> { check((int) call(a, "shelfBg", new Class<?>[]{}) == 0xff2c2020, "Private night palette missing"); set(lib.prefs(), "night", false); call(a, "showShelf", new Class<?>[]{}); });
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(home); Thread.sleep(250); a.startActivity(new Intent(a, NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); Thread.sleep(350); check((boolean) get(a, "privacyMode") && !a.isFinishing(), "Background return reset privacy");
        t.runOnMainSync(() -> { put(a, "active", "hidden-only"); put(a, "query", "Hidden QA"); ((Set<String>) get(a, "selectedBooks")).add(secret.optString("id")); put(a, "selectionMode", true); call(a, "showShelf", new Class<?>[]{}); });
        doubleTitle(t, a); t.runOnMainSync(() -> { check(!(boolean) get(a, "privacyMode") && "all".equals(get(a, "active")), "Exit left hidden shelf active"); check(((Set<?>) get(a, "selectedBooks")).isEmpty() && get(a, "query").equals(""), "Exit retained private search/selection"); check(((List<?>) get(a, "filteredBooks")).size() == 1, "Exit leaked private books"); check((int) call(a, "shelfBg", new Class<?>[]{}) == day && lib.state.optString("uiTheme").equals(theme), "Exit changed saved palette"); });
        photo(t, a, "ordinary-shelf"); doubleTitle(t, a);
        t.runOnMainSync(() -> click(a.getWindow().getDecorView(), "退出隐私模式")); check(!(boolean) get(a, "privacyMode"), "Eye did not exit"); doubleTitle(t, a);
        ((ExecutorService) get(a, "saveIo")).submit(() -> {}).get(); lib.saveSnapshot(lib.snapshot());
        JSONObject saved = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(backup, "userdata.json").toPath()), StandardCharsets.UTF_8)); check(!saved.has("privacyMode"), "Session mode was persisted"); check(saved.getJSONArray("books").getJSONObject(1).optBoolean("private"), "Privacy flag not exported");
        // Leave privacy active; the host force-stops and verifies an ordinary cold launch.
    }
}
