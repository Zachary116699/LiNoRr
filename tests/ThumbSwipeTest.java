package com.lnreader.app;

public class ThumbSwipeTest {
    public static void main(String[] args) {
        for (float density : new float[]{1, 2.75f, 3.5f}) for (int sign : new int[]{-1, 1}) {
            ThumbSwipe gesture = new ThumbSwipe(density); gesture.reset(100 * density);
            // Short, steep thumb arcs with a tiny hook at release, in either hand.
            for (float x : new float[]{.4f, 1, 1.7f, 2.3f, 4.1f, 4.8f, 4.2f}) gesture.move((100 + sign * x) * density);
            assert gesture.direction() == -sign && gesture.commit((100 + sign * 4.2f) * density) : "Thumb arc lost";
            gesture.reset(0); gesture.move(sign * .6f * density); assert gesture.commit(sign * .6f * density) : "Tiny swipe lost";
            gesture.reset(0); for (float x : new float[]{.1f, -.2f, .4f, -.1f}) gesture.move(x * density);
            assert gesture.direction() == 0 : "Tap jitter became a swipe";
            gesture.reset(0); gesture.move(sign * 6 * density); gesture.move(sign * 1.5f * density);
            assert !gesture.commit(sign * 1.5f * density) : "Deliberate return did not cancel";
            gesture.move(sign * 6 * density); assert gesture.commit(sign * 6 * density) : "Resumed direction lost";
            gesture.reset(0); gesture.move(-sign * 2 * density); gesture.move(sign * 6 * density);
            assert gesture.direction() == -sign && gesture.commit(sign * 6 * density) : "Initial hook locked the wrong direction";
        }
        System.out.println("PASS: mirrored thumb arcs, tiny swipes, release hooks, tap noise, cancellation and resumed direction at three densities");
    }
}
