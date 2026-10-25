package com.lnreader.app;

/** Horizontal intent in a thumb arc; vertical travel never gates a page turn. */
final class ThumbSwipe {
    private final float noise, reverseNoise;
    private float origin, extreme, furthest;
    private int direction;
    private boolean returning;
    ThumbSwipe(float density) { noise = 1.5f * density; reverseNoise = 3 * density; }
    void reset(float x) { origin = x; extreme = furthest = 0; direction = 0; returning = false; }
    int direction() { return direction; }
    float distance(float x) { return direction == 0 ? 0 : -direction * (x - origin); }
    boolean commit(float x) { return direction != 0 && !returning && distance(x) > 0; }
    void move(float x) {
        float dx = x - origin;
        if (direction == 0) {
            if (Math.abs(dx) <= noise) return;
            direction = dx < 0 ? 1 : -1; extreme = furthest = Math.abs(dx); return;
        }
        float distance = distance(x);
        // A small initial hook must not lock out the opposite, dominant part of an arc.
        if (distance < -Math.max(noise, furthest)) { direction = -direction; extreme = furthest = -distance; returning = false; return; }
        furthest = Math.max(furthest, distance);
        if (returning) {
            if (distance > extreme + reverseNoise) { returning = false; extreme = distance; }
            else extreme = Math.min(extreme, distance);
        } else {
            if (distance < extreme - reverseNoise) { returning = true; extreme = distance; }
            else extreme = Math.max(extreme, distance);
        }
    }
}
