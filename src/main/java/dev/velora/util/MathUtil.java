package dev.velora.util;

/**
 * Shared math helpers for every Velora module.
 *
 * <p>The client keeps its maths in one place so new combat, movement and render
 * modules can reuse the same, well-tested primitives instead of re-implementing
 * clamps, interpolations and projection helpers each time.</p>
 */
public final class MathUtil {

    public static final float PI = (float) Math.PI;
    public static final float TWO_PI = (float) (Math.PI * 2.0);
    public static final float HALF_PI = (float) (Math.PI / 2.0);
    public static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    public static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

    private MathUtil() {
    }

    // ------------------------------------------------------------------ clamp

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    public static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    public static double clamp01(double value) {
        return clamp(value, 0d, 1d);
    }

    // ----------------------------------------------------------- interpolation

    public static float lerp(float start, float end, float delta) {
        return start + (end - start) * clamp(delta, 0f, 1f);
    }

    public static double lerp(double start, double end, double delta) {
        return start + (end - start) * clamp(delta, 0d, 1d);
    }

    /** Unclamped linear interpolation, useful for extrapolation. */
    public static float lerpUnclamped(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    /** Position of {@code value} inside {@code [min, max]}, clamped to 0..1. */
    public static float inverseLerp(float min, float max, float value) {
        if (Math.abs(max - min) < 1.0E-6f) {
            return 0f;
        }
        return clamp01((value - min) / (max - min));
    }

    /** Maps a value from one range to another, clamped. */
    public static float map(float value, float inMin, float inMax, float outMin, float outMax) {
        return lerp(outMin, outMax, inverseLerp(inMin, inMax, value));
    }

    /** Smooth, frame-rate independent easing factor. */
    public static float ease(float factor, float deltaTime) {
        return 1f - (float) Math.pow(1f - clamp(factor, 0f, 1f), deltaTime * 60f);
    }

    /** Moves {@code current} towards {@code target} by at most {@code maxDelta}. */
    public static float approach(float current, float target, float maxDelta) {
        if (current < target) {
            return Math.min(current + maxDelta, target);
        }
        return Math.max(current - maxDelta, target);
    }

    public static double approach(double current, double target, double maxDelta) {
        if (current < target) {
            return Math.min(current + maxDelta, target);
        }
        return Math.max(current - maxDelta, target);
    }

    public static float smoothstep(float edge0, float edge1, float value) {
        float t = inverseLerp(edge0, edge1, value);
        return t * t * (3f - 2f * t);
    }

    public static float smootherstep(float edge0, float edge1, float value) {
        float t = inverseLerp(edge0, edge1, value);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /** Exponential smoothing that does not depend on the frame rate. */
    public static float expSmooth(float current, float target, float speed, float deltaSeconds) {
        float alpha = 1f - (float) Math.exp(-Math.max(0f, speed) * Math.max(0f, deltaSeconds));
        return current + (target - current) * alpha;
    }

    public static float roundTo(float value, float step) {
        if (step <= 0f) {
            return value;
        }
        return Math.round(value / step) * step;
    }

    // ------------------------------------------------------------------ angles

    /** Shortest signed difference between two angles, in degrees. */
    public static float angleDifference(float from, float to) {
        return wrapDegrees(to - from);
    }

    public static float wrapDegrees(float value) {
        value %= 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    public static double wrapDegrees(double value) {
        value %= 360.0;
        if (value >= 180.0) {
            value -= 360.0;
        }
        if (value < -180.0) {
            value += 360.0;
        }
        return value;
    }

    /** Interpolates two angles through the shortest arc, in degrees. */
    public static float lerpAngle(float from, float to, float delta) {
        return wrapDegrees(from + angleDifference(from, to) * clamp01(delta));
    }

    public static float toRadians(float degrees) {
        return degrees * DEG_TO_RAD;
    }

    public static float toDegrees(float radians) {
        return radians * RAD_TO_DEG;
    }

    // ---------------------------------------------------------------- geometry

    public static float sq(float value) {
        return value * value;
    }

    public static double sq(double value) {
        return value * value;
    }

    public static float length2D(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    public static double length3D(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public static float distance2D(float x1, float y1, float x2, float y2) {
        return length2D(x2 - x1, y2 - y1);
    }

    public static double distance3D(double x1, double y1, double z1,
                                    double x2, double y2, double z2) {
        return length3D(x2 - x1, y2 - y1, z2 - z1);
    }

    /** Signed area of a 2D polygon; negative / positive tells the winding order. */
    public static float signedArea(float[] xs, float[] ys, int count) {
        float area = 0f;
        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            area += xs[i] * ys[next] - xs[next] * ys[i];
        }
        return area * 0.5f;
    }

    // ---------------------------------------------------------------- random

    /** Uniform float in {@code [min, max)}. */
    public static float random(float min, float max) {
        if (max <= min) {
            return min;
        }
        return min + (float) Math.random() * (max - min);
    }

    public static double random(double min, double max) {
        if (max <= min) {
            return min;
        }
        return min + Math.random() * (max - min);
    }

    public static int randomInt(int min, int maxInclusive) {
        if (maxInclusive <= min) {
            return min;
        }
        return min + (int) (Math.random() * (maxInclusive - min + 1));
    }

    /** True with the given probability (0..1). */
    public static boolean chance(float probability) {
        return Math.random() < clamp01(probability);
    }

    public static boolean chance(double probability) {
        return Math.random() < clamp01(probability);
    }

    /** Gaussian sample centred on {@code mean} with the given standard deviation. */
    public static double gaussian(double mean, double deviation) {
        return mean + java.util.concurrent.ThreadLocalRandom.current().nextGaussian() * deviation;
    }

    public static float gaussian(float mean, float deviation) {
        return (float) gaussian((double) mean, (double) deviation);
    }

    // ---------------------------------------------------------------- curves

    /** Quadratic Bezier point at {@code t} in 0..1. */
    public static float bezier(float start, float control, float end, float t) {
        float inv = 1f - t;
        return inv * inv * start + 2f * inv * t * control + t * t * end;
    }

    /** Cubic Bezier point at {@code t} in 0..1. */
    public static float bezier(float start, float controlA, float controlB, float end, float t) {
        float inv = 1f - t;
        return inv * inv * inv * start
                + 3f * inv * inv * t * controlA
                + 3f * inv * t * t * controlB
                + t * t * t * end;
    }

    /** Catmull-Rom spline between {@code p1} and {@code p2}. */
    public static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2f * p1)
                + (-p0 + p2) * t
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    /** Value-noise interpolation of four samples. */
    public static float bilerp(float topLeft, float topRight, float bottomLeft, float bottomRight,
                              float x, float y) {
        float top = lerp(topLeft, topRight, x);
        float bottom = lerp(bottomLeft, bottomRight, x);
        return lerp(top, bottom, y);
    }

    // ------------------------------------------------------------- bits / bits

    /** Packs RGBA bytes into an ARGB int. */
    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static boolean roughlyEquals(float a, float b, float epsilon) {
        return Math.abs(a - b) <= Math.abs(epsilon);
    }

    public static boolean roughlyEquals(double a, double b, double epsilon) {
        return Math.abs(a - b) <= Math.abs(epsilon);
    }
}
