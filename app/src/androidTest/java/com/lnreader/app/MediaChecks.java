package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import static com.lnreader.app.Library.*;

final class MediaChecks {
    private static Object field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static Object call(Object o, String name, Class<?>[] types, Object... args) { try { Method method = o.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(o, args); } catch (Exception error) { throw new RuntimeException(error); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void screenshot(Instrumentation test, NativeActivity activity, String name) throws Exception { test.waitForIdleSync(); Thread.sleep(120); Bitmap image = test.getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(activity.getCacheDir(), name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle(); }
    private static View find(View root, String label) { if (label.equals(root.getContentDescription()) || root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), label); if (found != null) return found; } return null; }
    private static Dialog shown(NativeActivity activity) throws Exception { for (Dialog dialog : ((Map<Dialog,?>) field(activity, "dialogShells")).keySet()) if (dialog.isShowing()) return dialog; throw new AssertionError("Dialog missing"); }
    static void run(Instrumentation test, NativeActivity activity, String mode) throws Exception {
        boolean partial = mode.equals("partial");
        if (mode.equals("full")) test.getUiAutomation().grantRuntimePermission(activity.getPackageName(), android.Manifest.permission.READ_MEDIA_IMAGES);
        List<Uri> created = new ArrayList<>(); Library library = (Library) field(activity, "lib");
        File originalBackup = library.backup; boolean scoped = !mode.equals("allfiles"); if (scoped) library.backup = new File(activity.getCacheDir(), "scoped-media-qa");
        JSONObject book = json("id", "dddddddddddddddddddddddd", "title", "Media QA", "directory", "book/.qa-cover-0.9.14"); File directory = library.directory(book); BackupFiles.mkdir(directory); File legacyCover = new File(library.root, book.optString("id") + ".jpg");
        try {
            for (int i = 0; i < 2; i++) {
                ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "LNReader_QA_" + i + ".jpg"); values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LNReader_QA_Album_" + i); values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri image = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); check(image != null, "Media insertion failed"); created.add(image);
                Bitmap bitmap = Bitmap.createBitmap(960, 1440, Bitmap.Config.ARGB_8888); new Canvas(bitmap).drawColor(i == 0 ? Color.RED : Color.BLUE);
                try (OutputStream out = activity.getContentResolver().openOutputStream(image)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); } bitmap.recycle(); values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); activity.getContentResolver().update(image, values, null, null);
            }
            test.runOnMainSync(() -> call(activity, "chooseCover", new Class<?>[]{JSONObject.class}, book));
            MediaGallery gallery = null; Dialog dialog = null;
            for (int n = 0; n < 80 && gallery == null; n++) {
                Map<Dialog, LinearLayout> dialogs = (Map<Dialog, LinearLayout>) field(activity, "dialogShells");
                for (Map.Entry<Dialog, LinearLayout> entry : new ArrayList<>(dialogs.entrySet())) if (entry.getKey().isShowing()) { LinearLayout shell = entry.getValue(); for (int i = 0; i < shell.getChildCount(); i++) if (shell.getChildAt(i) instanceof MediaGallery) { gallery = (MediaGallery) shell.getChildAt(i); dialog = entry.getKey(); } }
                if (gallery == null) Thread.sleep(50);
            }
            check(gallery != null, "Native album gallery missing"); final MediaGallery view = gallery; final Spinner albums = (Spinner) field(view, "albums"); final GridView grid = (GridView) field(view, "grid");
            check(find(dialog.getWindow().getDecorView(), "照片权限设置") == null, "Manual photo settings shortcut remains");
            if (mode.equals("allfiles")) check((boolean) call(activity, "allPhotosAllowed", new Class<?>[]{}), "All-files grant was not recognized as photo access");
            if (mode.equals("full")) check(!Environment.isExternalStorageManager() && (boolean) call(activity, "allPhotosAllowed", new Class<?>[]{}), "Standalone photo grant was not recognized");
            for (int n = 0; n < 80 && albums.getAdapter() == null; n++) Thread.sleep(50);
            check(albums.getAdapter() != null && albums.getAdapter().getCount() >= 3, "Complete album list was not loaded");
            int second = -1; for (int i = 0; i < albums.getAdapter().getCount(); i++) if (albums.getAdapter().getItem(i).toString().contains("LNReader_QA_Album_1")) second = i;
            check(second >= 0, "Second local album missing"); final int index = second;
            test.runOnMainSync(() -> albums.setSelection(index)); Thread.sleep(200);
            check(grid.getAdapter().getCount() == 1, "Album filtering did not isolate its photo");
            if (partial) { check(!(boolean) call(activity, "allPhotosAllowed", new Class<?>[]{}), "Partial access was mistaken for full access"); check(view.getChildAt(1) instanceof Button && ((Button) view.getChildAt(1)).getText().toString().equals("允许全部照片"), "Partial-access reauthorization button missing"); }
            for (int n = 0; n < 80 && (grid.getChildCount() == 0 || ((ImageView) grid.getChildAt(0)).getDrawable() == null); n++) Thread.sleep(50);
            check(grid.getChildCount() > 0 && ((ImageView) grid.getChildAt(0)).getDrawable() != null, "Thumbnail failed to load");
            Bitmap screenshot = test.getUiAutomation().takeScreenshot(); try (OutputStream out = new FileOutputStream(new File(activity.getCacheDir(), partial ? "gallery-partial.png" : "gallery-full.png"))) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); } screenshot.recycle();
            test.runOnMainSync(() -> grid.performItemClick(grid.getChildAt(0), 0, grid.getAdapter().getItemId(0)));
            File cover = new File(directory, "cover.jpg"); for (int n = 0; n < 100 && !book.optBoolean("cover"); n++) Thread.sleep(50);
            Bitmap backed = BitmapFactory.decodeFile(cover.getPath()); check(backed != null && Math.max(backed.getWidth(), backed.getHeight()) <= 720, "Selected cover was not compressed and backed up"); check(Color.blue(backed.getPixel(backed.getWidth() / 2, backed.getHeight() / 2)) > 200, "Wrong album image was imported"); backed.recycle();
            for (int n = 0; n < 100 && (boolean) field(activity, "importing"); n++) Thread.sleep(50);
            if (partial) return; // Real imports require writable backup access first; partial grants are checked independently.
            BackupFiles.copy(cover, legacyCover); // Upgraded installations may retain the old private cover mirror.
            test.runOnMainSync(() -> call(activity, "bookEditor", new Class<?>[]{JSONObject.class}, book));
            screenshot(test, activity, "book-cover-actions");
            Dialog editor = shown(activity); final View root = editor.getWindow().getDecorView();
            test.runOnMainSync(() -> { View remove = find(root, "删除封面"), select = find(root, "导入封面"), rules = find(root, "章节匹配设置"), save = find(root, "保存"); check(remove != null && remove.isEnabled() && select != null && rules != null && save != null, "Book cover controls missing");
                ViewGroup body = (ViewGroup) save.getParent(); check(remove.getParent() == select.getParent() && body.indexOfChild((View) remove.getParent()) < body.indexOfChild(save) && body.indexOfChild(rules) < body.indexOfChild(save), "Cover controls must precede Save"); if (scoped) { editor.dismiss(); call(activity, "deleteCover", new Class<?>[]{JSONObject.class}, book); } else remove.performClick(); });
            for (int n = 0; n < 100 && (cover.exists() || book.optBoolean("cover")); n++) Thread.sleep(50);
            check(!cover.exists() && !legacyCover.exists() && !library.coverFile(book).isFile() && !book.optBoolean("cover"), "Cover deletion did not remove backup, legacy mirror and metadata");
            test.waitForIdleSync(); test.runOnMainSync(() -> { try { Dialog after = shown(activity); check(!find(after.getWindow().getDecorView(), "删除封面").isEnabled(), "No-cover delete action remains enabled"); after.dismiss(); call(activity, "sortDialog", new Class<?>[]{}); Dialog sort = shown(activity); View asc = find(sort.getWindow().getDecorView(), "升序"); check(((ViewGroup.MarginLayoutParams) asc.getLayoutParams()).getMarginEnd() >= 16 * activity.getResources().getDisplayMetrics().density, "Sort choices have no spacing"); } catch (Exception error) { throw new RuntimeException(error); } });
            screenshot(test, activity, "sort-spacing"); test.runOnMainSync(() -> { try { shown(activity).dismiss(); } catch (Exception error) { throw new RuntimeException(error); } });
        } finally { for (Uri uri : created) activity.getContentResolver().delete(uri, null, null); for (File file : Objects.requireNonNullElse(directory.listFiles(), new File[0])) file.delete(); directory.delete(); legacyCover.delete(); library.backup = originalBackup; }
    }
}
