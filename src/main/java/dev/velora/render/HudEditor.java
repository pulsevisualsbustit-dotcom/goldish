package dev.velora.render;

import dev.velora.Velora;
import dev.velora.render.Theme;
import dev.velora.render.Themes;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * Drag-and-drop editor for HUD widgets. It is active only while the vanilla
 * chat screen is open: click a widget and move the mouse, exactly like
 * arranging widgets in a normal cheat client.
 */
public final class HudEditor {

    private static HudElement dragging;
    private static float grabOffsetX;
    private static float grabOffsetY;

    /** Theme dots shown at the bottom of the editor. */
    private static final float SWATCH = 13f;
    private static final float SWATCH_GAP = 5f;
    private static final float SWATCH_BOTTOM = 40f;

    private HudEditor() {
    }

    private static List<? extends HudElement> elements() {
        Hud hud = Velora.get().getModuleManager().get(Hud.class);
        return hud == null ? List.of() : hud.getWidgets();
    }

    public static boolean isDragging() {
        return dragging != null;
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        // Theme dots sit above the widgets, so they get the click first.
        int theme = themeAt(mouseX, mouseY);
        if (theme >= 0) {
            Velora.get().getConfigManager().applyTheme(Themes.ALL.get(theme));
            return true;
        }
        List<? extends HudElement> elements = elements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            if (!element.isEnabled() || element.hudWidth() <= 0f || element.hudHeight() <= 0f) {
                continue;
            }
            if (inside(element, mouseX, mouseY, 3)) {
                dragging = element;
                grabOffsetX = (float) mouseX - element.hudX();
                grabOffsetY = (float) mouseY - element.hudY();
                return true;
            }
        }
        return false;
    }

    public static boolean mouseDragged(double mouseX, double mouseY) {
        if (dragging == null) {
            return false;
        }
        dragging.moveHud((float) mouseX - grabOffsetX, (float) mouseY - grabOffsetY);
        return true;
    }

    public static boolean mouseReleased() {
        if (dragging == null) {
            return false;
        }
        dragging = null;
        Velora.get().getConfigManager().save();
        return true;
    }

    public static void cancel() {
        dragging = null;
    }

    public static void render(DrawContext context, int mouseX, int mouseY) {
        int accent = Velora.get().getConfigManager().getAccentColor();
        var client = Velora.mc();
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();

        for (HudElement element : elements()) {
            if (!element.isEnabled() || element.hudWidth() <= 0f || element.hudHeight() <= 0f) {
                continue;
            }

            float x = element.hudX();
            float y = element.hudY();
            float width = element.hudWidth();
            float height = element.hudHeight();
            boolean hovered = inside(element, mouseX, mouseY, 3);
            boolean active = dragging == element;
            boolean focus = hovered || active;

            if (active) {
                // While dragging: a soft accent wash instead of a hard outline.
                Render2D.shadow(context, x - 3, y - 3, width + 6, height + 6, 5,
                        ColorUtil.withAlpha(accent, 70));
            }
            Render2D.roundRect(context, x - 3.5f, y - 3.5f, width + 7, height + 7, 5.5f,
                    ColorUtil.withAlpha(accent, focus ? 34 : 18));
            // Dashed 1px frame: reads as "editable" rather than a hard selection box.
            dashedFrame(context, x - 2.5f, y - 2.5f, width + 5, height + 5,
                    ColorUtil.withAlpha(accent, focus ? 255 : 150));

            // The name tag only appears on hover: every card already has a header,
            // so painting a pill over all of them just hides the design.
            if (!focus) {
                continue;
            }
            String label = element.getName();
            float labelWidth = Render2D.textWidth(label) + 12;
            float labelX = MathHelper.clamp(x - 2.5f, 2f, windowWidth - labelWidth - 2f);
            float labelY = y - 15;
            if (labelY < 2) {
                labelY = y + height + 5;
            }
            Render2D.roundRect(context, labelX, labelY, labelWidth, 12, 3.5f, 0xEA0E0E12);
            Render2D.roundRect(context, labelX + 0.5f, labelY + 0.5f, labelWidth - 1, 11, 3f, 0x14FFFFFF);
            Render2D.circle(context, labelX + 5.5f, labelY + 6, 4.2f,
                    ColorUtil.withAlpha(accent, 255));
            Render2D.text(context, label, labelX + 10.5f, labelY + 2f, accent, false);
        }

        // Theme swatches: one click retunes the whole HUD without opening a screen.
        var config = Velora.get().getConfigManager();
        float dotY = swatchY(windowHeight);
        for (int i = 0; i < Themes.ALL.size(); i++) {
            Theme theme = Themes.ALL.get(i);
            float x = swatchX(i, windowWidth);
            boolean active = config.getThemeId().equalsIgnoreCase(theme.id());
            boolean hovered = themeAt(mouseX, mouseY) == i;
            Render2D.roundRect(context, x - 1.5f, dotY - 1.5f, SWATCH + 3f, SWATCH + 3f, 4.5f,
                    ColorUtil.withAlpha(0xFFFFFF, active ? 210 : hovered ? 120 : 32));
            Render2D.roundRectGradient(context, x, dotY, SWATCH, SWATCH, 3.5f,
                    theme.accent(), theme.accentSecondary());
            if (active) {
                Render2D.circle(context, x + SWATCH / 2f, dotY + SWATCH / 2f, 3.4f,
                        ColorUtil.withAlpha(0xFFFFFFFF, 235));
            }
        }

        String hint = "Редактор HUD  [H]";
        float hintWidth = Render2D.textWidth(hint) + 24;
        float hintX = (windowWidth - hintWidth) / 2f;
        Render2D.roundRect(context, hintX, 4, hintWidth, 17, 4f, 0xEA0E0E14);
        Render2D.roundRect(context, hintX + 0.5f, 4.5f, hintWidth - 1, 16, 3.5f, 0x18FFFFFF);
        Render2D.roundRectGradient(context, hintX + 9, 19.4f, hintWidth - 18, 1.5f, 0.75f,
                ColorUtil.withAlpha(accent, 230), ColorUtil.withAlpha(accent, 0));
        Render2D.centeredText(context, hint, windowWidth / 2f, 8.5f,
                ColorUtil.withAlpha(HudStyle.text(), 240), false);
    }

    private static float swatchRowWidth() {
        int count = Themes.ALL.size();
        return count * SWATCH + (count - 1) * SWATCH_GAP;
    }

    private static float swatchX(int index, int windowWidth) {
        return (windowWidth - swatchRowWidth()) / 2f + index * (SWATCH + SWATCH_GAP);
    }

    private static float swatchY(int windowHeight) {
        return windowHeight - SWATCH_BOTTOM - SWATCH;
    }

    /** @return index of the theme dot under the mouse, or -1. */
    private static int themeAt(double mouseX, double mouseY) {
        var window = Velora.mc().getWindow();
        int windowWidth = window.getScaledWidth();
        float dotY = swatchY(window.getScaledHeight());
        for (int i = 0; i < Themes.ALL.size(); i++) {
            float x = swatchX(i, windowWidth);
            if (mouseX >= x - 2f && mouseX <= x + SWATCH + 2f
                    && mouseY >= dotY - 2f && mouseY <= dotY + SWATCH + 2f) {
                return i;
            }
        }
        return -1;
    }

    /** 1px dashed rectangle: alternating light and dark dashes, no solid border. */
    private static void dashedFrame(DrawContext context, float x, float y, float width, float height,
                                    int color) {
        float dash = 4f;
        for (float dx = 0; dx < width; dx += dash * 2) {
            float segment = Math.min(dash, width - dx);
            Render2D.rect(context, x + dx, y, segment, 1, color);
            Render2D.rect(context, x + dx, y + height, segment, 1, color);
        }
        for (float dy = 0; dy < height; dy += dash * 2) {
            float segment = Math.min(dash, height - dy);
            Render2D.rect(context, x, y + dy, 1, segment, color);
            Render2D.rect(context, x + width, y + dy, 1, segment, color);
        }
    }

    private static boolean inside(HudElement element, double mouseX, double mouseY, float padding) {
        return mouseX >= element.hudX() - padding
                && mouseX <= element.hudX() + element.hudWidth() + padding
                && mouseY >= element.hudY() - padding
                && mouseY <= element.hudY() + element.hudHeight() + padding;
    }
}
