package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static com.lnreader.app.Library.*;

/** Held gestures are dispatched separately so assertions run before the finger is released. */
public class GestureChecks extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private Object get(Object o, String key) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); } catch (Exception e) { throw new RuntimeException(e); } }
    private Object call(Object o, String key, Class<?>[] types, Object... args) { try { Method m = o.getClass().getDeclaredMethod(key, types); m.setAccessible(true); return m.invoke(o, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void touch(NativeActivity activity, int action, float x, long down, long when) { runOnMainSync(() -> { ReaderSurface v = (ReaderSurface) get(activity, "readerSurface"); MotionEvent event = MotionEvent.obtain(down, when, action, v.getWidth() * x, v.getHeight() * .5f, 0); v.dispatchTouchEvent(event); event.recycle(); }); }
    private void touchXY(NativeActivity activity, int action, float x, float y, long down, long when) { runOnMainSync(() -> { ReaderSurface v = (ReaderSurface) get(activity, "readerSurface"); MotionEvent event = MotionEvent.obtain(down, when, action, x, y, 0); v.dispatchTouchEvent(event); event.recycle(); }); }
    private View find(View root, String value) {
        if (value.equals(String.valueOf(root.getContentDescription())) || root instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View match = find(((ViewGroup) root).getChildAt(i), value); if (match != null) return match; } return null;
    }
    private void inject(int action, float x, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); check(getUiAutomation().injectInputEvent(event, true), "Pointer injection failed"); event.recycle();
    }
    private void progressChecks(NativeActivity activity, JSONObject book) throws Exception {
        Library lib = (Library) get(activity, "lib");
        for (String mode : new String[]{"paged", "scroll"}) {
            runOnMainSync(() -> { set(lib.prefs(), "readingMode", mode); call(activity, "buildReader", new Class<?>[]{int.class}, 0); call(activity, "showControls", new Class<?>[]{boolean.class}, true); }); waitForIdleSync(); Thread.sleep(220);
            ThumbSeekBar bar = (ThumbSeekBar) get(activity, "progress"); int[] origin = new int[2]; float[] points = new float[4];
            Bitmap debug = getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(activity.getCacheDir(), "progress-before.png"))) { debug.compress(Bitmap.CompressFormat.PNG, 100, out); } debug.recycle();
            check(activity.hasWindowFocus() && bar.isShown(), "Reader not focused for seeking: busy=" + get(activity, "importing") + " dialog=" + get(activity, "busyDialog"));
            Object layout = mode.equals("paged") ? get(get(activity, "pagedReader"), "layout") : get(activity, "readingText");
            runOnMainSync(() -> { bar.getLocationOnScreen(origin); RectF thumb = bar.thumbArea(); points[0] = origin[0] + thumb.centerX(); points[1] = origin[1] + thumb.centerY(); points[2] = origin[0] + bar.getWidth() * .85f; points[3] = origin[0] + bar.getWidth() * .60f; });
            int before = book.optInt("position"); long at = SystemClock.uptimeMillis();
            inject(MotionEvent.ACTION_DOWN, points[2], points[1], at); inject(MotionEvent.ACTION_UP, points[2], points[1], at); Thread.sleep(80);
            check(book.optInt("position") == before && bar.getProgress() == 0, "Track tap changed reading position: " + mode);
            at = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, points[2], points[1], at); inject(MotionEvent.ACTION_MOVE, points[0], points[1], at); inject(MotionEvent.ACTION_UP, points[0], points[1], at); Thread.sleep(80);
            check(book.optInt("position") == before && (boolean) get(activity, "controlsVisible"), "Track drag reached reader or sought: " + mode);
            at = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, points[0], points[1], at); inject(MotionEvent.ACTION_MOVE, points[3], points[1], at); Thread.sleep(70);
            check(book.optInt("position") > before && bar.getProgress() > 300 && (boolean) get(activity, "seekPreview"), "Thumb did not render a live preview: " + mode + " position=" + book.optInt("position") + " progress=" + bar.getProgress() + " preview=" + get(activity, "seekPreview") + " dragging=" + bar.isDraggingThumb() + " target=" + get(activity, "seekTarget") + " pending=" + get(activity, "seekFramePending"));
            check(mode.equals("paged") ? get(get(activity, "pagedReader"), "layout") == layout && ((PagedReaderView) get(activity, "pagedReader")).offset() > 0 : get(activity, "readingText") == layout && ((android.widget.ScrollView) get(activity, "readingScroll")).getScrollY() > 0, "Preview did not reuse/render the current section: " + mode);
            inject(MotionEvent.ACTION_MOVE, origin[0] + bar.getWidth() - bar.getPaddingRight(), points[1], at); Thread.sleep(100);
            check((int) get(activity, "chapter") > 0 && book.optString("chapterTitle").contains("第二章"), "Live seeking did not cross chapters: " + mode);
            inject(MotionEvent.ACTION_MOVE, points[3], points[1], at); Thread.sleep(100);
            check((int) get(activity, "chapter") == 0 && book.optInt("position") > before, "Live seeking could not return to the earlier chapter: " + mode);
            inject(MotionEvent.ACTION_UP, points[3], points[1], at); Thread.sleep(120); check(book.optInt("position") > before, "Thumb release did not seek: " + mode);
            before = book.optInt("position"); int oldProgress = bar.getProgress();
            runOnMainSync(() -> points[0] = origin[0] + bar.thumbArea().centerX()); at = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, points[0], points[1], at); inject(MotionEvent.ACTION_MOVE, origin[0] + bar.getWidth() * .15f, points[1], at); inject(MotionEvent.ACTION_CANCEL, origin[0] + bar.getWidth() * .15f, points[1], at); Thread.sleep(100);
            check(book.optInt("position") == before && bar.getProgress() == oldProgress, "Canceled thumb drag committed: " + mode);
            at = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, points[0], points[1], at); inject(MotionEvent.ACTION_UP, points[0], points[1], at); Thread.sleep(80);
            check(book.optInt("position") == before && bar.getProgress() == oldProgress, "Thumb tap without dragging changed progress: " + mode);
            runOnMainSync(() -> { Bundle args = new Bundle(); args.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 250); check(bar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args), "Accessibility seek rejected"); }); Thread.sleep(100);
            check(book.optInt("position") < before && book.optInt("position") > 0, "Accessibility seek did not update reading position: " + mode);
            runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, 0)); waitForIdleSync(); Thread.sleep(120);
            runOnMainSync(() -> { check(!((View) get(activity, "bookmarkButton")).isSelected(), "Empty page shows a filled bookmark"); call(activity, "addMark", new Class<?>[]{}); check(((View) get(activity, "bookmarkButton")).isSelected(), "Adding a bookmark did not fill the icon: " + mode); });
            runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, ((String) get(activity, "source")).indexOf("第二章"))); waitForIdleSync(); Thread.sleep(120);
            runOnMainSync(() -> check(!((View) get(activity, "bookmarkButton")).isSelected(), "Bookmark from another chapter fills this page: " + mode));
            runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, 0)); waitForIdleSync(); Thread.sleep(120);
            runOnMainSync(() -> { check(((View) get(activity, "bookmarkButton")).isSelected(), "Returning to a bookmark did not fill the icon: " + mode); call(activity, "marks", new Class<?>[]{}); for (Dialog d : ((Map<Dialog,?>) get(activity, "dialogShells")).keySet()) if (d.isShowing()) { find(d.getWindow().getDecorView(), "删除书签" + book.optJSONArray("marks").optJSONObject(0).optString("title")).performClick(); d.dismiss(); break; } check(!((View) get(activity, "bookmarkButton")).isSelected(), "Deleting a bookmark left the icon filled: " + mode); });
        }
        runOnMainSync(() -> { set(lib.prefs(), "readingMode", "paged"); call(activity, "buildReader", new Class<?>[]{int.class}, 0); call(activity, "showControls", new Class<?>[]{boolean.class}, false); }); waitForIdleSync(); Thread.sleep(200);
    }
    private void shelfChecks(NativeActivity activity) throws Exception {
        Library library = (Library) get(activity, "lib"); String active = (String) get(activity, "active"), original = active.startsWith("qa-drag-") ? "all" : active;
        JSONObject a = json("id", "qa-drag-a", "name", "Drag A"), b = json("id", "qa-drag-b", "name", "Drag B");
        try {
            runOnMainSync(() -> { library.shelves.removeIf(s -> s.optString("id").startsWith("qa-drag-")); library.shelves.add(a); library.shelves.add(b); try { Field field = NativeActivity.class.getDeclaredField("active"); field.setAccessible(true); field.set(activity, "qa-drag-a"); } catch (Exception e) { throw new RuntimeException(e); } call(activity, "showShelf", new Class<?>[]{}); });
            runOnMainSync(() -> { View icon = find(activity.getWindow().getDecorView(), "分类书架图标"); check(icon instanceof android.widget.ImageView && "shelf".equals(get(((android.widget.ImageView) icon).getDrawable(), "kind")), "User shelf icon changed"); call(activity, "openDrawer", new Class<?>[]{}); });
            Dialog drawer = (Dialog) get(activity, "drawer"); View root = drawer.getWindow().getDecorView(); runOnMainSync(() -> find(root, "编辑书架").performClick()); waitForIdleSync();
            final int[] start = new int[2], end = new int[2];
            runOnMainSync(() -> { View handle = find(root, "拖动Drag A"), target = find(root, "拖动Drag B"); check(handle != null && target != null, "Drag handles missing"); handle.getLocationOnScreen(start); start[0] += handle.getWidth() / 2; start[1] += handle.getHeight() / 2; target.getLocationOnScreen(end); end[0] += target.getWidth() / 2; end[1] += target.getHeight() / 2; });
            long down = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, start[0], start[1], down); Thread.sleep(180);
            final float[] x = new float[1]; runOnMainSync(() -> { View preview = find(root, "书架拖动预览"); check(preview != null, "Drag preview missing"); x[0] = preview.getX(); check(x[0] >= 0 && x[0] + preview.getWidth() <= ((View) preview.getParent()).getWidth(), "Preview begins outside drawer"); });
            inject(MotionEvent.ACTION_MOVE, start[0] + 160, end[1], down); Thread.sleep(180);
            Bitmap image = getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(getTargetContext().getCacheDir(), "shelf-drag.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle();
            runOnMainSync(() -> { check(find(root, "书架拖动预览").getX() == x[0], "Drag preview moved horizontally"); int[] shifted = new int[2]; View neighbor = find(root, "拖动Drag B"); neighbor.getLocationOnScreen(shifted); check(shifted[1] < end[1] - neighbor.getHeight(), "Neighbor did not make room before release: y=" + shifted[1] + " originalCenter=" + end[1] + " h=" + neighbor.getHeight() + " shift=" + ((View) neighbor.getParent().getParent()).getTranslationY()); check(library.shelves.indexOf(a) < library.shelves.indexOf(b), "Order saved before drop"); });
            inject(MotionEvent.ACTION_MOVE, end[0] + 240, end[1], down); Thread.sleep(100); inject(MotionEvent.ACTION_UP, end[0] + 240, end[1], down); Thread.sleep(250);
            runOnMainSync(() -> { check(library.shelves.indexOf(a) > library.shelves.indexOf(b), "Drop did not reorder shelves"); check(find(root, "书架拖动预览") == null, "Preview remains after drop"); View source = find(root, "拖动Drag A"), target = find(root, "拖动Drag B"); source.getLocationOnScreen(start); start[0] += source.getWidth() / 2; start[1] += source.getHeight() / 2; target.getLocationOnScreen(end); end[0] += target.getWidth() / 2; end[1] += target.getHeight() / 2; });
            down = SystemClock.uptimeMillis(); inject(MotionEvent.ACTION_DOWN, start[0], start[1], down); Thread.sleep(100); inject(MotionEvent.ACTION_MOVE, end[0] + 100, end[1], down); Thread.sleep(180);
            runOnMainSync(() -> { int[] shifted = new int[2]; find(root, "拖动Drag B").getLocationOnScreen(shifted); check(shifted[1] > end[1] + 30, "Upward drag did not push neighbor down"); find(root, "拖动Drag A").cancelDragAndDrop(); });
            inject(MotionEvent.ACTION_UP, end[0] + 100, end[1], down); Thread.sleep(220);
            for (int i = 0; i < 16; i++) { boolean[] settled = {false}; runOnMainSync(() -> settled[0] = find(root, "书架拖动预览") == null); if (settled[0]) break; Thread.sleep(50); }
            runOnMainSync(() -> { check(library.shelves.indexOf(a) > library.shelves.indexOf(b), "Cancelled drag saved a new order"); check(find(root, "书架拖动预览") == null, "Cancelled preview remains"); drawer.dismiss(); if (!library.books.isEmpty()) call(activity, "bookEditor", new Class<?>[]{JSONObject.class}, library.books.get(0)); });
            runOnMainSync(() -> { java.util.Map<Dialog,?> dialogs = (java.util.Map<Dialog,?>) get(activity, "dialogShells"); Dialog book = null; for (Dialog dialog : dialogs.keySet()) if (dialog.isShowing()) book = dialog; check(book != null, "Book editor missing"); View dialog = book.getWindow().getDecorView(); check(find(dialog, "文件名称") != null && find(dialog, "页码数量") != null, "Metadata labels missing"); book.dismiss(); });
        } finally {
            runOnMainSync(() -> { library.shelves.remove(a); library.shelves.remove(b); Dialog drawer = (Dialog) get(activity, "drawer"); if (drawer != null) drawer.dismiss(); try { Field field = NativeActivity.class.getDeclaredField("active"); field.setAccessible(true); field.set(activity, original); } catch (Exception e) { throw new RuntimeException(e); } call(activity, "showShelf", new Class<?>[]{}); call(activity, "persist", new Class<?>[]{}); });
            ((java.util.concurrent.ExecutorService) get(activity, "saveIo")).submit(() -> {}).get();
            library.saveSnapshot(library.snapshot());
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); File fixture = null;
        try {
            check("LNReader_QA".equals(arguments.getString("guard")), "Dedicated QA guard required");
            NativeActivity activity = (NativeActivity) startActivitySync(new Intent(getTargetContext(), NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (arguments.containsKey("privacy")) { Thread.sleep(400); PrivacyChecks.run(this, activity); result.putString("stream", "PASS: privacy visibility, title gestures, private import, metadata, palette and background return\n"); finish(Activity.RESULT_OK, result); return; }
            if (arguments.containsKey("gallery")) { Thread.sleep(400); MediaChecks.run(this, activity, arguments.getString("gallery")); result.putString("stream", "partial".equals(arguments.getString("gallery")) ? "PASS: native album browsing, partial photo grants, thumbnails and cover selection\n" : "PASS: native album browsing, current photo grants, compact book actions, compressed cover backup and deletion\n"); finish(Activity.RESULT_OK, result); return; }
            if (arguments.containsKey("interaction")) { Thread.sleep(400); InteractionChecks.run(this, activity); result.putString("stream", "PASS: batch shelf removal, recent hiding and re-entry, import quick categories, inline rename and cancellation\n"); finish(Activity.RESULT_OK, result); return; }
            if ("true".equals(arguments.getString("shelf"))) { Thread.sleep(400); shelfChecks(activity); result.putString("stream", "PASS: fixed-X shelf dragging, native drop reordering, custom shelf icon and metadata labels\n"); finish(Activity.RESULT_OK, result); return; }
            Thread.sleep(500); Library library = (Library) get(activity, "lib");
            String content = "第一章 起点\n" + "正文在这一行，检查平移时相邻页面的位置和取消后的阅读进度。\n".repeat(160) + "第二章 下一章\n章节边界后的正文。\n";
            String id = "eeeeeeeeeeeeeeeeeeeeeeee"; fixture = new File(library.root, id + ".txt"); BackupFiles.write(fixture, content.getBytes(StandardCharsets.UTF_8));
            library.books.add(json("id",id,"title","Gesture QA","position",0,"shelf","default","marks",new JSONArray()));JSONObject book=library.book(id);
            runOnMainSync(() -> { set(library.prefs(), "readingMode", "paged"); set(library.prefs(), "pageAnimation", "slide"); call(activity, "openBook", new Class<?>[]{JSONObject.class, int.class}, book, 0); });
            for (int i = 0; i < 200 && get(activity, "pageTotals") == null; i++) Thread.sleep(50);
            Thread.sleep(400); progressChecks(activity, book); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, 0)); final int original = book.optInt("position");
            long at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); Thread.sleep(100);
            runOnMainSync(() -> { PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); check(view.dragFraction() > .45f && view.dragFraction() < .55f, "Held page does not match half-width displacement"); Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); try (OutputStream out = new FileOutputStream(new File(getTargetContext().getCacheDir(), "drag-half.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } catch (IOException e) { throw new RuntimeException(e); } image.recycle(); });
            check(book.optInt("position") == original, "Preview changed history before release");
            touch(activity, MotionEvent.ACTION_MOVE, .5f, at, at + 250); touch(activity, MotionEvent.ACTION_UP, .5f, at, at + 500); Thread.sleep(180); check(book.optInt("position") == original, "Reverse gesture did not cancel");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .75f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .75f, at, at + 500); Thread.sleep(180); check(book.optInt("position") > original, "Slow short swipe lost sensitivity"); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, original));
            // 32px is above native touch slop but below the former 14dp threshold.
            float shortX = .8f - 32f / getTargetContext().getResources().getDisplayMetrics().widthPixels;
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, shortX, at, at + 100); touch(activity, MotionEvent.ACTION_UP, shortX, at, at + 500); Thread.sleep(180); check(book.optInt("position") > original, "Small intentional swipe still requires fixed travel"); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, original));
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .6f, at, at + 100);
            for (int i = 1; i <= 20; i++) touch(activity, MotionEvent.ACTION_MOVE, .6f + i * .003f, at, at + 100 + i * 20);
            touch(activity, MotionEvent.ACTION_UP, .66f, at, at + 700); Thread.sleep(180); check(book.optInt("position") == original, "Slow accumulated reversal was ignored");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .6f, at, at + 100); touch(activity, MotionEvent.ACTION_MOVE, .67f, at, at + 200); touch(activity, MotionEvent.ACTION_MOVE, .58f, at, at + 300); touch(activity, MotionEvent.ACTION_UP, .58f, at, at + 700); Thread.sleep(180); check(book.optInt("position") > original, "Resumed forward drag should commit"); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, original));
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .3f, at, at + 400); Thread.sleep(180); check(book.optInt("position") > original, "Forward release did not commit");
            runOnMainSync(() -> { call(activity, "jump", new Class<?>[]{int.class}, content.indexOf("第二章") - 1); call(activity, "capture", new Class<?>[]{}); }); int previous = book.optInt("position");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_CANCEL, .3f, at, at + 300); Thread.sleep(180); check(book.optInt("position") == previous, "System cancellation changed chapter");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .3f, at, at + 400); Thread.sleep(180); check(book.optInt("position") >= content.indexOf("第二章"), "Cross-chapter drag failed");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .2f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .7f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .7f, at, at + 400); Thread.sleep(180); check(book.optInt("position") < content.indexOf("第二章"), "Backward cross-chapter drag failed");
            runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, original));
            for (int direction : new int[]{1, -1}) for (int i = 0; i < 4; i++) {
                int before = book.optInt("position"); at = SystemClock.uptimeMillis(); float end = .5f - direction * .05f;
                touch(activity, MotionEvent.ACTION_DOWN, .5f, at, at); touch(activity, MotionEvent.ACTION_MOVE, end, at, at + 50); touch(activity, MotionEvent.ACTION_UP, end, at, at + 100);
                check(direction > 0 ? book.optInt("position") > before : book.optInt("position") < before, "Continuous drag lost a turn during settling"); Thread.sleep(20);
            }
            check(book.optInt("position") == original, "Continuous forward/backward paging lost its position");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, -.25f, at, at + 100);
            final Bitmap[] held = new Bitmap[1];
            runOnMainSync(() -> { PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); held[0] = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(held[0])); });
            check(book.optInt("position") == original, "Full-width preview changed history before release");
            touch(activity, MotionEvent.ACTION_UP, -.25f, at, at + 400); Thread.sleep(180);
            runOnMainSync(() -> { PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); Bitmap committed = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(committed)); check(held[0].sameAs(committed), "Full-width held preview snapped back before release"); held[0].recycle(); committed.recycle(); });
            // The horizontal component is deliberately below Android's touch slop.
            for (String animation : new String[]{"slide", "none"}) for (int horizontal : new int[]{-1, 1}) for (int vertical : new int[]{-1, 1}) {
                runOnMainSync(() -> { set(library.prefs(), "pageAnimation", animation); call(activity, "jump", new Class<?>[]{int.class}, 0); if (horizontal > 0) call(activity, "turnPageNow", new Class<?>[]{int.class}, 1); });
                int before = book.optInt("position"); at = SystemClock.uptimeMillis();
                touchXY(activity, MotionEvent.ACTION_DOWN, 540, 900, at, at); touchXY(activity, MotionEvent.ACTION_MOVE, 540 + horizontal * 12, 900 + vertical * 20, at, at + 60); touchXY(activity, MotionEvent.ACTION_UP, 540 + horizontal * 12, 900 + vertical * 20, at, at + 120);
                check(horizontal < 0 ? book.optInt("position") > before : book.optInt("position") < before, "Short diagonal trend ignored: " + animation + "/" + horizontal + "/" + vertical); Thread.sleep(120);
            }
            runOnMainSync(() -> { set(library.prefs(), "pageAnimation", "slide"); call(activity, "jump", new Class<?>[]{int.class}, 0); });
            at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, 540, 900, at, at); touchXY(activity, MotionEvent.ACTION_MOVE, 541, 970, at, at + 60); touchXY(activity, MotionEvent.ACTION_UP, 541, 970, at, at + 120); check(book.optInt("position") == original, "Vertical movement should not flip pages");
            float density = activity.getResources().getDisplayMetrics().density;
            // Sparse fast flings, release-coordinate contraction and delayed/batched first movement.
            for (String animation : new String[]{"slide", "none"}) for (int side : new int[]{-1, 1}) for (int duration : new int[]{12, 45, 120, 700}) {
                runOnMainSync(() -> { set(library.prefs(), "pageAnimation", animation); call(activity, "jump", new Class<?>[]{int.class}, 0); if (side > 0) call(activity, "turnPageNow", new Class<?>[]{int.class}, 1); });
                int before = book.optInt("position"); float x = side < 0 ? 850 : 200; at = SystemClock.uptimeMillis();
                touchXY(activity, MotionEvent.ACTION_DOWN, x, 1000, at, at);
                if (duration != 12) touchXY(activity, MotionEvent.ACTION_MOVE, x + side * 100 * density, 1060, at, at + duration - 5);
                touchXY(activity, MotionEvent.ACTION_UP, x + side * 85 * density, 1080, at, at + duration);
                check(side < 0 ? book.optInt("position") > before : book.optInt("position") < before, "Medium swipe lost: " + animation + "/" + side + "/" + duration); Thread.sleep(100);
            }
            for (String animation : new String[]{"slide", "none"}) for (int side : new int[]{-1, 1}) for (int vertical : new int[]{-1, 1}) for (float length : new float[]{.6f, 1.8f, 5f}) {
                runOnMainSync(() -> { set(library.prefs(), "pageAnimation", animation); call(activity, "showControls", new Class<?>[]{boolean.class}, false); call(activity, "jump", new Class<?>[]{int.class}, 0); if (side > 0) call(activity, "turnPageNow", new Class<?>[]{int.class}, 1); });
                int before = book.optInt("position"); float startX = side < 0 ? 900 : 160; at = SystemClock.uptimeMillis();
                touchXY(activity, MotionEvent.ACTION_DOWN, startX, 1000, at, at);
                for (int i = 1; i <= 5; i++) touchXY(activity, MotionEvent.ACTION_MOVE, startX + side * length * density * i / 5, 1000 + vertical * 14 * density * i, at, at + i * 25);
                touchXY(activity, MotionEvent.ACTION_UP, startX + side * (length - .2f) * density, 1000 + vertical * 72 * density, at, at + 180);
                check(side < 0 ? book.optInt("position") > before : book.optInt("position") < before, "Thumb arc failed: " + animation + "/" + side + "/" + vertical + "/" + length); Thread.sleep(100);
            }
            runOnMainSync(() -> { set(library.prefs(), "pageAnimation", "slide"); call(activity, "showControls", new Class<?>[]{boolean.class}, false); call(activity, "jump", new Class<?>[]{int.class}, 0); });
            at = SystemClock.uptimeMillis(); final long batchAt = at;
            touchXY(activity, MotionEvent.ACTION_DOWN, 800, 900, at, at);
            runOnMainSync(() -> { ReaderSurface surface = (ReaderSurface) get(activity, "readerSurface"); MotionEvent batch = MotionEvent.obtain(batchAt, batchAt + 30, MotionEvent.ACTION_MOVE, 800 - 2 * density, 900 + 30 * density, 0); batch.addBatch(batchAt + 60, 800 - 4 * density, 900 + 60 * density, 1, 1, 0); batch.addBatch(batchAt + 90, 800 - 3.5f * density, 900 + 70 * density, 1, 1, 0); surface.dispatchTouchEvent(batch); batch.recycle(); });
            touchXY(activity, MotionEvent.ACTION_UP, 800 - 3.5f * density, 900 + 70 * density, at, at + 130); check(book.optInt("position") > original, "Batched thumb arc was ignored"); Thread.sleep(120);
            int beforeHook = book.optInt("position"); at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, 800, 900, at, at); touchXY(activity, MotionEvent.ACTION_MOVE, 800 + 2 * density, 910, at, at + 30); touchXY(activity, MotionEvent.ACTION_MOVE, 800 - 6 * density, 940, at, at + 60); touchXY(activity, MotionEvent.ACTION_UP, 800 - 5.8f * density, 950, at, at + 100); check(book.optInt("position") > beforeHook, "Initial hook turned the wrong way"); Thread.sleep(120);
            runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, 0));
            final float[] word = new float[2];
            runOnMainSync(() -> { PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); android.text.StaticLayout layout = (android.text.StaticLayout) get(view, "layout"); int index = layout.getText().toString().indexOf("相邻"); int line = layout.getLineForOffset(index); word[0] = view.getPaddingLeft() + layout.getPrimaryHorizontal(index) + 4; word[1] = view.getPaddingTop() + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f; });
            at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, word[0], word[1], at, at); Thread.sleep(ViewConfiguration.getLongPressTimeout() + 180); touchXY(activity, MotionEvent.ACTION_UP, word[0], word[1], at, SystemClock.uptimeMillis());
            PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); check(view.selectionActive() && !view.selectedText().isEmpty(), "Long press did not select text");
            final String firstSelection = view.selectedText(); final float[][] handle = new float[1][];
            runOnMainSync(() -> handle[0] = (float[]) call(view, "handlePoint", new Class<?>[]{int.class, boolean.class}, (int) get(view, "selectionEnd"), true));
            at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, handle[0][0], handle[0][1], at, at); touchXY(activity, MotionEvent.ACTION_MOVE, handle[0][0] + 150, handle[0][1] + 70, at, at + 100); touchXY(activity, MotionEvent.ACTION_UP, handle[0][0] + 150, handle[0][1] + 70, at, at + 200);
            check(view.selectionActive() && !view.selectedText().equals(firstSelection), "Selection handle did not adjust text"); check(book.optInt("position") == original, "Dragging selection unexpectedly paged");
            Thread.sleep(150); Bitmap selectionImage = getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(getTargetContext().getCacheDir(), "text-selection.png"))) { selectionImage.compress(Bitmap.CompressFormat.PNG, 100, out); } selectionImage.recycle();
            runOnMainSync(() -> { String selected = view.selectedText(); ActionMode mode = (ActionMode) get(view, "selectionMode"); check(mode != null && mode.getMenu().findItem(android.R.id.copy) != null, "Copy is missing from selection menu"); mode.getMenu().performIdentifierAction(android.R.id.copy, 0); ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE); check(clipboard.hasPrimaryClip() && selected.contentEquals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity)), "Copied text does not match selected text"); check(!view.selectionActive(), "Copy did not dismiss selection"); });
            for (String readingMode : new String[]{"paged", "scroll"}) {
                runOnMainSync(() -> { set(library.prefs(), "readingMode", readingMode); call(activity, "buildReader", new Class<?>[]{int.class}, 0); call(activity, "showControls", new Class<?>[]{boolean.class}, false); }); waitForIdleSync(); Thread.sleep(150);
                ReaderSurface surface = (ReaderSurface) get(activity, "readerSurface"); int position = book.optInt("position");
                at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, surface.getWidth() * .94f, surface.getHeight() - 2, at, at); touchXY(activity, MotionEvent.ACTION_UP, surface.getWidth() * .94f, surface.getHeight() - 2, at, at + 40);
                check((boolean) get(activity, "controlsVisible") && book.optInt("position") == position, "Bottom status or its lower gap did not open the menu: " + readingMode);
                at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, 800, 900, at, at); touchXY(activity, MotionEvent.ACTION_MOVE, 300, 700, at, at + 30); touchXY(activity, MotionEvent.ACTION_UP, 300, 700, at, at + 60);
                check((boolean) get(activity, "controlsVisible") && book.optInt("position") == position, "Menu body swipe changed the page or dismissed the menu");
                runOnMainSync(() -> { activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)); activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)); });
                check(book.optInt("position") == position, "Volume key paged behind the menu");
                if (readingMode.equals("scroll")) check(((android.widget.ScrollView) get(activity, "readingScroll")).getScrollY() == 0, "Body scroll moved behind the menu");
                at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, surface.getWidth() * .1f, 900, at, at); touchXY(activity, MotionEvent.ACTION_UP, surface.getWidth() * .1f, 900, at, at + 30);
                check(!(boolean) get(activity, "controlsVisible") && book.optInt("position") == position, "Body tap did not only dismiss the menu");
                for (float fraction : new float[]{.27f, .73f}) {
                    at = SystemClock.uptimeMillis(); touchXY(activity, MotionEvent.ACTION_DOWN, surface.getWidth() * fraction, 900, at, at); touchXY(activity, MotionEvent.ACTION_UP, surface.getWidth() * fraction, 900, at, at + 30);
                    check((boolean) get(activity, "controlsVisible") && book.optInt("position") == position, "Expanded middle tap area still flipped: " + fraction); runOnMainSync(() -> call(activity, "showControls", new Class<?>[]{boolean.class}, false));
                }
            }
            runOnMainSync(() -> { set(library.prefs(), "readingMode", "paged"); call(activity, "buildReader", new Class<?>[]{int.class}, 0); }); waitForIdleSync();
            runOnMainSync(() -> { call(activity, "catalog", new Class<?>[]{}); Map<Dialog,?> dialogs = (Map<Dialog,?>) get(activity, "dialogShells"); for (Dialog dialog : dialogs.keySet()) if (dialog.isShowing()) {
                android.widget.EditText input = (android.widget.EditText) find(dialog.getWindow().getDecorView(), "搜索章节输入框");
                android.widget.ListView entries = (android.widget.ListView) ((ViewGroup) input.getParent().getParent()).getChildAt(1); int before = entries.getAdapter().getCount(); input.setText("NoSuchChapter"); check(entries.getAdapter().getCount() == before, "Typing filtered the catalog");
                find(dialog.getWindow().getDecorView(), "搜索章节").performClick(); check(entries.getAdapter().getCount() == 0, "Catalog search icon did not submit"); input.setText(""); check(entries.getAdapter().getCount() == 0, "Clearing auto-submitted the catalog"); input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); check(entries.getAdapter().getCount() == before, "Catalog keyboard search did not submit"); dialog.dismiss(); break;
            } });
            runOnMainSync(() -> call(activity, "back", new Class<?>[]{})); result.putString("stream", "PASS: short and medium swipes, reversal, continuous paging, text selection, bottom menu taps, expanded middle area and menu paging/scroll/volume isolation; thumb-only progress, canceled and accessibility seeking\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result); }
        finally { if (fixture != null) fixture.delete(); }
    }
}
