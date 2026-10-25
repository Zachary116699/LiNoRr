package com.lnreader.app;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
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

    public PagedReaderView(Context context) { this(context, new Listener() { public void positionChanged(int offset, int page, int count) {} public void turn(int direction) {} }); }

    PagedReaderView(Context context, Listener listener) {
        super(context); this.listener = listener; setFocusable(true);
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
        text = value; desiredOffset = Math.max(0, Math.min(value.length(), offset));
        // Reuse the boundary preview when release commits into that section.
        if (adjacentFrame != null && android.text.TextUtils.equals(value, adjacentFrame.layout.getText())) {
            layout = adjacentFrame.layout; starts = adjacentFrame.starts; selectPage(); changed();
        } else relayout();
    }
    int offset() { return layout == null || starts.isEmpty() ? desiredOffset : layout.getLineStart(starts.get(page)); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { desiredOffset = offset(); relayout(); listener.layoutChanged(); }
    private void relayout() {
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
    @Override protected void onDetachedFromWindow() { finishAnimation(); super.onDetachedFromWindow(); }
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
        canvas.translate(getPaddingLeft(), getPaddingTop() - top); layout.draw(canvas); canvas.restore();
    }
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
