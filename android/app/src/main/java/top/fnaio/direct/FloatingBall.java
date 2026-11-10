package top.fnaio.direct;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** A 34dp translucent visual inside a 44dp accessible drag/click target. */
public final class FloatingBall extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean direct, expanded, interacting;
    private LinearGradient gradient;
    public FloatingBall(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setFocusable(true);
        setAlpha(0.72f);
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    public void setDirect(boolean value) { direct = value; updateGradient(); invalidate(); }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); updateGradient();
    }
    private void updateGradient() {
        float x = getWidth() / 2f, y = getHeight() / 2f, radius = dp(17);
        int top = direct ? Color.argb(205, 65, 145, 139) : Color.argb(205, 104, 125, 166);
        int bottom = direct ? Color.argb(185, 30, 103, 104) : Color.argb(185, 52, 70, 106);
        gradient = new LinearGradient(x, y - radius, x, y + radius, top, bottom, Shader.TileMode.CLAMP);
    }
    public void setExpanded(boolean value) { expanded = value; updateOpacity(); invalidate(); }
    public void setInteracting(boolean value) { interacting = value; updateOpacity(); }
    private void updateOpacity() { animate().alpha(expanded || interacting ? 1f : 0.72f).setDuration(120).start(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f, y = getHeight() / 2f, radius = dp(17);
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(3), 0, dp(1), Color.argb(50, 20, 31, 54));
        paint.setShader(gradient);
        canvas.drawCircle(x, y, radius, paint);
        paint.clearShadowLayer(); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(0.8f)); paint.setColor(Color.argb(180, 255, 255, 255));
        canvas.drawCircle(x, y, radius - dp(0.5f), paint);
        paint.setColor(Color.argb(245, 255, 255, 255));
        if (expanded) {
            paint.setStrokeWidth(dp(1.6f)); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x - dp(4), y - dp(4), x + dp(4), y + dp(4), paint);
            canvas.drawLine(x + dp(4), y - dp(4), x - dp(4), y + dp(4), paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            for (int dx = -1; dx <= 1; dx += 2) for (int dy = -1; dy <= 1; dy += 2) canvas.drawCircle(x + dp(3) * dx, y + dp(3) * dy, dp(1.5f), paint);
        }
    }
    @Override public boolean performClick() { return super.performClick(); }
}
