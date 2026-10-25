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
            case "expand_all":
            case "collapse_all":
                for (int y = 6; y <= 15; y += 9) { boolean down = kind.equals("expand_all"); p.moveTo(5, down ? y : y + 5); p.lineTo(12, down ? y + 5 : y); p.lineTo(19, down ? y : y + 5); canvas.drawPath(p, paint); p.reset(); } break;
            case "search":
                canvas.drawCircle(10, 10, 7, paint); canvas.drawLine(15, 15, 22, 22, paint); break;
            case "drag":
                paint.setStyle(Paint.Style.FILL); for (int x = 8; x <= 16; x += 8) for (int y = 5; y <= 19; y += 7) canvas.drawCircle(x, y, 1.3f, paint); break;
            case "edit":
                p.moveTo(4, 16); p.lineTo(16, 4); p.lineTo(20, 8); p.lineTo(8, 20); p.lineTo(3, 21); p.close(); canvas.drawPath(p, paint); canvas.drawLine(13, 7, 17, 11, paint); break;
            case "trash":
                canvas.drawLine(3, 5, 21, 5, paint); canvas.drawLine(9, 2, 15, 2, paint); p.moveTo(5, 5); p.lineTo(6, 22); p.lineTo(18, 22); p.lineTo(19, 5); canvas.drawPath(p, paint); canvas.drawLine(10, 9, 10, 18, paint); canvas.drawLine(14, 9, 14, 18, paint); break;
            case "shelf":
                canvas.drawLine(2, 21, 22, 21, paint); canvas.drawRoundRect(3, 4, 8, 19, 1, 1, paint); canvas.drawRoundRect(10, 3, 14, 19, 1, 1, paint); p.moveTo(16, 5); p.lineTo(20, 4); p.lineTo(23, 18); p.lineTo(19, 19); p.close(); canvas.drawPath(p, paint); break;
            case "grid":
                for (int x = 3; x <= 13; x += 10) for (int y = 3; y <= 13; y += 10) canvas.drawRoundRect(x, y, x + 7, y + 7, 1, 1, paint); break;
            case "list":
                for (int y = 4; y <= 18; y += 7) { canvas.drawRoundRect(2, y, 6, y + 4, .5f, .5f, paint); canvas.drawLine(10, y + 2, 22, y + 2, paint); } break;
            case "filter":
                for (int y = 5; y <= 19; y += 7) { canvas.drawLine(3, y, 21, y, paint); canvas.drawCircle(y == 12 ? 16 : 8, y, 2, paint); } break;
            case "sort":
                canvas.drawLine(5, 3, 5, 21, paint); p.moveTo(2, 17); p.lineTo(5, 21); p.lineTo(8, 17); canvas.drawPath(p, paint); canvas.drawLine(11, 5, 22, 5, paint); canvas.drawLine(11, 12, 19, 12, paint); canvas.drawLine(11, 19, 16, 19, paint); break;
            case "import":
                p.moveTo(3, 15); p.lineTo(3, 21); p.lineTo(21, 21); p.lineTo(21, 15); canvas.drawPath(p, paint); canvas.drawLine(12, 2, 12, 15, paint); p.reset(); p.moveTo(7, 10); p.lineTo(12, 15); p.lineTo(17, 10); canvas.drawPath(p, paint); break;
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
