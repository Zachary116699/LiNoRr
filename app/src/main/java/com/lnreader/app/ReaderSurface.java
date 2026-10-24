package com.lnreader.app;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** Tap zones leave visible controls and long-press text selection alone. */
final class ReaderSurface extends FrameLayout {
    interface Listener { void tap(float fraction); void turn(int direction); }
    private final Listener listener;
    private final boolean paged;
    private final int slop;
    private View header, footer;
    private ScrollView scroll;
    private float downX, downY;
    private long downAt;
    private boolean tracking, moved, atTop, atBottom;
    private boolean multiTouch;
    private VelocityTracker velocity;
    private void releaseVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
    @Override protected void onDetachedFromWindow() { releaseVelocity(); super.onDetachedFromWindow(); }
    public ReaderSurface(Context context) { this(context, true, new Listener() { public void tap(float fraction) {} public void turn(int direction) {} }); }
    ReaderSurface(Context context, boolean paged, Listener listener) {
        super(context); this.paged = paged; this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }
    void overlays(View header, View footer) { this.header = header; this.footer = footer; }
    void scrollChild(ScrollView child) { scroll = child; }
    private boolean inside(View v, float x, float y) { return v != null && v.getVisibility() == VISIBLE && x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom(); }
    private void cancelChildren(MotionEvent event) { MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); downAt = event.getEventTime(); moved = false; multiTouch = false;
            releaseVelocity(); velocity = VelocityTracker.obtain();
            tracking = !inside(header, downX, downY) && !inside(footer, downX, downY);
            atTop = scroll == null || !scroll.canScrollVertically(-1);
            atBottom = scroll == null || !scroll.canScrollVertically(1);
        }
        if (!tracking) { releaseVelocity(); return super.dispatchTouchEvent(event); }
        if (velocity != null) velocity.addMovement(event);
        if (event.getPointerCount() > 1) multiTouch = true;
        if (action == MotionEvent.ACTION_CANCEL) { releaseVelocity(); tracking = false; return super.dispatchTouchEvent(event); }
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (Math.abs(dx) > slop || Math.abs(dy) > slop || event.getPointerCount() > 1) moved = true;
        if (action == MotionEvent.ACTION_UP) {
            float speed = 0;
            if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = Math.abs(velocity.getXVelocity()); releaseVelocity(); }
            if (multiTouch) return super.dispatchTouchEvent(event);
            if (!moved && event.getEventTime() - downAt < ViewConfiguration.getLongPressTimeout()) {
                cancelChildren(event); listener.tap(event.getX() / Math.max(1, getWidth())); return true;
            }
            float threshold = 48 * getResources().getDisplayMetrics().density;
            float distance = Math.max(slop * 1.25f, 14 * getResources().getDisplayMetrics().density);
            boolean fling = Math.abs(dx) > slop && speed >= ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity() * 2;
            if (paged && (Math.abs(dx) >= distance || fling) && Math.abs(dx) > Math.abs(dy) * 1.1f) {
                cancelChildren(event); listener.turn(dx < 0 ? 1 : -1); return true;
            }
            if (!paged && Math.abs(dy) > threshold && Math.abs(dy) > Math.abs(dx) * 1.2f && (dy < 0 ? atBottom : atTop)) {
                cancelChildren(event); listener.turn(dy < 0 ? 1 : -1); return true;
            }
        }
        boolean handled = super.dispatchTouchEvent(event);
        return paged || handled;
    }
}
