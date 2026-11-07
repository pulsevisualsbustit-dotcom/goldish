package dev.velora.combat.trigger;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * The validated attack TriggerBot is about to perform.
 *
 * <p>{@code ClientPlayerInteractionManager#attackEntity} builds the packet from
 * the interpolated render position of the target, and the server measures the
 * distance from the position in the flying packet of the tick. Whenever those
 * disagree - the target moved, the render state is a tick behind, a module
 * rotated the look vector after the frame was rendered - a hit that looked fine
 * on screen is judged as too far or outside the box. This object freezes the
 * hit as it was validated (target, attacker position, hit point) right before
 * the attack, and the packet hook checks the outgoing packet against it.</p>
 */
public final class TriggerHit {

    private static TriggerHit pending;

    private final Entity entity;
    private final int entityId;
    private final Vec3d from;
    private final Vec3d point;

    private TriggerHit(Entity entity, Vec3d from, Vec3d point) {
        this.entity = entity;
        this.entityId = entity.getId();
        this.from = from;
        this.point = point;
    }

    /** Registers the hit the next attack packet of this tick belongs to. */
    public static void begin(Entity entity, Vec3d from, Vec3d point) {
        pending = new TriggerHit(entity, from, point);
    }

    public static void clear() {
        pending = null;
    }

    public static TriggerHit peek() {
        return pending;
    }

    /**
     * The server measures eye-to-hitbox while the client scores the hit from the
     * feet position, so the eye is derived from the feet here. Every edge case
     * is strict: an eye inside the box is the only accepted state.
     */
    public static double eyeDistanceSqr(Vec3d feet, Vec3d point, Box box) {
        Vec3d eye = feet.add(0.0, 1.62, 0.0);
        if (!box.contains(eye)) {
            return Double.MAX_VALUE;
        }
        return eye.squaredDistanceTo(point);
    }

    public Entity entity() {
        return entity;
    }

    public int entityId() {
        return entityId;
    }

    public Vec3d from() {
        return from;
    }

    public Vec3d point() {
        return point;
    }

    /** Squared reach used for the packet check: the server's limit minus a safety margin. */
    public static double maxDistanceSqr() {
        double limit = 3.0 - 0.12;
        return limit * limit;
    }

    /**
     * The server only counts hits this close, and it measures from the eye to the
     * expanded hitbox; the safety margin covers the eye height of the tick.
     */
    public boolean stillValid(Box targetBox) {
        return eyeDistanceSqr(from, point, targetBox) <= maxDistanceSqr();
    }
}