package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ModuleList: the enabled modules in the top-right corner.
 *
 * <p>Follows the reference panels: a card with a "Modules" header, a list badge
 * on the right and one 11px row per module. The name sits on the left, an
 * optional suffix is right-aligned in the accent colour. The card keeps itself
 * clear of the vanilla status-effect icons.</p>
 */
public class ModuleListWidget extends HudWidget {

    private static final float MIN_WIDTH = 80f;
    private static final float PAD = 6f;

    private final BooleanSetting showSuffix = register(new BooleanSetting("Modules Suffix", true));
    private final BooleanSetting background = register(new BooleanSetting("Modules Background", true));

    public ModuleListWidget() {
        super("ModuleList", "Module list");
        // Right-hand column: it stacks with the media player and cooldown cards.
        topRow("right");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        List<Module> modules = new ArrayList<>(Velora.get().getModuleManager().getEnabledModules());
        boolean editor = hudEditorOpen();
        if (modules.isEmpty() && !editor) {
            setSize(0, 0);
            return;
        }

        // Alphabetical, like the reference list; the suffix stays out of the sort.
        modules.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));

        int accent = HudStyle.accent();
        float width = Math.max(MIN_WIDTH,
                PAD * 2f + Render2D.textWidth("Modules") + 26f);
        for (Module module : modules) {
            float row = PAD + Render2D.smallWidth(module.getName());
            String suffix = showSuffix.get() ? module.getSuffix() : "";
            if (!suffix.isEmpty()) {
                row += 8f + Render2D.smallWidth(suffix);
            }
            width = Math.max(width, row + PAD);
        }

        int rows = Math.max(1, modules.size());
        float height = HudStyle.HEADER + rows * HudStyle.ROW_HEIGHT + 4f;

        setSize(width, height);
        anchorTopRightClear(5f);
        place();

        float x = screenX();
        float y = screenY();

        if (background.get() || editor) {
            HudStyle.card(context, x, y, width, height, 4f);
        }

        HudStyle.header(context, x, y, width, "Modules");
        HudIcons.list(context, x + width - 16f, y + 5.5f, 8f,
                ColorUtil.withAlpha(accent, 235));

        if (modules.isEmpty()) {
            Render2D.smallText(context, "No modules enabled", x + PAD, y + HudStyle.HEADER,
                    HudStyle.textFaint());
            setSize(width, height);
            return;
        }

        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            float centerY = y + HudStyle.HEADER + i * HudStyle.ROW_HEIGHT + HudStyle.ROW_HEIGHT / 2f;
            float textY = centerY - Render2D.smallHeight() / 2f;

            Render2D.smallText(context, module.getName(), x + PAD, textY, HudStyle.text());

            String suffix = showSuffix.get() ? module.getSuffix() : "";
            if (!suffix.isEmpty()) {
                Render2D.smallRight(context, suffix, x + width - PAD, textY, accent);
            }
        }

        setSize(width, height);
    }
}
