package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keybinds: the modules that are switched on and have a bind.
 *
 * <p>Follows the reference "KeyBinds" panel: a card with a keyboard badge in the
 * header and one 11px row per module, the name in white on the left and the
 * bound key in the accent colour on the right.</p>
 */
public class KeybindsWidget extends HudWidget {

    private static final float MIN_WIDTH = 80f;
    private static final float PAD = 6f;

    private final BooleanSetting showHidden = register(new BooleanSetting("Keybinds Hidden", false));
    private final BooleanSetting onlyEnabled = register(new BooleanSetting("Keybinds Enabled only", true));
    private final ModeSetting sort = register(new ModeSetting("Keybinds Sort", "Name", "Name", "Key", "Category"));
    private final BooleanSetting background = register(new BooleanSetting("Keybinds Background", true));

    public KeybindsWidget() {
        super("Keybinds", "Keybinds");
        topRow();
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        List<Module> bound = new ArrayList<>();
        for (Module module : Velora.get().getModuleManager().getModules()) {
            if (module.getKey() == GLFW.GLFW_KEY_UNKNOWN) {
                continue;
            }
            if (onlyEnabled.get() && !module.isEnabled()) {
                continue;
            }
            if (showHidden.get() && module.getName().toLowerCase().contains("hidden")) {
                continue;
            }
            bound.add(module);
        }

        if (sort.is("Name")) {
            bound.sort(Comparator.comparing(Module::getName));
        } else if (sort.is("Category")) {
            bound.sort(Comparator.comparing(module -> module.getCategory().name()));
        } else {
            bound.sort(Comparator.comparingInt(Module::getKey));
        }

        boolean editor = hudEditorOpen();
        if (bound.isEmpty() && !editor) {
            setSize(0, 0);
            return;
        }

        int accent = HudStyle.accent();
        float width = MIN_WIDTH;
        for (Module module : bound) {
            width = Math.max(width, PAD * 2f + Render2D.smallWidth(module.getName())
                    + 12f + Render2D.smallWidth(keyName(module.getKey())));
        }
        int rows = Math.max(1, bound.size());
        float height = HudStyle.HEADER + rows * HudStyle.ROW_HEIGHT + 4f;

        setSize(width, height);
        anchorTopLeft(6f, 8f);
        place();

        float x = screenX();
        float y = screenY();

        if (background.get() || editor) {
            HudStyle.card(context, x, y, width, height, 4f);
        }

        HudStyle.header(context, x, y, width, "KeyBinds");
        HudIcons.keyboard(context, x + width - 16f, y + 5.5f, 8f,
                ColorUtil.withAlpha(accent, 235));

        if (bound.isEmpty()) {
            Render2D.smallText(context, "No binds set", x + PAD, y + HudStyle.HEADER,
                    HudStyle.textFaint());
            setSize(width, height);
            return;
        }

        for (int i = 0; i < bound.size(); i++) {
            Module module = bound.get(i);
            float centerY = y + HudStyle.HEADER + i * HudStyle.ROW_HEIGHT + HudStyle.ROW_HEIGHT / 2f;
            float textY = centerY - Render2D.smallHeight() / 2f;
            Render2D.smallText(context, module.getName(), x + PAD, textY, HudStyle.text());
            Render2D.smallRight(context, keyName(module.getKey()), x + width - PAD, textY,
                    accent);
        }

        setSize(width, height);
    }

    private String keyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) {
            return "NONE";
        }
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) {
            return name.toUpperCase();
        }
        return switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            default -> "KEY_" + key;
        };
    }
}
