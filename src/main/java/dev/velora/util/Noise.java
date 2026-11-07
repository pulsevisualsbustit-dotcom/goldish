package dev.velora.util;

import java.util.Random;

/**
 * Deterministic, seeded value noise with fractal (fBm) variants.
 *
 * <p>Useful for organic motion: ESP pulses, particle drift, watermark shimmer,
 * future particle systems. Everything is smooth and frame-independent, so the
 * results are stable across machines when the same seed is used.</p>
 */
public final class Noise {

    private final int[] permutation = new int[512];

    public Noise() {
        this(0x9E3779B9L);
    }

    public Noise(long seed) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = i;
        }
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = table[i];
            table[i] = table[j];
            table[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            permutation[i] = table[i & 255];
        }
    }

    /** Smooth 1D value noise in {@code [-1, 1]}. */
    public float noise(float x) {
        int floor = fastFloor(x);
        float t = x - floor;
        float a = gradient1(floor);
        float b = gradient1(floor + 1);
        return MathUtil.lerp(a, b, MathUtil.smootherstep(0f, 1f, t));
    }

    /** Smooth 2D value noise in {@code [-1, 1]}. */
    public float noise(float x, float y) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        float tx = MathUtil.smootherstep(0f, 1f, x - x0);
        float ty = MathUtil.smootherstep(0f, 1f, y - y0);

        float topLeft = gradient2(x0, y0);
        float topRight = gradient2(x0 + 1, y0);
        float bottomLeft = gradient2(x0, y0 + 1);
        float bottomRight = gradient2(x0 + 1, y0 + 1);
        return MathUtil.bilerp(topLeft, topRight, bottomLeft, bottomRight, tx, ty);
    }

    /** Fractal 1D noise (sum of octaves) in {@code [-1, 1]}. */
    public float fbm(float x, int octaves, float lacunarity, float gain) {
        float sum = 0f;
        float amplitude = 1f;
        float frequency = 1f;
        float total = 0f;
        for (int i = 0; i < Math.max(1, octaves); i++) {
            sum += noise(x * frequency + i * 13.37f) * amplitude;
            total += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total <= 0f ? 0f : sum / total;
    }

    public float fbm(float x, int octaves) {
        return fbm(x, octaves, 2f, 0.5f);
    }

    /** Fractal 2D noise in {@code [-1, 1]}. */
    public float fbm(float x, float y, int octaves, float lacunarity, float gain) {
        float sum = 0f;
        float amplitude = 1f;
        float frequency = 1f;
        float total = 0f;
        for (int i = 0; i < Math.max(1, octaves); i++) {
            sum += noise(x * frequency + i * 31.7f, y * frequency - i * 17.3f) * amplitude;
            total += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return total <= 0f ? 0f : sum / total;
    }

    public float fbm(float x, float y, int octaves) {
        return fbm(x, y, octaves, 2f, 0.5f);
    }

    /** {@link #noise(float)} remapped to {@code [0, 1]}. */
    public float noise01(float x) {
        return noise(x) * 0.5f + 0.5f;
    }

    public float noise01(float x, float y) {
        return noise(x, y) * 0.5f + 0.5f;
    }

    private float gradient1(int x) {
        return (permutation[x & 255] / 255f) * 2f - 1f;
    }

    private float gradient2(int x, int y) {
        int hash = permutation[(x & 255) + permutation[y & 255]];
        return (hash / 255f) * 2f - 1f;
    }

    private static int fastFloor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
