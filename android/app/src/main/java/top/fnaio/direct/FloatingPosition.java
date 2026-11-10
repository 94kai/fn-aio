package top.fnaio.direct;

/** Coordinates are relative to the inset-safe WebView container. */
public final class FloatingPosition {
    private FloatingPosition() {}
    public static float clamp(float value, float max) { return Math.max(0, Math.min(value, Math.max(0, max))); }
    public static float ballX(float x, float width, float ballSize) { return clamp(x, width - ballSize); }
    public static float ballY(float y, float height, float ballSize, float dropdownSpace) {
        return clamp(y, height - ballSize - dropdownSpace);
    }
    public static float panelX(float ballX, float width, float ballSize, float panelWidth) {
        return clamp(ballX + ballSize - panelWidth, width - panelWidth);
    }
    public static float fraction(float position, float available) { return available <= 0 ? 0 : clamp(position / available, 1); }
}
