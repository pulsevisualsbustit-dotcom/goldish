package dev.velora.render.lightning;

import net.minecraft.entity.LivingEntity;

/**
 * Per-frame animation state for the Lightning Target ESP.
 *
 * Owns the three things the renderer cannot derive on its own:
 * <ul>
 *   <li>{@code progress} — 0..1 fade-in/fade-out of the whole ESP</li>
 *   <li>{@code time} — monotonic seconds, drives the bolt animation</li>
 *   <li>{@code occlusion} — 0..1 weight of the "through walls" pass</li>
 * </ul>
 *
 * All timing mirrors Star Client's {@code AnimatedValue} + {@code Easing}:
 * 350ms cubic ease-in-out for both tweens, and a 50ms dt clamp so a lag spike
 * does not teleport the animation.
 */
public final class LightningEspState {

    private static final long PROGRESS_DURATION_MS = 350L;
    private static final long OCCLUSION_DURATION_MS = 350L;
    private static final float MAX_DELTA_SECONDS = 0.05F;

    private final Tween progress = new Tween(PROGRESS_DURATION_MS);
    private final Tween occlusion = new Tween(OCCLUSION_DURATION_MS);

    private float time;
    private float degrees;
    private long lastFrameNanos;
    private LivingEntity lastTarget;
    private boolean occlusionTarget;

    /**
     * Advances all animations. Call exactly once per rendered frame, before
     * reading any getter.
     *
     * @param hasTarget whether a target is currently available
     */
    public void tick(boolean hasTarget) {
        tick(hasTarget, 1.0F);
    }

    /**
     * Same as {@link #tick(boolean)} but with a multiplier on the bolt
     * animation time. {@code 1.0F} is the original speed; values below 1 slow
     * the whole lightning set down uniformly (flicker cadence, jitter crawl
     * and bobbing) while the fade tweens keep their real 350ms timing.
     *
     * @param hasTarget whether a target is currently available
     * @param timeScale bolt animation speed multiplier
     */
    public void tick(boolean hasTarget, float timeScale) {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L
            ? 0.016666668F
            : Math.min((float) (now - this.lastFrameNanos) / 1.0E9F, MAX_DELTA_SECONDS);
        this.lastFrameNanos = now;

        this.time += delta * clampScale(timeScale);

        // Kept for parity with the original module. The Lightning path only uses
        // `time`, but host modules that also ported the circle/jello modes read
        // this accumulator.
        this.degrees += delta * 428.57F;
        if (!(this.degrees < 3.0E7F)) {
            this.degrees = 0.0F;
        }

        this.progress.set(hasTarget);
        this.occlusion.set(this.occlusionTarget);
    }

    /** 0..1 fade progress. */
    public float getProgress() {
        return this.progress.value();
    }

    /** Monotonic seconds accumulator driving the bolt animation. */
    public float getTime() {
        return this.time;
    }

    /** 0..1 weight of the through-walls pass; 0 means "not occluded". */
    public float getOcclusion() {
        return this.occlusion.value();
    }

    /** Monotonic degree accumulator, for host modules that ported other modes. */
    public float getDegrees() {
        return this.degrees;
    }

    /** Drives the occlusion ("x-ray") tween from a boolean target. */
    public void setOccluded(boolean occluded) {
        this.occlusionTarget = occluded;
    }

    /** Keeps the animation speed multiplier in a sane range (0.05..8). */
    private static float clampScale(float timeScale) {
        return Math.max(0.05F, Math.min(8.0F, timeScale));
    }

    /**
     * Sticky target. The ESP keeps drawing the last target while fading out, so
     * the bolts do not vanish the instant the crosshair leaves the entity.
     */
    public LivingEntity getLastTarget() {
        return this.lastTarget;
    }

    /** Remembers the current target; pass {@code null} when nothing is targeted. */
    public void updateTarget(LivingEntity target) {
        if (target != null) {
            this.lastTarget = target;
        }
    }

    /** Clears the sticky target. Call on module disable. */
    public void clearTarget() {
        this.lastTarget = null;
        this.lastFrameNanos = 0L;
    }

    /**
     * Minimal tween using a cubic ease-in-out. Re-targeting mid-flight
     * preserves the current phase instead of restarting, which prevents
     * stutter when the target is re-confirmed every frame.
     */
    private static final class Tween {

        private final long durationMs;
        private float from;
        private float to;
        private long startMs;

        Tween(long durationMs) {
            this.durationMs = durationMs;
        }

        void set(boolean target) {
            set(target ? 1.0F : 0.0F);
        }

        void set(float target) {
            if (!(Math.abs(target - this.to) > 1.0E-4F)) {
                return;
            }
            long now = System.currentTimeMillis();
            float elapsed = this.startMs == 0L ? 0.0F : (float) (now - this.startMs) / (float) this.durationMs;
            if (elapsed < 0.0F || elapsed > 1.0F) {
                elapsed = 0.0F;
            }
            this.from = value();
            this.to = target;
            this.startMs = now - (long) (elapsed * (float) this.durationMs);
        }

        float value() {
            if (this.startMs == 0L) {
                return this.to;
            }
            long since = System.currentTimeMillis() - this.startMs;
            if (since <= 0L) {
                return this.from;
            }
            if (since >= this.durationMs) {
                return this.to;
            }
            float t = (float) since / (float) this.durationMs;
            float eased = t < 0.5F
                ? 4.0F * t * t * t
                : 1.0F - (float) (Math.pow(-2.0F * t + 2.0F, 3.0) / 2.0);
            return this.from + (this.to - this.from) * eased;
        }
    }
}
