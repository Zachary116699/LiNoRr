package com.lnreader.app;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

/** Horizontal drawer gesture leaves vertical scrolling and text input alone. */
final class ShelfSurface extends FrameLayout {
    private final Runnable open;
    private float x, y;
    private boolean candidate, opened;
    public ShelfSurface(Context context) { this(context, () -> {}); }
    ShelfSurface(Context context, Runnable open) { super(context); this.open = open; }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x = event.getX(); y = event.getY(); candidate = x <= 80 * getResources().getDisplayMetrics().density; opened = false; }
        if (event.getPointerCount() > 1) candidate = false;
        float dx = event.getX() - x, dy = event.getY() - y;
        if (candidate && event.getActionMasked() == MotionEvent.ACTION_MOVE && dx > 64 * getResources().getDisplayMetrics().density && dx > Math.abs(dy) * 2) { opened = true; candidate = false; return true; }
        if (Math.abs(dy) > 24 * getResources().getDisplayMetrics().density) candidate = false;
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) { if (opened) { if (event.getActionMasked() == MotionEvent.ACTION_UP) { opened = false; performClick(); } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) opened = false; return true; } return super.onTouchEvent(event); }
    @Override public boolean performClick() { super.performClick(); open.run(); return true; }
}
