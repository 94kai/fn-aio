package top.fnaio.direct;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small self-contained line icons for the native shortcut screen. */
final class QuickIcon extends Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stroke;

    QuickIcon(String name, int color) { this(name, color, 1.8f); }
    QuickIcon(String name, int color, float stroke) {
        this.name = name; this.stroke = stroke;
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private void line(Canvas c, float... xy) {
        Path p = new Path(); p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        c.drawPath(p, paint);
    }
    @Override public void draw(Canvas c) {
        Rect r = getBounds(); int save = c.save();
        c.translate(r.left, r.top); c.scale(r.width() / 24f, r.height() / 24f);
        switch (name) {
            case "globe": case "network":
                c.drawCircle(12, 12, 9, paint); c.drawOval(8, 3, 16, 21, paint);
                line(c, 3, 12, 21, 12); line(c, 5, 7, 19, 7); line(c, 5, 17, 19, 17); break;
            case "speaker":
                line(c, 4, 9, 8, 9, 13, 5, 13, 19, 8, 15, 4, 15, 4, 9);
                c.drawArc(10, 5, 20, 19, -55, 110, false, paint);
                c.drawArc(9, 2, 23, 22, -55, 110, false, paint); break;
            case "volume":
                line(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
                c.drawArc(9, 6, 18, 18, -50, 100, false, paint); break;
            case "light":
                c.drawArc(5, 4, 19, 18, 185, 170, false, paint);
                line(c, 5, 12, 4, 16, 20, 16, 19, 12); line(c, 10, 19, 14, 19); break;
            case "music":
                line(c, 10, 17, 10, 5, 19, 3, 19, 15);
                c.drawOval(5, 16, 10, 20, paint); c.drawOval(14, 14, 19, 18, paint); break;
            case "car":
                line(c, 4, 16, 4, 11, 6, 6, 18, 6, 20, 11, 20, 16, 4, 16);
                line(c, 5, 11, 19, 11);
                c.drawCircle(7, 14, 0.9f, paint); c.drawCircle(17, 14, 0.9f, paint);
                line(c, 6, 16, 6, 19); line(c, 18, 16, 18, 19); break;
            case "search": c.drawCircle(10, 10, 6, paint); line(c, 14.5f, 14.5f, 20, 20); break;
            case "list":
                line(c, 8, 6, 20, 6); line(c, 8, 12, 20, 12); line(c, 8, 18, 20, 18);
                c.drawCircle(4, 6, 0.9f, paint); c.drawCircle(4, 12, 0.9f, paint); c.drawCircle(4, 18, 0.9f, paint); break;
            case "play": line(c, 8, 5, 19, 12, 8, 19, 8, 5); break;
            case "stop":
                paint.setStyle(Paint.Style.FILL); c.drawRoundRect(6, 6, 18, 18, 2.5f, 2.5f, paint); paint.setStyle(Paint.Style.STROKE); break;
            case "power": c.drawArc(4, 4, 20, 20, -55, 290, false, paint); line(c, 12, 3, 12, 12); break;
            case "shuffle":
                line(c, 4, 6, 7, 6, 17, 18, 20, 18); line(c, 17, 15, 20, 18, 17, 21);
                line(c, 4, 18, 7, 18, 17, 6, 20, 6); line(c, 17, 3, 20, 6, 17, 9); break;
            case "login": line(c, 13, 4, 20, 4, 20, 20, 13, 20); line(c, 4, 12, 15, 12); line(c, 11, 8, 15, 12, 11, 16); break;
            case "refresh": c.drawArc(4, 4, 20, 20, -45, 260, false, paint); line(c, 16, 3, 20, 4, 20, 8); break;
            case "settings":
                c.drawCircle(12, 12, 7, paint); c.drawCircle(12, 12, 2.5f, paint);
                line(c, 12, 2, 12, 5); line(c, 12, 19, 12, 22);
                line(c, 2, 12, 5, 12); line(c, 19, 12, 22, 12); break;
            case "add": line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case "close": line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
            case "heart":
                Path heart = new Path(); heart.moveTo(12, 20); heart.cubicTo(10, 18, 3, 13, 3, 8); heart.cubicTo(3, 3, 9, 2, 12, 7); heart.cubicTo(15, 2, 21, 3, 21, 8); heart.cubicTo(21, 13, 14, 18, 12, 20); c.drawPath(heart, paint); break;
            case "check": line(c, 5, 12, 10, 17, 19, 7); break;
            case "trash":
                line(c, 4, 7, 20, 7); line(c, 9, 3, 9, 7); line(c, 15, 3, 15, 7);
                line(c, 6, 7, 7, 20, 17, 20, 18, 7); line(c, 10, 11, 10, 16); line(c, 14, 11, 14, 16); break;
            case "chevron": line(c, 9, 5, 16, 12, 9, 19); break;
            default: c.drawCircle(12, 12, 8, paint); break;
        }
        c.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
