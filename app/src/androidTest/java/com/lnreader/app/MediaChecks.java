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
    static void run(Instrumentation test, NativeActivity activity, boolean partial) throws Exception {
        try (InputStream command = new ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand("appops set --uid com.lnreader.app MANAGE_EXTERNAL_STORAGE allow"))) { command.readAllBytes(); }
        if (!partial) test.getUiAutomation().grantRuntimePermission(activity.getPackageName(), android.Manifest.permission.READ_MEDIA_IMAGES);
        List<Uri> created = new ArrayList<>(); Library library = (Library) field(activity, "lib");
        JSONObject book = json("id", "media-qa", "title", "Media QA", "directory", "book/.qa-cover-0.9.12"); File directory = library.directory(book); BackupFiles.mkdir(directory);
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
        } finally { for (Uri uri : created) activity.getContentResolver().delete(uri, null, null); for (File file : Objects.requireNonNullElse(directory.listFiles(), new File[0])) file.delete(); directory.delete(); }
    }
}
