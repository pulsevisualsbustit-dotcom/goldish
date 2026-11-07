package dev.velora.render;

public final class ColorUtil {

    private ColorUtil() {
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    public static int rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static int alpha(int color) {
        return (color >> 24) & 0xFF;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((clamp(alpha) << 24));
    }

    public static int multiplyAlpha(int color, float factor) {
        return withAlpha(color, Math.round(alpha(color) * factor));
    }

    public static int lerp(int from, int to, float delta) {
        float t = Math.max(0f, Math.min(1f, delta));
        return argb(
                Math.round(alpha(from) + (alpha(to) - alpha(from)) * t),
                Math.round(red(from) + (red(to) - red(from)) * t),
                Math.round(green(from) + (green(to) - green(from)) * t),
                Math.round(blue(from) + (blue(to) - blue(from)) * t)
        );
    }

    /** Multiplies RGB channels, keeping alpha. Factor &lt; 1 darkens, &gt; 1 brightens. */
    public static int scale(int color, float factor) {
        return argb(alpha(color),
                Math.round(red(color) * factor),
                Math.round(green(color) * factor),
                Math.round(blue(color) * factor));
    }

    public static int mix(int first, int second, float delta) {
        return lerp(first, second, delta);
    }

    /** Smooth rainbow helper for animated accents. */
    public static int rainbow(float progress, float saturation, float brightness, int alpha) {
        int rgb = java.awt.Color.HSBtoRGB(progress % 1f, saturation, brightness);
        return withAlpha(rgb, alpha);
    }
}
