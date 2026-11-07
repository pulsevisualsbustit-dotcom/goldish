package dev.velora.combat;

import dev.velora.combat.trigger.TargetType;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.MultiBooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.PlayerUtil;
import dev.velora.util.ServerSprint;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple trigger bot: attacks what the crosshair is on.
 *
 * <p>Cooldown, charge, crosshair, reach and target checks stay simple - the
 * crit control (the Crits setting) is the only smart part: it waits for the
 * vanilla falling window, releases sprint through the input right before the
 * hit and jumps for the crit on flat ground, all vanilla-consistent.</p>
 */
public class TriggerBot extends Module {

    /** Give up on the sprint stop and hit without the crit after this many ticks. */
    private static final int MAX_SPRINT_RELEASE_TICKS = 5;
    /**
     * Finishing-hit margin: the raw attack attribute overestimates the real
     * damage (armor, resistance), so a hit only counts as finishing when the
     * target sits below this share of it.
     */
    private static final float FINISH_DAMAGE_MARGIN = 0.75F;

    private final MultiBooleanSetting targets = register(new MultiBooleanSetting(
            "Targets", "Типы существ, по которым можно бить")
            .add(TargetType.PLAYER.getTargetName(), true)
            .add(TargetType.MOB.getTargetName(), true)
            .add(TargetType.ANIMAL.getTargetName(), false)
            .add(TargetType.STAND.getTargetName(), false));

    private final NumberSetting hitInterval = register(new NumberSetting(
            "Hit interval", "Минимальный интервал между ударами (мс)", 500, 100, 2000, 50));

    private final NumberSetting reach = register(new NumberSetting(
            "Reach", "Дистанция удара", 3.0, 1.5, 4.5, 0.1));

    private final ModeSetting crits = register(new ModeSetting(
            "Crits",
            "Off - бить всегда; Smart - прыгать ради крита, но добивать обычным ударом; Only - только критические удары",
            "Off", "Off", "Smart", "Only"));

    private final BooleanSetting stopSprint = register(new BooleanSetting(
            "Stop sprint", "Останавливать бег перед ударом (только в режиме Off)", true));

    private long lastAttackTime;
    private int sprintReleaseTicks;
    /** Set while the synthetic one-tick jump press is active (see PlayerUtil#pressJump). */
    private boolean jumpQueued;

    public TriggerBot() {
        super("TriggerBot", "Attacks the entity under your crosshair", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }
        // The synthetic jump press of the previous tick has been consumed by
        // this tick's KeyboardInput.tick: restore the real keyboard states.
        releaseQueuedJump();
        if (mc.currentScreen != null || AutoSwap.isFreezing()) {
            return;
        }

        ClientPlayerEntity player = mc.player;
        if (player.isDead() || player.isSpectator()) {
            return;
        }

        // Check cooldown
        if (System.currentTimeMillis() - lastAttackTime < hitInterval.getInt()) {
            return;
        }

        // Check attack charge
        if (player.getAttackCooldownProgress(0.5F) < 0.9F) {
            return;
        }

        // Get what the crosshair is pointing at
        HitResult hit = mc.crosshairTarget;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) {
            return;
        }

        Entity entity = ((EntityHitResult) hit).getEntity();
        if (!isValidTarget(entity)) {
            return;
        }

        // Check reach
        double distance = player.getEyePos().distanceTo(
                new net.minecraft.util.math.Vec3d(entity.getX(), entity.getY(), entity.getZ()));
        if (distance > reach.get()) {
            return;
        }

        // Crit control
        if (!crits.is("Off")) {
            if (!critReady(player, (LivingEntity) entity)) {
                return;
            }
        } else if (stopSprint.get() && player.isSprinting()) {
            // Stop sprint for critical
            player.setSprinting(false);
        }

        // Attack
        mc.interactionManager.attackEntity(player, entity);
        player.swingHand(Hand.MAIN_HAND);
        lastAttackTime = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {
        sprintReleaseTicks = 0;
        releaseQueuedJump();
    }

    private boolean isValidTarget(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        if (entity == mc.player || !living.isAlive() || living.isRemoved()) {
            return false;
        }
        if (PlayerUtil.isFriend(living)) {
            return false;
        }

        List<TargetType> enabled = new ArrayList<>();
        for (TargetType type : TargetType.values()) {
            if (targets.isSelected(type.getTargetName())) {
                enabled.add(type);
            }
        }

        for (TargetType type : enabled) {
            if (type.matches(living)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Crit gate for the selected Crits mode: true = attack right now, false =
     * wait (a jump has just been queued or the sprint release is running).
     * Mode semantics are the same as in KillAura#critGate: Off - always,
     * Smart - finishing hits go out immediately, otherwise jump and wait for
     * the falling window, Only - never attack without the crit.
     */
    private boolean critReady(ClientPlayerEntity player, LivingEntity target) {
        if (crits.is("Smart") && isFinishingHit(player, target)) {
            // The next hit kills even without a crit: no reason to lose tempo.
            sprintReleaseTicks = 0;
            return true;
        }
        if (PlayerUtil.isFallingForCrit(target)) {
            return releaseSprintForHit(player);
        }
        sprintReleaseTicks = 0;
        if (!player.isOnGround()) {
            // Rising after a jump: the falling window opens in a few ticks.
            return false;
        }
        if (!PlayerUtil.canCritEver() || !PlayerUtil.canJump()) {
            // Crits are impossible here (water, ladder, blindness, vehicle, no
            // headroom): Smart keeps the tempo, Only keeps waiting.
            return !crits.is("Only");
        }
        PlayerUtil.pressJump();
        jumpQueued = true;
        return false;
    }

    /**
     * Sprint stop for the crit window. A hit only crits when the server thinks
     * we are not sprinting: the release goes through the input, vanilla itself
     * sends STOP_SPRINTING on the next tick, and the attack waits exactly one
     * tick so the crit lands.
     */
    private boolean releaseSprintForHit(ClientPlayerEntity player) {
        if (!ServerSprint.isSprinting()) {
            sprintReleaseTicks = 0;
            return true;
        }
        if (sprintReleaseTicks >= MAX_SPRINT_RELEASE_TICKS) {
            // Sprint refuses to stop (lag, other module): hit anyway, no crit.
            return true;
        }
        PlayerUtil.releaseSprintForCrit(player);
        sprintReleaseTicks++;
        return false;
    }

    /**
     * Rough lethal check: the raw attack attribute of the player (weapon and
     * effects, no crit) against the health of the target, padded down because
     * the real damage is cut by armor and resistance.
     */
    private boolean isFinishingHit(ClientPlayerEntity player, LivingEntity target) {
        float damage = (float) player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        return target.getHealth() <= damage * FINISH_DAMAGE_MARGIN;
    }

    /** Ends the synthetic one-tick jump press (see PlayerUtil#pressJump). */
    private void releaseQueuedJump() {
        if (jumpQueued) {
            jumpQueued = false;
            KeyBinding.updatePressedStates();
        }
    }

    @Override
    public String getSuffix() {
        return targets.getDisplayValue();
    }
}
