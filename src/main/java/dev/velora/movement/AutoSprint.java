package dev.velora.movement;

import dev.velora.combat.AutoSwap;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;

/**
 * Automatic sprinting.
 *
 * <p>With <b>Keep Sprint</b> enabled the module re-asserts the sprint flag every
 * tick. Vanilla clears it when the player stops pressing forward, attacks, or
 * when the hunger bar is low; re-asserting makes the sprint survive those cases,
 * which is the behaviour "Keep Sprint" is known for.</p>
 */
public class AutoSprint extends Module {

    private final BooleanSetting keepSprint = register(new BooleanSetting("Keep Sprint", true));
    private final BooleanSetting onlyMoving = register(new BooleanSetting("Only Moving", true));
    private final BooleanSetting whileUsingItem = register(new BooleanSetting("While Using Item", false));

    public AutoSprint() {
        super("AutoSprint", "Sprints automatically, optionally keeping the sprint through attacks", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }
        // AutoSwap is stopping on purpose: do not re-assert sprint, otherwise
        // the player keeps sliding through the swap.
        if (AutoSwap.isPausing()) {
            return;
        }
        var player = mc.player;
        if (player.isSneaking() || player.hasVehicle()) {
            return;
        }

        boolean moving = player.input.hasForwardMovement();
        if (onlyMoving.get() && !moving) {
            return;
        }
        if (!whileUsingItem.get() && player.isUsingItem()) {
            return;
        }

        if (keepSprint.get()) {
            // Deliberately ignores hunger and collisions: that is what "keep" means.
            player.setSprinting(true);
            return;
        }

        if (moving && !player.isUsingItem() && !player.horizontalCollision
                && player.getHungerManager().canSprint()) {
            player.setSprinting(true);
        }
    }

    @Override
    public String getSuffix() {
        return keepSprint.get() ? "Keep" : "";
    }
}
