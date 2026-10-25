package com.lnreader.app;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Local MediaStore albums with recycled, bounded thumbnails; no full-size image preload. */
final class MediaGallery extends LinearLayout implements AutoCloseable {
    interface Pick { void image(Uri uri); }
    private static final class Photo {
        final long id; final String name, bucket, album;
        Photo(long id, String name, String bucket, String album) { this.id = id; this.name = name; this.bucket = bucket; this.album = album; }
        Uri uri() { return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id); }
    }
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> thumbnails = new LruCache<Long, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(Long key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final List<Photo> photos = new ArrayList<>(), visible = new ArrayList<>();
    private final Spinner albums;
    private final TextView status;
    private final GridView grid;
    private final Pick pick;
    private final int ink, background;
    private volatile boolean closed;
    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return visible.size(); }
        public Object getItem(int position) { return visible.get(position); }
        public long getItemId(int position) { return visible.get(position).id; }
        public View getView(int position, View recycled, ViewGroup parent) {
            Cell cell = recycled instanceof Cell ? (Cell) recycled : new Cell(); cell.bind(visible.get(position)); return cell;
        }
    };
    MediaGallery(Context context, int ink, int background, boolean partial, Runnable permissions, Pick pick) {
        super(context); this.ink = ink; this.background = background; this.pick = pick; setOrientation(VERTICAL);
        if (partial) {
            TextView note = label("当前仅能访问获准照片；允许全部照片后可浏览完整图集。", 14); addView(note);
            Button grant = new Button(context); grant.setText("允许全部照片"); grant.setTextColor(ink);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(background); shape.setCornerRadius(dp(8)); shape.setStroke(dp(1), (ink & 0x00ffffff) | 0x55000000); grant.setBackground(shape);
            grant.setOnClickListener(v -> permissions.run()); addView(grant);
        }
        albums = new Spinner(context); albums.setContentDescription("选择相册"); addView(albums, new LayoutParams(-1, dp(48)));
        status = label("正在读取相册…", 14); addView(status);
        grid = new GridView(context); grid.setNumColumns(3); grid.setHorizontalSpacing(dp(4)); grid.setVerticalSpacing(dp(4)); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setContentDescription("相册图片");
        grid.setAdapter(adapter); addView(grid, new LayoutParams(-1, dp(Math.max(200, getResources().getConfiguration().screenHeightDp / 2))));
        grid.setOnItemClickListener((parent, view, position, id) -> pick.image(visible.get(position).uri()));
        worker.execute(this::read);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String value, int size) { TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(ink); view.setPadding(0, dp(4), 0, dp(4)); return view; }
    private void read() {
        List<Photo> loaded = new ArrayList<>();
        try (Cursor cursor = getContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            new String[]{"_id", "_display_name", "bucket_id", "bucket_display_name"}, null, null, "date_added DESC, _id DESC")) {
            if (cursor != null) while (!closed && cursor.moveToNext()) loaded.add(new Photo(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)));
            ui.post(() -> { if (closed) return; photos.addAll(loaded); showAlbums(); });
        } catch (Exception error) { ui.post(() -> { if (!closed) status.setText("无法读取相册，请检查照片权限后重试。"); }); }
    }
    private void showAlbums() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>(); Map<String, Integer> counts = new HashMap<>();
        for (Photo photo : photos) { String key = photo.bucket == null ? "" : photo.bucket; names.putIfAbsent(key, photo.album == null || photo.album.isEmpty() ? "其他图片" : photo.album); counts.put(key, counts.getOrDefault(key, 0) + 1); }
        List<String> keys = new ArrayList<>(), labels = new ArrayList<>(); keys.add(null); labels.add("全部图片（" + photos.size() + "）");
        for (String key : names.keySet()) { keys.add(key); labels.add(names.get(key) + "（" + counts.get(key) + "）"); }
        albums.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int p, View recycled, ViewGroup parent) { View view = super.getView(p, recycled, parent); ((TextView) view).setTextColor(ink); ((TextView) view).setTextSize(17); return view; }
            @Override public View getDropDownView(int p, View recycled, ViewGroup parent) { View view = super.getDropDownView(p, recycled, parent); view.setBackgroundColor(background); ((TextView) view).setTextColor(ink); return view; }
        });
        albums.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                visible.clear(); String key = keys.get(position); for (Photo photo : photos) if (key == null || key.equals(photo.bucket == null ? "" : photo.bucket)) visible.add(photo);
                int cell = Math.max(40, (Math.min(480, getResources().getConfiguration().screenWidthDp - 32) - 52) / 3);
                ViewGroup.LayoutParams size = grid.getLayoutParams(); size.height = dp(Math.min(getResources().getConfiguration().screenHeightDp / 2, Math.max(1, (visible.size() + 2) / 3) * (cell + 4))); grid.setLayoutParams(size);
                adapter.notifyDataSetChanged(); grid.setSelection(0); status.setText(visible.isEmpty() ? "此相册没有可访问的图片" : visible.size() + " 张图片");
            }
        });
    }
    private final class Cell extends ImageView {
        private Future<?> job; private CancellationSignal cancellation; private long bound = -1;
        Cell() { super(MediaGallery.this.getContext()); setScaleType(ScaleType.CENTER_CROP); }
        @Override protected void onMeasure(int width, int height) { int side = MeasureSpec.getSize(width); super.onMeasure(width, MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)); }
        void bind(Photo photo) {
            if (job != null) job.cancel(true); if (cancellation != null) cancellation.cancel();
            bound = photo.id; setContentDescription("选择图片：" + photo.name); setBackgroundColor(background ^ 0x00101010); setImageDrawable(null);
            Bitmap cached = thumbnails.get(photo.id); if (cached != null) { setImageBitmap(cached); return; }
            CancellationSignal signal = new CancellationSignal(); cancellation = signal;
            if (closed) return;
            job = worker.submit(() -> {
                try {
                    Bitmap bitmap = Build.VERSION.SDK_INT >= 29 ? getContext().getContentResolver().loadThumbnail(photo.uri(), new Size(240, 240), signal)
                        : MediaStore.Images.Thumbnails.getThumbnail(getContext().getContentResolver(), photo.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                    if (bitmap == null || closed || signal.isCanceled()) return; thumbnails.put(photo.id, bitmap);
                    ui.post(() -> { if (!closed && bound == photo.id && !signal.isCanceled()) setImageBitmap(bitmap); });
                } catch (Exception ignored) { /* A photo can be removed or its grant revoked while browsing. */ }
            });
        }
    }
    @Override public void close() { closed = true; worker.shutdownNow(); ui.removeCallbacksAndMessages(null); thumbnails.evictAll(); }
}
