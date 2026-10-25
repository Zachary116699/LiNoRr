package com.lnreader.app;

import android.content.Context;
import android.view.*;
import android.widget.*;

/** Track gestures even when the child ScrollView consumes its moves. */
final class ShelfSurface extends FrameLayout {
    private final Runnable open;
    private final int direction;
    private float x, y;
    private boolean tracking, multiTouch, moved;
    private ScrollView scroll;
    private int scrollStart;
    private VelocityTracker velocity;
    public ShelfSurface(Context context) { this(context, () -> {}); }
    ShelfSurface(Context context, Runnable open) { this(context, open, 1); }
    ShelfSurface(Context context, Runnable open, int direction) { super(context); this.open = open; this.direction = direction; }
    void scrollChild(ScrollView view) { scroll = view; }
    private void release() { if (velocity != null) { velocity.recycle(); velocity = null; } }
    private boolean editing(View view, float x, float y) {
        if (view.getVisibility() != VISIBLE) return false;
        if (view instanceof EditText || view instanceof HorizontalScrollView) return true;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View child = group.getChildAt(i); float cx = x + view.getScrollX() - child.getLeft(), cy = y + view.getScrollY() - child.getTop(); if (cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight() && editing(child, cx, cy)) return true; } }
        return false;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { release(); x = event.getX(); y = event.getY(); multiTouch = false; moved = false; scrollStart = scroll == null ? 0 : scroll.getScrollY(); tracking = !editing(this, x, y); if (tracking) velocity = VelocityTracker.obtain(); }
        if (velocity != null) velocity.addMovement(event);
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (Math.abs(event.getX() - x) > slop || Math.abs(event.getY() - y) > slop) moved = true;
        if (event.getPointerCount() > 1) multiTouch = true;
        if (action == MotionEvent.ACTION_CANCEL) { tracking = false; release(); }
        if (action == MotionEvent.ACTION_UP) {
            float dx = event.getX() - x, dy = event.getY() - y, speed = 0;
            if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(); } release();
            ViewConfiguration config = ViewConfiguration.get(getContext());
            boolean swipe = direction * dx >= Math.max(slop * 1.25f, 14 * getResources().getDisplayMetrics().density) || direction * dx > slop && direction * speed >= config.getScaledMinimumFlingVelocity() * 2;
            if (tracking && !multiTouch && swipe && direction * dx > Math.abs(dy) * 1.1f) {
                tracking = false; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); performClick(); return true;
            }
            // A cover remains clickable after a small in-bounds swipe unless explicitly cancelled.
            // Let an actively scrolled ScrollView receive UP so its inertia is preserved.
            if (tracking && (multiTouch || moved && (Math.abs(dx) > Math.abs(dy) || direction == 1 && (scroll == null || scroll.getScrollY() == scrollStart)))) {
                tracking = false; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); return true;
            }
            tracking = false;
        }
        boolean handled = super.dispatchTouchEvent(event);
        return tracking || handled;
    }
    @Override protected void onDetachedFromWindow() { release(); super.onDetachedFromWindow(); }
    @Override public boolean performClick() { super.performClick(); open.run(); return true; }
}
