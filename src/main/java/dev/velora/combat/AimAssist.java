package dev.velora.combat;

import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.PlayerUtil;
import dev.velora.util.RotationUtil;
import dev.velora.util.TargetUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Simple aim assist: smoothly rotates the camera towards the target.
 *
 * <p>No complex humanization, no tremor, no overshoot. Just a smooth
 * exponential follow that looks like a normal player moving their mouse.</p>
 */
public class AimAssist extends Module {

    private final NumberSetting range = register(new NumberSetting(
            "Range", "Максимальная дистанция до цели", 4.0, 2.0, 6.0, 0.1));

    private final NumberSetting fov = register(new NumberSetting(
            "FOV", "Угол обзора для поиска цели", 60, 10, 180, 5));

    private final NumberSetting speed = register(new NumberSetting(
            "Speed", "Скорость наведения", 4.0, 0.5, 10.0, 0.5));

    private final BooleanSetting players = register(new BooleanSetting(
            "Players", "Наводиться на игроков", true));

    private final BooleanSetting mobs = register(new BooleanSetting(
            "Mobs", "Наводиться на мобов", false));

    private final BooleanSetting throughWalls = register(new BooleanSetting(
            "Through walls", "Видеть сквозь стены", false));

    private final BooleanSetting onlyWhenTargeted = register(new BooleanSetting(
            "Only when targeted", "Работать только когда прицел на цели", true));

    private final ModeSetting mode = register(new ModeSetting(
            "Mode", "Режим наведения", "Smooth", "Smooth", "Linear"));

    private LivingEntity target;

    public AimAssist() {
        super("AimAssist", "Плавно помогает наводиться на цель", Category.COMBAT);
    }

    public LivingEntity getTarget() {
        return target;
    }

    @Override
    public void onFrame(float deltaTicks) {
        target = null;

        if (mc.player == null || mc.world == null || mc.currentScreen != null
                || AutoSwap.isFreezing()) {
            return;
        }

        if (!mc.player.isAlive() || mc.player.isSpectator()) {
            return;
        }

        // Find target
        LivingEntity candidate = TargetUtil.findBest(range.get(), fov.get(),
                players.get(), mobs.get(), throughWalls.get());

        if (candidate == null) {
            return;
        }

        // Check if crosshair is already on target (if enabled)
        if (onlyWhenTargeted.get() && !isCrosshairOn(candidate)) {
            return;
        }

        target = candidate;

        // Calculate rotation to target
        Vec3d eyes = mc.player.getEyePos();
        Vec3d targetPos = TargetUtil.aimPoint(candidate);
        RotationUtil.Rotation rotation = RotationUtil.look(eyes, targetPos);

        // Apply rotation
        float yawDelta = wrapDegrees(rotation.yaw() - mc.player.getYaw());
        float pitchDelta = rotation.pitch() - mc.player.getPitch();

        // Deadzone
        if (Math.abs(yawDelta) < 0.5f && Math.abs(pitchDelta) < 0.5f) {
            return;
        }

        float step = speed.getFloat() * deltaTicks * 2.0f;

        if (mode.is("Linear")) {
            // Linear: constant speed
            float yawStep = Math.min(Math.abs(yawDelta), step) * Math.signum(yawDelta);
            float pitchStep = Math.min(Math.abs(pitchDelta), step) * Math.signum(pitchDelta);
            mc.player.setYaw(mc.player.getYaw() + yawStep);
            mc.player.setPitch(mc.player.getPitch() + pitchStep);
        } else {
            // Smooth: exponential ease-out
            float response = 1.0f - (float) Math.exp(-speed.getFloat() * deltaTicks * 0.1f);
            float yawStep = yawDelta * response;
            float pitchStep = pitchDelta * response;

            // Limit step size
            float maxStep = speed.getFloat() * deltaTicks * 3.0f;
            yawStep = Math.min(Math.abs(yawStep), maxStep) * Math.signum(yawStep);
            pitchStep = Math.min(Math.abs(pitchStep), maxStep) * Math.signum(pitchStep);

            mc.player.setYaw(mc.player.getYaw() + yawStep);
            mc.player.setPitch(mc.player.getPitch() + pitchStep);
        }
    }

    private boolean isCrosshairOn(LivingEntity entity) {
        if (mc.player == null) {
            return false;
        }
        Vec3d eyes = mc.player.getEyePos();
        Vec3d targetPos = TargetUtil.aimPoint(entity);
        RotationUtil.Rotation rotation = RotationUtil.look(eyes, targetPos);
        float yawDiff = Math.abs(wrapDegrees(rotation.yaw() - mc.player.getYaw()));
        float pitchDiff = Math.abs(rotation.pitch() - mc.player.getPitch());
        return yawDiff < 20f && pitchDiff < 20f;
    }

    private float wrapDegrees(float degrees) {
        float f = degrees % 360.0F;
        if (f >= 180.0F) {
            f -= 360.0F;
        }
        if (f < -180.0F) {
            f += 360.0F;
        }
        return f;
    }

    @Override
    public String getSuffix() {
        return mode.getValue();
    }
}
