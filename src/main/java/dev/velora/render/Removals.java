package dev.velora.render;

import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;

/**
 * Removаls: hides parts of the vanilla HUD.
 *
 * <p>Every option removes exactly one vanilla element (the mixin queries the
 * module by element name), so the screen can be cleaned up without turning off
 * the Velora HUD itself.</p>
 */
public class Removals extends Module {

    private static Removals active;

    private final BooleanSetting crosshair = register(new BooleanSetting("Crosshair", false));
    private final BooleanSetting hotbar = register(new BooleanSetting("Hotbar", false));
    private final BooleanSetting statusBars = register(new BooleanSetting("Status bars", false));
    private final BooleanSetting experienceBar = register(new BooleanSetting("Experience bar", false));
    private final BooleanSetting heldItemTooltip = register(new BooleanSetting("Held item tooltip", false));
    private final BooleanSetting effects = register(new BooleanSetting("Effects", false));
    private final BooleanSetting scoreboard = register(new BooleanSetting("Scoreboard", false));
    private final BooleanSetting bossBar = register(new BooleanSetting("Boss bar", false));
    private final BooleanSetting playerList = register(new BooleanSetting("Tab list", false));
    private final BooleanSetting vignette = register(new BooleanSetting("Vignette", false));

    public Removals() {
        super("Removals", "Removes parts of the vanilla HUD", Category.RENDER);
        active = this;
    }

    /**
     * @param element vanilla HUD element key, see the switch below.
     */
    public static boolean isRemoving(String element) {
        Removals removals = active;
        if (removals == null || !removals.isEnabled()) {
            return false;
        }
        return switch (element) {
            case "crosshair" -> removals.crosshair.get();
            case "hotbar" -> removals.hotbar.get();
            case "statusBars" -> removals.statusBars.get();
            case "experienceBar" -> removals.experienceBar.get();
            case "heldItemTooltip" -> removals.heldItemTooltip.get();
            case "effects" -> removals.effects.get();
            case "scoreboard" -> removals.scoreboard.get();
            case "bossBar" -> removals.bossBar.get();
            case "playerList" -> removals.playerList.get();
            case "vignette" -> removals.vignette.get();
            default -> false;
        };
    }
}
