package com.lnreader.app;

import android.content.Context;
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
    interface Listener { void positionChanged(int offset, int page, int pageCount); void turn(int direction); }
    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private CharSequence text = "";
    private StaticLayout layout;
    private List<Integer> starts = Collections.emptyList();
    private int page, desiredOffset;
    private float leading = 1.5f;
    private final Listener listener;

    public PagedReaderView(Context context) { this(context, new Listener() { public void positionChanged(int offset, int page, int count) {} public void turn(int direction) {} }); }

    PagedReaderView(Context context, Listener listener) {
        super(context); this.listener = listener; setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        int x = Math.round(24 * getResources().getDisplayMetrics().density);
        int y = Math.round(20 * getResources().getDisplayMetrics().density);
        setPadding(x, y, x, y);
    }
    void configure(float size, Typeface font, int ink, int background, float leading) {
        paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
        paint.setTypeface(font); paint.setColor(ink); setBackgroundColor(background); this.leading = leading;
    }
    void content(CharSequence value, int offset) { text = value; desiredOffset = Math.max(0, Math.min(value.length(), offset)); relayout(); }
    int offset() { return layout == null || starts.isEmpty() ? desiredOffset : layout.getLineStart(starts.get(page)); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { desiredOffset = offset(); relayout(); }
    private void relayout() {
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
        int target = layout.getLineForOffset(Math.min(text.length(), desiredOffset)); page = 0;
        for (int i = 0; i < starts.size(); i++) if (starts.get(i) <= target) page = i;
        changed();
    }
    boolean turnPage(int direction) {
        int next = page + direction;
        if (layout == null || next < 0 || next >= starts.size()) return false;
        page = next; desiredOffset = offset(); changed();
        return true;
    }
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
