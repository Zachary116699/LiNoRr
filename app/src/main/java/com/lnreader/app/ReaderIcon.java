package com.lnreader.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small native line icons; no bitmap assets or icon font required. */
final class ReaderIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private final int width, height, level;
    ReaderIcon(String kind, int color, float density) { this(kind, color, density, -1); }
    ReaderIcon(String kind, int color, float density, int level) {
        this.kind = kind; this.level = level;
        width = Math.round(24 * density);
        height = Math.round((kind.equals("battery") ? 14 : 24) * density);
        paint.setColor(color); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    void color(int color) { paint.setColor(color); invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / (kind.equals("battery") ? 12f : 24f));
        paint.setStyle(Paint.Style.STROKE);
        Path p = new Path();
        switch (kind) {
            case "sun":
                canvas.drawCircle(12, 12, 4, paint);
                for (int i = 0; i < 8; i++) { double angle = Math.PI * i / 4; canvas.drawLine(12 + (float) Math.cos(angle) * 8, 12 + (float) Math.sin(angle) * 8, 12 + (float) Math.cos(angle) * 11, 12 + (float) Math.sin(angle) * 11, paint); } break;
            case "more":
                paint.setStyle(Paint.Style.FILL); for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 1.6f, paint); break;
            case "catalog":
                canvas.drawRoundRect(4, 2, 20, 22, 3, 3, paint);
                for (int y = 7; y <= 17; y += 5) canvas.drawLine(8, y, 16, y, paint);
                break;
            case "bookmark":
                p.moveTo(6, 3); p.lineTo(18, 3); p.lineTo(18, 22); p.lineTo(12, 17); p.lineTo(6, 22); p.close(); canvas.drawPath(p, paint); break;
            case "moon":
                p.moveTo(14, 2); p.cubicTo(9, 11, 13, 16, 22, 14); p.cubicTo(20, 25, 4, 25, 3, 13); p.cubicTo(2, 7, 7, 2, 14, 2); canvas.drawPath(p, paint); break;
            case "settings":
                for (int i = 0; i < 6; i++) { double a = Math.PI * i / 3; float x = 12 + (float) Math.cos(a) * 10, y = 12 + (float) Math.sin(a) * 10; if (i == 0) p.moveTo(x, y); else p.lineTo(x, y); }
                p.close(); canvas.drawPath(p, paint); canvas.drawCircle(12, 12, 3.5f, paint); break;
            case "battery":
                paint.setStrokeWidth(1); canvas.drawRoundRect(1, 1, 21, 11, 1.8f, 1.8f, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(22, 4, 24, 8, .6f, .6f, paint);
                if (level >= 0) canvas.drawRect(3, 3, 3 + 16 * Math.min(100, Math.max(0, level)) / 100f, 9, paint);
                break;
            default: p.moveTo(15, 4); p.lineTo(7, 12); p.lineTo(15, 20); canvas.drawPath(p, paint);
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
}
