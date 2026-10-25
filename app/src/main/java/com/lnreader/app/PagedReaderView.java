package com.lnreader.app;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/** Native, line-aligned pages of a bounded chapter, independent of menu visibility. */
final class PagedReaderView extends View {
    interface Listener { void positionChanged(int offset, int page, int pageCount); void turn(int direction); default void layoutChanged() {} }
    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private CharSequence text = "";
    private StaticLayout layout;
    private List<Integer> starts = Collections.emptyList();
    private int page, desiredOffset;
    private float leading = 1.5f;
    private final Listener listener;
    private Bitmap previousPage;
    private Frame dragFrom, dragTo, adjacentFrame;
    private static final class Frame {
        final StaticLayout layout; final List<Integer> starts; final int page;
        Frame(StaticLayout layout, List<Integer> starts, int page) { this.layout = layout; this.starts = starts; this.page = page; }
    }
    private boolean dragging;
    private Runnable dragCommit;
    private ValueAnimator animator;
    private float fraction = 1;
    private int direction;
    private final GestureDetector selectionGesture;
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ActionMode selectionMode;
    private int selectionStart = -1, selectionEnd = -1, selectionHandle;
    private float touchX, touchY;

    public PagedReaderView(Context context) { this(context, new Listener() { public void positionChanged(int offset, int page, int count) {} public void turn(int direction) {} }); }

    PagedReaderView(Context context, Listener listener) {
        super(context); this.listener = listener; setFocusable(true);
        setLongClickable(true);
        selectionGesture = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { return true; }
            @Override public void onLongPress(MotionEvent event) { touchX = event.getX(); touchY = event.getY(); performLongClick(); }
        });
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        int x = Math.round(24 * getResources().getDisplayMetrics().density);
        int y = Math.round(20 * getResources().getDisplayMetrics().density);
        setPadding(x, y, x, y);
    }
    void configure(float size, Typeface font, int ink, int background, float leading) {
        adjacentFrame = null;
        paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(font); paint.setColor(ink); setBackgroundColor(background); this.leading = leading;
    }
    void colors(int ink, int background) { finishAnimation(); paint.setColor(ink); setBackgroundColor(background); invalidate(); }
    PageCounter counter() {
        return new PageCounter(new TextPaint(paint), getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom(), leading);
    }
    static final class PageCounter {
        private final TextPaint paint;
        private final int width, height;
        private final float leading;
        PageCounter(TextPaint paint, int width, int height, float leading) { this.paint = paint; this.width = width; this.height = height; this.leading = leading; }
        int count(CharSequence text) {
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0, leading).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
            int n = layout.getLineCount(); int[] tops = new int[n], bottoms = new int[n];
            for (int i = 0; i < n; i++) { tops[i] = layout.getLineTop(i); bottoms[i] = layout.getLineBottom(i); }
            return Pagination.starts(tops, bottoms, Math.max(1, height)).size();
        }
    }
    void content(CharSequence value, int offset) {
        clearSelection();
        text = value; desiredOffset = Math.max(0, Math.min(value.length(), offset));
        // Reuse the boundary preview when release commits into that section.
        if (adjacentFrame != null && android.text.TextUtils.equals(value, adjacentFrame.layout.getText())) {
            layout = adjacentFrame.layout; starts = adjacentFrame.starts; selectPage(); changed();
        } else relayout();
    }
    int offset() { return layout == null || starts.isEmpty() ? desiredOffset : layout.getLineStart(starts.get(page)); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { desiredOffset = offset(); relayout(); listener.layoutChanged(); }
    private void relayout() {
        clearSelection();
        adjacentFrame = null;
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(0, leading)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
        int count = layout.getLineCount(); int[] tops = new int[count], bottoms = new int[count];
        for (int i = 0; i < count; i++) { tops[i] = layout.getLineTop(i); bottoms[i] = layout.getLineBottom(i); }
        starts = Pagination.starts(tops, bottoms, height);
        selectPage();
        changed();
    }
    private void selectPage() {
        int target = layout.getLineForOffset(Math.min(text.length(), desiredOffset)); page = 0;
        for (int i = 0; i < starts.size(); i++) if (starts.get(i) <= target) page = i;
    }
    boolean turnPage(int direction) {
        clearSelection();
        int next = page + direction;
        if (layout == null || next < 0 || next >= starts.size()) return false;
        page = next; desiredOffset = offset(); changed();
        return true;
    }
    boolean canTurnPage(int direction) { return ready() && page + direction >= 0 && page + direction < starts.size(); }
    void slide(int direction, Runnable change) {
        if (!ValueAnimator.areAnimatorsEnabled() || getWidth() <= 0 || getHeight() <= 0) { finishAnimation(); change.run(); return; }
        // Only viewport snapshots, including chapter boundaries; never snapshot the book.
        Bitmap snapshot;
        try { snapshot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError error) { finishAnimation(); change.run(); return; }
        // Capture the current moving frame before cancelling, so rapid input stays continuous.
        draw(new Canvas(snapshot)); finishAnimation(); previousPage = snapshot; this.direction = direction; fraction = 0;
        change.run();
        animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(140);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(value -> { fraction = (float) value.getAnimatedValue(); invalidate(); });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { recycleSnapshot(); }
        });
        animator.start();
    }
    boolean beginDrag(int direction, CharSequence adjacent, Runnable commit) {
        clearSelection();
        finishAnimation(); if (!ready() || getWidth() <= 0 || getHeight() <= 0) return false;
        dragFrom = new Frame(layout, starts, page);
        if (canTurnPage(direction)) dragTo = new Frame(layout, starts, page + direction);
        else {
            if (adjacent == null) { dragFrom = null; return false; }
            if (adjacentFrame != null && android.text.TextUtils.equals(adjacent, adjacentFrame.layout.getText())) dragTo = new Frame(adjacentFrame.layout, adjacentFrame.starts, direction > 0 ? 0 : adjacentFrame.starts.size() - 1);
            else {
                StaticLayout next = StaticLayout.Builder.obtain(adjacent, 0, adjacent.length(), paint, getWidth() - getPaddingLeft() - getPaddingRight())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0, leading).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
                int count = next.getLineCount(); int[] tops = new int[count], bottoms = new int[count];
                for (int i = 0; i < count; i++) { tops[i] = next.getLineTop(i); bottoms[i] = next.getLineBottom(i); }
                List<Integer> pages = Pagination.starts(tops, bottoms, getHeight() - getPaddingTop() - getPaddingBottom());
                dragTo = new Frame(next, pages, direction > 0 ? 0 : pages.size() - 1);
                adjacentFrame = dragTo;
            }
        }
        this.direction = direction; fraction = 0; dragging = true; dragCommit = commit; invalidate(); return true;
    }
    void drag(float pixels) { if (dragging) { fraction = Math.max(0, Math.min(1, pixels / Math.max(1, getWidth()))); invalidate(); } }
    void endDrag(boolean commit) {
        if (!dragging) return; dragging = false; Runnable action = dragCommit; dragCommit = null;
        if (commit && action != null) action.run();
        float target = commit ? 1 : 0;
        if (!ValueAnimator.areAnimatorsEnabled()) { recycleSnapshot(); return; }
        animator = ValueAnimator.ofFloat(fraction, target); animator.setDuration(Math.max(35, Math.round(90 * Math.abs(target - fraction))));
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator()); animator.addUpdateListener(value -> { fraction = (float) value.getAnimatedValue(); invalidate(); });
        animator.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { recycleSnapshot(); } }); animator.start();
    }
    float dragFraction() { return fraction; }
    private void recycleSnapshot() { dragging = false; dragCommit = null; dragFrom = null; dragTo = null; if (previousPage != null) { previousPage.recycle(); previousPage = null; } fraction = 1; invalidate(); }
    private void finishAnimation() { if (animator != null) { animator.cancel(); animator = null; } recycleSnapshot(); }
    @Override protected void onDetachedFromWindow() { clearSelection(); finishAnimation(); super.onDetachedFromWindow(); }
    boolean ready() { return layout != null && !starts.isEmpty(); }
    private void changed() {
        invalidate();
        if (layout == null || starts.isEmpty()) return;
        int end = page + 1 < starts.size() ? layout.getLineStart(starts.get(page + 1)) : text.length();
        setContentDescription("正文，第" + (page + 1) + "/" + starts.size() + "页\n" + text.subSequence(offset(), end));
        listener.positionChanged(offset(), page + 1, starts.size());
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (layout == null || starts.isEmpty()) return;
        if (dragFrom != null && dragTo != null) {
            canvas.save(); canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.save(); canvas.translate(-direction * fraction * getWidth(), 0); drawPage(canvas, dragFrom.layout, dragFrom.starts, dragFrom.page); canvas.restore();
            canvas.translate(direction * (1 - fraction) * getWidth(), 0); drawPage(canvas, dragTo.layout, dragTo.starts, dragTo.page); canvas.restore();
        } else if (previousPage != null && fraction < 1) {
            canvas.drawBitmap(previousPage, -direction * fraction * getWidth(), 0, null);
            canvas.save(); canvas.translate(direction * (1 - fraction) * getWidth(), 0);
            drawPage(canvas); canvas.restore();
        } else drawPage(canvas);
    }
    private void drawPage(Canvas canvas) {
        drawPage(canvas, layout, starts, page);
    }
    private void drawPage(Canvas canvas, StaticLayout layout, List<Integer> starts, int page) {
        int first = starts.get(page), last = page + 1 < starts.size() ? starts.get(page + 1) - 1 : layout.getLineCount() - 1;
        int top = layout.getLineTop(first), bottom = layout.getLineBottom(last);
        canvas.save();
        canvas.clipRect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(),
            Math.min(getHeight() - getPaddingBottom(), getPaddingTop() + bottom - top));
        canvas.translate(getPaddingLeft(), getPaddingTop() - top);
        if (selectionActive() && layout == this.layout && page == this.page) {
            Path path = new Path(); layout.getSelectionPath(selectionStart, selectionEnd, path);
            selectionPaint.setColor((paint.getColor() & 0x00ffffff) | 0x33000000); canvas.drawPath(path, selectionPaint);
        }
        layout.draw(canvas); canvas.restore();
        if (selectionActive() && layout == this.layout && page == this.page) {
            selectionPaint.setColor(paint.getColor()); float radius = 6 * getResources().getDisplayMetrics().density;
            for (int endpoint : new int[]{selectionStart, selectionEnd}) {
                float[] point = handlePoint(endpoint, endpoint == selectionEnd);
                canvas.drawRect(point[0] - radius / 6, point[1] - radius * 2, point[0] + radius / 6, point[1], selectionPaint);
                canvas.drawCircle(point[0], point[1], radius, selectionPaint);
            }
        }
    }
    boolean selectionActive() { return selectionStart >= 0 && selectionEnd > selectionStart; }
    String selectedText() { return selectionActive() ? text.subSequence(selectionStart, selectionEnd).toString() : ""; }
    void clearSelection() {
        ActionMode old = selectionMode; selectionMode = null; selectionStart = selectionEnd = -1; selectionHandle = 0;
        if (old != null) old.finish(); invalidate();
    }
    private int pageEnd() { return page + 1 < starts.size() ? layout.getLineStart(starts.get(page + 1)) : text.length(); }
    private int textOffset(float x, float y) {
        int top = layout.getLineTop(starts.get(page));
        int line = layout.getLineForVertical(Math.round(y - getPaddingTop() + top));
        int last = page + 1 < starts.size() ? starts.get(page + 1) - 1 : layout.getLineCount() - 1;
        line = Math.max(starts.get(page), Math.min(last, line));
        return Math.max(offset(), Math.min(pageEnd(), layout.getOffsetForHorizontal(line, x - getPaddingLeft())));
    }
    private float[] handlePoint(int endpoint, boolean end) {
        int line = layout.getLineForOffset(Math.max(0, end ? endpoint - 1 : endpoint));
        float x = end && layout.getLineForOffset(endpoint) > line ? layout.getLineRight(line) : layout.getPrimaryHorizontal(endpoint);
        return new float[]{getPaddingLeft() + x, getPaddingTop() + layout.getLineBottom(line) - layout.getLineTop(starts.get(page)) + 8 * getResources().getDisplayMetrics().density};
    }
    @Override public boolean performLongClick() {
        if (!ready() || text.length() == 0) return false;
        finishAnimation(); int at = Math.min(pageEnd() - 1, textOffset(touchX, touchY));
        if (at < offset()) return false;
        java.text.BreakIterator words = java.text.BreakIterator.getWordInstance(Locale.getDefault()); words.setText(text.toString());
        selectionStart = Math.max(offset(), words.isBoundary(at) ? at : words.preceding(at));
        int next = words.following(at); selectionEnd = Math.min(pageEnd(), next < 0 ? text.length() : next);
        if (!selectionActive() || selectedText().trim().isEmpty()) { clearSelection(); return false; }
        getParent().requestDisallowInterceptTouchEvent(true); invalidate();
        selectionMode = startActionMode(new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { menu.add(0, android.R.id.copy, 0, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM); return true; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != android.R.id.copy) return false;
                ((ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("正文", selectedText())); clearSelection(); return true;
            }
            @Override public void onDestroyActionMode(ActionMode mode) { if (selectionMode == mode) { selectionMode = null; selectionStart = selectionEnd = -1; selectionHandle = 0; invalidate(); } }
            @Override public void onGetContentRect(ActionMode mode, View view, Rect out) {
                if (!selectionActive()) { out.setEmpty(); return; }
                Path path = new Path(); layout.getSelectionPath(selectionStart, selectionEnd, path); RectF rect = new RectF(); path.computeBounds(rect, true);
                rect.offset(getPaddingLeft(), getPaddingTop() - layout.getLineTop(starts.get(page))); rect.roundOut(out);
            }
        }, ActionMode.TYPE_FLOATING);
        return true;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        touchX = event.getX(); touchY = event.getY();
        if (selectionActive()) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float[] start = handlePoint(selectionStart, false), end = handlePoint(selectionEnd, true);
                double a = Math.hypot(touchX - start[0], touchY - start[1]), b = Math.hypot(touchX - end[0], touchY - end[1]);
                if (Math.min(a, b) > 28 * getResources().getDisplayMetrics().density) clearSelection(); else selectionHandle = a < b ? -1 : 1;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && selectionHandle != 0) {
                int at = textOffset(touchX, touchY - 8 * getResources().getDisplayMetrics().density);
                if (selectionHandle < 0) selectionStart = Math.min(at, Character.offsetByCodePoints(text, selectionEnd, -1)); else selectionEnd = Math.max(Character.offsetByCodePoints(text, selectionStart, 1), at);
                if (selectionMode != null) selectionMode.invalidateContentRect(); invalidate();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) selectionHandle = 0;
            return true;
        }
        boolean handled = selectionGesture.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && handled) performClick(); return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public CharSequence getAccessibilityClassName() { return PagedReaderView.class.getName(); }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { listener.turn(1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { listener.turn(-1); return true; }
        return super.performAccessibilityAction(action, args);
    }
}
