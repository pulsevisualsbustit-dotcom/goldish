package dev.velora.combat;

import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BindSetting;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.InventoryUtil;
import dev.velora.util.Keys;
import dev.velora.util.PlayerUtil;
import dev.velora.util.RotationUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;

import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

/**
 * Helps with mace smashes: optionally switches to the mace while falling, shows
 * a small "SMASH" indicator and (optionally) attacks the target you look at.
 *
 * <p>The wind charge bind throws a wind charge straight down to launch the
 * player into the air. The look-down is spread over {@code Wind slowdown}
 * milliseconds in one step per tick, so only a couple of rotation packets are
 * sent instead of an instant snap that anticheats flag.</p>
 */
public class MaceHelper extends Module {

    private final BooleanSetting autoAttack = register(new BooleanSetting(
            "Auto attack", "Бить цель булавой во время падения", true));
    private final BooleanSetting autoSwitch = register(new BooleanSetting(
            "Auto switch", "Переключаться на булаву в хотбаре при падении", false));
    private final NumberSetting minFall = register(new NumberSetting("Min fall", 1.5, 0.5, 10.0, 0.1));
    private final NumberSetting minHeight = register(new NumberSetting(
            "Min height", 1.0, 0.0, 8.0, 0.5));
    private final NumberSetting range = register(new NumberSetting("Range", 3.0, 2.0, 4.5, 0.1));
    private final BooleanSetting indicator = register(new BooleanSetting("Indicator", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", false));

    private final BindSetting windChargeBind = register(new BindSetting(
            "Wind charge", "Бросить ветровой заряд себе под ноги"));
    private final NumberSetting windSlowdown = register(new NumberSetting(
            "Wind slowdown", "За сколько мс довернуть взгляд вниз", 150, 50, 300, 10));

    private int previousSlot = -1;
    private LivingEntity target;

    private enum WindPhase {
        IDLE, LOOK_DOWN, RESTORE
    }

    private WindPhase windPhase = WindPhase.IDLE;
    private boolean windBindHeld;
    private long windStartAt;
    private long windRestoreAt;
    private float windStartPitch;
    private int windPreviousSlot = -1;
    private int windUsedSlot = -1;

    public MaceHelper() {
        super("MaceHelper", "Assists with mace smash attacks", Category.COMBAT);
    }

    @Override
    public boolean ticksBeforeMovement() {
        return true;
    }

    @Override
    public void onTick() {
        target = null;
        if (mc.player == null || mc.world == null || AutoSwap.isPausing()) {
            cancelWindCharge();
            return;
        }

        handleWindChargeBind();

        // Finish or abort the wind charge sequence before the mace logic so the
        // two never fight over the selected slot or the rotation.
        if (windPhase != WindPhase.IDLE) {
            if (mc.currentScreen != null) {
                cancelWindCharge();
                return;
            }
            tickWindCharge();
            return;
        }

        boolean falling = !mc.player.isOnGround() && mc.player.fallDistance > 0f;
        if (!falling) {
            restoreSlot();
            return;
        }

        if (autoSwitch.get() && !InventoryUtil.isHolding(Items.MACE)) {
            // Searches the hotbar first and pulls the mace out of the main
            // inventory when it only sits there.
            if (InventoryUtil.findInHotbar(Items.MACE) == -1) {
                InventoryUtil.equipFromInventory(Items.MACE);
            }
            int slot = InventoryUtil.findInHotbar(Items.MACE);
            if (slot != -1) {
                if (previousSlot == -1) {
                    previousSlot = InventoryUtil.getSelectedSlot();
                }
                // Vanilla-ordered switch: selecting + reporting the slot in one
                // place avoids the desync that got the account kicked.
                InventoryUtil.switchToSlot(slot);
            }
        }

        if (!InventoryUtil.isHolding(Items.MACE)) {
            return;
        }
        if (mc.player.fallDistance < minFall.get()) {
            return;
        }

        LivingEntity candidate = PlayerUtil.getCrosshairTarget(true, mobs.get());
        if (candidate == null) {
            return;
        }
        if (PlayerUtil.distanceTo(candidate) > range.get()) {
            return;
        }
        if (mc.player.getY() - candidate.getY() < minHeight.get()) {
            return;
        }

        target = candidate;

        if (autoAttack.get() && PlayerUtil.attackCooldown() > 0.9f
                && InventoryUtil.isCooldownReady(Items.MACE) && mc.interactionManager != null) {
            mc.interactionManager.attackEntity(mc.player, candidate);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    // ------------------------------------------------------------- wind charge

    private void handleWindChargeBind() {
        if (!windChargeBind.isSet() || mc.currentScreen != null) {
            windBindHeld = false;
            return;
        }
        boolean down = Keys.isDown(windChargeBind.get());
        if (down && !windBindHeld && windPhase == WindPhase.IDLE) {
            startWindCharge();
        }
        windBindHeld = down;
    }

    private void startWindCharge() {
        if (mc.player == null) {
            return;
        }
        int slot = InventoryUtil.findInHotbar(Items.WIND_CHARGE);
        if (slot == -1) {
            InventoryUtil.equipFromInventory(Items.WIND_CHARGE);
            slot = InventoryUtil.findInHotbar(Items.WIND_CHARGE);
        }
        if (slot == -1) {
            return;
        }

        windPreviousSlot = InventoryUtil.getSelectedSlot();
        windUsedSlot = slot;
        // switchToSlot also emits the vanilla UpdateSelectedSlot packet, so the
        // very next use packet is already addressed to the wind charge.
        InventoryUtil.switchToSlot(slot);

        windStartPitch = mc.player.getPitch();
        windStartAt = System.nanoTime();
        windPhase = WindPhase.LOOK_DOWN;
    }

    /**
     * One rotation step per tick, so the server receives a handful of rotation
     * packets instead of a per-frame stream. The wind charge itself is used as
     * soon as the look-down has finished.
     */
    private void tickWindCharge() {
        long now = System.nanoTime();

        if (windPhase == WindPhase.LOOK_DOWN) {
            long duration = Math.max(1L, (long) (windSlowdown.get() * 1_000_000.0));
            float progress = MathHelper.clamp((now - windStartAt) / (float) duration, 0f, 1f);
            float pitch = MathHelper.lerp(easeOut(progress), windStartPitch, 90f);
            RotationUtil.setRotation(mc.player, mc.player.getYaw(), pitch);

            if (progress >= 1f) {
                RotationUtil.setRotation(mc.player, mc.player.getYaw(), 90f);
                // interactItem sends the look direction inside the use packet and
                // ignores whatever block is under the crosshair, so the wind
                // charge is always thrown straight down.
                if (mc.interactionManager != null) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                windPhase = WindPhase.RESTORE;
                windRestoreAt = now + 50_000_000L;
            }
            return;
        }

        if (windPhase == WindPhase.RESTORE && now >= windRestoreAt) {
            RotationUtil.setRotation(mc.player, mc.player.getYaw(), windStartPitch);
            if (windPreviousSlot != -1 && windPreviousSlot != windUsedSlot) {
                InventoryUtil.switchToSlot(windPreviousSlot);
            }
            windPreviousSlot = -1;
            windUsedSlot = -1;
            windPhase = WindPhase.IDLE;
        }
    }

    private static float easeOut(float progress) {
        float inverse = 1f - progress;
        return 1f - inverse * inverse;
    }

    private void cancelWindCharge() {
        if (windPhase == WindPhase.IDLE) {
            return;
        }
        if (mc.player != null) {
            RotationUtil.setRotation(mc.player, mc.player.getYaw(), windStartPitch);
        }
        if (windPreviousSlot != -1 && windPreviousSlot != windUsedSlot) {
            InventoryUtil.switchToSlot(windPreviousSlot);
        }
        windPreviousSlot = -1;
        windUsedSlot = -1;
        windPhase = WindPhase.IDLE;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void onRender2D(DrawContext context, float tickDelta) {
        if (!indicator.get() || mc.player == null) {
            return;
        }
        if (!InventoryUtil.isFallingWithMace()) {
            return;
        }
        boolean ready = mc.player.fallDistance >= minFall.get();
        String text = ready ? "SMASH" : String.format(java.util.Locale.ROOT, "%.1f", mc.player.fallDistance);
        int color = ready ? 0xFFFFD24A : 0xFFB0B0B0;

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();
        int width = mc.textRenderer.getWidth(text);
        dev.velora.render.Render2D.text(context, text, screenWidth / 2f - width / 2f,
                screenHeight / 2f + 16, color, true);
    }

    private void restoreSlot() {
        if (previousSlot != -1) {
            // Report the restore to the server too, otherwise the client and the
            // server disagree about the held item for the rest of the session.
            InventoryUtil.switchToSlot(previousSlot);
            previousSlot = -1;
        }
    }

    @Override
    public void onDisable() {
        cancelWindCharge();
        restoreSlot();
        target = null;
    }

    public LivingEntity getTarget() {
        return target;
    }
}
