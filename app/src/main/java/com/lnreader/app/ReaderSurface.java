package com.lnreader.app;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/** Tap zones leave visible controls and long-press text selection alone. */
final class ReaderSurface extends FrameLayout {
    interface Listener { void tap(float fraction); void turn(int direction); default boolean beginDrag(int direction) { return false; } default void drag(float pixels) {} default void endDrag(boolean commit) {} default boolean selecting() { return false; } }
    private final Listener listener;
    private final boolean paged;
    private final int slop;
    private View header, footer;
    private ScrollView scroll;
    private float downX, downY;
    private long downAt;
    private boolean tracking, moved, atTop, atBottom;
    private boolean multiTouch;
    private boolean dragging, swiping;
    private final ThumbSwipe swipe;
    public ReaderSurface(Context context) { this(context, true, new Listener() { public void tap(float fraction) {} public void turn(int direction) {} }); }
    ReaderSurface(Context context, boolean paged, Listener listener) {
        super(context); this.paged = paged; this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipe = new ThumbSwipe(getResources().getDisplayMetrics().density);
    }
    void overlays(View header, View footer) { this.header = header; this.footer = footer; }
    void scrollChild(ScrollView child) { scroll = child; }
    private boolean inside(View v, float x, float y) { return v != null && v.getVisibility() == VISIBLE && x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom(); }
    private void cancelChildren(MotionEvent event) { MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); downAt = event.getEventTime(); moved = false; multiTouch = false; dragging = false; swiping = false;
            swipe.reset(downX);
            tracking = !inside(header, downX, downY) && !inside(footer, downX, downY);
            atTop = scroll == null || !scroll.canScrollVertically(-1);
            atBottom = scroll == null || !scroll.canScrollVertically(1);
        }
        if (!tracking) return super.dispatchTouchEvent(event);
        if (listener.selecting()) { tracking = false; return super.dispatchTouchEvent(event); }
        if (event.getPointerCount() > 1) multiTouch = true;
        if (action == MotionEvent.ACTION_CANCEL) { if (dragging) listener.endDrag(false); dragging = false; swiping = false; tracking = false; return super.dispatchTouchEvent(event); }
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (Math.abs(dx) > slop || Math.abs(dy) > slop || event.getPointerCount() > 1) moved = true;
        if (swiping && multiTouch) { if (dragging) listener.endDrag(false); dragging = false; swiping = false; tracking = false; cancelChildren(event); return true; }
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && paged && !multiTouch) {
            int previousDirection = swipe.direction();
            if (swiping || event.getEventTime() - downAt < ViewConfiguration.getLongPressTimeout()) {
                // Batched events can contain the outward arc even when the last sample curls back.
                for (int i = 0; i < event.getHistorySize(); i++) swipe.move(event.getHistoricalX(i));
                swipe.move(event.getX());
            }
            if (!swiping && swipe.direction() != 0) {
                swiping = true; dragging = listener.beginDrag(swipe.direction()); cancelChildren(event);
            } else if (swiping && swipe.direction() != previousDirection) {
                if (dragging) listener.endDrag(false);
                dragging = listener.beginDrag(swipe.direction());
            }
            if (swiping) { if (dragging) listener.drag(Math.max(0, swipe.distance(event.getX()))); if (action == MotionEvent.ACTION_MOVE) return true; }
        }
        if (action == MotionEvent.ACTION_UP) {
            if (swiping) {
                boolean commit = swipe.commit(event.getX());
                if (dragging) { listener.drag(Math.max(0, swipe.distance(event.getX()))); listener.endDrag(commit); } else if (commit) listener.turn(swipe.direction());
                dragging = false; swiping = false; tracking = false; return true;
            }
            if (multiTouch) return super.dispatchTouchEvent(event);
            if (!moved && event.getEventTime() - downAt < ViewConfiguration.getLongPressTimeout()) {
                cancelChildren(event); listener.tap(event.getX() / Math.max(1, getWidth())); return true;
            }
            float threshold = 48 * getResources().getDisplayMetrics().density;
            if (!paged && Math.abs(dy) > threshold && Math.abs(dy) > Math.abs(dx) * 1.2f && (dy < 0 ? atBottom : atTop)) {
                cancelChildren(event); listener.turn(dy < 0 ? 1 : -1); return true;
            }
        }
        boolean handled = super.dispatchTouchEvent(event);
        return paged || handled;
    }
}
