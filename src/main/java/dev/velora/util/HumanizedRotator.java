package dev.velora.util;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Simulates how a real wrist moves a mouse when flicking onto a target: fast
 * initial travel, overshoot, settle, and a constant micro tremor on top. Every
 * step is snapped to the mouse grid (GCD) and capped, so the movement packets
 * are indistinguishable from real mouse input.
 *
 * <p>Two entry points: {@link #step} rotates the player itself (visible,
 * Legit-style aiming), while {@link #stepRotation} advances an arbitrary
 * virtual rotation and returns the result - the silent-aim case, where the
 * rotation lives only in the movement packets and the camera never sees it.</p>
 */
public final class HumanizedRotator {

    private float tremorPhase;
    private float tremorYaw;
    private float tremorPitch;
    private boolean settling;
    private float overshootYaw;
    private float overshootPitch;

    /**
     * Advances a virtual rotation one step towards the target rotation with
     * the same humanization {@link #step} applies to the camera. For the
     * silent use case call this once per tick with {@code deltaTicks = 1}, so
     * each movement packet carries exactly one humanized increment.
     *
     * @param current       the rotation to advance (what the server last saw)
     * @param target        the rotation to aim for
     * @param speed         base turning strength, roughly degrees per tick at
     *                      full distance
     * @param randomization 0..1, how much tremor and speed noise to mix in
     * @param deltaTicks    elapsed ticks since the previous step
     * @param vertical      whether pitch is adjusted or yaw only
     * @param maxFlick      hard cap of the per-step rotation, degrees
     * @return the advanced rotation (wrapped yaw, clamped pitch); the exact
     *         {@code current} instance when parked inside the dead zone
     */
    public RotationUtil.Rotation stepRotation(RotationUtil.Rotation current, RotationUtil.Rotation target,
            float speed, float randomization, float deltaTicks, boolean vertical, float maxFlick) {
        float dt = MathHelper.clamp(deltaTicks, 0.01F, 4.0F);
        float strength = MathHelper.clamp(speed, 0.5F, 15.0F);
        float yawDelta = MathUtil.angleDifference(current.yaw(), target.yaw());
        float pitchDelta = vertical ? target.pitch() - current.pitch() : 0.0F;
        // Micro-dead-zone: a real hand parks within a fraction of a mouse count
        // instead of converging to the exact pixel.
        float gcd = RotationUtil.gcdDegrees();
        float deadzone = Math.max(gcd * 0.8F, 0.05F);
        if (Math.abs(yawDelta) < deadzone) {
            yawDelta = 0.0F;
        }
        if (Math.abs(pitchDelta) < deadzone) {
            pitchDelta = 0.0F;
        }
        if (yawDelta == 0.0F && pitchDelta == 0.0F) {
            // Parked: drop the trajectory shaping until the next real move.
            settling = false;
            overshootYaw = 0.0F;
            overshootPitch = 0.0F;
            return current;
        }

        float tremorAmount = MathHelper.clamp(randomization, 0.0F, 1.0F) * 0.05F;

        if (!settling && Math.abs(yawDelta) > 6.0F + Math.abs(overshootYaw)) {
            // Far from the target: arm a fresh overshoot in the direction of
            // travel, proportional to the remaining distance.
            overshootYaw = (float) (MathUtil.gaussian(0.0, 0.5) + Math.signum(yawDelta)
                    * (0.7F + MathUtil.random(0.0, 0.8) * (float) Math.abs(yawDelta) * 0.05F));
            settling = true;
        } else if (settling && Math.signum(yawDelta) != Math.signum(overshootYaw)) {
            // The remaining distance changed sign while we were already
            // overshooting (target moved): re-arm the overshoot in the new
            // direction so the settle stays smooth.
            overshootYaw = (float) (MathUtil.gaussian(0.0, 0.5) + Math.signum(yawDelta)
                    * (0.7F + MathUtil.random(0.0, 0.8) * (float) Math.abs(yawDelta) * 0.05F));
        }
        if (vertical) {
            if (!settling && Math.abs(pitchDelta) > 6.0F + Math.abs(overshootPitch)) {
                overshootPitch = (float) (MathUtil.gaussian(0.0, 0.3) + Math.signum(pitchDelta)
                        * (0.5F + MathUtil.random(0.0, 0.6) * (float) Math.abs(pitchDelta) * 0.04F));
                settling = true;
            } else if (settling && Math.signum(pitchDelta) != Math.signum(overshootPitch)) {
                overshootPitch = (float) (MathUtil.gaussian(0.0, 0.3) + Math.signum(pitchDelta)
                        * (0.5F + MathUtil.random(0.0, 0.6) * (float) Math.abs(pitchDelta) * 0.04F));
            }
        }

        // Tremor: a smooth wandering bias, re-sampled once in a while.
        tremorPhase += dt * MathUtil.random(0.6, 1.4);
        if (tremorPhase >= 1.0F) {
            tremorPhase = 0.0F;
            tremorYaw = (float) (MathUtil.gaussian(0.0, 0.6) * MathUtil.gaussian(0.0, 0.6)) * 3.0F;
            if (vertical) {
                tremorPitch = (float) (MathUtil.gaussian(0.0, 0.6) * MathUtil.gaussian(0.0, 0.6)) * 2.0F;
            }
        }
        float tremorDecay = 1.0F - MathHelper.clamp(
                Math.abs(yawDelta) / 90.0F, 0.0F, 1.0F);
        tremorDecay = 0.15F + 0.85F * tremorDecay;

        // Exponential ease-out response: strong far away, damped near the aim
        // point, scaled by how long this step lasts.
        float response = 1.0F - (float) Math.exp(-0.08F * strength * dt);

        float yawStep = yawDelta * response;
        float pitchStep = pitchDelta * response;
        if (settling) {
            // Blend the armed overshoot in: approach past the target, then the
            // sign flip of the remaining delta walks it back out.
            yawStep += overshootYaw * response * 0.35F;
            if (vertical) {
                pitchStep += overshootPitch * response * 0.35F;
            }
        }
        yawStep += tremorYaw * tremorAmount * tremorDecay * dt;
        pitchStep += tremorPitch * tremorAmount * tremorDecay * dt;

        // Noise on the response itself: hands are not metronomes.
        if (tremorAmount > 0.0F) {
            yawStep *= (float) MathUtil.random(1.0 - 0.25 * tremorAmount, 1.0 + 0.25 * tremorAmount);
            pitchStep *= (float) MathUtil.random(1.0 - 0.25 * tremorAmount, 1.0 + 0.25 * tremorAmount);
        }

        // Hard flick cap: one step can never look like a teleport.
        float cap = MathHelper.clamp(maxFlick, 1.0F, 180.0F) * dt;
        yawStep = MathHelper.clamp(yawStep, -cap, cap);
        pitchStep = MathHelper.clamp(pitchStep, -cap, cap);

        yawStep = snapToGcd(yawStep, gcd);
        pitchStep = snapToGcd(pitchStep, gcd);

        return new RotationUtil.Rotation(
                MathUtil.wrapDegrees(current.yaw() + yawStep),
                MathHelper.clamp(current.pitch() + pitchStep, -90.0F, 90.0F));
    }

    /**
     * Moves the player's real (visible) rotation one step towards the target
     * rotation with the wrist-flick trajectory described above.
     *
     * @param target        where to aim
     * @param speed         base turning strength, roughly degrees per tick at
     *                      full distance
     * @param randomization 0..1, how much tremor and speed noise to mix in
     * @param deltaTicks    elapsed ticks since the previous step (frame time)
     * @param vertical      whether pitch is adjusted or yaw only
     * @param maxFlick      hard cap of the per-step rotation, degrees
     */
    public void step(ClientPlayerEntity player, RotationUtil.Rotation target,
            float speed, float randomization, float deltaTicks, boolean vertical, float maxFlick) {
        RotationUtil.Rotation before = new RotationUtil.Rotation(player.getYaw(), player.getPitch());
        RotationUtil.Rotation stepped = stepRotation(before, target, speed, randomization,
                deltaTicks, vertical, maxFlick);
        if (stepped == before) {
            // Parked: leave the camera alone.
            return;
        }
        RotationUtil.setRotation(player, stepped.yaw(), stepped.pitch());
    }

    /**
     * Forgets the trajectory shaping (overshoot, tremor); the next step starts
     * a fresh approach.
     */
    public void reset() {
        settling = false;
        overshootYaw = 0.0F;
        overshootPitch = 0.0F;
        tremorPhase = 0.0F;
        tremorYaw = 0.0F;
        tremorPitch = 0.0F;
    }

    private float snapToGcd(float step, float gcd) {
        if (gcd <= 0.0F) {
            return step;
        }
        return Math.round(step / gcd) * gcd;
    }
}
