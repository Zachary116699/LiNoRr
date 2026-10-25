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
    private static final int TXT = 51, TREE = 52, FONT = 53, COVER = 54, BACKUP_TREE = 55, STORAGE = 56;
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
    private List<BookParser.Chapter> toc = new ArrayList<>();
    private Library.Document document;
    private DisplayText display;
    private JSONObject coverTarget;
    private Runnable storageAction;
    private boolean waitingStorage;
    private int chapter, bodyOffset, pagePrefix, searchToken;
    private boolean list, importing, positioning, controlsVisible, endReached;
    private int pageNumber = 1, pageCount = 1;
    private volatile int paginationGeneration;
    private int[] pageTotals;
    private Runnable indexTask;
    private String pageIndexSource = "pending";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService openIo = Executors.newSingleThreadExecutor();
    private final ExecutorService pageIo = Executors.newSingleThreadExecutor();
    private final ExecutorService saveIo = Executors.newSingleThreadExecutor();
    private final ExecutorService coverIo = Executors.newFixedThreadPool(2);
    private final android.util.LruCache<String, Bitmap> coverCache = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) { @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); } };
    private GridView bookGrid;
    private ShelfAdapter shelfAdapter;
    private TextView shelfCount, emptyShelf;
    private LinearLayout selectionActions;
    private LinearLayout shelfTools;
    private Button cancelSelection;
    private LinearLayout countRow;
    private final LinkedHashSet<String> importShelves = new LinkedHashSet<>();
    private boolean toolsExpanded;
    private final List<JSONObject> filteredBooks = new ArrayList<>();
    private final Set<String> selectedBooks = new HashSet<>();
    private boolean selectionMode;
    private Set<String> shelfMatches;
    private final List<JSONObject> pageBooks = new ArrayList<>();
    private int shelfPage;
    private LinearLayout shelfPager;
    private Dialog drawer;
    private JSONObject recentSortView = json("sort", "lastRead", "ascending", false);
    private final Map<Dialog, LinearLayout> dialogShells = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog busyDialog;
    private TextView busyText;
    private Runnable progressTask;
    private long lastVolumeTurn;
    private final Runnable backgroundSave = () -> { if (lib != null) persist(); };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { lib = new Library(this); list = lib.state.optBoolean("list"); showShelf(); if (lib.canWrite()) { saveIo.execute(lib::cleanupPending); runWithBackup(() -> {}); } }
        catch (Exception e) { new AlertDialog.Builder(this).setTitle("书库暂时无法打开").setMessage(e.getMessage()).setPositiveButton("关闭", (d, w) -> finish()).show(); }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void padding(View v, int x, int y) { v.setPadding(dp(x), dp(y), dp(x), dp(y)); }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void rule(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(shelfDark() ? 0xff424a3e : 0xffe5e5dc); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private Button button(String value, boolean primary, Runnable click) {
        Button b = new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(destructive(value) ? dangerInk() : primary ? 0xfffffef6 : uiAccent());
        b.setBackground(shape(primary ? current != null ? ACCENT : uiPalette()[2] : Color.TRANSPARENT, 9)); b.setTag(primary ? "primary" : destructive(value) ? "danger" : "action"); b.setMinHeight(dp(42)); b.setMinimumHeight(dp(42)); b.setMinWidth(0); b.setMinimumWidth(0); padding(b, 13, 4); b.setOnClickListener(v -> click.run()); return b;
    }
    private void weighted(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private EditText input(String hint, String value) { EditText e = new EditText(this); e.setTextSize(14); e.setTextColor(uiInk()); e.setHintTextColor(uiMuted()); e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(uiAccent())); e.setSingleLine(true); e.setHint(hint); e.setText(value); padding(e, 10, 8); return e; }
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
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(readerInkSafe() ? 0 : appearance, appearance);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (visible) controller.show(WindowInsets.Type.systemBars()); else controller.hide(WindowInsets.Type.systemBars());
            }
        }
    }
    private boolean readerInkSafe() { return current != null ? readerInk() == 0xffdeded4 : shelfDark(); }
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
        set(lib.state, "active", active); set(lib.state, "list", list);
        handler.removeCallbacks(exportTask); handler.postDelayed(exportTask, 120);
    }
    private final Runnable exportTask = () -> {
        if (lib == null) return;
        if (importing) { handler.postDelayed(this.exportTask, 200); return; }
        String snapshot = lib.snapshot(); Library target = lib; saveIo.execute(() -> { if (target != lib) return; try { target.saveSnapshot(snapshot); } catch (Exception e) { toast("保存失败：" + e.getMessage()); } });
    };
    private void ensureStorage(Runnable action) {
        if (lib.canWrite()) { runWithBackup(action); return; }
        storageAction = action; waitingStorage = true;
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        } else requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE);
    }
    private void runWithBackup(Runnable action) { if (!lib.portableReady) migrateBackup(() -> runWithBackup(action)); else if (hasRemoved()) performRemove(Collections.emptySet(), action); else action.run(); }
    private void migrateBackup(Runnable after) {
        showBusy("正在准备小说备份…");
        JSONObject snapshot; try { snapshot = new JSONObject(lib.snapshot()); } catch (Exception e) { hideBusy(); toast(e.getMessage()); return; }
        saveIo.execute(() -> { try {
            JSONObject updated = lib.prepareBackup(snapshot);
            runOnUiThread(() -> { lib.applyPaths(updated); hideBusy(); persist(); if (after != null) after.run(); else showShelf(); });
        } catch (Exception e) { runOnUiThread(this::hideBusy); toast("备份未完成：" + e.getMessage()); } });
    }
    private void organizeAndRefresh() { if (lib.canWrite()) migrateBackup(this::showShelf); else { persist(); showShelf(); } }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == STORAGE) completeStorageRequest(); }
    private void completeStorageRequest() {
        if (!waitingStorage) return; waitingStorage = false; Runnable action = storageAction; storageAction = null;
        if (lib.canWrite()) {
            // Reinstalling or clearing app data recovers the portable metadata before any save.
            if (lib.books.isEmpty() && new File(lib.backup, "userdata.json").isFile()) try { lib = new Library(this, true); list = lib.state.optBoolean("list"); showShelf(); } catch (Exception e) { toast("恢复书库失败：" + e.getMessage()); return; }
            runWithBackup(action == null ? () -> {} : action);
        } else toast("未取得文件读写权限；导入前请在设置中授权");
    }

    private void showShelf() {
        recentSortView = json("sort", "lastRead", "ascending", false);
        if (active.equals("recent")) { shelfView().remove("sort"); shelfView().remove("ascending"); }
        list = shelfView().optBoolean("list", lib.state.optBoolean("list"));
        set(shelfView(), "list", list);
        paginationGeneration++; pageTotals = null; current = null; source = ""; base(shelfBg(), shelfDark());
        ShelfSurface surface = new ShelfSurface(this, this::openDrawer); root.addView(surface, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout outer = column(); surface.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        int headerColor = shelfHeaderBg();
        LinearLayout brand = row(); padding(brand, 20, 8); brand.setBackgroundColor(headerColor); TextView logo = text("LNReader", 25, shelfInk()); logo.setTypeface(Typeface.SERIF); padding(logo, 0, 8); weighted(brand, logo);
        brand.addView(shelfIcon(shelfDark() ? "sun" : "moon", "日夜间模式切换", this::toggleNight), new LinearLayout.LayoutParams(dp(42), dp(44)));
        View spacer = new View(this); brand.addView(spacer, new LinearLayout.LayoutParams(dp(6), 1));
        brand.addView(shelfIcon("settings", "书架设置", this::shelfSettings), new LinearLayout.LayoutParams(dp(42), dp(44)));
        brand.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        brand.addView(shelfIcon("import", "导入书籍", () -> ensureStorage(this::importDialog)), new LinearLayout.LayoutParams(dp(42), dp(44))); outer.addView(brand);
        getWindow().setStatusBarColor(headerColor);
        content = column(); padding(content, 20, 10); outer.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout shelfHeading = row(); LinearLayout shelfLink = row(); shelfLink.setContentDescription("打开书架抽屉"); shelfLink.setOnClickListener(v -> openDrawer());
        ImageView shelfMark = new ImageView(this); shelfMark.setContentDescription(active.equals("recent") ? "最近阅读时钟" : active.equals("all") ? "全部书籍图标" : "分类书架图标"); shelfMark.setImageDrawable(new ReaderIcon(active.equals("recent") ? "clock" : active.equals("all") ? "library" : "shelf", uiAccent(), getResources().getDisplayMetrics().density)); shelfLink.addView(shelfMark, new LinearLayout.LayoutParams(dp(24), dp(28)));
        TextView shelfName = text(shelfName(active), 20, shelfInk()); shelfName.setSingleLine(); shelfName.setEllipsize(TextUtils.TruncateAt.END); shelfName.setPadding(dp(8), 0, 0, 0); shelfLink.addView(shelfName); weighted(shelfHeading, shelfLink);
        shelfHeading.addView(shelfIcon("sort", "书籍排序", this::sortDialog), new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton viewToggle = shelfIcon(list ? "grid" : "list", "切换列表或网格显示", () -> {}); viewToggle.setOnClickListener(v -> { list = !list; set(shelfView(), "list", list); viewToggle.setImageDrawable(new ReaderIcon(list ? "grid" : "list", shelfInk(), getResources().getDisplayMetrics().density)); persist(); renderCards(shelfMatches); }); shelfHeading.addView(viewToggle, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton expand = shelfIcon("filter", "展开或收起筛选排序", () -> { toolsExpanded = !toolsExpanded; if (!toolsExpanded) { selectionMode = false; selectedBooks.clear(); } updateShelfTools(); renderCards(shelfMatches); }); shelfHeading.addView(expand, new LinearLayout.LayoutParams(dp(44), dp(44))); content.addView(shelfHeading); gap(content, 4);
        shelfTools = column(); content.addView(shelfTools);
        LinearLayout tools = row(); EditText search = input("搜索书名或文件名", query); search.setTextSize(17); search.setContentDescription("搜索书名或文件名"); weighted(tools, search);
        tools.addView(shelfIcon("search", "搜索书籍", () -> { query = search.getText().toString(); shelfPage = 0; scheduleSearch(); ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0); }), new LinearLayout.LayoutParams(dp(44), dp(44))); shelfTools.addView(tools);
        countRow = row(); content.addView(countRow); shelfCount = text("", 14, shelfMuted()); weighted(countRow, shelfCount);
        cancelSelection = shelfButton("取消选择", () -> { selectedBooks.clear(); selectionMode = false; renderCards(shelfMatches); }); countRow.addView(cancelSelection);
        cancelSelection.setMinHeight(dp(36)); cancelSelection.setMinimumHeight(dp(36)); padding(cancelSelection, 8, 2);
        selectionActions = column(); content.addView(selectionActions);
        HorizontalScrollView selectionScroll = new HorizontalScrollView(this); LinearLayout selectionRow = row(); selectionScroll.addView(selectionRow); selectionActions.addView(selectionScroll);
        selectionRow.addView(shelfButton("全选当前筛选", () -> { selectedBooks.clear(); for (JSONObject b : filteredBooks) selectedBooks.add(b.optString("id")); renderCards(shelfMatches); }));
        selectionRow.addView(shelfButton("添加到书架", this::batchShelf)); selectionRow.addView(button("移除所选", false, () -> confirmRemove(new HashSet<>(selectedBooks))));
        updateShelfTools(); gap(content, 4);
        shelfScroll = new ScrollView(this); shelfScroll.setFillViewport(true); shelfScroll.setClipToPadding(false); content.addView(shelfScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        surface.scrollChild(shelfScroll);
        LinearLayout page = column(); shelfScroll.addView(page);
        bookGrid = new GridView(this); bookGrid.setHorizontalSpacing(dp(12)); bookGrid.setVerticalSpacing(dp(16)); bookGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); bookGrid.setSelector(android.R.color.transparent); bookGrid.setContentDescription("书架书籍"); bookGrid.setNestedScrollingEnabled(false);
        page.addView(bookGrid, new LinearLayout.LayoutParams(-1, dp(1)));
        emptyShelf = text("", 18, shelfMuted()); emptyShelf.setGravity(Gravity.CENTER); page.addView(emptyShelf, new LinearLayout.LayoutParams(-1, dp(280))); bookGrid.setEmptyView(emptyShelf);
        shelfPager = row(); shelfPager.setGravity(Gravity.CENTER); padding(shelfPager, 0, 18); page.addView(shelfPager);
        shelfAdapter = new ShelfAdapter(); bookGrid.setAdapter(shelfAdapter);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); search.setOnEditorActionListener((v, action, event) -> { query = search.getText().toString(); shelfPage = 0; scheduleSearch(); return false; });
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString(); shelfPage = 0; scheduleSearch(); } public void afterTextChanged(Editable e) {} });
        if (query.isEmpty()) renderCards(null); else scheduleSearch();
    }
    private JSONObject shelfView() {
        JSONObject views = lib.state.optJSONObject("shelfViews"); if (views == null) { views = new JSONObject(); set(lib.state, "shelfViews", views); }
        JSONObject view = views.optJSONObject(active); if (view == null) { view = new JSONObject(); set(views, active, view); } return view;
    }
    private void updateShelfTools() { if (shelfTools != null) shelfTools.setVisibility(toolsExpanded ? View.VISIBLE : View.GONE); }
    private void sortDialog() {
        LinearLayout body = column(); JSONObject view = active.equals("recent") ? recentSortView : shelfView(); Dialog dialog = sheet("书籍排序", body);
        body.addView(text("排序信息", 16, uiMuted())); RadioGroup fields = new RadioGroup(this); body.addView(fields);
        String key = view.optString("sort", active.equals("recent") ? "lastRead" : "added");
        String[] keys = {"lastRead", "filename", "size", "added", "title"}, labels = {"最近阅读时间", "文件名", "文件大小", "导入时间", "书籍名称"};
        Map<Integer,String> options = new HashMap<>();
        for (int i = 0; i < keys.length; i++) { RadioButton choice = new RadioButton(this); choice.setId(View.generateViewId()); choice.setText(labels[i]); choice.setTextSize(16); fields.addView(choice); options.put(choice.getId(), keys[i]); if (key.equals(keys[i])) fields.check(choice.getId()); }
        fields.setOnCheckedChangeListener((group, id) -> { set(view, "sort", options.get(id)); persist(); changeShelfPage(0); });
        gap(body, 8); body.addView(text("排序方式", 16, uiMuted())); RadioGroup order = new RadioGroup(this); order.setOrientation(LinearLayout.HORIZONTAL); body.addView(order);
        RadioButton ascending = new RadioButton(this), descending = new RadioButton(this); ascending.setId(View.generateViewId()); descending.setId(View.generateViewId()); ascending.setText("升序"); descending.setText("降序"); order.addView(ascending); order.addView(descending); order.check(view.optBoolean("ascending", false) ? ascending.getId() : descending.getId());
        order.setOnCheckedChangeListener((group, id) -> { set(view, "ascending", id == ascending.getId()); persist(); changeShelfPage(0); });
        body.addView(button("完成", true, dialog::dismiss));
    }
    private String shelfName(String id) { if (id.equals("recent")) return "最近阅读"; if (id.equals("all")) return "全部书籍"; for (JSONObject s : lib.shelves) if (id.equals(s.optString("id"))) return s.optString("name"); return "全部书籍"; }
    private void openDrawer() {
        if (importing || current != null || drawer != null && drawer.isShowing()) return;
        drawer = new Dialog(this); ShelfSurface surface = new ShelfSurface(this, drawer::dismiss, -1); LinearLayout shell = column(); padding(shell, 16, 20); shell.setBackgroundColor(uiBg()); surface.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = row(); weighted(top, text("书架", 23, uiInk())); top.addView(button("×", false, drawer::dismiss)); shell.addView(top); gap(shell, 12);
        List<JSONObject> entries = new ArrayList<>(); Runnable refill = () -> { entries.clear(); entries.add(json("id", "recent", "name", "最近阅读")); entries.add(json("id", "all", "name", "全部书籍")); for (JSONObject shelf : lib.shelves) if (!isBuiltinShelf(shelf.optString("id"))) entries.add(shelf); }; refill.run();
        boolean[] editing = {false}; ListView choices = new ListView(this); choices.setDivider(null); choices.setContentDescription("书架分类列表"); final ShelfReorder[] reorder = {null};
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return entries.size(); } public Object getItem(int p) { return entries.get(p); } public long getItemId(int p) { return entries.get(p).optString("id").hashCode(); }
            public View getView(int p, View recycled, ViewGroup parent) {
                JSONObject shelf = entries.get(p); String id = shelf.optString("id"); boolean user = !isBuiltinShelf(id); LinearLayout item = column(), line = row(); padding(line, 8, 4); line.setMinimumHeight(dp(52));
                if (editing[0] && user) { ImageButton handle = shelfIcon("drag", "拖动" + shelf.optString("name"), () -> {}); handle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
                    reorder[0].start(v, line, shelf); return true;
                }); line.addView(handle, new LinearLayout.LayoutParams(dp(32), dp(44))); }
                TextView name = text(shelf.optString("name"), 17, uiInk()); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); name.setTypeface(null, id.equals(active) ? Typeface.BOLD : Typeface.NORMAL); weighted(line, name);
                if (editing[0] && user) {
                    line.addView(shelfIcon("edit", "重命名" + shelf.optString("name"), () -> { drawer.dismiss(); shelfEditor(shelf); }), new LinearLayout.LayoutParams(dp(36), dp(44)));
                    ImageButton delete = shelfIcon("trash", "删除" + shelf.optString("name"), () -> { drawer.dismiss(); deleteShelf(shelf); }); delete.setImageDrawable(new ReaderIcon("trash", dangerInk(), getResources().getDisplayMetrics().density)); line.addView(delete, new LinearLayout.LayoutParams(dp(36), dp(44)));
                } else { int count = 0; for (JSONObject book : lib.books) if (inShelf(book, id)) count++; line.addView(text(String.valueOf(id.equals("recent") ? Math.min(90, count) : count), 14, uiMuted())); }
                if (id.equals(active)) line.setBackground(shape(shelfHeaderBg(), 8)); item.addView(line); if (p == 1) { gap(item, 8); rule(item); gap(item, 8); } reorder[0].bind(item, shelf, p); return item;
            }
        };
        reorder[0] = new ShelfReorder(surface, choices, new ShelfReorder.Model() {
            public int indexOf(Object item) { return entries.indexOf(item); }
            public int count() { return entries.size(); }
            public int background() { return shelfHeaderBg(); }
            public void move(Object item, int target) {
                JSONObject moving = (JSONObject) item, destination = entries.get(target);
                if (moving == destination) return;
                int index = lib.shelves.indexOf(destination); lib.shelves.remove(moving); lib.shelves.add(Math.min(index, lib.shelves.size()), moving);
                refill.run(); adapter.notifyDataSetChanged(); persist();
            }
        });
        choices.setAdapter(adapter);
        choices.setOnItemClickListener((parent, view, p, id) -> { if (editing[0]) return; active = entries.get(p).optString("id"); shelfPage = 0; drawer.dismiss(); persist(); showShelf(); });
        choices.setOnItemLongClickListener((parent, view, p, id) -> { JSONObject shelf = entries.get(p); if (!isBuiltinShelf(shelf.optString("id"))) { drawer.dismiss(); shelfEditor(shelf); return true; } return false; });
        shell.addView(choices, new LinearLayout.LayoutParams(-1, 0, 1)); shell.addView(button("新增书架", true, () -> { drawer.dismiss(); shelfEditor(null); }));
        Button edit = button("编辑书架", false, () -> {}); edit.setOnClickListener(v -> { editing[0] = !editing[0]; edit.setText(editing[0] ? "完成编辑" : "编辑书架"); adapter.notifyDataSetChanged(); }); shell.addView(edit);
        drawer.setContentView(surface); Window window = drawer.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setGravity(Gravity.START | Gravity.TOP); }
        drawer.show(); if (window != null) window.setLayout(Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(36)), -1);
    }
    private void deleteShelf(JSONObject shelf) {
        if (isBuiltinShelf(shelf.optString("id"))) return;
        confirmation("删除书架？", "移除这个分类归属；仍有其他分类的书籍保留其余分类，没有其他分类的书籍移至未分类。", "删除", () -> { String id = shelf.optString("id"); for (JSONObject book : lib.books) { List<String> ids = Library.shelfIds(book); if (ids.remove(id)) Library.setShelfIds(book, ids); } lib.shelves.remove(shelf); JSONObject views = lib.state.optJSONObject("shelfViews"); if (views != null) views.remove(id); if (active.equals(id)) active = "all"; shelfPage = 0; organizeAndRefresh(); });
    }
    private boolean shelfDark() { return lib != null && lib.prefs().optBoolean("night", false); }
    private int[] uiPalette() {
        String name = lib == null ? "forest" : lib.state.optString("uiTheme", "forest");
        switch (name) {
            case "blue": return new int[]{0xfff3f6f8, 0xffe1eaf0, 0xff4b667b, 0xff1f262c, 0xff2c3741, 0xffb3cbdd};
            case "sand": return new int[]{0xfff8f5ef, 0xffeee5d7, 0xff806746, 0xff29251e, 0xff393126, 0xffd9c5a3};
            case "rose": return new int[]{0xfff8f3f4, 0xffefe1e5, 0xff815c6b, 0xff292126, 0xff3b2e35, 0xffdcb8c8};
            case "slate": return new int[]{0xfff5f5f5, 0xffe5e6e8, 0xff5d626b, 0xff232428, 0xff32343a, 0xffc3c7d0};
            case "lavender": return new int[]{0xfff6f3f8, 0xffe9e2ef, 0xff706080, 0xff26222c, 0xff342d3d, 0xffcdbddd};
            default: return new int[]{BG, 0xffe9ede3, ACCENT, 0xff20231f, 0xff2c332b, 0xffb9cdb0};
        }
    }
    private int shelfBg() { return uiPalette()[shelfDark() ? 3 : 0]; }
    private int shelfHeaderBg() { return uiPalette()[shelfDark() ? 4 : 1]; }
    private static int blend(int a, int b, float amount) { return Color.rgb(Math.round(Color.red(a) * (1 - amount) + Color.red(b) * amount), Math.round(Color.green(a) * (1 - amount) + Color.green(b) * amount), Math.round(Color.blue(a) * (1 - amount) + Color.blue(b) * amount)); }
    private int uiBg() { return current != null ? readerBg() : shelfBg(); }
    private int uiInk() { return current != null ? readerInk() : shelfInk(); }
    private int uiMuted() { return shelfDark() ? 0xffb0b8a8 : MUTED; }
    private int uiAccent() { return current != null ? shelfDark() ? 0xffb9cdb0 : ACCENT : uiPalette()[shelfDark() ? 5 : 2]; }
    private int dangerInk() { return shelfDark() ? 0xffe7aaa2 : 0xffa4524c; }
    private boolean destructive(String label) { return label.contains("移除") || label.contains("删除") || label.contains("重置") || label.contains("恢复默认"); }
    private void toggleNight() { set(lib.prefs(), "night", !shelfDark()); persist(); if (current != null) { repaintReader(); } else showShelf(); }
    private int shelfInk() { return shelfDark() ? 0xffe5e6dd : INK; }
    private int shelfMuted() { return shelfDark() ? 0xffb3baac : MUTED; }
    private Button shelfButton(String title, Runnable action) { Button b = button(title, false, action); b.setTextColor(shelfDark() ? 0xffcbd8c3 : ACCENT); return b; }
    private ImageButton shelfIcon(String kind, String title, Runnable action) { ImageButton b = readerIconButton(kind, title, action); padding(b, 8, 8); b.setImageDrawable(new ReaderIcon(kind, uiAccent(), getResources().getDisplayMetrics().density)); return b; }
    private boolean isBuiltinShelf(String id) { return id.equals("all") || id.equals("recent") || id.equals("default"); }
    private boolean inShelf(JSONObject book, String id) { return id.equals("all") || (id.equals("recent") ? book.optLong("lastRead") > 0 : id.equals("default") ? Library.shelfIds(book).isEmpty() : Library.shelfIds(book).contains(id)); }
    private int percent(JSONObject b) { int length = b.optInt("length"), pos = b.optInt("position"); return length == 0 ? 0 : pos >= length - 2 ? 100 : Math.min(99, (int) (100L * pos / length)); }
    private void scheduleSearch() {
        final int token = ++searchToken;
        handler.postDelayed(() -> {
            if (token != searchToken || current != null) return;
            String term = query.trim().toLowerCase(Locale.ROOT); Set<String> matches = new HashSet<>();
            for (JSONObject b : lib.books) if (b.optString("title").toLowerCase(Locale.ROOT).contains(term) || Library.fileNames(b).toLowerCase(Locale.ROOT).contains(term)) matches.add(b.optString("id"));
            renderCards(matches);
        }, 120);
    }
    private void renderCards(Set<String> matches) {
        if (bookGrid == null || shelfAdapter == null) return; shelfMatches = matches; filteredBooks.clear();
        for (JSONObject b : lib.books) if (inShelf(b, active) && (matches == null || matches.contains(b.optString("id")))) filteredBooks.add(b);
        if (active.equals("recent")) { filteredBooks.clear(); List<JSONObject> recent = new ArrayList<>(); for (JSONObject b : lib.books) if (b.optLong("lastRead") > 0) recent.add(b); recent.sort((a, b) -> Long.compare(b.optLong("lastRead"), a.optLong("lastRead"))); for (int i = 0; i < Math.min(90, recent.size()); i++) { JSONObject b = recent.get(i); if (matches == null || matches.contains(b.optString("id"))) filteredBooks.add(b); } }
        JSONObject view = active.equals("recent") ? recentSortView : shelfView(); String sort = view.optString("sort", active.equals("recent") ? "lastRead" : "added"); int direction = view.optBoolean("ascending", false) ? 1 : -1;
        Map<String, Long> sizes = new HashMap<>(); if (sort.equals("size")) for (JSONObject b : filteredBooks) { long size = b.has("fileSize") ? b.optLong("fileSize") : lib.fileSize(b); set(b, "fileSize", size); sizes.put(b.optString("id"), size); }
        filteredBooks.sort((a, b) -> { int result;
            if (sort.equals("filename") || sort.equals("title")) result = BookParser.naturalCompare(sort.equals("filename") ? Library.fileNames(a) : a.optString("title"), sort.equals("filename") ? Library.fileNames(b) : b.optString("title"));
            else result = Long.compare(sort.equals("size") ? sizes.get(a.optString("id")) : a.optLong(sort), sort.equals("size") ? sizes.get(b.optString("id")) : b.optLong(sort));
            return result == 0 ? a.optString("id").compareTo(b.optString("id")) : direction * result; });
        int columns = list ? 1 : getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3;
        shelfPage = Math.max(0, Math.min(shelfPage, Math.max(0, (filteredBooks.size() - 1) / 15))); pageBooks.clear();
        for (int i = shelfPage * 15; i < Math.min(filteredBooks.size(), (shelfPage + 1) * 15); i++) pageBooks.add(filteredBooks.get(i));
        bookGrid.setNumColumns(columns);
        int coverWidth = (getResources().getConfiguration().screenWidthDp - 40 - 12 * (columns - 1)) / columns;
        int rowHeight = list ? 84 : Math.round(coverWidth * 1.4f) + 7 + 34 + 28, rows = (pageBooks.size() + columns - 1) / columns;
        bookGrid.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(Math.max(1, rows * rowHeight + Math.max(0, rows - 1) * 16))));
        shelfCount.setText(selectionMode ? getString(R.string.selection_count, filteredBooks.size(), selectedBooks.size()) : getString(R.string.book_count, filteredBooks.size())); emptyShelf.setText(!query.isEmpty() ? "未找到书籍" : active.equals("recent") ? "暂无最近阅读" : "暂无书籍");
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE); shelfAdapter.notifyDataSetChanged();
        cancelSelection.setVisibility(selectionMode ? View.VISIBLE : View.GONE); countRow.setMinimumHeight(dp(selectionMode ? 38 : 28));
        shelfPager.removeAllViews(); shelfPager.setVisibility(filteredBooks.size() > 15 ? View.VISIBLE : View.GONE);
        if (filteredBooks.size() > 15) {
            int pages = (filteredBooks.size() + 14) / 15; Button previous = button("上一页", false, () -> changeShelfPage(shelfPage - 1)); previous.setEnabled(shelfPage > 0); shelfPager.addView(previous);
            EditText page = input("页码", String.valueOf(shelfPage + 1)); page.setContentDescription("书架页码"); page.setInputType(InputType.TYPE_CLASS_NUMBER); page.setSelectAllOnFocus(true); page.setGravity(Gravity.CENTER); shelfPager.addView(page, new LinearLayout.LayoutParams(dp(52), -2)); shelfPager.addView(text("/ " + pages, 14, uiMuted()));
            shelfPager.addView(button("跳转", false, () -> { try { int number = Integer.parseInt(page.getText().toString()); if (number < 1 || number > pages) throw new NumberFormatException(); changeShelfPage(number - 1); } catch (NumberFormatException error) { page.setError("1～" + pages); } }));
            Button next = button("下一页", false, () -> changeShelfPage(shelfPage + 1)); next.setEnabled(shelfPage + 1 < pages); shelfPager.addView(next);
        }
    }
    private void changeShelfPage(int page) { shelfPage = page; renderCards(shelfMatches); shelfScroll.post(() -> shelfScroll.scrollTo(0, 0)); ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(shelfScroll.getWindowToken(), 0); }
    private final class ShelfAdapter extends BaseAdapter {
        public int getCount() { return pageBooks.size(); } public Object getItem(int p) { return pageBooks.get(p); } public long getItemId(int p) { return pageBooks.get(p).optString("id").hashCode(); }
        public int getViewTypeCount() { return 2; } public int getItemViewType(int p) { return list ? 1 : 0; }
        public View getView(int p, View recycled, ViewGroup parent) { BookCell cell = recycled instanceof BookCell && ((BookCell) recycled).horizontal == list ? (BookCell) recycled : new BookCell(list); cell.bind(pageBooks.get(p)); return cell; }
    }
    private final class BookCell extends LinearLayout {
        final boolean horizontal; final FrameLayout cover; final ImageView image; final TextView generated, title, progressText, check; final ImageButton more; JSONObject book; Future<?> job;
        BookCell(boolean horizontal) {
            super(NativeActivity.this); this.horizontal = horizontal; setOrientation(horizontal ? HORIZONTAL : VERTICAL); setGravity(horizontal ? Gravity.CENTER_VERTICAL : Gravity.TOP);
            cover = new FrameLayout(NativeActivity.this); cover.setBackground(shape(0xffd8dfcd, 7)); cover.setClipToOutline(true);
            generated = text("", horizontal ? 10 : 17, 0xff425047); generated.setTypeface(Typeface.SERIF); generated.setGravity(Gravity.CENTER); generated.setMaxLines(horizontal ? 4 : 7); generated.setEllipsize(TextUtils.TruncateAt.END); padding(generated, horizontal ? 4 : 10, horizontal ? 4 : 10); cover.addView(generated, new FrameLayout.LayoutParams(-1, -1));
            image = new ImageView(NativeActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            check = text("", 12, Color.WHITE); check.setGravity(Gravity.CENTER); FrameLayout.LayoutParams checkbox = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END); checkbox.setMargins(0, dp(5), dp(5), 0); cover.addView(check, checkbox);
            int cols = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 3, width = (getResources().getConfiguration().screenWidthDp - 40 - 12 * (cols - 1)) / cols;
            addView(cover, new LinearLayout.LayoutParams(horizontal ? dp(60) : -1, horizontal ? dp(84) : dp(width * 1.4f)));
            LinearLayout info = column(); if (horizontal) info.setPadding(dp(14), 0, 0, 0); else gap(info, 7);
            title = text("", horizontal ? 16 : 14, shelfInk()); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); info.addView(title, new LinearLayout.LayoutParams(-1, horizontal ? -2 : dp(34)));
            LinearLayout status = row(); progressText = text("", 11, shelfMuted()); progressText.setSingleLine(true); weighted(status, progressText); more = shelfIcon("more", "", () -> { if (book != null) bookEditor(book); }); more.setScaleType(ImageView.ScaleType.CENTER); more.setImageDrawable(new ReaderIcon("more", shelfInk(), getResources().getDisplayMetrics().density * .65f)); padding(more, 0, 0); status.addView(more, new LinearLayout.LayoutParams(dp(28), dp(28))); info.addView(status);
            if (horizontal) addView(info, new LinearLayout.LayoutParams(0, -2, 1)); else addView(info);
            View.OnClickListener click = v -> { if (selectionMode) { if (!selectedBooks.remove(book.optString("id"))) selectedBooks.add(book.optString("id")); renderCards(shelfMatches); } else openBook(book, -1); };
            View.OnLongClickListener hold = v -> { selectionMode = true; toolsExpanded = true; updateShelfTools(); selectedBooks.add(book.optString("id")); renderCards(shelfMatches); return true; };
            setOnClickListener(click); cover.setOnClickListener(click); info.setOnClickListener(click); setOnLongClickListener(hold); cover.setOnLongClickListener(hold); info.setOnLongClickListener(hold);
        }
        void bind(JSONObject b) {
            book = b; String id = b.optString("id"), name = b.optString("title"); title.setText(name); generated.setText(name); image.setImageDrawable(null); cover.setContentDescription(name + "封面");
            cover.setBackground(shape(BookIdentity.coverColor(b.optString("contentHash", id)), 7));
            if (b.optString("contentHash").isEmpty()) coverIo.submit(() -> { try { String hash = BookIdentity.fingerprint(lib.partFiles(b)); handler.post(() -> { set(b, "contentHash", hash); if (book == b) cover.setBackground(shape(BookIdentity.coverColor(hash), 7)); persist(); }); } catch (Exception ignored) {} });
            progressText.setText(b.optLong("lastRead") > 0 ? getString(R.string.book_progress, percent(b)) : "未读"); more.setContentDescription("管理" + name);
            boolean selected = selectionMode && selectedBooks.contains(id); setSelected(selected); setBackground(selected ? shape(blend(shelfBg(), uiAccent(), .18f), 8) : null); cover.setForeground(selected ? shape(0x26000000, 7) : null);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE); check.setText(selected ? "✓" : ""); check.setBackground(shape(selected ? uiAccent() : 0x995b6257, 10));
            if (job != null) job.cancel(false); File file = lib.coverFile(b); if (!file.isFile()) return;
            String key = id + ":" + file.lastModified(); Bitmap cached = coverCache.get(key); if (cached != null) { image.setImageBitmap(cached); return; }
            job = coverIo.submit(() -> { BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), options); options.inSampleSize = 1; while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480) options.inSampleSize *= 2; options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options); if (bitmap != null) { coverCache.put(key, bitmap); handler.post(() -> { if (book == b) image.setImageBitmap(bitmap); }); } });
        }
    }
    private Dialog sheet(String title, LinearLayout body) {
        Dialog d = new Dialog(this); LinearLayout shell = column(); padding(shell, 22, 16); shell.setBackground(shape(uiBg(), 18)); LinearLayout heading = row(); TextView headingText = text(title, 21, uiInk()); headingText.setSingleLine(); headingText.setEllipsize(TextUtils.TruncateAt.END); weighted(heading, headingText); heading.addView(button("×", false, d::dismiss)); shell.addView(heading); gap(shell, 10); shell.addView(body);
        body.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() { public void onChildViewAdded(View parent, View child) { themeDialog(child); } public void onChildViewRemoved(View parent, View child) {} }); themeDialog(body);
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
        dialogShells.put(d, shell);
        return d;
    }
    private void sheetAction(Dialog dialog, String icon, String label, Runnable action) { LinearLayout shell = dialogShells.get(dialog); if (shell != null) { LinearLayout heading = (LinearLayout) shell.getChildAt(0); heading.addView(readerIconButton(icon, label, action), heading.getChildCount() - 1, new LinearLayout.LayoutParams(dp(40), dp(42))); } }
    private void themeDialog(View view) {
        if (view.getTag() != null && view.getTag().equals("preview")) return;
        if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof ReaderIcon) ((ReaderIcon) ((ImageView) view).getDrawable()).color(uiInk());
        if (view instanceof TextView) {
            TextView text = (TextView) view; Object tag = view.getTag();
            text.setTextColor("danger".equals(tag) || text instanceof Button && destructive(text.getText().toString()) ? dangerInk() : "primary".equals(tag) ? Color.WHITE : uiInk()); text.setHintTextColor(uiMuted());
            if (text instanceof android.widget.CompoundButton) ((CompoundButton) text).setButtonTintList(android.content.res.ColorStateList.valueOf(uiAccent()));
        }
        if (view instanceof Spinner) ((Spinner) view).setPopupBackgroundDrawable(shape(uiBg(), 8));
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() { public void onChildViewAdded(View parent, View child) { themeDialog(child); } public void onChildViewRemoved(View parent, View child) {} }); for (int i = 0; i < group.getChildCount(); i++) themeDialog(group.getChildAt(i)); }
    }
    private ArrayAdapter<String> choicesAdapter(List<String> values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            @Override public View getView(int p, View recycled, ViewGroup parent) { View view = super.getView(p, recycled, parent); if (view instanceof TextView) ((TextView) view).setTextColor(uiInk()); return view; }
            @Override public View getDropDownView(int p, View recycled, ViewGroup parent) { View view = super.getDropDownView(p, recycled, parent); view.setBackgroundColor(uiBg()); if (view instanceof TextView) ((TextView) view).setTextColor(uiInk()); return view; }
        };
    }
    private Spinner shelfSpinner(String selected) {
        Spinner spinner = new Spinner(this); List<String> names = new ArrayList<>(); int index = 0;
        for (int i = 0; i < lib.shelves.size(); i++) { names.add(lib.shelves.get(i).optString("name")); if (lib.shelves.get(i).optString("id").equals(selected)) index = i; }
        spinner.setAdapter(choicesAdapter(names)); spinner.setPopupBackgroundDrawable(shape(uiBg(), 8)); spinner.setSelection(index); return spinner;
    }
    private LinearLayout categoryChoices(LinkedHashSet<String> selected) {
        LinearLayout body = column(); body.addView(text("可多选；第一项决定备份目录，不选则为未分类。", 13, uiMuted()));
        TextView first = text("", 14, uiMuted()); body.addView(first);
        Runnable update = () -> first.setText(selected.isEmpty() ? "未分类" : "首个分类：" + shelfName(selected.iterator().next())); update.run();
        for (JSONObject shelf : lib.shelves) if (!isBuiltinShelf(shelf.optString("id"))) {
            String id = shelf.optString("id"); CheckBox box = new CheckBox(this); box.setText(shelf.optString("name")); box.setContentDescription("分类：" + shelf.optString("name")); box.setTextSize(16); box.setChecked(selected.contains(id)); body.addView(box);
            box.setOnCheckedChangeListener((v, checked) -> { if (checked) selected.add(id); else selected.remove(id); update.run(); });
        }
        return body;
    }
    private View bookCategories(LinkedHashSet<String> selected) {
        LinearLayout body = column(), options = column(); options.setVisibility(View.GONE);
        Button toggle = button("", false, () -> {});
        Runnable summary = () -> toggle.setText(getString(R.string.category_summary, selected.isEmpty() ? "未分类" : getString(R.string.category_count, selected.size()), options.getVisibility() == View.VISIBLE ? "▴" : "▾"));
        toggle.setContentDescription("展开或收起所属分类"); toggle.setOnClickListener(v -> { options.setVisibility(options.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); summary.run(); });
        settingRow(body, "所属分类", toggle); ((ViewGroup) body.getChildAt(0)).getChildAt(0).setOnClickListener(v -> toggle.performClick()); body.addView(options);
        options.addView(text("可多选，第一项决定备份目录。", 13, uiMuted()));
        for (JSONObject shelf : lib.shelves) if (!isBuiltinShelf(shelf.optString("id"))) {
            String id = shelf.optString("id"); LinearLayout line = row(); weighted(line, text(shelf.optString("name"), 16, uiInk()));
            CheckBox box = new CheckBox(this); box.setContentDescription("分类：" + shelf.optString("name")); box.setChecked(selected.contains(id)); line.addView(box, new LinearLayout.LayoutParams(dp(48), dp(44)));
            line.setOnClickListener(v -> box.toggle()); box.setOnCheckedChangeListener((v, checked) -> { if (checked) selected.add(id); else selected.remove(id); summary.run(); }); options.addView(line);
        }
        summary.run(); return body;
    }
    private void shelfEditor(JSONObject shelf) {
        LinearLayout body = column(); EditText name = input("书架名称", shelf == null ? "" : shelf.optString("name")); name.setContentDescription("书架名称"); settingRow(body, "名称", name); gap(body, 20);
        Dialog dialog = sheet(shelf == null ? "新建书架" : "管理书架", body);
        body.addView(button("保存书架", true, () -> { String value = name.getText().toString().trim(); if (value.isEmpty() || value.length() > 20) { toast("书架名称请输入 1～20 字"); return; } for (JSONObject s : lib.shelves) if (s != shelf && s.optString("name").equals(value)) { toast("书架名称已经存在"); return; } if (shelf == null) { JSONObject s = json("id", UUID.randomUUID().toString(), "name", value); lib.shelves.add(s); active = s.optString("id"); } else set(shelf, "name", value); dialog.dismiss(); organizeAndRefresh(); }));
        if (shelf != null) body.addView(button("删除书架", false, () -> { dialog.dismiss(); deleteShelf(shelf); }));
    }
    private void bookEditor(JSONObject book) {
        LinearLayout body = column(); LinearLayout cover = row(); cover.setGravity(Gravity.CENTER); cover.addView(bookCover(book, 100, 140)); body.addView(cover); gap(body, 18);
        infoRow(body, "文件名称", Library.fileNames(book)); infoRow(body, "文件大小", android.text.format.Formatter.formatShortFileSize(this, lib.fileSize(book)));
        infoRow(body, "导入日期", book.optLong("added") > 0 ? android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", new Date(book.optLong("added"))).toString() : "—");
        infoRow(body, "页码数量", book.optInt("lastPageCount") > 0 ? book.optInt("lastPage", 1) + "/" + book.optInt("lastPageCount") : "—/—"); infoRow(body, "阅读进度", percent(book) + "%");
        EditText name = input("书籍名称", book.optString("title")); name.setContentDescription("书籍名称"); name.setSelectAllOnFocus(true); settingRow(body, "书籍名称", name);
        LinkedHashSet<String> memberships = new LinkedHashSet<>(Library.shelfIds(book)); body.addView(bookCategories(memberships)); gap(body, 8);
        Dialog dialog = sheet("书籍管理", body);
        body.addView(button("保存", true, () -> { String value = name.getText().toString().trim(); if (value.isEmpty()) return; set(book, "title", value.substring(0, Math.min(value.length(), 120))); Library.setShelfIds(book, memberships); dialog.dismiss(); organizeAndRefresh(); }));
        body.addView(button("导入图片作为封面", false, () -> { dialog.dismiss(); ensureStorage(() -> { coverTarget = book; Intent picker = Build.VERSION.SDK_INT >= 33 ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") : new Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"); try { startActivityForResult(picker, COVER); } catch (ActivityNotFoundException error) { toast("未找到可用的系统相册"); } }); }));
        body.addView(button("章节匹配设置", false, () -> { dialog.dismiss(); chapterSettings(book); }));
        body.addView(button("从书架移除", false, () -> { dialog.dismiss(); confirmRemove(Collections.singleton(book.optString("id"))); }));
    }
    private void infoRow(LinearLayout body, String label, String value) {
        LinearLayout line = row(); line.setMinimumHeight(dp(28)); padding(line, 0, 2);
        line.addView(text(label, 14, uiMuted()), new LinearLayout.LayoutParams(dp(76), -2));
        TextView text = text(value.isEmpty() ? "—" : value, 14, uiMuted()); text.setGravity(Gravity.END); text.setTextIsSelectable(true); weighted(line, text); body.addView(line);
    }
    private View bookCover(JSONObject book, int width, int height) {
        FrameLayout cover = new FrameLayout(this); cover.setBackground(shape(BookIdentity.coverColor(book.optString("contentHash", book.optString("id"))), 7)); cover.setClipToOutline(true); cover.setLayoutParams(new LinearLayout.LayoutParams(dp(width), dp(height)));
        TextView name = text(book.optString("title"), 15, 0xff425047); name.setTypeface(Typeface.SERIF); name.setTag("preview"); name.setGravity(Gravity.CENTER); padding(name, 8, 8); name.setMaxLines(6); name.setEllipsize(TextUtils.TruncateAt.END); cover.addView(name, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.addView(image, new FrameLayout.LayoutParams(-1, -1)); File file = lib.coverFile(book);
        if (file.isFile()) coverIo.execute(() -> { BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 2; Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts); if (bitmap != null) handler.post(() -> image.setImageBitmap(bitmap)); }); return cover;
    }
    private void chapterSettings(JSONObject book) {
        LinearLayout body = column(); body.addView(text("仅影响这本书。选择要识别的标题名称，未选中的名称不会进入目录。", 14, uiMuted())); gap(body, 12);
        BookParser.Rules old = Library.chapterRules(book); Map<String, CheckBox> boxes = new LinkedHashMap<>(); LinearLayout line = null; int count = 0;
        for (String kind : BookParser.ALL_KINDS) {
            if (count == 0 || count == 12 || (count < 12 ? count % 3 == 0 : (count - 12) % 2 == 0)) { line = row(); body.addView(line); }
            CheckBox box = new CheckBox(this); box.setText(kind); box.setTextSize(14); box.setTextColor(uiInk()); box.setButtonTintList(android.content.res.ColorStateList.valueOf(uiAccent())); box.setChecked(old.kinds.contains(kind)); boxes.put(kind, box); line.addView(box, new LinearLayout.LayoutParams(0, dp(44), 1)); count++;
        }
        gap(body, 14); body.addView(text("自定义名称", 16, uiInk())); body.addView(text("例如“季”匹配“第1季”，“EP.”匹配“EP.1”。标题保持原文；层级由卷到目递进。", 13, uiMuted()));
        List<BookParser.Rule> custom = new ArrayList<>(old.custom); LinearLayout customRows = column(); body.addView(customRows);
        Runnable[] render = new Runnable[1]; render[0] = () -> { customRows.removeAllViews(); for (BookParser.Rule rule : new ArrayList<>(custom)) { LinearLayout row = row(); weighted(row, text(rule.name + " · " + new String[]{"", "卷级", "篇级", "章级", "节级", "目级"}[rule.level], 14, uiInk())); row.addView(button("删除", false, () -> { custom.remove(rule); render[0].run(); })); customRows.addView(row); } }; render[0].run();
        EditText keyword = input("匹配名称", ""); keyword.setContentDescription("自定义章节名称"); settingRow(body, "名称", keyword); Spinner level = new Spinner(this); level.setAdapter(choicesAdapter(Arrays.asList("卷级", "篇级", "章级", "节级", "目级"))); level.setSelection(2); settingRow(body, "结构层级", level);
        body.addView(button("添加匹配", false, () -> { try { BookParser.Rule rule = new BookParser.Rule(keyword.getText().toString(), level.getSelectedItemPosition() + 1); for (BookParser.Rule existing : custom) if (existing.name.equalsIgnoreCase(rule.name)) { keyword.setError("已经添加这个名称"); return; } custom.add(rule); keyword.setText(""); render[0].run(); } catch (IllegalArgumentException error) { keyword.setError(error.getMessage()); } }));
        Dialog d = sheet("章节匹配设置", body); body.addView(button("恢复默认匹配", false, () -> { for (Map.Entry<String,CheckBox> entry : boxes.entrySet()) entry.getValue().setChecked(BookParser.DEFAULT_KINDS.contains(entry.getKey())); custom.clear(); render[0].run(); }));
        body.addView(button("保存章节匹配", true, () -> {
            JSONArray kinds = new JSONArray(), names = new JSONArray(); boolean defaults = custom.isEmpty(); String previous = book.optString("chapterRules");
            for (Map.Entry<String,CheckBox> entry : boxes.entrySet()) { if (entry.getValue().isChecked()) kinds.put(entry.getKey()); if (entry.getValue().isChecked() != BookParser.DEFAULT_KINDS.contains(entry.getKey())) defaults = false; }
            for (BookParser.Rule rule : custom) names.put(json("name", rule.name, "level", rule.level)); if (current != null) capture();
            if (defaults) book.remove("chapterRules"); else set(book, "chapterRules", json("kinds", kinds, "custom", names)); d.dismiss();
            if (previous.equals(book.optString("chapterRules"))) { if (current == null) bookEditor(book); else catalog(); } else rebuildChapters(book);
        }));
    }
    private void rebuildChapters(JSONObject book) {
        boolean reading = current != null && current.optString("id").equals(book.optString("id")); int position = book.optInt("position"); showBusy("正在更新目录…");
        openIo.execute(() -> { try { Library.Document loaded = lib.document(book); runOnUiThread(() -> { applyDocumentInfo(book, loaded); hideBusy(); if (reading) { document = loaded; source = loaded.text; toc = loaded.toc; chapters = loaded.sections; buildReader(position); catalog(); } else { showShelf(); bookEditor(book); } persist(); }); } catch (Exception error) { runOnUiThread(this::hideBusy); toast("更新目录失败：" + error.getMessage()); } });
    }
    private void applyDocumentInfo(JSONObject book, Library.Document loaded) { set(book, "chapterCount", loaded.toc.size()); set(book, "length", loaded.text.length()); set(book, "contentHash", loaded.contentHash); set(book, "fileSize", loaded.fileSize); set(book, "contentSignature", loaded.signature); }
    private void batchShelf() {
        if (selectedBooks.isEmpty()) { toast("请先选择书籍"); return; }
        LinkedHashSet<String> targets = new LinkedHashSet<>(); LinearLayout body = categoryChoices(targets); Dialog d = sheet("批量添加到书架", body);
        body.addView(button("添加到所选书架", true, () -> { if (targets.isEmpty()) { toast("请勾选分类"); return; } for (JSONObject b : lib.books) if (selectedBooks.contains(b.optString("id"))) { LinkedHashSet<String> ids = new LinkedHashSet<>(Library.shelfIds(b)); ids.addAll(targets); Library.setShelfIds(b, ids); } selectedBooks.clear(); selectionMode = false; d.dismiss(); organizeAndRefresh(); }));
    }
    private void confirmRemove(Set<String> ids) {
        if (ids.isEmpty()) return;
        confirmation("移除 " + ids.size() + " 本书？", "会删除这些书的应用备份、封面、阅读记录和书签。应用不会删除导入前的外部原文件。", "移除", () -> ensureStorage(() -> performRemove(ids, null)));
    }
    private void confirmation(String title, String message, String positive, Runnable action) {
        LinearLayout body = column(); body.addView(text(message, 15, uiInk())); gap(body, 18); Dialog d = sheet(title, body); LinearLayout actions = row();
        weighted(actions, button("取消", false, d::dismiss)); weighted(actions, button(positive, false, () -> { d.dismiss(); action.run(); })); body.addView(actions);
    }
    private boolean hasRemoved() { JSONArray removed = lib.state.optJSONArray("removedBooks"); return removed != null && removed.length() > 0; }
    private void performRemove(Set<String> ids, Runnable after) {
        showBusy("正在移除备份和记录…"); handler.removeCallbacks(exportTask);
        JSONObject snapshot; try { snapshot = new JSONObject(lib.snapshot()); } catch (Exception e) { hideBusy(); toast(e.getMessage()); return; }
        saveIo.execute(() -> { try {
            JSONObject updated = lib.removeBooks(snapshot, ids);
            runOnUiThread(() -> { lib.state = updated; lib.books.clear(); JSONArray books = updated.optJSONArray("books"); if (books != null) for (int i = 0; i < books.length(); i++) lib.books.add(books.optJSONObject(i));
                selectedBooks.clear(); selectionMode = false; coverCache.evictAll(); hideBusy(); persist(); showShelf(); if (!lib.maintenanceWarning.isEmpty()) toast(lib.maintenanceWarning); if (after != null) after.run(); });
        } catch (Exception e) { runOnUiThread(this::hideBusy); toast("移除未完成：" + e.getMessage()); } });
    }
    private void shelfSettings() {
        LinearLayout body = column(); Dialog d = sheet("书架设置", body);
        body.addView(text("小说备份文件夹", 16, uiInk())); gap(body, 6);
        TextView location = text(lib.backup.getPath().replace(android.os.Environment.getExternalStorageDirectory().getPath() + "/", ""), 15, uiMuted()); location.setTextIsSelectable(true); padding(location, 12, 10); location.setBackground(shape(shelfDark() ? 0xff30382e : 0xffe9ede3, 8)); body.addView(location, new LinearLayout.LayoutParams(-1, -2));
        body.addView(button("选择备份文件夹", false, () -> { d.dismiss(); ensureStorage(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), BACKUP_TREE)); }));
        body.addView(button("恢复默认备份文件夹", false, () -> { d.dismiss(); confirmation("恢复默认备份文件夹？", "将当前书库迁移到 Download/LNReader，保留书籍与阅读记录。", "恢复默认", () -> ensureStorage(() -> changeBackupFolder(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LNReader")))); }));
        body.addView(text("导入后从备份阅读；移除书籍同时删除备份和记录。更换文件夹会迁移当前书库。", 14, uiMuted())); gap(body, 16);
        Button grant = button(lib.canWrite() ? "已授权" : "去授权", false, () -> { d.dismiss(); ensureStorage(this::showShelf); }); settingRow(body, "文件读写权限", grant);
        settingRow(body, "UI 色系", button(uiThemeName(), false, () -> { d.dismiss(); uiThemeDialog(); }));
    }
    private String uiThemeName() { switch (lib.state.optString("uiTheme", "forest")) { case "blue": return "雾蓝"; case "sand": return "暖沙"; case "rose": return "藕粉"; case "slate": return "石墨"; case "lavender": return "藤紫"; default: return "青绿"; } }
    private void uiThemeDialog() {
        LinearLayout body = column(); body.addView(text("只调整书架和管理界面的配色，阅读背景单独设置。", 14, uiMuted())); Dialog d = sheet("UI 色系", body);
        String[] keys = {"forest", "blue", "sand", "rose", "slate", "lavender"}, names = {"青绿", "雾蓝", "暖沙", "藕粉", "石墨", "藤紫"};
        int[] colors = {0xffe9ede3, 0xffe1eaf0, 0xffeee5d7, 0xffefe1e5, 0xffe5e6e8, 0xffe9e2ef};
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) body.addView(row()); LinearLayout line = (LinearLayout) body.getChildAt(body.getChildCount() - 1); final int index = i;
            boolean selected = keys[i].equals(lib.state.optString("uiTheme", "forest"));
            Button choice = button((selected ? "✓ " : "") + names[i], false, () -> { set(lib.state, "uiTheme", keys[index]); d.dismiss(); persist(); showShelf(); });
            choice.setContentDescription(names[i]); choice.setSelected(selected); choice.setTag("preview"); choice.setTextColor(0xff41464a);
            GradientDrawable background = shape(colors[i], 9); background.setStroke(dp(selected ? 2 : 1), selected ? uiAccent() : 0x33626a70); choice.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); params.setMargins(dp(3), dp(3), dp(3), dp(3)); line.addView(choice, params);
        }
    }
    private File selectedDirectory(Uri tree) throws IOException {
        String document = DocumentsContract.getTreeDocumentId(tree); int colon = document.indexOf(':'); if (colon < 0) throw new IOException("请选择手机或存储卡上的文件夹");
        String volume = document.substring(0, colon), relative = document.substring(colon + 1); File base = volume.equals("primary") ? android.os.Environment.getExternalStorageDirectory() : new File("/storage", volume);
        File target = relative.isEmpty() ? base.getCanonicalFile() : BackupFiles.child(base, relative); if (!target.isDirectory()) throw new IOException("无法访问这个文件夹"); return target;
    }
    private void changeBackupFolder(Uri tree) {
        File target; try { target = selectedDirectory(tree); } catch (Exception error) { toast(error.getMessage()); return; }
        changeBackupFolder(target);
    }
    private void changeBackupFolder(File target) {
        if (target.equals(lib.backup)) return;
        if (new File(target, "userdata.json").isFile() && !lib.books.isEmpty() && !Library.emptyPortable(target)) { toast("目标已有书库，请选择其他文件夹，避免覆盖"); return; }
        if (new File(target, "userdata.json").isFile() && lib.books.isEmpty()) {
            String previous = lib.backup.getPath();
            try { new JSONObject(new String(read(new FileInputStream(new File(target, "userdata.json")), 32_000_000), StandardCharsets.UTF_8)); if (!getSharedPreferences("storage", 0).edit().putString("backupPath", target.getPath()).commit()) throw new IOException("无法保存文件夹设置"); lib = new Library(this, true); list = lib.state.optBoolean("list"); shelfPage = 0; persist(); showShelf(); }
            catch (Exception error) { if (!getSharedPreferences("storage", 0).edit().putString("backupPath", previous).commit()) toast("原备份路径设置未能恢复：" + previous); toast("打开备份失败：" + error.getMessage()); } return;
        }
        showBusy("正在迁移备份文件夹…"); handler.removeCallbacks(exportTask);
        JSONObject snapshot; try { snapshot = new JSONObject(lib.snapshot()); } catch (Exception error) { hideBusy(); toast(error.getMessage()); return; }
        saveIo.execute(() -> { try { JSONObject moved = lib.relocate(target, snapshot); runOnUiThread(() -> { lib.state = moved; lib.applyPaths(moved); hideBusy(); persist(); showShelf(); toast(lib.maintenanceWarning.isEmpty() ? "备份文件夹已更改" : lib.maintenanceWarning); }); }
            catch (Exception error) { runOnUiThread(this::hideBusy); toast("迁移未完成：" + error.getMessage()); } });
    }
    private byte[] compressedCover(Uri uri) throws Exception {
        byte[] raw = read(getContentResolver().openInputStream(uri), 32_000_000); BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取图片"); options.inSampleSize = 1; while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1440) options.inSampleSize *= 2; options.inJustDecodeBounds = false;
        Bitmap original = BitmapFactory.decodeByteArray(raw, 0, raw.length, options); if (original == null) throw new IOException("无法解码图片");
        float ratio = Math.min(1, 720f / Math.max(original.getWidth(), original.getHeight())); Bitmap cover = Bitmap.createBitmap(Math.max(1, Math.round(original.getWidth() * ratio)), Math.max(1, Math.round(original.getHeight() * ratio)), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(cover); canvas.drawColor(Color.WHITE); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); canvas.drawBitmap(original, null, new Rect(0, 0, cover.getWidth(), cover.getHeight()), paint); original.recycle();
        ByteArrayOutputStream output = new ByteArrayOutputStream(); cover.compress(Bitmap.CompressFormat.JPEG, 82, output); cover.recycle(); return output.toByteArray();
    }

    private void openBook(JSONObject book, int offset) {
        if (!book.optString("directory").isEmpty() && !lib.canWrite()) { ensureStorage(() -> { JSONObject restored = lib.book(book.optString("id")); if (restored != null) openBook(restored, offset); }); return; }
        if (importing) return; importing = true;
        long started = SystemClock.elapsedRealtime();
        Runnable spinner = () -> showBusy("正在打开…"); handler.postDelayed(spinner, 180);
        openIo.execute(() -> { try { Library.Document loaded = lib.document(book); if (loaded.sections.isEmpty()) throw new IOException("空文件");
            runOnUiThread(() -> { handler.removeCallbacks(spinner); hideBusy(); document = loaded; current = book; controlsVisible = false; collapsedChapters.clear(); source = loaded.text; toc = loaded.toc; chapters = loaded.sections;
                set(current, "lastRead", System.currentTimeMillis()); applyDocumentInfo(current, loaded); buildReader(offset < 0 ? current.optInt("position") : offset); persist();
                View openedSurface = readerSurface; openedSurface.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() { public boolean onPreDraw() { openedSurface.getViewTreeObserver().removeOnPreDrawListener(this); android.util.Log.d("LNReader", "reader_open_ms=" + (SystemClock.elapsedRealtime() - started)); return true; } }); });
        } catch (Exception e) { runOnUiThread(() -> { handler.removeCallbacks(spinner); hideBusy(); }); toast("无法打开：" + e.getMessage()); } });
    }
    private int readerBg() { if (shelfDark()) return 0xff20231f; return dayBg(); }
    private int dayBg() { try { return Color.parseColor(lib.prefs().optString("bg", "#f7f3e8")); } catch (Exception e) { return 0xfff7f3e8; } }
    private int readerInk() { int bg = readerBg(); return Color.red(bg) * .299 + Color.green(bg) * .587 + Color.blue(bg) * .114 < 135 ? 0xffdeded4 : 0xff363c32; }
    private Button readerButton(String title, Runnable action) { Button b = button(title, false, action); b.setTextColor(readerInk()); b.setTextSize(15); b.setMinHeight(dp(48)); return b; }
    private Button readerAction(String title, String icon, Runnable action) {
        Button b = readerButton(title, action); b.setTextSize(13); b.setGravity(Gravity.CENTER); padding(b, 6, 8); b.setMinHeight(dp(72)); b.setMinimumHeight(dp(72));
        if (title.equals("日 / 夜")) b.setTag("nightToggle");
        b.setCompoundDrawablesWithIntrinsicBounds(null, new ReaderIcon(icon, readerInk(), getResources().getDisplayMetrics().density), null, null); b.setCompoundDrawablePadding(dp(5)); return b;
    }
    private ImageButton readerIconButton(String icon, String label, Runnable action) {
        ImageButton button = new ImageButton(this); button.setImageDrawable(new ReaderIcon(icon, uiInk(), getResources().getDisplayMetrics().density));
        button.setContentDescription(label); button.setBackground(shape(Color.TRANSPARENT, 8)); padding(button, 12, 12);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void schedulePageIndex() {
        int generation = ++paginationGeneration; pageTotals = null; pageIndexSource = "pending";
        if (indexTask != null) handler.removeCallbacks(indexTask);
        indexTask = () -> indexPages(generation); handler.postDelayed(indexTask, 16);
    }
    private boolean isPagedMode() { return !lib.prefs().optString("readingMode", "paged").equals("scroll"); }
    private int pageSourceOffset(int local) { return display == null ? chapters.get(chapter).start : Math.min(chapters.get(chapter).end - 1, display.sourceOffset(local)); }
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
            public boolean beginDrag(int direction) { return beginPageDrag(direction); }
            public void drag(float pixels) { if (pagedReader != null) pagedReader.drag(pixels); }
            public void endDrag(boolean commit) { if (pagedReader != null) pagedReader.endDrag(commit); }
            public boolean selecting() { return pagedReader != null && pagedReader.selectionActive() || readingText != null && readingText.hasSelection(); }
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
            readingScroll = new ScrollView(this); readingScroll.setFillViewport(false); readingScroll.setFocusableInTouchMode(true); readingScroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS); surface.scrollChild(readingScroll);
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
        weighted(actions, readerAction("日 / 夜", shelfDark() ? "sun" : "moon", this::toggleNight));
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
        final ScrollPageCounter scrollCounter = counter == null ? new ScrollPageCounter(readingText, readingScroll.getHeight(), dp(40)) : null;
        final String bookText = source;
        final List<BookParser.Chapter> bookChapters = chapters;
        final int spacing = dp((float) (lib.prefs().optDouble("paragraph", .4) * lib.prefs().optDouble("size", 18)));
        final int indent = Math.round((float) lib.prefs().optDouble("size", 18) * getResources().getDisplayMetrics().scaledDensity * 2);
        final String geometry = "layout6:" + document.signature + ":" + isPagedMode() + ":" + readerSurface.getWidth() + ":" + readerSurface.getHeight() + ":" + fontLayoutKey() + ":" + lib.prefs().optDouble("size") + ":" + lib.prefs().optDouble("line") + ":" + lib.prefs().optDouble("paragraph") + ":" + getResources().getDisplayMetrics().density + ":" + getResources().getDisplayMetrics().scaledDensity;
        JSONObject saved = current.optJSONObject("pagination");
        if (saved != null && geometry.equals(saved.optString("key"))) { int[] cached = savedPageTotals(saved.optJSONArray("totals"), bookChapters.size() + 1); if (cached != null) { pageTotals = cached; pageIndexSource = "userdata"; positionUI(); android.util.Log.d("LNReader", "page_index_source=userdata"); return; } }
        set(current, "lastPageCount", 0);
        final JSONObject config = json("key", geometry, "font", lib.prefs().optString("font"), "fontKey", fontLayoutKey(), "size", lib.prefs().optDouble("size"), "line", lib.prefs().optDouble("line"), "paragraph", lib.prefs().optDouble("paragraph"), "width", readerSurface.getWidth(), "height", readerSurface.getHeight(), "density", getResources().getDisplayMetrics().scaledDensity, "mode", isPagedMode() ? "paged" : "scroll");
        final File pageCache = new File(lib.cache, current.optString("id") + "-" + Integer.toHexString(geometry.hashCode()) + ".pages");
        pageIo.execute(() -> {
            int[] totals = new int[bookChapters.size() + 1];
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(pageCache)))) {
                if (input.readUTF().equals(geometry) && input.readInt() == totals.length) { for (int i = 0; i < totals.length; i++) totals[i] = input.readInt(); handler.post(() -> installPageTotals(generation, totals, config, "disk")); return; }
            } catch (Exception ignored) {}
            for (int i = 0; i < bookChapters.size(); i++) {
                if (generation != paginationGeneration || Thread.currentThread().isInterrupted()) return;
                BookParser.Chapter c = bookChapters.get(i); DisplayText clean = new DisplayText(bookText, c.start, c.end, true, c.heading);
                SpannableString styled = styledSection(clean, c, spacing, indent); totals[i + 1] = totals[i] + (counter != null ? counter.count(styled) : scrollCounter.count(styled));
            }
            try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream output = new DataOutputStream(bytes)) { output.writeUTF(geometry); output.writeInt(totals.length); for (int value : totals) output.writeInt(value); } atomic(pageCache, bytes.toByteArray()); } catch (Exception ignored) {}
            handler.post(() -> installPageTotals(generation, totals, config, "computed"));
        });
    }
    private String fontLayoutKey() { String name = lib.prefs().optString("font", "系统默认"); File file = name.equals("自选字体") ? new File(getFilesDir(), "reader-font") : new File(name); return name + (file.isFile() ? ":" + file.length() + ":" + file.lastModified() + ":" + lib.prefs().optString("customFontHash") : ":system:" + Build.FINGERPRINT); }
    private int[] savedPageTotals(JSONArray stored, int length) { if (stored == null || stored.length() != length || stored.optInt(0, -1) != 0) return null; int[] totals = new int[length]; for (int i = 1; i < length; i++) { totals[i] = stored.optInt(i, -1); if (totals[i] <= totals[i - 1] || totals[i] > 10000000) return null; } return totals; }
    private void installPageTotals(int generation, int[] totals, JSONObject config, String from) { if (generation != paginationGeneration || current == null) return; JSONArray saved = new JSONArray(); for (int value : totals) saved.put(value); set(config, "totals", saved); set(current, "pagination", config); pageTotals = totals; pageIndexSource = from; positionUI(); persist(); android.util.Log.d("LNReader", "page_index_source=" + from); }
    private static final class ScrollPageCounter {
        final TextPaint body; final int width, height, padding; final float line;
        ScrollPageCounter(TextView text, int height, int padding) {
            body = new TextPaint(text.getPaint()); width = text.getWidth(); this.height = Math.max(1, height); this.padding = padding; line = text.getLineSpacingMultiplier();
        }
        int measure(CharSequence text, TextPaint paint, float multiplier) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).setIncludePad(true).setLineSpacing(0, multiplier).setBreakStrategy(simpleBreakStrategy()).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().getHeight();
        }
        int count(CharSequence text) { return Math.max(1, (int) Math.ceil((measure(text, body, line) + padding) / (double) height)); }
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
        if (toc.isEmpty()) { toast("未识别到章节"); return; }
        int next = currentTocIndex() + direction;
        if (next < 0 || next >= toc.size()) { toast(direction < 0 ? "已到第一章" : "已到最后一章"); return; }
        jump(toc.get(next).start); persist();
    }
    private int currentTocIndex() { int low = 0, high = toc.size() - 1, found = -1, offset = current == null ? 0 : current.optInt("position"); while (low <= high) { int mid = (low + high) >>> 1; if (toc.get(mid).start <= offset) { found = mid; low = mid + 1; } else high = mid - 1; } return found; }
    private Typeface readingTypeface() {
        String font = lib.prefs().optString("font", "系统默认");
        try { if (font.equals("自选字体")) return Typeface.createFromFile(new File(getFilesDir(), "reader-font")); if (font.startsWith("/system/fonts/")) return Typeface.createFromFile(font); if (font.equals("系统衬线")) return Typeface.SERIF; if (font.equals("等宽字体")) return Typeface.MONOSPACE; } catch (Exception ignored) {}
        return Typeface.DEFAULT;
    }
    private void jump(int offset) {
        if (progressTask != null) handler.removeCallbacks(progressTask); positioning = true; offset = Math.max(0, Math.min(source.length() - 1, offset)); endReached = offset >= source.length() - 2;
        chapter = chapters.size() - 1; for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).end > offset) { chapter = i; break; }
        BookParser.Chapter c = chapters.get(chapter); set(current, "position", offset); set(current, "chapterTitle", c.title); chapterLabel.setText(c.title);
        quietTitle.setText(c.title.isEmpty() ? current.optString("title") : c.title);
        display = new DisplayText(source, c.start, c.end, true, c.heading);
        SpannableString styled = styledSection(display, c, dp((float) (lib.prefs().optDouble("paragraph", .4) * lib.prefs().optDouble("size", 18))), Math.round((float) lib.prefs().optDouble("size", 18) * getResources().getDisplayMetrics().scaledDensity * 2));
        if (isPagedMode()) {
            pagedReader.content(styled, display.displayOffset(offset));
            positioning = false; positionUI(); return;
        }
        readingBody.removeAllViews(); readingText = text("", (float) lib.prefs().optDouble("size", 18), readerInk()); readingText.setTypeface(readingTypeface()); readingText.setTextIsSelectable(true); readingText.setLineSpacing(0, (float) lib.prefs().optDouble("line", 1.4));
        readingText.setText(styled); readingBody.addView(readingText, new LinearLayout.LayoutParams(-1, -2)); gap(readingBody, 20);
        readingScroll.requestFocus();
        readingText.setBreakStrategy(simpleBreakStrategy()); readingText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE); if (Build.VERSION.SDK_INT >= 28) readingText.setFallbackLineSpacing(false);
        int desired = offset; TextView target = readingText; DisplayText mapped = display;
        readingText.post(() -> { if (readingText != target) return; if (target.getLayout() != null) { int local = mapped.displayOffset(desired); int y = desired <= c.start ? 0 : target.getTop() + target.getLayout().getLineTop(target.getLayout().getLineForOffset(local)); readingScroll.scrollTo(0, y); } positioning = false; positionUI(); });
    }
    private static SpannableString styledSection(DisplayText display, BookParser.Chapter section, int spacing, int indent) {
        String text = display.text; SpannableString styled = new SpannableString(text); int cursor = 0, line = 0;
        while (cursor < text.length()) { int end = text.indexOf('\n', cursor); if (end < 0) end = text.length(); else end++;
            styled.setSpan(new ParagraphSpace(spacing), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            boolean heading = line == 0 && section.heading;
            if (heading) styleHeading(styled, Math.max(0, end - (text.charAt(end - 1) == '\n' ? 1 : 0)));
            cursor = end; line++;
        }
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
    private boolean beginPageDrag(int direction) {
        if (current == null || positioning || importing || !isPagedMode() || pagedReader == null || !pagedReader.ready() || !lib.prefs().optString("pageAnimation", "slide").equals("slide")) return false;
        if (!(pagedReader.canTurnPage(direction) || (direction > 0 ? chapter + 1 < chapters.size() : chapter > 0))) return false;
        CharSequence adjacent = null;
        if (!pagedReader.canTurnPage(direction)) {
            BookParser.Chapter next = chapters.get(chapter + direction);
            adjacent = styledSection(new DisplayText(source, next.start, next.end, true, next.heading), next,
                dp((float) (lib.prefs().optDouble("paragraph", .4) * lib.prefs().optDouble("size", 18))), 0);
        }
        return pagedReader.beginDrag(direction, adjacent, () -> turnPageNow(direction));
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
        int y = Math.max(0, readingScroll.getScrollY() - readingText.getTop()); int line = readingText.getLayout().getLineForVertical(y); int offset = display.sourceOffset(readingText.getLayout().getLineStart(line));
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
            if (pageTotals != null) { set(current, "lastPage", pageTotals[chapter] + Math.min(page, pageTotals[chapter + 1] - pageTotals[chapter])); set(current, "lastPageCount", pageTotals[pageTotals.length - 1]); }
        }
    }
    private void back() { capture(); persist(); if (progressTask != null) handler.removeCallbacks(progressTask); showShelf(); }
    private void catalog() {
        if (toc.isEmpty()) { LinearLayout empty = column(); empty.addView(text("未识别到章节", 16, uiMuted())); Dialog d = sheet("目录", empty); sheetAction(d, "settings", "章节匹配设置", () -> { d.dismiss(); chapterSettings(current); }); empty.addView(button("返回阅读", false, d::dismiss)); return; }
        List<BookParser.Chapter> chapters = toc;
        LinearLayout body = column(); EditText search = input("搜索章节", ""); search.setTextSize(17); body.addView(search); ListView entries = new ListView(this); body.addView(entries, new LinearLayout.LayoutParams(-1, dp(getResources().getConfiguration().screenHeightDp / 2))); Dialog d = sheet("目录 · " + chapters.size() + " 章", body);
        ChapterTree tree = new ChapterTree(chapters);
        collapsedChapters.clear(); collapsedChapters.addAll(tree.collapsedOutsidePath(currentTocIndex()));
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
                item.setSelected(index == currentTocIndex()); item.setBackground(shape(index == currentTocIndex() ? shelfDark() ? 0xff46513f : 0xffd5dfcf : Color.TRANSPARENT, 8));
                if (tree.branch[index]) {
                    Button toggle = button(collapsedChapters.contains(index) && search.length() == 0 ? "▸" : "▾", false, () -> {
                        int first = entries.getFirstVisiblePosition(); View top = entries.getChildAt(0); int y = top == null ? 0 : top.getTop();
                        if (!collapsedChapters.remove(index)) collapsedChapters.add(index);
                        indices.clear(); indices.addAll(tree.visible(collapsedChapters, search.getText().toString())); notifyDataSetChanged(); fit.run(); entries.setSelectionFromTop(first, y);
                    });
                    toggle.setContentDescription((collapsedChapters.contains(index) ? "展开" : "收起") + chapters.get(index).title);
                    padding(toggle, 0, 0); item.addView(toggle, new LinearLayout.LayoutParams(dp(32), dp(48)));
                } else item.addView(new View(NativeActivity.this), new LinearLayout.LayoutParams(dp(32), 1));
                TextView label = text(chapters.get(index).title, 16, uiInk());
                label.setTypeface(null, index == currentTocIndex() ? Typeface.BOLD : Typeface.NORMAL);
                label.setContentDescription(chapters.get(index).title + (index == currentTocIndex() ? "，当前章节" : ""));
                label.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.setSelected(index == currentTocIndex());
                    }
                });
                padding(label, 4, 12); label.setMinHeight(dp(48)); weighted(item, label);
                label.setOnClickListener(v -> { jump(chapters.get(index).start); persist(); notifyDataSetChanged(); });
                return item;
            }
        };
        entries.setAdapter(adapter);
        Runnable render = () -> { indices.clear(); indices.addAll(tree.visible(collapsedChapters, search.getText().toString())); adapter.notifyDataSetChanged(); fit.run(); };
        sheetAction(d, "expand_all", "全部展开", () -> { collapsedChapters.clear(); render.run(); });
        sheetAction(d, "collapse_all", "全部收起", () -> { for (int i = 0; i < tree.branch.length; i++) if (tree.branch[i]) collapsedChapters.add(i); render.run(); });
        sheetAction(d, "settings", "章节匹配设置", () -> { d.dismiss(); chapterSettings(current); });
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) {} public void onTextChanged(CharSequence s, int a, int b, int c) { render.run(); } public void afterTextChanged(Editable e) {} }); render.run();
        entries.setSelection(Math.max(0, indices.indexOf(currentTocIndex())));
        body.addView(button("返回阅读", false, d::dismiss));
    }
    private JSONArray bookmarks() { JSONArray marks = current.optJSONArray("marks"); if (marks == null) { marks = new JSONArray(); set(current, "marks", marks); } return marks; }
    private void addMark() { capture(); JSONArray marks = bookmarks(); for (int i = 0; i < marks.length(); i++) if (Math.abs(marks.optJSONObject(i).optInt("offset") - current.optInt("position")) < 80) { toast("这个位置已经有书签"); return; } String title = chapters.get(chapter).title; marks.put(json("offset", current.optInt("position"), "title", title.isEmpty() ? current.optString("title") : title, "date", System.currentTimeMillis())); persist(); toast("已添加书签"); }
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
        mode.setContentDescription("翻页方式"); mode.setAdapter(choicesAdapter(Arrays.asList("左右翻页", "滚动翻页")));
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
        animation.setAdapter(choicesAdapter(Arrays.asList("平移", "无动画")));
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
        body.addView(button("恢复默认设置", false, () -> { body.clearFocus(); boolean night = shelfDark(); set(lib.state, "prefs", defaultPrefs()); set(lib.prefs(), "night", night); buildReader(current.optInt("position")); persist(); d.dismiss(); }));
    }
    private void settingRow(LinearLayout body, String title, View value) {
        LinearLayout line = row(); line.setMinimumHeight(dp(46)); weighted(line, text(title, 16, uiInk())); int max = dp(Math.min(200, getResources().getConfiguration().screenWidthDp - 160)); if (value instanceof TextView) ((TextView) value).setMaxWidth(max); line.addView(value, value instanceof LinearLayout ? new LinearLayout.LayoutParams(max, -2) : new LinearLayout.LayoutParams(-2, -2)); body.addView(line);
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
            if ("nightToggle".equals(t.getTag())) t.setCompoundDrawablesWithIntrinsicBounds(null, new ReaderIcon(shelfDark() ? "sun" : "moon", readerInk(), getResources().getDisplayMetrics().density), null, null);
            for (android.graphics.drawable.Drawable icon : t.getCompoundDrawables()) if (icon instanceof ReaderIcon) ((ReaderIcon) icon).color(readerInk());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) colorViews(((ViewGroup) view).getChildAt(i));
    }
    private void applyBackground(String color) {
        set(lib.prefs(), "bg", color); set(lib.prefs(), "night", false); repaintReader();
        handler.removeCallbacks(backgroundSave); handler.postDelayed(backgroundSave, 250);
    }
    private void repaintReader() {
        root.setBackgroundColor(readerBg()); readerSurface.setBackgroundColor(readerBg());
        for (View view : new View[]{readerHeader, readerFooter, quietHeader, quietFooter}) { view.setBackgroundColor(readerBg()); colorViews(view); }
        if (pagedReader != null) pagedReader.colors(readerInk(), readerBg());
        if (readingBody != null) colorViews(readingBody);
        getWindow().setStatusBarColor(readerBg()); getWindow().setNavigationBarColor(readerBg()); systemBars(controlsVisible);
        for (Iterator<Map.Entry<Dialog, LinearLayout>> iterator = dialogShells.entrySet().iterator(); iterator.hasNext();) { Map.Entry<Dialog, LinearLayout> entry = iterator.next(); if (!entry.getKey().isShowing()) iterator.remove(); else { entry.getValue().setBackground(shape(uiBg(), 18)); themeDialog(entry.getValue()); } }
    }
    private void backgroundPicker(LinearLayout body) {
        String[] names = {"纸白", "青竹", "米黄", "暖灰", "雾蓝", "浅杏", "淡粉", "浅紫", "自定义"};
        String[] colors = {"#f7f3e8", "#e5ebdf", "#d4c9aa", "#e4e0d9", "#dce7ec", "#f2e2cc", "#f0e0e5", "#e9e3ef"};
        LinearLayout choices = column(), custom = column(); choices.setVisibility(View.GONE); custom.setVisibility(View.GONE);
        Button expand = button("", false, () -> choices.setVisibility(choices.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        settingRow(body, "背景色", expand); body.addView(choices);
        Button[] presets = new Button[names.length]; int[] channels = new int[3]; boolean[] syncing = {false};
        SeekBar[] sliders = new SeekBar[3]; EditText[] values = new EditText[3];
        TextView preview = text("", 16, INK); preview.setTag("preview"); preview.setGravity(Gravity.CENTER); preview.setMinHeight(dp(50)); custom.addView(preview);
        Runnable render = () -> {
            String selected = lib.prefs().optString("bg"); int match = -1;
            for (int i = 0; i < colors.length; i++) if (colors[i].equalsIgnoreCase(selected)) match = i;
            boolean isCustom = lib.prefs().optBoolean("customBackground", match < 0); int selectedIndex = isCustom ? colors.length : match;
            expand.setText(getString(R.string.background_choice, selectedIndex < 0 ? "自定义" : names[selectedIndex]));
            for (int i = 0; i < presets.length; i++) {
                Button b = presets[i]; if (b == null) continue;
                GradientDrawable background = shape(i < colors.length ? Color.parseColor(colors[i]) : 0xffeeeee6, 8);
                if (i == selectedIndex) background.setStroke(dp(2), ACCENT);
                b.setBackground(background); b.setTag("preview"); b.setText(getString(R.string.selected_choice, i == selectedIndex ? "✓ " : "", names[i])); b.setSelected(i == selectedIndex); b.setTextColor(INK);
                b.setContentDescription(names[i] + (i == selectedIndex ? "，已选择" : ""));
            }
            custom.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            int bg = dayBg(); channels[0] = Color.red(bg); channels[1] = Color.green(bg); channels[2] = Color.blue(bg);
            syncing[0] = true;
            for (int i = 0; i < 3; i++) if (values[i] != null) { sliders[i].setProgress(channels[i]); if (!values[i].hasFocus()) values[i].setText(String.valueOf(channels[i])); }
            syncing[0] = false;
            preview.setText(selected.toUpperCase(Locale.ROOT)); preview.setBackground(shape(bg, 8)); preview.setTextColor(Color.red(bg) * .299 + Color.green(bg) * .587 + Color.blue(bg) * .114 < 135 ? 0xffdeded4 : INK);
        };
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) choices.addView(row()); LinearLayout line = (LinearLayout) choices.getChildAt(choices.getChildCount() - 1);
            final int index = i;
            presets[i] = button(names[i], false, () -> { set(lib.prefs(), "customBackground", index == colors.length); if (index < colors.length) applyBackground(colors[index]); else persist(); render.run(); });
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
        LinearLayout body = column(); ListView options = new ListView(this); options.setAdapter(choicesAdapter(names)); body.addView(options, new LinearLayout.LayoutParams(-1, dp(360))); Dialog d = sheet("选择字体", body);
        options.setOnItemClickListener((parent, view, index, id) -> { d.dismiss(); if (index == 4) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), FONT); else { capture(); set(lib.prefs(), "font", values.get(index)); buildReader(current.optInt("position")); persist(); } });
    }

    private void importDialog() {
        LinearLayout body = column(); RadioGroup modes = new RadioGroup(this); RadioButton separate = new RadioButton(this); separate.setId(View.generateViewId()); separate.setText(R.string.import_separate); RadioButton merged = new RadioButton(this); merged.setId(View.generateViewId()); merged.setText(R.string.import_merged); separate.setTextSize(14); merged.setTextSize(14); modes.addView(separate); modes.addView(merged); separate.setChecked(true); body.addView(modes); gap(body, 14); body.addView(text("导入到书架", 14, MUTED)); LinkedHashSet<String> selected = new LinkedHashSet<>(); if (!isBuiltinShelf(active)) selected.add(active); body.addView(categoryChoices(selected)); gap(body, 12); Dialog d = sheet("导入书籍", body);
        Runnable prepare = () -> { grouping = merged.isChecked() ? "merged" : "separate"; importShelves.clear(); importShelves.addAll(selected); importShelf = selected.isEmpty() ? "default" : selected.iterator().next(); d.dismiss(); };
        body.addView(button("＋ 选择 TXT 文件（支持多选）", true, () -> { prepare.run(); Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(intent, TXT); })); gap(body, 9);
        body.addView(button("选择文件夹（包含子目录）", false, () -> { prepare.run(); Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); String previous = getPreferences(0).getString("lastImportTree", getPreferences(0).getString("tree", "")); if (!previous.isEmpty()) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(Uri.parse(previous), DocumentsContract.getTreeDocumentId(Uri.parse(previous)))); startActivityForResult(intent, TREE); })); gap(body, 16); body.addView(text("导入即备份，相同内容自动跳过。文件夹导入可识别同名封面或 cover。", 13, uiMuted()));
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
    private void scan(Uri tree) {
        showBusy("正在导入文件夹…");
        io.execute(() -> { try {
            List<Entry> entries = new ArrayList<>(); String id = DocumentsContract.getTreeDocumentId(tree); walk(tree, id, "", entries, 0);
            String name = entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), "").path;
            importEntries(entries, name, tree.toString(), null);
        } catch (Exception e) { runOnUiThread(this::hideBusy); toast("无法读取文件夹：" + e.getMessage()); } });
    }
    private Entry imageFor(String path, List<Entry> images, String folder) {
        int slash = path.lastIndexOf('/'); String parent = slash < 0 ? "" : path.substring(0, slash + 1), stem = path.substring(slash + 1).replaceFirst("(?i)\\.txt$", ""); Entry fallback = null;
        for (Entry e : images) { int s = e.path.lastIndexOf('/'); String p = s < 0 ? "" : e.path.substring(0, s + 1); if (!p.equals(parent)) continue; String name = e.path.substring(s + 1).replaceFirst("\\.[^.]+$", ""); if (name.equalsIgnoreCase(stem)) return e; if (name.matches("(?i)cover|folder|封面") || name.equalsIgnoreCase(folder)) fallback = e; } return fallback;
    }
    private void importEntries(List<Entry> entries, String name, String folder, JSONObject metadata) {
        List<Entry> texts = new ArrayList<>(), images = new ArrayList<>();
        for (Entry e : entries) { if (e.path.toLowerCase(Locale.ROOT).endsWith(".txt")) texts.add(e); else if (e.path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$")) images.add(e); }
        texts.sort((a, b) -> BookParser.naturalCompare(a.path, b.path));
        if (texts.isEmpty()) { runOnUiThread(this::hideBusy); toast("没有找到 TXT 文件"); return; }
        List<List<Entry>> groups = new ArrayList<>(); if (grouping.equals("merged")) groups.add(texts); else for (Entry e : texts) groups.add(Collections.singletonList(e));
        List<JSONObject> imported = new ArrayList<>(); List<String> failed = new ArrayList<>(); Map<String, JSONObject> hashes = new HashMap<>(); Set<String> indexed = new HashSet<>();
        for (JSONObject book : lib.books) if (!book.optString("contentHash").isEmpty()) { hashes.put(book.optString("contentHash"), book); indexed.add(book.optString("id")); }
        int duplicates = 0;
        for (List<Entry> group : groups) {
            File stage = null;
            try {
                String title = group.size() > 1 ? name : new File(group.get(0).path).getName().replaceFirst("(?i)\\.txt$", "");
                String category = shelfName(importShelf); if (isBuiltinShelf(importShelf)) category = "无分类";
                stage = lib.createDirectory(category, ".import-" + UUID.randomUUID()); JSONArray parts = new JSONArray(), paths = new JSONArray(); Set<String> used = new HashSet<>(); List<File> rawFiles = new ArrayList<>();
                runOnUiThread(() -> { if (busyText != null) busyText.setText(getString(R.string.organizing, title)); });
                long total = 0; int limit = (int) Math.min(80_000_000, Runtime.getRuntime().maxMemory() / 8);
                for (Entry e : group) {
                    byte[] raw = read(getContentResolver().openInputStream(e.uri), (int) (limit - total)); total += raw.length;
                    String decoded = BookParser.decode(raw); if (decoded.indexOf('\0') >= 0 || decoded.trim().isEmpty()) throw new IOException("不是有效的 TXT 正文");
                    String filename = BackupFiles.name(new File(e.path).getName()), original = filename; int duplicate = 2;
                    while (!used.add(filename.toLowerCase(Locale.ROOT))) filename = BackupFiles.numbered(original, duplicate++);
                    File file = new File(stage, filename); BackupFiles.write(file, raw); rawFiles.add(file); parts.put(filename); paths.put(e.path);
                }
                String hash = BookIdentity.fingerprint(rawFiles); JSONObject previous = hashes.get(hash);
                if (previous == null) for (JSONObject oldBook : lib.books) if (!indexed.contains(oldBook.optString("id")) && lib.fileSize(oldBook) == total) {
                    indexed.add(oldBook.optString("id")); try { String existing = BookIdentity.fingerprint(lib.partFiles(oldBook)); hashes.put(existing, oldBook); if (existing.equals(hash)) { previous = oldBook; break; } } catch (Exception ignored) {}
                }
                if (previous != null) { duplicates++; continue; }
                String id = hash.substring(0, 24); JSONObject book = json("id", id, "contentHash", hash, "title", title, "shelf", importShelf, "position", 0, "marks", new JSONArray(), "added", System.currentTimeMillis(), "fileSize", total); Library.setShelfIds(book, importShelves.isEmpty() ? Arrays.asList(importShelf) : importShelves);
                Entry image = imageFor(group.get(0).path, images, name); if (image == null && group.size() > 1) image = imageFor(name + ".txt", images, name);
                if (image != null) try { atomic(new File(stage, "cover.jpg"), compressedCover(image.uri)); } catch (Exception ignored) {}
                File target = BackupFiles.available(stage.getParentFile(), title); java.nio.file.Files.move(stage.toPath(), target.toPath()); stage = null;
                set(book, "directory", lib.relative(target)); set(book, "parts", parts); set(book, "sources", paths); set(book, "organizedTitle", title); set(book, "directoryName", target.getName()); set(book, "cover", new File(target, "cover.jpg").isFile());
                Library.Document loaded = lib.document(book); set(book, "length", loaded.text.length()); set(book, "chapterCount", loaded.toc.size()); set(book, "contentSignature", loaded.signature); imported.add(book); hashes.put(hash, book);
            } catch (Exception e) { failed.add(new File(group.get(0).path).getName() + "：" + e.getMessage()); }
            finally { if (stage != null) deleteStaging(stage); }
        }
        final int skipped = duplicates;
        runOnUiThread(() -> { for (Map.Entry<String,JSONObject> entry : hashes.entrySet()) set(entry.getValue(), "contentHash", entry.getKey()); lib.books.addAll(imported); hideBusy(); if (!imported.isEmpty()) { active = "all"; shelfPage = 0; } persist(); showShelf(); toast("已导入 " + imported.size() + " 本，跳过重复 " + skipped + " 本" + (failed.isEmpty() ? "" : "；失败 " + failed.size() + " 本，" + failed.get(0))); });
    }
    private void deleteStaging(File directory) {
        if (!directory.getName().startsWith(".import-")) return;
        File[] files = directory.listFiles(); if (files != null) for (File file : files) if (file.isFile()) file.delete(); directory.delete();
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
        if ((request == TREE || request == BACKUP_TREE) && data.getData() != null) { Uri tree = data.getData(); try { int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION; if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION; getContentResolver().takePersistableUriPermission(tree, flags); if (request == BACKUP_TREE) changeBackupFolder(tree); else { getPreferences(0).edit().putString("lastImportTree", tree.toString()).apply(); scan(tree); } } catch (Exception e) { toast("文件夹授权失败，请重试"); } }
        else if (request == COVER && data.getData() != null && coverTarget != null) {
            JSONObject book = coverTarget; coverTarget = null; showBusy("正在压缩并备份封面…"); Uri image = data.getData();
            io.execute(() -> { try { atomic(new File(lib.directory(book), "cover.jpg"), compressedCover(image)); runOnUiThread(() -> { set(book, "cover", true); coverCache.evictAll(); hideBusy(); persist(); showShelf(); }); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("封面导入失败：" + e.getMessage()); } });
        }
        else if (request == TXT) { showBusy("正在导入…"); io.execute(() -> { try { List<Entry> entries = new ArrayList<>(); if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) entries.add(entry(data.getClipData().getItemAt(i).getUri(), "")); else if (data.getData() != null) entries.add(entry(data.getData(), "")); importEntries(entries, "合辑", "", null); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("导入失败：" + e.getMessage()); } }); }
        else if (request == FONT && data.getData() != null) { showBusy("正在加载字体…"); io.execute(() -> { try { byte[] bytes = read(getContentResolver().openInputStream(data.getData()), 40_000_000); File temp = new File(getCacheDir(), "font-check"); atomic(temp, bytes); Typeface.createFromFile(temp); atomic(new File(getFilesDir(), "reader-font"), bytes); temp.delete(); runOnUiThread(() -> { hideBusy(); set(lib.prefs(), "font", "自选字体"); if (current != null) buildReader(current.optInt("position")); persist(); toast("字体已应用"); }); } catch (Exception e) { runOnUiThread(this::hideBusy); toast("字体无效，请选择 TTF / OTF 文件"); } }); }
    }
    @Override public void onBackPressed() { if (current != null && pagedReader != null && pagedReader.selectionActive()) pagedReader.clearSelection(); else if (current != null) back(); else if (selectionMode) { selectionMode = false; selectedBooks.clear(); renderCards(shelfMatches); } else { persist(); moveTaskToBack(true); } }
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
    @Override protected void onPause() { super.onPause(); if (lib != null) { capture(); persist(); handler.removeCallbacks(exportTask); exportTask.run(); } }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus && root != null) systemBars(current == null || controlsVisible); }
    @Override protected void onResume() {
        super.onResume();
        if (lib != null && waitingStorage && Build.VERSION.SDK_INT >= 30) completeStorageRequest();
        if (!statusRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(Intent.ACTION_TIME_TICK); filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(statusReceiver, filter); statusRegistered = true;
        }
        updateDeviceStatus();
    }
    @Override protected void onStop() { if (statusRegistered) { unregisterReceiver(statusReceiver); statusRegistered = false; } super.onStop(); }
    @Override protected void onDestroy() { paginationGeneration++; if (statusRegistered) unregisterReceiver(statusReceiver); handler.removeCallbacksAndMessages(null); io.shutdownNow(); openIo.shutdownNow(); pageIo.shutdownNow(); coverIo.shutdownNow(); saveIo.shutdown(); super.onDestroy(); }
}
