package com.lnreader.app;

import android.content.Context;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.SeekBar;

/** Track taps are inert; only a gesture beginning on the thumb can seek. */
final class ThumbSeekBar extends SeekBar {
    private boolean tracking, commit, moved;
    private int original;
    private float grabOffset, startX;
    ThumbSeekBar(Context context) { super(context); }
    RectF thumbArea() {
        RectF area = new RectF(getThumb().getBounds());
        area.offset(getPaddingLeft() - getThumbOffset(), getPaddingTop());
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) area.set(getWidth() - area.right, area.top, getWidth() - area.left, area.bottom);
        return area;
    }
    boolean isTouchTracking() { return tracking; }
    boolean isDraggingThumb() { return tracking && moved; }
    boolean shouldCommit() { return commit && getProgress() != original; }
    @Override public boolean performClick() { return super.performClick(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            RectF target = thumbArea(); float center = target.centerX(), extra = 6 * getResources().getDisplayMetrics().density;
            target.inset(-extra, -extra);
            tracking = isEnabled() && target.contains(event.getX(), event.getY()); commit = false;
            if (!tracking) return true; // Consume the entire track gesture without reaching the reader.
            original = getProgress(); startX = event.getX(); moved = false; grabOffset = startX - center;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!tracking) return true;
        if (Math.abs(event.getX() - startX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
        boolean canceled = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_UP && !moved;
        commit = action == MotionEvent.ACTION_UP && !canceled;
        if (canceled) setProgress(original);
        MotionEvent adjusted = MotionEvent.obtain(event);
        adjusted.offsetLocation(-grabOffset, 0);
        if (canceled) adjusted.setAction(MotionEvent.ACTION_CANCEL);
        boolean handled = super.onTouchEvent(adjusted); adjusted.recycle();
        if (commit) performClick();
        if (commit || canceled) { tracking = false; commit = false; getParent().requestDisallowInterceptTouchEvent(false); }
        return handled;
    }
}
