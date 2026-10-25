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
import static com.lnreader.app.Library.*;

/** Held gestures are dispatched separately so assertions run before the finger is released. */
public class GestureChecks extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private Object get(Object o, String key) { try { Field f = o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); } catch (Exception e) { throw new RuntimeException(e); } }
    private Object call(Object o, String key, Class<?>[] types, Object... args) { try { Method m = o.getClass().getDeclaredMethod(key, types); m.setAccessible(true); return m.invoke(o, args); } catch (Exception e) { throw new RuntimeException(e); } }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void touch(NativeActivity activity, int action, float x, long down, long when) { runOnMainSync(() -> { ReaderSurface v = (ReaderSurface) get(activity, "readerSurface"); MotionEvent event = MotionEvent.obtain(down, when, action, v.getWidth() * x, v.getHeight() * .5f, 0); v.dispatchTouchEvent(event); event.recycle(); }); }
    @Override public void onStart() {
        Bundle result = new Bundle(); File fixture = null;
        try {
            check("LNReader_QA".equals(arguments.getString("guard")), "Dedicated QA guard required");
            NativeActivity activity = (NativeActivity) startActivitySync(new Intent(getTargetContext(), NativeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(500); Library library = (Library) get(activity, "lib");
            String content = "第一章 起点\n" + "正文在这一行，检查平移时相邻页面的位置和取消后的阅读进度。\n".repeat(160) + "第二章 下一章\n章节边界后的正文。\n";
            String id = "eeeeeeeeeeeeeeeeeeeeeeee"; fixture = new File(library.root, id + ".txt"); BackupFiles.write(fixture, content.getBytes(StandardCharsets.UTF_8));
            JSONObject book = json("id", id, "title", "Gesture QA", "position", 0, "shelf", "default", "marks", new JSONArray());
            runOnMainSync(() -> { set(library.prefs(), "readingMode", "paged"); set(library.prefs(), "pageAnimation", "slide"); call(activity, "openBook", new Class<?>[]{JSONObject.class, int.class}, book, 0); });
            for (int i = 0; i < 200 && get(activity, "pageTotals") == null; i++) Thread.sleep(50);
            Thread.sleep(400); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, 0)); final int original = book.optInt("position");
            long at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); Thread.sleep(100);
            runOnMainSync(() -> { PagedReaderView view = (PagedReaderView) get(activity, "pagedReader"); check(view.dragFraction() > .45f && view.dragFraction() < .55f, "Held page does not match half-width displacement"); Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(image)); try (OutputStream out = new FileOutputStream(new File(getTargetContext().getCacheDir(), "drag-half.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } catch (IOException e) { throw new RuntimeException(e); } image.recycle(); });
            check(book.optInt("position") == original, "Preview changed history before release");
            touch(activity, MotionEvent.ACTION_MOVE, .5f, at, at + 250); touch(activity, MotionEvent.ACTION_UP, .5f, at, at + 500); Thread.sleep(180); check(book.optInt("position") == original, "Reverse gesture did not cancel");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .75f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .75f, at, at + 500); Thread.sleep(180); check(book.optInt("position") > original, "Slow short swipe lost sensitivity"); runOnMainSync(() -> call(activity, "jump", new Class<?>[]{int.class}, original));
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .3f, at, at + 400); Thread.sleep(180); check(book.optInt("position") > original, "Forward release did not commit");
            runOnMainSync(() -> { call(activity, "jump", new Class<?>[]{int.class}, content.indexOf("第二章") - 1); call(activity, "capture", new Class<?>[]{}); }); int previous = book.optInt("position");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_CANCEL, .3f, at, at + 300); Thread.sleep(180); check(book.optInt("position") == previous, "System cancellation changed chapter");
            at = SystemClock.uptimeMillis(); touch(activity, MotionEvent.ACTION_DOWN, .8f, at, at); touch(activity, MotionEvent.ACTION_MOVE, .3f, at, at + 100); touch(activity, MotionEvent.ACTION_UP, .3f, at, at + 400); Thread.sleep(180); check(book.optInt("position") >= content.indexOf("第二章"), "Cross-chapter drag failed");
            runOnMainSync(() -> call(activity, "back", new Class<?>[]{})); result.putString("stream", "PASS: held half-page rendering, reversible drag, commit, cancellation and chapter boundaries\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result); }
        finally { if (fixture != null) fixture.delete(); }
    }
}
