package dev.velora.util;

import dev.velora.Velora;
import dev.velora.mixin.ClientPlayerEntityAccessor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class PlayerUtil {

    private PlayerUtil() {
    }

    public static ClientPlayerEntity player() {
        return Velora.mc().player;
    }

    public static boolean isHoldingWeapon() {
        var stack = player().getMainHandStack();
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.MACE);
    }

    public static float attackCooldown() {
        return player() == null ? 0f : player().getAttackCooldownProgress(0.5f);
    }

    public static boolean isFriend(Entity entity) {
        return entity instanceof PlayerEntity playerEntity
                && Velora.get().getFriendManager().isFriend(playerEntity);
    }

    public static boolean isValidTarget(Entity entity, boolean attackPlayers, boolean attackMobs) {
        if (!(entity instanceof LivingEntity living) || entity == player() || !living.isAlive()) {
            return false;
        }
        if (living instanceof PlayerEntity playerEntity) {
            if (!attackPlayers || playerEntity.isCreative() || playerEntity.isSpectator()) {
                return false;
            }
            return !Velora.get().getFriendManager().isFriend(playerEntity);
        }
        return living instanceof MobEntity && attackMobs;
    }

    /**
     * Vanilla 1.21.11 critical hit conditions (see {@code PlayerEntity#isCriticalHit}).
     * A hit only crits while falling, not on ground, not climbing / swimming,
     * without blindness, not riding anything and not sprinting.
     */
    public static boolean canCrit(LivingEntity target) {
        return isFallingForCrit(target) && !player().isSprinting();
    }

    /**
     * The falling window for a crit WITHOUT the sprint condition.
     *
     * <p>While running, vanilla (and AutoSprint) re-assert the sprint flag at
     * the start of every tick, so checking {@code !isSprinting()} at TriggerBot's
     * end-of-tick hook is always false there and a strict check would miss the
     * entire falling window — the bot would stall instead of attacking. Sprint
     * only decides whether the punch crits or not; it is released explicitly
     * right before the attack instead.</p>
     */
    public static boolean isFallingForCrit(LivingEntity target) {
        ClientPlayerEntity player = player();
        return player != null && target != null && target.isAlive()
                && player.fallDistance > 0.0f
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasBlindnessEffect()
                && !player.hasVehicle();
    }

    /** Space check used before the TriggerBot auto-jumps for a crit. */
    public static boolean canJump() {
        ClientPlayerEntity player = player();
        if (player == null || Velora.mc().world == null) {
            return false;
        }
        Box box = player.getBoundingBox();
        return Velora.mc().world.isSpaceEmpty(player, box.offset(0.0, 1.0, 0.0));
    }

    /**
     * Whether a critical hit is possible in the current environment at all:
     * not in water, not on a ladder, no blindness, not riding anything. The
     * remaining vanilla conditions (falling and not sprinting) are per-moment
     * and are handled by {@link #isFallingForCrit} and
     * {@link #releaseSprintForCrit}.
     */
    public static boolean canCritEver() {
        ClientPlayerEntity player = player();
        return player != null
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasBlindnessEffect()
                && !player.hasVehicle();
    }

    /**
     * Releases sprint through the input so vanilla notices it on its own:
     * playerInput and lastPlayerInput lose the sprint bit (the input packet
     * state stays consistent) and setSprinting(false) makes the sprint packet
     * logic send STOP_SPRINTING on the next tick. A falling hit crits only
     * after the server has seen that stop (isSprinting() is part of
     * PlayerEntity#isCriticalHit in 1.21.11).
     */
    public static void releaseSprintForCrit(ClientPlayerEntity player) {
        PlayerInput current = player.input.playerInput;
        if (current.sprint()) {
            PlayerInput released = new PlayerInput(current.forward(), current.backward(),
                    current.left(), current.right(), current.jump(), current.sneak(), false);
            player.input.playerInput = released;
            ((ClientPlayerEntityAccessor) player).velora$setLastPlayerInput(released);
        }
        player.setSprinting(false);
    }

    /**
     * Presses the vanilla jump key for exactly one tick. KeyboardInput.tick
     * rebuilds PlayerInput from KeyBinding#isPressed() (verified in the 1.21.11
     * bytecode), so on the next tick the player physically jumps and the input
     * packet carries jump=true - indistinguishable from a real space press.
     * The real keyboard state must be restored one tick later with
     * KeyBinding#updatePressedStates().
     */
    public static void pressJump() {
        Velora.mc().options.jumpKey.setPressed(true);
    }

    /** Line of sight check against blocks only (entities are ignored). */
    public static boolean canSee(Entity entity) {
        ClientPlayerEntity player = player();
        if (player == null) {
            return false;
        }
        return canSeePoint(player.getEyePos(), TargetUtil.aimPoint(entity));
    }

    public static boolean canSeePoint(Vec3d from, Vec3d to) {
        ClientPlayerEntity player = player();
        if (player == null || Velora.mc().world == null) {
            return false;
        }
        BlockHitResult result = Velora.mc().world.raycast(new RaycastContext(
                from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        return result.getType() == HitResult.Type.MISS;
    }

    public static LivingEntity findTarget(double range, double fov, boolean players, boolean mobs, boolean walls) {
        return TargetUtil.findBest(range, fov, players, mobs, walls);
    }

    public static LivingEntity getCrosshairTarget(boolean players, boolean mobs) {
        HitResult hit = Velora.mc().crosshairTarget;
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            return isValidTarget(entity, players, mobs) ? (LivingEntity) entity : null;
        }
        return null;
    }

    public static double distanceTo(Entity entity) {
        ClientPlayerEntity player = player();
        return player == null ? Double.MAX_VALUE : player.getEyePos().distanceTo(entity.getBoundingBox().getCenter());
    }

    public static boolean isShieldBlocking(LivingEntity entity) {
        if (!(entity instanceof PlayerEntity player)) {
            return false;
        }
        return player.isBlocking() && player.getActiveItem().isOf(Items.SHIELD);
    }
}
