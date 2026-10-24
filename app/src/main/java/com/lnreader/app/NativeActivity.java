package com.lnreader.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.lnreader.app.Library.*;

/** The shipped Android UI uses native views exclusively. */
public final class NativeActivity extends Activity {
    private static final int TXT = 51, TREE = 52, FONT = 53;
    private static final int BG = 0xfff7f6f2, INK = 0xff303b32, MUTED = 0xff8a8e83, ACCENT = 0xff52634e;
    private Library lib;
    private LinearLayout root, content, cards, readingBody;
    private ScrollView shelfScroll, readingScroll;
    private TextView progressLabel, chapterLabel, readingText;
    private LinearLayout readerHeader, readerFooter;
    private LinearLayout quietHeader, quietFooter;
    private TextView quietTitle, quietProgress, deviceStatus;
    private int battery = -1;
    private int readerBottomInset;
    private boolean statusRegistered;
    private final Set<Integer> collapsedChapters = new HashSet<>();
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                battery = scale <= 0 ? -1 : Math.round(100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / scale);
            }
            updateDeviceStatus();
        }
    };
    private ReaderSurface readerSurface;
    private PagedReaderView pagedReader;
    private SeekBar progress;
    private JSONObject current;
    private String source = "", active = "recent", query = "", importShelf = "default", grouping = "separate";
    private List<BookParser.Chapter> chapters = new ArrayList<>();
    private int chapter, bodyOffset, pagePrefix, searchToken;
    private boolean list, importing, positioning, controlsVisible, endReached;
    private int pageNumber = 1, pageCount = 1;
    private volatile int paginationGeneration;
    private int[] pageTotals;
    private Runnable indexTask;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog busyDialog;
    private TextView busyText;
    private Runnable progressTask;
    private long lastVolumeTurn;
    private final Runnable backgroundSave = () -> { if (lib != null) persist(); };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { lib = new Library(this); list = lib.state.optBoolean("list"); showShelf(); }
        catch (Exception e) { new AlertDialog.Builder(this).setTitle("书库暂时无法打开").setMessage(e.getMessage()).setPositiveButton("关闭", (d, w) -> finish()).show(); }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void padding(View v, int x, int y) { v.setPadding(dp(x), dp(y), dp(x), dp(y)); }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void rule(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(0xffe5e5dc); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private Button button(String value, boolean primary, Runnable click) {
        Button b = new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary ? 0xfffffef6 : ACCENT);
        b.setBackground(shape(primary ? ACCENT : Color.TRANSPARENT, 9)); b.setMinHeight(dp(42)); b.setMinimumHeight(dp(42)); b.setMinWidth(0); b.setMinimumWidth(0); padding(b, 13, 4); b.setOnClickListener(v -> click.run()); return b;
    }
    private void weighted(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private EditText input(String hint, String value) { EditText e = new EditText(this); e.setTextSize(14); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setSingleLine(true); e.setHint(hint); e.setText(value); padding(e, 10, 8); return e; }
    private void toast(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()); }
    private void base(int background, boolean dark) {
        root = column(); root.setBackgroundColor(background); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // Stable safe space prevents repagination when system bars are toggled.
            if (current != null && Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, 0); readerBottomInset = safe.bottom;
            } else if (current != null) { view.setPadding(insets.getStableInsetLeft(), insets.getStableInsetTop(), insets.getStableInsetRight(), 0); readerBottomInset = insets.getStableInsetBottom(); }
            else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            if (current != null && readerFooter != null && readerFooter.getParent() == readerSurface) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) readerFooter.getLayoutParams();
                if (params.bottomMargin != readerBottomInset) { params.bottomMargin = readerBottomInset; readerFooter.setLayoutParams(params); }
            }
            return insets.consumeSystemWindowInsets();
        });
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        root.setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        systemBars(current == null || controlsVisible);
    }
    private void systemBars(boolean visible) {
        int flags = readerInkSafe() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (current != null) flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!visible) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        root.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (visible) controller.show(WindowInsets.Type.systemBars()); else controller.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    private boolean readerInkSafe() { return current != null && readerInk() == 0xffdeded4; }
    private void updateDeviceStatus() {
        if (deviceStatus != null && current != null) {
            String clock = android.text.format.DateFormat.getTimeFormat(this).format(new Date());
            String level = battery < 0 ? "—" : battery + "%";
            deviceStatus.setText(getString(R.string.device_status, clock, level)); deviceStatus.setContentDescription("时间 " + clock + "，电量 " + level);
            deviceStatus.setCompoundDrawablesWithIntrinsicBounds(null, null, new ReaderIcon("battery", readerInk(), getResources().getDisplayMetrics().density, battery), null);
            deviceStatus.setCompoundDrawablePadding(dp(5));
        }
    }
    private void persist() {
        try {
            set(lib.state, "active", active); set(lib.state, "list", list); lib.save();
        } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
        handler.removeCallbacks(exportTask); handler.postDelayed(exportTask, 1500);
    }
    private final Runnable exportTask = () -> {
        if (lib == null) return;
        if (importing) { handler.postDelayed(this.exportTask, 1500); return; }
        final String snapshot = lib.state.toString(); final String tree = getPreferences(0).getString("tree", "");
        if (!tree.isEmpty()) io.execute(() -> {
            try {
                Uri uri = Uri.parse(tree); String id = DocumentsContract.getTreeDocumentId(uri);
                Uri file = child(uri, id, "lnreader-library.json");
                if (file == null) file = DocumentsContract.createDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(uri, id), "application/json", "lnreader-library.json");
                try (OutputStream output = getContentResolver().openOutputStream(file, "wt")) { output.write(snapshot.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception e) { toast("书库已在应用内保存；文件夹备份未写入，请检查授权"); }
        });
    };

    private void showShelf() {
        paginationGeneration++; pageTotals = null;
        current = null; source = ""; base(BG, false);
        shelfScroll = new ScrollView(this); shelfScroll.setFillViewport(true); root.addView(shelfScroll, new LinearLayout.LayoutParams(-1, -1));
        content = column(); padding(content, 24, 24); shelfScroll.addView(content);
        LinearLayout brand = row(); TextView logo = text("LNReader", 25, INK); logo.setTypeface(Typeface.SERIF); weighted(brand, logo); brand.addView(button("＋ 导入书籍", true, this::importDialog)); content.addView(brand); gap(content, 24);
        LinearLayout tabsRow = row(); HorizontalScrollView tabs = new HorizontalScrollView(this); tabs.setHorizontalScrollBarEnabled(false); LinearLayout tabButtons = row(); tabs.addView(tabButtons);
        List<JSONObject> all = new ArrayList<>(); all.add(json("id", "recent", "name", "最近阅读")); all.add(json("id", "all", "name", "全部"));
        for (JSONObject shelf : lib.shelves) if (!shelf.optString("id").equals("default")) all.add(shelf);
        for (JSONObject shelf : all) {
            String id = shelf.optString("id"); int count = 0; for (JSONObject b : lib.books) if (inShelf(b, id)) count++;
            Button tab = button(shelf.optString("name") + "  " + count, false, () -> { active = id; persist(); showShelf(); });
            if (active.equals(id)) { tab.setTextColor(INK); tab.setTypeface(null, Typeface.BOLD); tab.setBackground(shape(0xffe8ebdf, 7)); }
            tabButtons.addView(tab);
        }
        weighted(tabsRow, tabs); tabsRow.addView(button("＋", false, () -> shelfEditor(null))); if (!isBuiltinShelf(active)) tabsRow.addView(button("···", false, () -> { for (JSONObject s : lib.shelves) if (active.equals(s.optString("id"))) { shelfEditor(s); break; } }));
        content.addView(tabsRow); gap(content, 4); rule(content); gap(content, 12);
        LinearLayout tools = row(); EditText search = input("搜索书名", query); search.setContentDescription("搜索书名"); weighted(tools, search);
        Button viewToggle = button(list ? "▦" : "≡", false, () -> {}); viewToggle.setContentDescription("切换列表或瀑布流显示"); viewToggle.setOnClickListener(v -> { list = !list; viewToggle.setText(list ? "▦" : "≡"); persist(); if (query.isEmpty()) renderCards(null); else scheduleSearch(); }); tools.addView(viewToggle); content.addView(tools); gap(content, 18);
        cards = column(); content.addView(cards);
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); scheduleSearch(); } public void afterTextChanged(Editable e) {} });
        if (query.isEmpty()) renderCards(null); else scheduleSearch();
        gap(content, 18); rule(content); content.addView(button("同步文件夹", false, this::syncFolder));
    }
    private boolean isBuiltinShelf(String id) { return id.equals("all") || id.equals("recent") || id.equals("default"); }
    private boolean inShelf(JSONObject book, String id) { return id.equals("all") || (id.equals("recent") ? book.optLong("lastRead") > 0 : id.equals(book.optString("shelf"))); }
    private int percent(JSONObject b) { int length = b.optInt("length"), pos = b.optInt("position"); return length == 0 ? 0 : pos >= length - 2 ? 100 : Math.min(99, (int) (100L * pos / length)); }
    private void scheduleSearch() {
        final int token = ++searchToken;
        handler.postDelayed(() -> {
            if (token != searchToken || current != null) return;
            String term = query.trim().toLowerCase(Locale.ROOT); Set<String> matches = new HashSet<>();
            for (JSONObject b : lib.books) if (b.optString("title").toLowerCase(Locale.ROOT).contains(term)) matches.add(b.optString("id"));
            renderCards(matches);
        }, 120);
    }
    private void renderCards(Set<String> matches) {
        if (cards == null) return; cards.removeAllViews();
        List<JSONObject> books = new ArrayList<>(); for (JSONObject b : lib.books) if (inShelf(b, active) && (matches == null || matches.contains(b.optString("id")))) books.add(b);
        books.sort((a, b) -> Long.compare(active.equals("recent") ? b.optLong("lastRead") : b.optLong("added"), active.equals("recent") ? a.optLong("lastRead") : a.optLong("added")));
        if (books.isEmpty()) {
            gap(cards, 42); TextView title = text(!query.isEmpty() ? "未找到书籍" : active.equals("recent") ? "暂无最近阅读" : "暂无书籍", 18, MUTED); title.setGravity(Gravity.CENTER); cards.addView(title); gap(cards, 24);
            cards.addView(button("导入书籍", true, this::importDialog));
            if (active.equals("recent") && !lib.books.isEmpty()) cards.addView(button("查看全部书籍", false, () -> { active = "all"; showShelf(); }));
            gap(cards, 35); return;
        }
        TextView count = text(books.size() + " 本书", 10, MUTED); cards.addView(count); gap(cards, 14);
        LinearLayout grid = row(); grid.setGravity(Gravity.TOP); cards.addView(grid); int cols = list ? 1 : getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3;
        List<LinearLayout> columns = new ArrayList<>(); for (int i = 0; i < cols; i++) { LinearLayout col = column(); if (i != 0) col.setPadding(dp(13), 0, 0, 0); weighted(grid, col); columns.add(col); }
        for (int i = 0; i < books.size(); i++) {
            JSONObject book = books.get(i); LinearLayout card = list ? row() : column(); card.setGravity(list ? Gravity.CENTER_VERTICAL : Gravity.TOP);
            int width = (getResources().getConfiguration().screenWidthDp - 48 - 13 * (cols - 1)) / cols;
            View cover = cover(book); card.addView(cover, new LinearLayout.LayoutParams(list ? dp(60) : -1, dp(list ? 84 : (int) (width * (i % 3 == 1 ? 1.32 : 1.5)))));
            LinearLayout info = column(); if (list) info.setPadding(dp(18), 0, 0, 0); else gap(info, 10);
            TextView name = text(book.optString("title"), list ? 15 : 12, INK); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); info.addView(name); gap(info, 7); info.addView(text(book.optLong("lastRead") > 0 ? "已读 " + percent(book) + "%" : book.optInt("chapterCount") + " 章 · 未开始", 9, MUTED));
            if (list) weighted(card, info); else card.addView(info);
            Button more = button("···", false, () -> bookEditor(book)); more.setContentDescription("管理" + book.optString("title")); card.addView(more, new LinearLayout.LayoutParams(list ? dp(38) : -1, dp(32)));
            cover.setOnClickListener(v -> openBook(book, -1)); info.setOnClickListener(v -> openBook(book, -1)); card.setOnLongClickListener(v -> { bookEditor(book); return true; });
            columns.get(i % cols).addView(card); gap(columns.get(i % cols), 18);
        }
    }
    private View cover(JSONObject book) {
        String id = book.optString("id"); File image = new File(lib.root, id + ".jpg");
        if (book.optBoolean("cover") && image.exists()) { ImageView v = new ImageView(this); BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2; v.setImageBitmap(BitmapFactory.decodeFile(image.getPath(), options)); v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setContentDescription(book.optString("title") + "封面"); v.setClipToOutline(true); v.setBackground(shape(0xffd8dfcd, 7)); return v; }
        int[] palette = { 0xffd8dfcd, 0xffe8dfcc, 0xffd4dedb, 0xffddd8e1, 0xffe4d5cc }; String name = book.optString("title");
        TextView v = text(name, 17, 0xff5b6653); v.setGravity(Gravity.CENTER); padding(v, 10, 12); v.setTypeface(Typeface.SERIF); v.setBackground(shape(palette[Math.abs(name.hashCode() % palette.length)], 7)); v.setMaxLines(8); v.setEllipsize(TextUtils.TruncateAt.END); return v;
    }
    private Dialog sheet(String title, LinearLayout body) {
        Dialog d = new Dialog(this); LinearLayout shell = column(); padding(shell, 24, 22); shell.setBackground(shape(0xfffffefa, 18)); LinearLayout heading = row(); weighted(heading, text(title, 21, INK)); heading.addView(button("×", false, d::dismiss)); shell.addView(heading); gap(shell, 18); shell.addView(body);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int max = getResources().getDisplayMetrics().heightPixels * 85 / 100;
                int available = View.MeasureSpec.getMode(height) == View.MeasureSpec.UNSPECIFIED ? max : Math.min(max, View.MeasureSpec.getSize(height));
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false); scroll.addView(shell); d.setContentView(scroll); Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawableResource(android.R.color.transparent); w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
        d.show(); if (w != null) { WindowManager.LayoutParams params = w.getAttributes(); params.width = Math.min(dp(480), getResources().getDisplayMetrics().widthPixels - dp(32)); params.height = -2; params.y = dp(16); w.setAttributes(params); }
        return d;
    }
    private Spinner shelfSpinner(String selected) {
        Spinner spinner = new Spinner(this); List<String> names = new ArrayList<>(); int index = 0;
        for (int i = 0; i < lib.shelves.size(); i++) { names.add(lib.shelves.get(i).optString("name")); if (lib.shelves.get(i).optString("id").equals(selected)) index = i; }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names); spinner.setAdapter(adapter); spinner.setSelection(index); return spinner;
    }
    private void shelfEditor(JSONObject shelf) {
        LinearLayout body = column(); EditText name = input("比如：枕边常读", shelf == null ? "" : shelf.optString("name")); body.addView(name); gap(body, 20);
        Dialog dialog = sheet(shelf == null ? "新建书架" : "管理书架", body);
        body.addView(button("保存书架", true, () -> { String value = name.getText().toString().trim(); if (value.isEmpty() || value.length() > 20) { toast("书架名称请输入 1～20 字"); return; } for (JSONObject s : lib.shelves) if (s != shelf && s.optString("name").equals(value)) { toast("书架名称已经存在"); return; } if (shelf == null) { JSONObject s = json("id", UUID.randomUUID().toString(), "name", value); lib.shelves.add(s); active = s.optString("id"); } else set(shelf, "name", value); persist(); dialog.dismiss(); showShelf(); }));
        if (shelf != null) body.addView(button("删除书架，书籍移至未分类", false, () -> new AlertDialog.Builder(this).setMessage("删除此书架？书籍会保留并移到未分类。").setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> { for (JSONObject b : lib.books) if (b.optString("shelf").equals(shelf.optString("id"))) set(b, "shelf", "default"); lib.shelves.remove(shelf); active = "all"; persist(); dialog.dismiss(); showShelf(); }).show()));
    }
    private void bookEditor(JSONObject book) {
        LinearLayout body = column(); EditText name = input("书名", book.optString("title")); body.addView(name); gap(body, 15); Spinner shelf = shelfSpinner(book.optString("shelf")); body.addView(shelf); gap(body, 20);
        Dialog dialog = sheet("书籍管理", body);
        body.addView(button("保存", true, () -> { String value = name.getText().toString().trim(); if (value.isEmpty()) return; set(book, "title", value.substring(0, Math.min(value.length(), 120))); set(book, "shelf", lib.shelves.get(shelf.getSelectedItemPosition()).optString("id")); persist(); dialog.dismiss(); showShelf(); }));
        body.addView(button("从书架移除", false, () -> new AlertDialog.Builder(this).setTitle("移除这本书？").setMessage("应用中的副本、进度和书签会移除，原始 TXT 保留。").setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> { lib.books.remove(book); persist(); lib.textFile(book.optString("id")).delete(); new File(lib.root, book.optString("id") + ".jpg").delete(); dialog.dismiss(); showShelf(); }).show()));
    }

    private void openBook(JSONObject book, int offset) {
        if (importing) return; showBusy("正在翻开书页…");
        io.execute(() -> { try { String value = lib.text(book); List<BookParser.Chapter> parsed = BookParser.parse(value); runOnUiThread(() -> { hideBusy(); current = book; controlsVisible = false; collapsedChapters.clear(); source = value; chapters = parsed; set(current, "lastRead", System.currentTimeMillis()); set(current, "chapterCount", parsed.size()); set(current, "length", source.length()); buildReader(offset < 0 ? current.optInt("position") : offset); persist(); }); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("无法打开，请重新导入原文件：" + e.getMessage()); } });
    }
    private int readerBg() { try { return Color.parseColor(lib.prefs().optString("bg", "#f7f3e8")); } catch (Exception e) { return 0xfff7f3e8; } }
    private int readerInk() { int bg = readerBg(); return Color.red(bg) * .299 + Color.green(bg) * .587 + Color.blue(bg) * .114 < 135 ? 0xffdeded4 : 0xff363c32; }
    private Button readerButton(String title, Runnable action) { Button b = button(title, false, action); b.setTextColor(readerInk()); b.setTextSize(15); b.setMinHeight(dp(48)); return b; }
    private Button readerAction(String title, String icon, Runnable action) {
        Button b = readerButton(title, action); b.setTextSize(13); b.setGravity(Gravity.CENTER); padding(b, 6, 8); b.setMinHeight(dp(72)); b.setMinimumHeight(dp(72));
        b.setCompoundDrawablesWithIntrinsicBounds(null, new ReaderIcon(icon, readerInk(), getResources().getDisplayMetrics().density), null, null); b.setCompoundDrawablePadding(dp(5)); return b;
    }
    private ImageButton readerIconButton(String icon, String label, Runnable action) {
        ImageButton button = new ImageButton(this); button.setImageDrawable(new ReaderIcon(icon, readerInk(), getResources().getDisplayMetrics().density));
        button.setContentDescription(label); button.setBackground(shape(Color.TRANSPARENT, 8)); padding(button, 12, 12);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void schedulePageIndex() {
        int generation = ++paginationGeneration; pageTotals = null;
        if (indexTask != null) handler.removeCallbacks(indexTask);
        indexTask = () -> indexPages(generation); handler.postDelayed(indexTask, 200);
    }
    private boolean isPagedMode() { return !lib.prefs().optString("readingMode", "paged").equals("scroll"); }
    private int pageSourceOffset(int local) { return local <= pagePrefix ? chapters.get(chapter).start : Math.min(chapters.get(chapter).end - 1, bodyOffset + local - pagePrefix); }
    private void showControls(boolean show) {
        controlsVisible = show; readerHeader.setVisibility(show ? View.VISIBLE : View.GONE); readerFooter.setVisibility(show ? View.VISIBLE : View.GONE);
        quietHeader.setVisibility(show ? View.GONE : View.VISIBLE); quietFooter.setVisibility(show ? View.GONE : View.VISIBLE);
        readerSurface.overlays(show ? readerHeader : quietHeader, show ? readerFooter : quietFooter);
        systemBars(show);
    }
    private void buildReader(int offset) {
        ++paginationGeneration; pageTotals = null;
        if (indexTask != null) handler.removeCallbacks(indexTask);
        if (progressTask != null) handler.removeCallbacks(progressTask);
        base(readerBg(), readerInk() == 0xffdeded4);
        ReaderSurface surface = new ReaderSurface(this, isPagedMode(), new ReaderSurface.Listener() {
            public void tap(float fraction) { if (fraction < 1f / 3) turnPage(-1); else if (fraction > 2f / 3) turnPage(1); else showControls(!controlsVisible); }
            public void turn(int direction) { turnPage(direction); }
        }); readerSurface = surface; root.addView(surface, new LinearLayout.LayoutParams(-1, -1));
        readingScroll = null; readingText = null; readingBody = null; pagedReader = null;
        if (isPagedMode()) {
            pagedReader = new PagedReaderView(this, new PagedReaderView.Listener() {
                public void positionChanged(int local, int page, int count) {
                    if (current == null || readerSurface != surface) return;
                    pageNumber = page; pageCount = count;
                    set(current, "position", endReached ? source.length() - 1 : pageSourceOffset(local)); positionUI();
                }
                public void turn(int direction) { turnPage(direction); }
                public void layoutChanged() {
                    if (readerSurface != surface) return;
                    schedulePageIndex();
                }
            });
            pagedReader.configure((float) lib.prefs().optDouble("size", 18), readingTypeface(), readerInk(), readerBg(), (float) lib.prefs().optDouble("line", 1.4));
            pagedReader.setPadding(dp(24), dp(58), dp(24), dp(40));
            surface.addView(pagedReader, new FrameLayout.LayoutParams(-1, -1));
        } else {
            readingScroll = new ScrollView(this); readingScroll.setFillViewport(false); surface.scrollChild(readingScroll);
            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(-1, -1); scrollParams.topMargin = dp(48); scrollParams.bottomMargin = dp(32);
            surface.addView(readingScroll, scrollParams); readingBody = column(); padding(readingBody, 24, 10); readingScroll.addView(readingBody);
            readingScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) schedulePageIndex(); });
            readingScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (positioning) return; if (progressTask != null) handler.removeCallbacks(progressTask); progressTask = () -> { capture(); persist(); }; handler.postDelayed(progressTask, 250); });
        }
        readerHeader = row(); readerHeader.setBackgroundColor(readerBg()); padding(readerHeader, 6, 6); readerHeader.addView(readerIconButton("back", "返回书架", this::back), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column(); TextView title = text(current.optString("title"), 16, readerInk()); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); titles.addView(title);
        chapterLabel = text("", 15, MUTED); chapterLabel.setMaxLines(1); chapterLabel.setEllipsize(TextUtils.TruncateAt.END); titles.addView(chapterLabel); weighted(readerHeader, titles);
        readerHeader.addView(readerIconButton("bookmark", "书签", this::marks), new LinearLayout.LayoutParams(dp(48), dp(48))); surface.addView(readerHeader, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        readerFooter = column(); readerFooter.setBackgroundColor(readerBg()); padding(readerFooter, 16, 8); progressLabel = text("", 14, MUTED); readerFooter.addView(progressLabel);
        LinearLayout navigation = row(); navigation.addView(readerButton("上一章", () -> chapterStep(-1)));
        progress = new SeekBar(this); progress.setMax(1000); progress.setContentDescription("阅读进度"); weighted(navigation, progress);
        navigation.addView(readerButton("下一章", () -> chapterStep(1))); readerFooter.addView(navigation);
        LinearLayout actions = row(); weighted(actions, readerAction("目录", "catalog", this::catalog));
        weighted(actions, readerAction("日 / 夜", "moon", () -> { capture(); set(lib.prefs(), "customBackground", false); applyBackground(readerBg() == 0xff20231f ? "#f7f3e8" : "#20231f"); }));
        weighted(actions, readerAction("设置", "settings", this::settings)); readerFooter.addView(actions);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); footerParams.bottomMargin = readerBottomInset; surface.addView(readerFooter, footerParams);
        quietHeader = row(); padding(quietHeader, 8, 0); quietHeader.setBackgroundColor(readerBg());
        quietHeader.addView(readerIconButton("back", "返回书架", this::back), new LinearLayout.LayoutParams(dp(48), dp(48)));
        quietTitle = text("", 16, readerInk()); quietTitle.setAlpha(.75f); quietTitle.setSingleLine(true); quietTitle.setEllipsize(TextUtils.TruncateAt.END); weighted(quietHeader, quietTitle);
        surface.addView(quietHeader, new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP));
        quietFooter = row(); padding(quietFooter, 24, 0); quietFooter.setBackgroundColor(readerBg());
        quietProgress = text("", 14, readerInk()); quietProgress.setAlpha(.75f); quietProgress.setSingleLine(true); quietProgress.setEllipsize(TextUtils.TruncateAt.END); weighted(quietFooter, quietProgress);
        deviceStatus = text("", 14, readerInk()); deviceStatus.setAlpha(.75f); deviceStatus.setSingleLine(true); quietFooter.addView(deviceStatus); updateDeviceStatus();
        FrameLayout.LayoutParams quietParams = new FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM); quietParams.bottomMargin = dp(4); surface.addView(quietFooter, quietParams);
        showControls(controlsVisible);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s, int p, boolean fromUser) {} public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) { jump((int) ((long) s.getProgress() * Math.max(0, source.length() - 1) / 1000)); persist(); } });
        jump(offset);
    }
    private void indexPages(int generation) {
        if (generation != paginationGeneration || current == null) return;
        if (isPagedMode() ? pagedReader == null || !pagedReader.ready() : readingText == null || readingText.getWidth() <= 0) return;
        final PagedReaderView.PageCounter counter = isPagedMode() ? pagedReader.counter() : null;
        final ScrollPageCounter scrollCounter = counter == null ? new ScrollPageCounter((TextView) readingBody.getChildAt(0), readingText, readingScroll.getHeight(), dp(60)) : null;
        final String bookText = source;
        final List<BookParser.Chapter> bookChapters = chapters;
        final int spacing = dp((float) (lib.prefs().optDouble("paragraph", .4) * lib.prefs().optDouble("size", 18)));
        io.execute(() -> {
            int[] totals = new int[bookChapters.size() + 1];
            for (int i = 0; i < bookChapters.size(); i++) {
                if (generation != paginationGeneration || Thread.currentThread().isInterrupted()) return;
                BookParser.Chapter c = bookChapters.get(i); String body = bookText.substring(c.start, c.end);
                while (body.startsWith("\n")) body = body.substring(1);
                int line = body.indexOf('\n');
                if (line >= 0 && body.substring(0, line).trim().replaceFirst("^#+\\s*", "").equals(c.title)) body = body.substring(line + 1);
                SpannableString styled = styledText(c.title + "\n\n" + body, spacing);
                styleHeading(styled, c.title.length()); totals[i + 1] = totals[i] + (counter != null ? counter.count(styled) : scrollCounter.count(c.title, styledText(body, spacing)));
            }
            handler.post(() -> { if (generation == paginationGeneration && current != null) { pageTotals = totals; positionUI(); } });
        });
    }
    private static final class ScrollPageCounter {
        final TextPaint heading, body; final int width, height, padding; final float line;
        ScrollPageCounter(TextView title, TextView text, int height, int padding) {
            heading = new TextPaint(title.getPaint()); body = new TextPaint(text.getPaint()); width = text.getWidth(); this.height = Math.max(1, height); this.padding = padding; line = text.getLineSpacingMultiplier();
        }
        int measure(CharSequence text, TextPaint paint, float multiplier) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).setIncludePad(true).setLineSpacing(0, multiplier).setBreakStrategy(simpleBreakStrategy()).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().getHeight();
        }
        int count(String title, CharSequence text) { return Math.max(1, (int) Math.ceil((measure(title, heading, 1.4f) + measure(text, body, line) + padding) / (double) height)); }
    }
    private static int simpleBreakStrategy() {
        // The constant moved to LineBreaker in API 29; older releases expose the same strategy in Layout.
        return Build.VERSION.SDK_INT >= 29 ? android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE : Layout.BREAK_STRATEGY_SIMPLE;
    }
    private static void styleHeading(SpannableString styled, int length) {
        styled.setSpan(new android.text.style.RelativeSizeSpan(1.25f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private void chapterStep(int direction) {
        int next = chapter + direction;
        if (next < 0 || next >= chapters.size()) { toast(direction < 0 ? "已到第一章" : "已到最后一章"); return; }
        jump(chapters.get(next).start); persist();
    }
    private Typeface readingTypeface() {
        String font = lib.prefs().optString("font", "系统默认");
        try { if (font.equals("自选字体")) return Typeface.createFromFile(new File(getFilesDir(), "reader-font")); if (font.startsWith("/system/fonts/")) return Typeface.createFromFile(font); if (font.equals("系统衬线")) return Typeface.SERIF; if (font.equals("等宽字体")) return Typeface.MONOSPACE; } catch (Exception ignored) {}
        return Typeface.DEFAULT;
    }
    private void jump(int offset) {
        if (progressTask != null) handler.removeCallbacks(progressTask); positioning = true; offset = Math.max(0, Math.min(source.length() - 1, offset)); endReached = offset >= source.length() - 2;
        chapter = chapters.size() - 1; for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).end > offset) { chapter = i; break; }
        BookParser.Chapter c = chapters.get(chapter); set(current, "position", offset); set(current, "chapterTitle", c.title); chapterLabel.setText(c.title);
        quietTitle.setText(c.title);
        bodyOffset = c.start; String body = source.substring(c.start, c.end);
        while (body.startsWith("\n")) { body = body.substring(1); bodyOffset++; }
        int firstLineEnd = body.indexOf('\n'); if (firstLineEnd >= 0 && body.substring(0, firstLineEnd).trim().replaceFirst("^#+\\s*", "").equals(c.title)) { bodyOffset += firstLineEnd + 1; body = body.substring(firstLineEnd + 1); }
        if (isPagedMode()) {
            pagePrefix = c.title.length() + 2; SpannableString styled = styledText(c.title + "\n\n" + body);
            styleHeading(styled, c.title.length());
            pagedReader.content(styled, offset <= bodyOffset ? 0 : pagePrefix + offset - bodyOffset);
            positioning = false; positionUI(); return;
        }
        readingBody.removeAllViews(); TextView heading = text(c.title, (float) lib.prefs().optDouble("size", 18) * 1.25f, readerInk()); heading.setTypeface(readingTypeface(), Typeface.BOLD); heading.setLineSpacing(0, 1.4f); readingBody.addView(heading); gap(readingBody, 20);
        readingText = text(body, (float) lib.prefs().optDouble("size", 18), readerInk()); readingText.setTypeface(readingTypeface()); readingText.setTextIsSelectable(true); readingText.setLineSpacing(0, (float) lib.prefs().optDouble("line", 1.4));
        readingText.setText(styledText(body)); readingBody.addView(readingText, new LinearLayout.LayoutParams(-1, -2)); gap(readingBody, 20);
        for (TextView v : new TextView[]{heading, readingText}) { v.setBreakStrategy(simpleBreakStrategy()); v.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE); if (Build.VERSION.SDK_INT >= 28) v.setFallbackLineSpacing(false); }
        int desired = offset; readingText.post(() -> { if (readingText.getLayout() != null) { int local = Math.max(0, Math.min(readingText.length(), desired - bodyOffset)); int y = desired <= c.start ? 0 : readingText.getTop() + readingText.getLayout().getLineTop(readingText.getLayout().getLineForOffset(local)); readingScroll.scrollTo(0, y); } positioning = false; positionUI(); });
    }
    private SpannableString styledText(String text) {
        return styledText(text, dp((float) (lib.prefs().optDouble("paragraph", .4) * lib.prefs().optDouble("size", 18))));
    }
    private static SpannableString styledText(String text, int spacing) {
        SpannableString styled = new SpannableString(text); int cursor = 0;
        for (String line : text.split("\n", -1)) { int end = Math.min(text.length(), cursor + line.length() + 1); if (end > cursor) styled.setSpan(new ParagraphSpace(spacing), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); cursor = end; }
        return styled;
    }
    private void turnPage(int direction) {
        if (current != null && isPagedMode() && pagedReader != null) {
            boolean canMove = pagedReader.canTurnPage(direction) || (direction > 0 ? chapter + 1 < chapters.size() : chapter > 0);
            if (!positioning && !importing && canMove && pagedReader.ready() && lib.prefs().optString("pageAnimation", "slide").equals("slide")) {
                pagedReader.slide(direction, () -> turnPageNow(direction)); return;
            }
        }
        turnPageNow(direction);
    }
    private void turnPageNow(int direction) {
        if (current == null || positioning || importing) return;
        if (isPagedMode() && (pagedReader == null || !pagedReader.ready())) return;
        endReached = false;
        if (isPagedMode()) {
            if (pagedReader.turnPage(direction)) { capture(); persist(); return; }
        } else {
            int y = readingScroll.getScrollY(), max = Math.max(0, readingBody.getHeight() - readingScroll.getHeight());
            if (direction > 0 ? y < max - 2 : y > 2) {
                int step = Math.max(dp(40), readingScroll.getHeight() - dp(40));
                readingScroll.scrollTo(0, Math.max(0, Math.min(max, y + direction * step))); capture(); persist(); return;
            }
        }
        if (direction > 0 && chapter + 1 < chapters.size()) jump(chapters.get(chapter + 1).start);
        else if (direction < 0 && chapter > 0) jump(chapters.get(chapter - 1).end - 1);
        else { endReached = direction > 0; if (endReached) set(current, "position", source.length() - 1); toast(direction > 0 ? "已到最后一页" : "已到第一页"); }
        positionUI(); persist();
    }
    private static final class ParagraphSpace implements android.text.style.LineHeightSpan {
        private final int gap; ParagraphSpace(int gap) { this.gap = gap; }
        @Override public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, Paint.FontMetricsInt fm) {
            if (text.subSequence(start, end).toString().trim().isEmpty()) { fm.ascent = -1; fm.top = -1; fm.descent = 0; fm.bottom = 0; }
            else if (end > 0 && (end == text.length() || text.charAt(end - 1) == '\n')) { fm.descent += gap; fm.bottom += gap; }
        }
    }
    private void capture() {
        if (current == null || positioning) return;
        if (isPagedMode()) {
            if (pagedReader != null) set(current, "position", endReached ? source.length() - 1 : pageSourceOffset(pagedReader.offset()));
            set(current, "lastRead", System.currentTimeMillis()); positionUI(); return;
        }
        if (readingText == null || readingText.getLayout() == null) return;
        if (endReached) { set(current, "position", source.length() - 1); positionUI(); return; }
        int y = Math.max(0, readingScroll.getScrollY() - readingText.getTop()); int line = readingText.getLayout().getLineForVertical(y); int offset = bodyOffset + readingText.getLayout().getLineStart(line);
        if (readingScroll.getScrollY() <= dp(15)) offset = chapters.get(chapter).start;
        if (readingBody.getHeight() > readingScroll.getHeight() && readingScroll.getScrollY() + readingScroll.getHeight() >= readingBody.getHeight() - dp(4)) offset = chapters.get(chapter).end - 1;
        set(current, "position", Math.min(source.length() - 1, offset)); set(current, "lastRead", System.currentTimeMillis()); positionUI();
    }
    private void positionUI() {
        if (progressLabel == null || current == null) return;
        progress.setProgress((int) (1000L * current.optInt("position") / Math.max(1, source.length())));
        if (quietProgress != null) {
            int page = pageNumber, count = pageCount;
            if (!isPagedMode() && readingScroll != null && readingBody != null) {
                int height = Math.max(1, readingScroll.getHeight());
                count = Math.max(1, (int) Math.ceil((double) readingBody.getHeight() / height));
                page = Math.min(count, 1 + readingScroll.getScrollY() / height);
                if (readingScroll.getScrollY() > 0 && !readingScroll.canScrollVertically(1)) page = count;
            }
            String pages = pageTotals != null ? (pageTotals[chapter] + Math.min(page, pageTotals[chapter + 1] - pageTotals[chapter])) + "/" + pageTotals[pageTotals.length - 1] : "—/—";
            String label = getString(R.string.quiet_progress, pages, percent(current)); quietProgress.setText(label); progressLabel.setText(label);
        }
    }
    private void back() { capture(); persist(); if (progressTask != null) handler.removeCallbacks(progressTask); showShelf(); }
    private void catalog() {
        LinearLayout body = column(); EditText search = input("搜索章节", ""); body.addView(search); ListView entries = new ListView(this); body.addView(entries, new LinearLayout.LayoutParams(-1, dp(getResources().getConfiguration().screenHeightDp / 2))); Dialog d = sheet("目录 · " + chapters.size() + " 章", body);
        ChapterTree tree = new ChapterTree(chapters);
        collapsedChapters.clear(); collapsedChapters.addAll(tree.collapsedOutsidePath(chapter));
        List<Integer> indices = new ArrayList<>();
        Runnable fit = () -> {
            ViewGroup.LayoutParams params = entries.getLayoutParams();
            params.height = dp(Math.min(getResources().getConfiguration().screenHeightDp / 2, Math.max(1, indices.size()) * 56)); entries.setLayoutParams(params);
        };
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return indices.size(); }
            public Object getItem(int position) { return indices.get(position); }
            public long getItemId(int position) { return indices.get(position); }
            public View getView(int position, View recycled, ViewGroup parent) {
                int index = indices.get(position); LinearLayout item = row();
                item.setPadding(dp(Math.min(4, tree.depth[index]) * 8), 0, dp(4), 0);
                item.setSelected(index == chapter); item.setBackground(shape(index == chapter ? 0xffd5dfcf : Color.TRANSPARENT, 8));
                if (tree.branch[index]) {
                    Button toggle = button(collapsedChapters.contains(index) && search.length() == 0 ? "▸" : "▾", false, () -> {
                        int first = entries.getFirstVisiblePosition(); View top = entries.getChildAt(0); int y = top == null ? 0 : top.getTop();
                        if (!collapsedChapters.remove(index)) collapsedChapters.add(index);
                        indices.clear(); indices.addAll(tree.visible(collapsedChapters, search.getText().toString())); notifyDataSetChanged(); fit.run(); entries.setSelectionFromTop(first, y);
                    });
                    toggle.setContentDescription((collapsedChapters.contains(index) ? "展开" : "收起") + chapters.get(index).title);
                    padding(toggle, 0, 0); item.addView(toggle, new LinearLayout.LayoutParams(dp(32), dp(48)));
                } else item.addView(new View(NativeActivity.this), new LinearLayout.LayoutParams(dp(32), 1));
                TextView label = text(chapters.get(index).title, 16, index == chapter ? 0xff2f412c : INK);
                label.setTypeface(null, index == chapter ? Typeface.BOLD : Typeface.NORMAL);
                label.setContentDescription(chapters.get(index).title + (index == chapter ? "，当前章节" : ""));
                label.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.setSelected(index == chapter);
                    }
                });
                padding(label, 4, 12); label.setMinHeight(dp(48)); weighted(item, label);
                label.setOnClickListener(v -> { jump(chapters.get(index).start); persist(); notifyDataSetChanged(); });
                return item;
            }
        };
        entries.setAdapter(adapter);
        Runnable render = () -> { indices.clear(); indices.addAll(tree.visible(collapsedChapters, search.getText().toString())); adapter.notifyDataSetChanged(); fit.run(); };
        LinearLayout tools = row(); weighted(tools, button("全部展开", false, () -> { collapsedChapters.clear(); render.run(); }));
        weighted(tools, button("全部收起", false, () -> { for (int i = 0; i < tree.branch.length; i++) if (tree.branch[i]) collapsedChapters.add(i); render.run(); })); body.addView(tools, 1);
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { render.run(); } public void afterTextChanged(Editable e) {} }); render.run();
        entries.setSelection(Math.max(0, indices.indexOf(chapter)));
        body.addView(button("返回阅读", false, d::dismiss));
    }
    private JSONArray bookmarks() { JSONArray marks = current.optJSONArray("marks"); if (marks == null) { marks = new JSONArray(); set(current, "marks", marks); } return marks; }
    private void addMark() { capture(); JSONArray marks = bookmarks(); for (int i = 0; i < marks.length(); i++) if (Math.abs(marks.optJSONObject(i).optInt("offset") - current.optInt("position")) < 80) { toast("这个位置已经有书签"); return; } marks.put(json("offset", current.optInt("position"), "title", chapters.get(chapter).title, "date", System.currentTimeMillis())); persist(); toast("已添加书签"); }
    private void marks() {
        capture(); LinearLayout body = column(); Dialog d = sheet("我的书签", body); LinearLayout entries = column();
        body.addView(button("添加当前位置书签", false, () -> { addMark(); renderMarks(entries, d); })); body.addView(entries); renderMarks(entries, d);
    }
    private void renderMarks(LinearLayout body, Dialog dialog) {
        body.removeAllViews(); JSONArray marks = bookmarks();
        if (marks.length() == 0) body.addView(text("还没有书签", 15, MUTED));
        for (int i = 0; i < marks.length(); i++) { int index = i; JSONObject mark = marks.optJSONObject(i); LinearLayout r = row(); weighted(r, button(mark.optString("title") + " · " + (int) (100L * mark.optInt("offset") / Math.max(1, source.length())) + "%", false, () -> { jump(mark.optInt("offset")); persist(); dialog.dismiss(); })); Button remove = button("×", false, () -> { marks.remove(index); persist(); renderMarks(body, dialog); }); remove.setContentDescription("删除书签" + mark.optString("title")); r.addView(remove); body.addView(r); }
    }
    private void settings() {
        capture(); LinearLayout body = column(); Dialog d = sheet("阅读设置", body); d.setOnDismissListener(dialog -> body.clearFocus());
        Spinner mode = new Spinner(this);
        mode.setContentDescription("翻页方式"); mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{ "左右翻页", "滚动翻页" }));
        mode.setSelection(isPagedMode() ? 0 : 1); settingRow(body, "翻页方式", mode);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int index, long id) {
                String selected = index == 0 ? "paged" : "scroll";
                if (selected.equals(lib.prefs().optString("readingMode", "paged"))) return;
                capture(); set(lib.prefs(), "readingMode", selected); buildReader(current.optInt("position")); persist();
            }
        });
        Spinner animation = new Spinner(this); animation.setContentDescription("翻页动画");
        animation.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"平移", "无动画"}));
        animation.setSelection(lib.prefs().optString("pageAnimation", "slide").equals("none") ? 1 : 0); settingRow(body, "翻页动画", animation);
        animation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View view, int index, long id) { String value = index == 0 ? "slide" : "none"; if (!value.equals(lib.prefs().optString("pageAnimation", "slide"))) { set(lib.prefs(), "pageAnimation", value); persist(); } }
        });
        Switch volume = new Switch(this); volume.setContentDescription("音量键翻页");
        volume.setChecked(lib.prefs().optBoolean("volumeKeys", true)); volume.setOnCheckedChangeListener((v, enabled) -> { set(lib.prefs(), "volumeKeys", enabled); persist(); }); settingRow(body, "音量键翻页", volume);
        Button font = button(new File(lib.prefs().optString("font", "系统默认")).getName(), false, () -> { d.dismiss(); fonts(); }); settingRow(body, "字体", font);
        settingNumber(body, "字号", "size", 14, 36); settingNumber(body, "行间距", "line", 1.2, 2.8); settingNumber(body, "段间距", "paragraph", 0, 2.5);
        backgroundPicker(body);
        body.addView(button("恢复默认设置", false, () -> { body.clearFocus(); set(lib.state, "prefs", defaultPrefs()); buildReader(current.optInt("position")); persist(); d.dismiss(); }));
    }
    private void settingRow(LinearLayout body, String title, View value) {
        LinearLayout line = row(); line.setMinimumHeight(dp(52)); weighted(line, text(title, 16, INK)); line.addView(value); body.addView(line);
    }
    private void settingNumber(LinearLayout body, String title, String key, double min, double max) {
        EditText value = input(title, formatNumber(lib.prefs().optDouble(key))); value.setTextSize(16); value.setGravity(Gravity.END); value.setWidth(dp(88)); value.setSelectAllOnFocus(true);
        value.setContentDescription(title + "数值"); value.setInputType(InputType.TYPE_CLASS_NUMBER | (key.equals("size") ? 0 : InputType.TYPE_NUMBER_FLAG_DECIMAL)); value.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        Runnable apply = () -> {
            try {
                double number = Double.parseDouble(value.getText().toString());
                if (!Double.isFinite(number) || number < min || number > max || (key.equals("size") && number != Math.rint(number))) throw new NumberFormatException();
                if (Math.abs(lib.prefs().optDouble(key) - number) < .0001) return;
                capture(); int position = current.optInt("position"); set(lib.prefs(), key, number); buildReader(position); persist(); value.setError(null);
            } catch (NumberFormatException error) { value.setError("请输入 " + formatNumber(min) + "～" + formatNumber(max)); }
        };
        value.setOnFocusChangeListener((v, focus) -> { if (!focus) apply.run(); });
        value.setOnEditorActionListener((v, action, event) -> { apply.run(); ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(value.getWindowToken(), 0); value.clearFocus(); return true; });
        settingRow(body, title, value);
    }
    private String formatNumber(double value) { return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value); }
    private void colorViews(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof ReaderIcon) ((ReaderIcon) ((ImageView) view).getDrawable()).color(readerInk());
        if (view instanceof TextView) {
            TextView t = (TextView) view; t.setTextColor(readerInk());
            for (android.graphics.drawable.Drawable icon : t.getCompoundDrawables()) if (icon instanceof ReaderIcon) ((ReaderIcon) icon).color(readerInk());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) colorViews(((ViewGroup) view).getChildAt(i));
    }
    private void applyBackground(String color) {
        set(lib.prefs(), "bg", color);
        root.setBackgroundColor(readerBg()); readerSurface.setBackgroundColor(readerBg());
        for (View view : new View[]{readerHeader, readerFooter, quietHeader, quietFooter}) { view.setBackgroundColor(readerBg()); colorViews(view); }
        if (pagedReader != null) pagedReader.colors(readerInk(), readerBg());
        if (readingBody != null) colorViews(readingBody);
        getWindow().setStatusBarColor(readerBg()); getWindow().setNavigationBarColor(readerBg()); systemBars(controlsVisible);
        handler.removeCallbacks(backgroundSave); handler.postDelayed(backgroundSave, 250);
    }
    private void backgroundPicker(LinearLayout body) {
        String[] names = {"纸白", "青竹", "米黄", "暖灰", "雾蓝", "浅杏", "淡粉", "夜读", "自定义"};
        String[] colors = {"#f7f3e8", "#e5ebdf", "#d4c9aa", "#e4e0d9", "#dce7ec", "#f2e2cc", "#f0e0e5", "#20231f"};
        LinearLayout choices = column(), custom = column(); choices.setVisibility(View.GONE); custom.setVisibility(View.GONE);
        Button expand = button("", false, () -> choices.setVisibility(choices.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        settingRow(body, "背景色", expand); body.addView(choices);
        Button[] presets = new Button[names.length]; int[] channels = new int[3]; boolean[] syncing = {false};
        SeekBar[] sliders = new SeekBar[3]; EditText[] values = new EditText[3];
        TextView preview = text("", 16, INK); preview.setGravity(Gravity.CENTER); preview.setMinHeight(dp(50)); custom.addView(preview);
        Runnable render = () -> {
            String selected = lib.prefs().optString("bg"); int match = -1;
            for (int i = 0; i < colors.length; i++) if (colors[i].equalsIgnoreCase(selected)) match = i;
            boolean isCustom = lib.prefs().optBoolean("customBackground", match < 0); int selectedIndex = isCustom ? 8 : match;
            expand.setText(getString(R.string.background_choice, selectedIndex < 0 ? "自定义" : names[selectedIndex]));
            for (int i = 0; i < presets.length; i++) {
                Button b = presets[i]; if (b == null) continue;
                GradientDrawable background = shape(i < colors.length ? Color.parseColor(colors[i]) : 0xffeeeee6, 8);
                if (i == selectedIndex) background.setStroke(dp(2), ACCENT);
                b.setBackground(background); b.setText(getString(R.string.selected_choice, i == selectedIndex ? "✓ " : "", names[i])); b.setSelected(i == selectedIndex); b.setTextColor(i == 7 ? 0xffdeded4 : INK);
                b.setContentDescription(names[i] + (i == selectedIndex ? "，已选择" : ""));
            }
            custom.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            int bg = readerBg(); channels[0] = Color.red(bg); channels[1] = Color.green(bg); channels[2] = Color.blue(bg);
            syncing[0] = true;
            for (int i = 0; i < 3; i++) if (values[i] != null) { sliders[i].setProgress(channels[i]); if (!values[i].hasFocus()) values[i].setText(String.valueOf(channels[i])); }
            syncing[0] = false;
            preview.setText(selected.toUpperCase(Locale.ROOT)); preview.setBackground(shape(bg, 8)); preview.setTextColor(readerInk());
        };
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) choices.addView(row()); LinearLayout line = (LinearLayout) choices.getChildAt(choices.getChildCount() - 1);
            final int index = i;
            presets[i] = button(names[i], false, () -> { set(lib.prefs(), "customBackground", index == 8); if (index < colors.length) applyBackground(colors[index]); else persist(); render.run(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); params.setMargins(dp(3), dp(3), dp(3), dp(3)); line.addView(presets[i], params);
        }
        choices.addView(custom); gap(custom, 8);
        String[] labels = {"R", "G", "B"}; int[] tints = {0xffbd5555, 0xff51895e, 0xff517db5};
        for (int i = 0; i < 3; i++) {
            final int index = i; LinearLayout line = row(); TextView label = text(labels[i], 16, tints[i]); line.addView(label, new LinearLayout.LayoutParams(dp(24), -2));
            SeekBar slider = new SeekBar(this); sliders[i] = slider; slider.setMax(255); slider.setProgressTintList(android.content.res.ColorStateList.valueOf(tints[i])); slider.setThumbTintList(android.content.res.ColorStateList.valueOf(tints[i])); slider.setContentDescription(labels[i] + "颜色滑条"); weighted(line, slider);
            EditText value = input(labels[i], ""); values[i] = value; value.setWidth(dp(65)); value.setInputType(InputType.TYPE_CLASS_NUMBER); value.setSelectAllOnFocus(true); value.setContentDescription(labels[i] + "颜色数值"); line.addView(value); custom.addView(line);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) { persist(); }
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser || syncing[0]) return; channels[index] = progress; syncing[0] = true; value.setText(String.valueOf(progress)); syncing[0] = false;
                    applyBackground(String.format(Locale.ROOT, "#%02x%02x%02x", channels[0], channels[1], channels[2])); render.run();
                }
            });
            value.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (syncing[0]) return;
                    try { int n = Integer.parseInt(s.toString()); if (n < 0 || n > 255) throw new NumberFormatException(); value.setError(null); channels[index] = n; applyBackground(String.format(Locale.ROOT, "#%02x%02x%02x", channels[0], channels[1], channels[2])); render.run(); }
                    catch (NumberFormatException e) { value.setError("0～255"); }
                }
                public void afterTextChanged(Editable e) {}
            });
        }
        render.run();
    }
    private void fonts() {
        List<String> names = new ArrayList<>(Arrays.asList("系统默认", "系统衬线", "等宽字体", "自选字体", "导入 TTF / OTF 字体…")); List<String> values = new ArrayList<>(names);
        File[] fs = new File("/system/fonts").listFiles(); if (fs != null) { Arrays.sort(fs, Comparator.comparing(File::getName)); for (File f : fs) if (f.getName().matches(".*\\.(ttf|otf|ttc)$")) { names.add(f.getName()); values.add(f.getPath()); } }
        new AlertDialog.Builder(this).setTitle("选择系统或本地字体").setItems(names.toArray(new String[0]), (d, index) -> { if (index == 4) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), FONT); else { capture(); set(lib.prefs(), "font", values.get(index)); buildReader(current.optInt("position")); persist(); } }).show();
    }

    private void importDialog() {
        LinearLayout body = column(); RadioGroup modes = new RadioGroup(this); RadioButton separate = new RadioButton(this); separate.setId(View.generateViewId()); separate.setText(R.string.import_separate); RadioButton merged = new RadioButton(this); merged.setId(View.generateViewId()); merged.setText(R.string.import_merged); separate.setTextSize(14); merged.setTextSize(14); modes.addView(separate); modes.addView(merged); separate.setChecked(true); body.addView(modes); gap(body, 14); body.addView(text("导入到书架", 14, MUTED)); Spinner shelf = shelfSpinner(isBuiltinShelf(active) ? "default" : active); body.addView(shelf); gap(body, 18); Dialog d = sheet("导入书籍", body);
        Runnable prepare = () -> { grouping = merged.isChecked() ? "merged" : "separate"; importShelf = lib.shelves.get(shelf.getSelectedItemPosition()).optString("id"); d.dismiss(); };
        body.addView(button("＋ 选择 TXT 文件（支持多选）", true, () -> { prepare.run(); Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(intent, TXT); })); gap(body, 9);
        body.addView(button("▱ 选择文件夹（包含子目录）", false, () -> { prepare.run(); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), TREE); })); gap(body, 16); body.addView(text("建议选择 Download/LNReader 子目录。\n单独导入 TXT 时，请将封面图片一并多选；文件夹导入可自动识别同名图片及 cover。", 11, MUTED));
    }
    private static final class Entry { Uri uri; String path; long size; Entry(Uri uri, String path, long size) { this.uri = uri; this.path = path; this.size = size; } }
    private Entry entry(Uri uri, String path) throws Exception {
        String name = path; long size = 0;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) { if (c != null && c.moveToFirst()) { if (name.isEmpty()) name = c.getString(0); size = c.getLong(1); } }
        return new Entry(uri, name, size);
    }
    private void walk(Uri tree, String id, String prefix, List<Entry> entries, int depth) throws Exception {
        if (depth > 20) throw new IOException("文件夹层级过深");
        try (Cursor c = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            while (c != null && c.moveToNext()) { String child = c.getString(0), name = c.getString(1); if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) walk(tree, child, prefix + name + "/", entries, depth + 1); else if (name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|png|jpe?g|webp)$")) entries.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, child), prefix + name, c.getLong(3))); }
        }
    }
    private Uri child(Uri tree, String id, String name) throws Exception {
        try (Cursor c = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) { while (c != null && c.moveToNext()) if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); } return null;
    }
    private void syncFolder() { String tree = getPreferences(0).getString("tree", ""); if (tree.isEmpty()) { toast("请先导入 Download/LNReader 等文件夹"); importDialog(); return; } grouping = getPreferences(0).getString("grouping", "separate"); importShelf = isBuiltinShelf(active) ? "default" : active; scan(Uri.parse(tree)); }
    private void scan(Uri tree) {
        showBusy("正在扫描本地文件夹…"); io.execute(() -> { try {
            List<Entry> entries = new ArrayList<>(); String id = DocumentsContract.getTreeDocumentId(tree); walk(tree, id, "", entries, 0); String name = entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), "").path; JSONObject metadata = null;
            Uri backup = child(tree, id, "lnreader-library.json"); if (backup != null) try { metadata = new JSONObject(new String(read(getContentResolver().openInputStream(backup), 8_000_000), StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            importEntries(entries, name, tree.toString(), metadata);
        } catch (Exception e) { runOnUiThread(this::hideBusy); toast("无法读取文件夹，请重新授权：" + e.getMessage()); } });
    }
    private Entry imageFor(String path, List<Entry> images, String folder) {
        int slash = path.lastIndexOf('/'); String parent = slash < 0 ? "" : path.substring(0, slash + 1), stem = path.substring(slash + 1).replaceFirst("(?i)\\.txt$", ""); Entry fallback = null;
        for (Entry e : images) { int s = e.path.lastIndexOf('/'); String p = s < 0 ? "" : e.path.substring(0, s + 1); if (!p.equals(parent)) continue; String name = e.path.substring(s + 1).replaceFirst("\\.[^.]+$", ""); if (name.equalsIgnoreCase(stem)) return e; if (name.matches("(?i)cover|folder|封面") || name.equalsIgnoreCase(folder)) fallback = e; } return fallback;
    }
    private void importEntries(List<Entry> entries, String name, String folder, JSONObject metadata) {
        List<Entry> texts = new ArrayList<>(), images = new ArrayList<>(); for (Entry e : entries) { if (e.path.toLowerCase(Locale.ROOT).endsWith(".txt")) texts.add(e); else if (e.path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$")) images.add(e); }
        texts.sort((a, b) -> BookParser.naturalCompare(a.path, b.path)); if (texts.isEmpty()) { runOnUiThread(this::hideBusy); toast("没有找到 TXT 文件"); return; }
        List<List<Entry>> groups = new ArrayList<>(); if (grouping.equals("merged")) groups.add(texts); else for (Entry e : texts) groups.add(Collections.singletonList(e));
        List<JSONObject> imported = new ArrayList<>(); List<String> failed = new ArrayList<>();
        for (List<Entry> group : groups) try {
            String title = group.size() > 1 ? name.equals("合辑") ? new File(group.get(0).path).getName().replaceFirst("(?i)\\.txt$", "") + " · 合辑" : name : new File(group.get(0).path).getName().replaceFirst("(?i)\\.txt$", "");
            runOnUiThread(() -> busyText.setText(getString(R.string.organizing, title)));
            StringBuilder text = new StringBuilder(), key = new StringBuilder(grouping).append(':').append(folder); JSONArray paths = new JSONArray();
            int limit = (int) Math.min(80_000_000, Runtime.getRuntime().maxMemory() / 8);
            long bytes = 0; for (Entry e : group) { if (e.size > limit || bytes + e.size > limit) throw new IOException("超过本机内存对应的导入上限（" + limit / 1_000_000 + "MB），请分卷导入"); byte[] raw = read(getContentResolver().openInputStream(e.uri), (int) (limit - bytes)); bytes += raw.length; String decoded = BookParser.decode(raw); if (decoded.indexOf('\0') >= 0) throw new IOException("文本包含空字符，请检查编码"); if (group.size() > 1) text.append("\n\n第").append(paths.length() + 1).append("部 ").append(new File(e.path).getName().replaceFirst("(?i)\\.txt$", "")).append("\n\n"); text.append(decoded).append('\n'); key.append('|').append(e.uri); paths.put(e.path); }
            String source = text.toString(); if (source.trim().isEmpty()) throw new IOException("空文件"); String id = id(key.toString()); List<BookParser.Chapter> parsed = BookParser.parse(source); atomic(lib.textFile(id), source.getBytes(StandardCharsets.UTF_8));
            boolean hasCover = false; Entry cover = imageFor(group.get(0).path, images, name); if (cover == null && grouping.equals("merged")) cover = imageFor(name + ".txt", images, name);
            if (cover != null) try { byte[] raw = read(getContentResolver().openInputStream(cover.uri), 15_000_000); BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(raw, 0, raw.length, opts); int scale = 1; while (Math.max(opts.outWidth, opts.outHeight) / scale > 480) scale *= 2; opts.inJustDecodeBounds = false; opts.inSampleSize = scale; Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts); if (bitmap != null) { ByteArrayOutputStream output = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); bitmap.recycle(); atomic(new File(lib.root, id + ".jpg"), output.toByteArray()); hasCover = true; } } catch (Exception ignored) {}
            imported.add(json("id", id, "title", title, "shelf", importShelf, "length", source.length(), "chapterCount", parsed.size(), "position", 0, "marks", new JSONArray(), "added", System.currentTimeMillis(), "cover", hasCover, "sources", paths, "folder", folder));
        } catch (Exception e) { failed.add(new File(group.get(0).path).getName() + "：" + e.getMessage()); }
        runOnUiThread(() -> {
            boolean fresh = lib.books.isEmpty(); int updated = 0;
            if (fresh && metadata != null && metadata.optInt("version") == 1) {
                JSONArray ss = metadata.optJSONArray("shelves"); if (ss != null) for (int i = 0; i < Math.min(100, ss.length()); i++) { JSONObject s = ss.optJSONObject(i); if (s != null && s.optString("id").matches("[\\w-]{1,80}") && !s.optString("id").equals("default") && !s.optString("name").isEmpty()) lib.shelves.add(json("id", s.optString("id"), "name", s.optString("name"))); }
                JSONObject p = metadata.optJSONObject("prefs");
                if (p != null) {
                    double line = p.optDouble("line", 1.5), paragraph = p.optDouble("paragraph", .5);
                    if (!p.has("readingMode")) { if (Math.abs(line - 1.9) < .001) line = 1.5; if (Math.abs(paragraph - .8) < .001) paragraph = .5; }
                    set(lib.state, "prefs", json("size", Math.max(14, Math.min(36, p.optDouble("size", 20))), "line", Math.max(1.2, Math.min(2.8, line)), "paragraph", Math.max(0, Math.min(2.5, paragraph)), "bg", p.optString("bg").matches("#[a-fA-F0-9]{6}") ? p.optString("bg") : "#f7f3e8", "font", "系统默认", "readingMode", p.optString("readingMode").equals("scroll") ? "scroll" : "paged", "volumeKeys", p.optBoolean("volumeKeys", true)));
                    set(lib.prefs(), "defaultsVersion", p.optInt("defaultsVersion")); set(lib.prefs(), "customBackground", p.optBoolean("customBackground", false)); migrateDefaults(lib.prefs());
                    set(lib.prefs(), "pageAnimation", p.optString("pageAnimation").equals("none") ? "none" : "slide");
                }
            }
            for (JSONObject b : imported) {
                JSONObject old = lib.book(b.optString("id"));
                if (old == null && fresh && metadata != null) { JSONArray bs = metadata.optJSONArray("books"); if (bs != null) for (int i = 0; i < bs.length(); i++) if (bs.optJSONObject(i) != null && b.optString("id").equals(bs.optJSONObject(i).optString("id"))) old = bs.optJSONObject(i); }
                if (old != null) { for (String field : new String[]{ "title", "shelf", "position", "marks", "lastRead", "chapterTitle" }) if (old.has(field)) set(b, field, old.opt(field)); set(b, "position", Math.max(0, Math.min(b.optInt("length") - 1, b.optInt("position")))); if (!b.optBoolean("cover") && old.optBoolean("cover")) set(b, "cover", true); if (lib.books.remove(old)) updated++; }
                boolean validShelf = false; for (JSONObject s : lib.shelves) if (s.optString("id").equals(b.optString("shelf"))) validShelf = true; if (!validShelf) set(b, "shelf", "default");
                JSONArray safeMarks = new JSONArray(), oldMarks = b.optJSONArray("marks");
                if (oldMarks != null) for (int i = 0; i < Math.min(500, oldMarks.length()); i++) { JSONObject m = oldMarks.optJSONObject(i); if (m != null && m.optInt("offset", -1) >= 0 && m.optInt("offset") < b.optInt("length")) safeMarks.put(json("offset", m.optInt("offset"), "title", m.optString("title"), "date", m.optLong("date"))); }
                set(b, "marks", safeMarks); lib.books.add(b);
            }
            hideBusy(); if (active.equals("recent") && !imported.isEmpty()) active = "all"; persist(); showShelf(); toast("整理完成：新增 " + (imported.size() - updated) + " 本，更新 " + updated + " 本" + (failed.isEmpty() ? "" : "；失败 " + failed.size() + " 本，" + failed.get(0)));
        });
    }
    private void showBusy(String message) {
        importing = true; busyDialog = new Dialog(this); LinearLayout body = column(); body.setGravity(Gravity.CENTER); padding(body, 28, 25); body.setBackground(shape(BG, 15));
        ProgressBar spinner = new ProgressBar(this); body.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40))); gap(body, 18);
        busyText = text(message, 13, ACCENT); busyText.setGravity(Gravity.CENTER); body.addView(busyText, new LinearLayout.LayoutParams(-1, -2));
        busyDialog.setContentView(body); busyDialog.setCancelable(false); if (busyDialog.getWindow() != null) busyDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent); busyDialog.show();
        if (busyDialog.getWindow() != null) busyDialog.getWindow().setLayout(dp(280), -2);
    }
    private void hideBusy() { importing = false; if (busyDialog != null) busyDialog.dismiss(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) return;
        if (request == TREE && data.getData() != null) { Uri tree = data.getData(); try { int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION; if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION; getContentResolver().takePersistableUriPermission(tree, flags); getPreferences(0).edit().putString("tree", tree.toString()).putString("grouping", grouping).apply(); scan(tree); } catch (Exception e) { toast("文件夹授权失败，请重试"); } }
        else if (request == TXT) { showBusy("正在导入…"); io.execute(() -> { try { List<Entry> entries = new ArrayList<>(); if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) entries.add(entry(data.getClipData().getItemAt(i).getUri(), "")); else if (data.getData() != null) entries.add(entry(data.getData(), "")); importEntries(entries, "合辑", "", null); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("导入失败：" + e.getMessage()); } }); }
        else if (request == FONT && data.getData() != null) { showBusy("正在加载字体…"); io.execute(() -> { try { byte[] bytes = read(getContentResolver().openInputStream(data.getData()), 40_000_000); File temp = new File(getCacheDir(), "font-check"); atomic(temp, bytes); Typeface.createFromFile(temp); atomic(new File(getFilesDir(), "reader-font"), bytes); temp.delete(); runOnUiThread(() -> { hideBusy(); set(lib.prefs(), "font", "自选字体"); if (current != null) buildReader(current.optInt("position")); persist(); toast("字体已应用"); }); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("字体无效，请选择 TTF / OTF 文件"); } }); }
    }
    @Override public void onBackPressed() { if (current != null) back(); else { persist(); moveTaskToBack(true); } }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (current != null && !importing && getWindow().getDecorView().hasWindowFocus() && lib.prefs().optBoolean("volumeKeys", true) && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getRepeatCount() == 0 || event.getEventTime() - lastVolumeTurn >= 80)) {
                lastVolumeTurn = event.getEventTime(); turnPage(code == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) { super.onConfigurationChanged(config); if (current != null) { capture(); buildReader(current.optInt("position")); } else showShelf(); }
    @Override protected void onPause() { super.onPause(); if (lib != null) { capture(); persist(); } }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus && root != null) systemBars(current == null || controlsVisible); }
    @Override protected void onResume() {
        super.onResume();
        if (!statusRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(Intent.ACTION_TIME_TICK); filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(statusReceiver, filter); statusRegistered = true;
        }
        updateDeviceStatus();
    }
    @Override protected void onStop() { if (statusRegistered) { unregisterReceiver(statusReceiver); statusRegistered = false; } super.onStop(); }
    @Override protected void onDestroy() { paginationGeneration++; if (statusRegistered) unregisterReceiver(statusReceiver); handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
}
