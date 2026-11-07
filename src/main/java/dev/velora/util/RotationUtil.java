package dev.velora.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class RotationUtil {

    private RotationUtil() {
    }

    public record Rotation(float yaw, float pitch) {
    }

    public static Rotation look(Vec3d eyes, Vec3d point) {
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Rotation(MathUtil.wrapDegrees(yaw), MathUtil.clamp(pitch, -90f, 90f));
    }

    /**
     * Direct entry point ported from the Velora {@code Aura.java} helper.
     *
     * <p>Aims at the target's eye position and moves the camera towards it by at
     * most {@code maxDegreesPerTick} on each axis, so a caller can decide how
     * quickly the rotation should converge. The rotation is applied through
     * {@link #setRotation(ClientPlayerEntity, float, float)}, which is the only
     * rotation entry point modules should use.</p>
     */
    public static void rotateTo(MinecraftClient client, Entity target, float maxDegreesPerTick) {
        if (client == null || client.player == null || target == null) {
            return;
        }
        ClientPlayerEntity player = client.player;
        Rotation targetRotation = look(player.getEyePos(), target.getEyePos());
        float step = Math.max(0f, maxDegreesPerTick);

        float yaw = player.getYaw() + MathHelper.clamp(
                MathUtil.angleDifference(player.getYaw(), targetRotation.yaw()), -step, step);
        float pitch = player.getPitch() + MathHelper.clamp(
                targetRotation.pitch() - player.getPitch(), -step, step);

        setRotation(player, yaw, pitch);
    }

    /** Rotation towards the closest visible point of the hitbox, not its centre. */
    public static Rotation lookAt(Entity entity) {
        ClientPlayerEntity player = PlayerUtil.player();
        Vec3d eyes = player != null ? player.getEyePos() : entity.getEyePos();
        return look(eyes, TargetUtil.aimPoint(entity));
    }

    /** Rotation towards an explicit point in the world. */
    public static Rotation lookAt(Entity entity, Vec3d point) {
        ClientPlayerEntity player = PlayerUtil.player();
        Vec3d eyes = player != null ? player.getEyePos() : entity.getEyePos();
        return look(eyes, point);
    }

    /**
     * The smallest rotation the vanilla mouse can produce at the current
     * sensitivity, in degrees. Real mouse input moves the camera in whole
     * multiples of this step, so an assist that respects it leaves the same
     * fingerprint as a human hand on the mouse.
     */
    public static float gcdDegrees() {
        double sensitivity = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        double scaled = sensitivity * 0.6 + 0.2;
        double step = scaled * scaled * scaled * 8.0 * 0.15;
        return (float) Math.max(0.0025, step);
    }

    /**
     * Snaps a rotation delta to the sensitivity grid. Values below one mouse
     * count are left untouched, otherwise fine corrections would freeze.
     */
    public static float snapToGcd(float delta) {
        float step = gcdDegrees();
        if (Math.abs(delta) < step) {
            return delta;
        }
        return Math.round(delta / step) * step;
    }

    /** Angle between the player's look vector and the entity, in degrees. */
    public static float angleTo(ClientPlayerEntity player, Entity entity) {
        Rotation rotation = look(player.getEyePos(), TargetUtil.aimPoint(entity));
        float yawDiff = Math.abs(MathUtil.angleDifference(player.getYaw(), rotation.yaw()));
        float pitchDiff = Math.abs(player.getPitch() - rotation.pitch());
        return Math.max(yawDiff, pitchDiff);
    }

    /**
     * Frame-rate independent smooth rotation. The step is an exponential ease
     * (never a fixed jump), so the camera glides towards the target instead of
     * stuttering once every client tick. Jitter is opt-in and disabled by default.
     *
     * @param speed          how aggressively the camera follows (degrees / tick)
     * @param randomization  0..1 subtle humanisation, 0 = perfectly clean
     * @param deltaTicks     render delta in client ticks (1.0 = 50 ms)
     */
    public static void smoothLook(ClientPlayerEntity player, Rotation target,
                                  float speed, float randomization, float deltaTicks) {
        float dt = MathHelper.clamp(deltaTicks, 0.01f, 4f);
        float yawDelta = MathUtil.angleDifference(player.getYaw(), target.yaw());
        float pitchDelta = target.pitch() - player.getPitch();

        // Deadzone: do not twitch for sub-pixel differences.
        if (Math.abs(yawDelta) < 0.03f) {
            yawDelta = 0f;
        }
        if (Math.abs(pitchDelta) < 0.03f) {
            pitchDelta = 0f;
        }
        if (yawDelta == 0f && pitchDelta == 0f) {
            return;
        }

        float response = 1f - (float) Math.exp(-Math.max(0.1f, speed) * dt * 0.18f);
        float maxStep = Math.max(0.6f, speed * dt * 2.5f);

        float yawStep = moveTowards(yawDelta * response, yawDelta, maxStep);
        float pitchStep = moveTowards(pitchDelta * response, pitchDelta, maxStep);

        if (randomization > 0f) {
            yawStep += (float) ((Math.random() - 0.5) * 0.12 * randomization);
            pitchStep += (float) ((Math.random() - 0.5) * 0.08 * randomization);
        }

        setRotation(player, player.getYaw() + yawStep, player.getPitch() + pitchStep);
    }

    /** Straight constant-speed rotation, still spread over frames. */
    public static void linearLook(ClientPlayerEntity player, Rotation target, float speed, float deltaTicks) {
        float dt = MathHelper.clamp(deltaTicks, 0.01f, 4f);
        float maxStep = Math.max(0.6f, speed * dt);
        float yawDelta = MathUtil.angleDifference(player.getYaw(), target.yaw());
        float pitchDelta = target.pitch() - player.getPitch();
        if (Math.abs(yawDelta) < 0.03f) {
            yawDelta = 0f;
        }
        if (Math.abs(pitchDelta) < 0.03f) {
            pitchDelta = 0f;
        }
        setRotation(player,
                player.getYaw() + moveTowards(yawDelta, yawDelta, maxStep),
                player.getPitch() + moveTowards(pitchDelta, pitchDelta, maxStep));
    }

    private static float moveTowards(float step, float delta, float maxStep) {
        if (Math.abs(step) > Math.abs(delta)) {
            return delta;
        }
        return MathHelper.clamp(step, -maxStep, maxStep);
    }

    public static void setRotation(ClientPlayerEntity player, float yaw, float pitch) {
        player.setYaw(MathUtil.wrapDegrees(yaw));
        player.setPitch(MathUtil.clamp(pitch, -90f, 90f));
        // NOTE (1.21.11): lastYawClient/lastPitchClient are NOT camera interpolation
        // fields - they mirror the last rotation that was actually sent to the server,
        // and ClientPlayerEntity.sendMovementPackets only puts the rotation into the
        // movement packet when the current yaw/pitch differ from them. Syncing them
        // here (the old "keep the camera in sync" trick) makes vanilla believe
        // "nothing changed since the last packet": the camera would turn on the
        // client, but the server keeps the old view direction forever and every
        // anticheat sees attacks that were never aimed.
        //
        // The real camera interpolation runs through renderYaw/renderPitch, which
        // chase yaw/pitch by 0.5 every tick and do not depend on the packet fields
        // at all, so a per-frame rotation stays smooth without any sync here.
        // Correct behaviour: leave lastYawClient/lastPitchClient alone and let
        // vanilla send the new rotation itself.
    }

    /** Legacy entry point kept for callers that run once per client tick. */
    public static void applySmooth(ClientPlayerEntity player, Rotation target, float speed, float randomization) {
        smoothLook(player, target, speed, randomization, 1f);
    }
}
