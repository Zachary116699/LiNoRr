package com.lnreader.app;

import android.graphics.Canvas;
import android.graphics.Point;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ListView;
import java.util.*;

/** Move a gap through stable list rows while keeping the dragged preview on its original X. */
final class ShelfReorder implements View.OnDragListener {
    interface Model {
        int indexOf(Object item);
        int count();
        default int firstMovable() { return 2; }
        void move(Object item, int target);
        int background();
    }
    private final FrameLayout overlay;
    private final ListView list;
    private final Model model;
    private final Map<View, Float> shifts = new WeakHashMap<>();
    private Object moving;
    private View preview;
    private int from, target, height;
    private boolean dropped;
    ShelfReorder(FrameLayout overlay, ListView list, Model model) { this.overlay = overlay; this.list = list; this.model = model; list.setOnDragListener(this); }
    private float shift(int index) {
        if (moving == null || dropped) return 0;
        if (from < target && index > from && index <= target) return -height;
        if (from > target && index >= target && index < from) return height;
        return 0;
    }
    void bind(View row, Object item, int index) {
        row.setAlpha(item == moving ? 0 : 1); row.setTranslationY(shift(index));
        shifts.put(row, shift(index));
    }
    boolean start(View handle, View line, Object item) {
        if (moving != null || model.indexOf(item) < model.firstMovable()) return false;
        moving = item; from = target = model.indexOf(item); height = line.getHeight(); dropped = false; shifts.clear();
        preview = new View(line.getContext()) {
            @Override protected void onDraw(Canvas canvas) { canvas.drawColor(model.background()); line.draw(canvas); }
        };
        preview.setContentDescription("书架拖动预览"); preview.setElevation(8 * line.getResources().getDisplayMetrics().density);
        int[] origin = new int[2], base = new int[2]; line.getLocationInWindow(origin); overlay.getLocationInWindow(base);
        overlay.addView(preview, new FrameLayout.LayoutParams(line.getWidth(), height));
        preview.setX(origin[0] - base[0]); preview.setY(origin[1] - base[1]); ((View) line.getParent()).setAlpha(0);
        boolean started = handle.startDragAndDrop(null, new View.DragShadowBuilder() {
            @Override public void onProvideShadowMetrics(Point size, Point touch) { size.set(1, 1); touch.set(0, 0); }
            @Override public void onDrawShadow(Canvas canvas) {}
        }, item, 0);
        if (!started) cleanup(); return started;
    }
    private void animateGap() {
        int first = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i); float destination = shift(first + i);
            row.setAlpha(first + i == from && !dropped ? 0 : 1);
            if (shifts.containsKey(row) && shifts.get(row) == destination) continue;
            shifts.put(row, destination);
            row.animate().translationY(destination).setDuration(130).setInterpolator(new DecelerateInterpolator()).start();
        }
    }
    private void cleanup() {
        if (preview != null) { preview.animate().cancel(); overlay.removeView(preview); }
        moving = null; preview = null; dropped = false; shifts.clear();
        for (int i = 0; i < list.getChildCount(); i++) { View row = list.getChildAt(i); row.animate().cancel(); row.setTranslationY(0); row.setAlpha(1); }
    }
    private void settle() {
        if (preview == null) { cleanup(); return; }
        int index = model.indexOf(moving), local = index - list.getFirstVisiblePosition();
        if (local < 0 || local >= list.getChildCount()) { cleanup(); return; }
        View row = list.getChildAt(local); int[] origin = new int[2], base = new int[2]; row.getLocationInWindow(origin); overlay.getLocationInWindow(base);
        preview.animate().y(origin[1] - base[1]).setDuration(130).setInterpolator(new DecelerateInterpolator()).withEndAction(this::cleanup).start();
    }
    @Override public boolean onDrag(View view, DragEvent event) {
        // cancelDragAndDrop may clear the local payload before delivering the end event.
        if (moving == null || event.getAction() != DragEvent.ACTION_DRAG_ENDED && event.getLocalState() != moving) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION: {
                int[] origin = new int[2], base = new int[2]; list.getLocationInWindow(origin); overlay.getLocationInWindow(base);
                preview.setY(origin[1] - base[1] + event.getY() - height / 2f);
                // Hit the original slots, not the animated rows passing underneath the finger.
                int index = ListView.INVALID_POSITION;
                for (int i = 0; i < list.getChildCount(); i++) { View row = list.getChildAt(i); if (event.getY() >= row.getTop() && event.getY() < row.getBottom()) { index = list.getFirstVisiblePosition() + i; break; } }
                if (index == ListView.INVALID_POSITION) index = event.getY() < 0 ? model.firstMovable() : model.count() - 1;
                target = Math.max(model.firstMovable(), Math.min(model.count() - 1, index)); animateGap();
                float density = list.getResources().getDisplayMetrics().density;
                if (event.getY() < 48 * density) list.smoothScrollBy(-(int) (24 * density), 80);
                else if (event.getY() > list.getHeight() - 48 * density) list.smoothScrollBy((int) (24 * density), 80);
                return true;
            }
            case DragEvent.ACTION_DROP:
                dropped = true; model.move(moving, target);
                list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() { list.getViewTreeObserver().removeOnPreDrawListener(this); if (moving != null) settle(); return true; }
                });
                list.requestLayout(); return true;
            case DragEvent.ACTION_DRAG_ENDED:
                if (!dropped) { target = from; animateGap(); settle(); } return true;
            default: return true;
        }
    }
}
