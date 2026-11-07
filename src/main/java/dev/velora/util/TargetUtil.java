package dev.velora.util;

import dev.velora.Velora;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Shared target selection for every combat / visual module.
 *
 * <p>Targets are scored by the angle between the crosshair and the <em>closest
 * point of the hitbox</em> instead of the entity origin. That is what makes aim
 * feel like it locks onto the player and not onto the middle of the model (or
 * the nametag above it). The same aim point is used by AimAssist, TriggerBot,
 * TargetHUD and TargetESP, so the visuals always agree with the fight.</p>
 */
public final class TargetUtil {

    private TargetUtil() {
    }

    /** Best target inside a cone: smallest angle to the crosshair, distance as a tie-breaker. */
    public static LivingEntity findBest(double range, double fovDegrees,
                                        boolean players, boolean mobs, boolean throughWalls) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null || Velora.mc().world == null) {
            return null;
        }

        double halfFov = MathHelper.clamp(fovDegrees, 1.0, 360.0) / 2.0;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity entity : Velora.mc().world.getEntities()) {
            if (!PlayerUtil.isValidTarget(entity, players, mobs)) {
                continue;
            }
            LivingEntity living = (LivingEntity) entity;
            Vec3d aim = aimPoint(living);
            double distance = player.getEyePos().distanceTo(living.getBoundingBox().getCenter());
            if (distance > range) {
                continue;
            }

            RotationUtil.Rotation rotation = RotationUtil.look(player.getEyePos(), aim);
            float angle = angleBetween(player, rotation);
            if (angle > halfFov) {
                continue;
            }
            if (!throughWalls && !PlayerUtil.canSeePoint(player.getEyePos(), aim)) {
                continue;
            }

            double score = angle + distance * 0.05;
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    /** Target directly under the crosshair, if it is still valid. */
    public static LivingEntity crosshair(boolean players, boolean mobs) {
        return PlayerUtil.getCrosshairTarget(players, mobs);
    }

    /**
     * Point of the hitbox the player is already looking at. If the look ray hits
     * the box, that exact surface point is used; otherwise the closest point of
     * the box to the ray is used. The Y coordinate is kept away from the very top
     * and bottom edges, which is the "aim at the hologram" bug.
     */
    public static Vec3d aimPoint(Entity entity) {
        ClientPlayerEntity player = PlayerUtil.player();
        Box box = entity.getBoundingBox();
        if (player == null) {
            return box.getCenter();
        }

        Vec3d eyes = player.getEyePos();
        Vec3d look = player.getRotationVector();
        Vec3d end = eyes.add(look.multiply(64.0));

        Box shrunk = box.expand(-0.06);
        var hit = shrunk.raycast(eyes, end);
        if (hit.isPresent()) {
            return clampVertical(hit.get(), box);
        }

        Vec3d center = box.getCenter();
        double projection = center.subtract(eyes).dotProduct(look);
        Vec3d projected = projection > 0.0 ? eyes.add(look.multiply(projection)) : center;
        Vec3d closest = new Vec3d(
                MathHelper.clamp(projected.x, box.minX, box.maxX),
                MathHelper.clamp(projected.y, box.minY, box.maxY),
                MathHelper.clamp(projected.z, box.minZ, box.maxZ)
        );
        return clampVertical(closest, box);
    }

    /** Keeps the aim point on the torso instead of the very top / feet of the hitbox. */
    private static Vec3d clampVertical(Vec3d point, Box box) {
        double height = box.getLengthY();
        double min = box.minY + height * 0.12;
        double max = box.maxY - height * 0.10;
        return new Vec3d(point.x, MathHelper.clamp(point.y, min, max), point.z);
    }

    public static float angleBetween(ClientPlayerEntity player, RotationUtil.Rotation rotation) {
        float yaw = Math.abs(MathUtil.angleDifference(player.getYaw(), rotation.yaw()));
        float pitch = Math.abs(player.getPitch() - rotation.pitch());
        return Math.max(yaw, pitch);
    }
}
