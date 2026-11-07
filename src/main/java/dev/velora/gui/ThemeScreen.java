package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.render.ColorUtil;
import dev.velora.render.Render2D;
import dev.velora.render.Theme;
import dev.velora.render.Themes;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * In-client theme picker. Click a card to apply the theme immediately; the
 * choice is persisted in the config.
 *
 * <p>The grid is rebuilt from {@link Themes#ALL} every frame, so themes loaded
 * from {@code config/velora/themes} or saved from this screen appear without a
 * restart. Custom themes can be removed with a right click.</p>
 */
public class ThemeScreen extends Screen {

    private static final int COLUMNS = 3;
    private static final float CARD_WIDTH = 150;
    private static final float CARD_HEIGHT = 62;
    private static final float CARD_GAP = 10;
    private static final float PADDING = 16;
    private static final float HEADER = 42;
    private static final float FOOTER = 46;

    private final Screen parent;

    private float uiScale = 1f;
    private float offsetX;
    private float offsetY;
    private float designHeight = 300f;
    private int rows = 3;

    private String status = "";
    private int statusColor = 0xFFB9B9C4;

    public ThemeScreen(Screen parent) {
        super(Text.literal("Themes"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        recalculateLayout();
    }

    /** The grid grows with the number of themes; the whole UI scales to fit. */
    private void recalculateLayout() {
        rows = Math.max(1, (Themes.ALL.size() + COLUMNS - 1) / COLUMNS);
        float designWidth = COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP + PADDING * 2;
        designHeight = HEADER + rows * CARD_HEIGHT + (rows - 1) * CARD_GAP + FOOTER;
        uiScale = Math.min(1f, Math.min((width - 12f) / designWidth,
                (height - 12f) / designHeight));
        offsetX = (width - designWidth * uiScale) / 2f;
        offsetY = (height - designHeight * uiScale) / 2f;
    }

    private float designWidth() {
        return COLUMNS * CARD_WIDTH + (COLUMNS - 1) * CARD_GAP + PADDING * 2;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        Render2D.ensureInit();
        recalculateLayout();

        var config = Velora.get().getConfigManager();
        int accent = config.getAccentColor();
        int accentSecondary = config.getAccentSecondary();
        float designWidth = designWidth();

        double localMouseX = toDesignX(mouseX);
        double localMouseY = toDesignY(mouseY);

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(offsetX, offsetY);
        matrices.scale(uiScale, uiScale);

        Render2D.shadow(context, 0, 0, designWidth, designHeight, 12, 0x60000000);
        Render2D.roundRect(context, 0, 0, designWidth, designHeight, 10,
                ColorUtil.withAlpha(config.getBackgroundColor(), 245));
        Render2D.roundRect(context, 0, 0, designWidth, 2.5f, 1.25f, accent);

        String title = "THEMES";
        float titleWidth = Render2D.textWidth(title);
        Render2D.gradientText(context, title, (designWidth - titleWidth) / 2f, 12,
                accent, accentSecondary, true);
        Render2D.logo(context, 12, 8, 20, accent);
        Render2D.centeredText(context, "Click to apply · right-click a custom theme to delete",
                designWidth / 2f, 25, config.getMutedTextColor(), false);

        for (int i = 0; i < Themes.ALL.size(); i++) {
            Theme theme = Themes.ALL.get(i);
            float x = cardX(i);
            float y = cardY(i);
            boolean hovered = localMouseX >= x && localMouseX <= x + CARD_WIDTH
                    && localMouseY >= y && localMouseY <= y + CARD_HEIGHT;
            boolean active = config.getThemeId().equalsIgnoreCase(theme.id());

            Render2D.panel(context, x, y, CARD_WIDTH, CARD_HEIGHT, 7,
                    ColorUtil.withAlpha(theme.background(), hovered ? 255 : 235),
                    ColorUtil.withAlpha(theme.accent(), active ? 240 : hovered ? 160 : 55),
                    active ? 1.6f : 1f);

            Render2D.text(context, theme.name(), x + 10, y + 8,
                    ColorUtil.withAlpha(theme.accent(), 255), true);

            float swatchY = y + 26;
            Render2D.circle(context, x + 15, swatchY, 9, theme.accent());
            Render2D.circle(context, x + 27, swatchY, 9, theme.accentSecondary());
            Render2D.circle(context, x + 39, swatchY, 9, theme.background());
            Render2D.circle(context, x + 51, swatchY, 9, theme.text());

            String tag = active ? "ACTIVE" : hovered ? "APPLY" : "";
            if (!tag.isEmpty()) {
                float tagWidth = Render2D.textWidth(tag);
                Render2D.text(context, tag, x + CARD_WIDTH - 10 - tagWidth, y + 8,
                        active ? ColorUtil.withAlpha(theme.accent(), 240) : 0xFF8A8A96, false);
            }

            Render2D.text(context, Themes.isCustom(theme.id())
                            ? "custom · accent · text" : "accent · text · background",
                    x + 10, y + 41, ColorUtil.withAlpha(theme.muted(), 220), false);
        }

        float backX = designWidth - PADDING - 64;
        float backY = designHeight - 30;
        boolean backHovered = localMouseX >= backX && localMouseX <= backX + 64
                && localMouseY >= backY && localMouseY <= backY + 20;
        Render2D.panel(context, backX, backY, 64, 20, 5,
                backHovered ? ColorUtil.withAlpha(accent, 210) : 0xFF1A1A21,
                ColorUtil.withAlpha(accent, backHovered ? 240 : 100), 1);
        Render2D.centeredText(context, "BACK", backX + 32, backY + 6,
                backHovered ? 0xFF1A1206 : 0xFFE9E9EF, true);

        float saveX = 16;
        boolean saveHovered = localMouseX >= saveX && localMouseX <= saveX + 120
                && localMouseY >= backY && localMouseY <= backY + 20;
        Render2D.panel(context, saveX, backY, 120, 20, 5,
                saveHovered ? ColorUtil.withAlpha(accent, 210) : 0xFF1A1A21,
                ColorUtil.withAlpha(accent, saveHovered ? 240 : 100), 1);
        Render2D.centeredText(context, "SAVE CURRENT", saveX + 60, backY + 6,
                saveHovered ? 0xFF1A1206 : 0xFFE9E9EF, true);

        if (!status.isEmpty()) {
            Render2D.text(context, status, 146, backY + 6, statusColor, false);
        }

        matrices.popMatrix();
    }

    private float cardX(int index) {
        int column = index % COLUMNS;
        return PADDING + column * (CARD_WIDTH + CARD_GAP);
    }

    private float cardY(int index) {
        int row = index / COLUMNS;
        return HEADER + row * (CARD_HEIGHT + CARD_GAP);
    }

    private double toDesignX(double mouseX) {
        return (mouseX - offsetX) / uiScale;
    }

    private double toDesignY(double mouseY) {
        return (mouseY - offsetY) / uiScale;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        recalculateLayout();
        double mouseX = toDesignX(click.x());
        double mouseY = toDesignY(click.y());

        float designWidth = designWidth();
        float backX = designWidth - PADDING - 64;
        float backY = designHeight - 30;
        if (mouseX >= backX && mouseX <= backX + 64 && mouseY >= backY && mouseY <= backY + 20) {
            if (click.button() == 0) {
                close();
            }
            return true;
        }

        float saveX = 16;
        if (mouseX >= saveX && mouseX <= saveX + 120 && mouseY >= backY && mouseY <= backY + 20) {
            if (click.button() == 0) {
                saveCurrentTheme();
            }
            return true;
        }

        for (int i = 0; i < Themes.ALL.size(); i++) {
            float x = cardX(i);
            float y = cardY(i);
            if (mouseX >= x && mouseX <= x + CARD_WIDTH && mouseY >= y && mouseY <= y + CARD_HEIGHT) {
                Theme theme = Themes.ALL.get(i);
                if (click.button() == 1 && Themes.isCustom(theme.id())) {
                    if (Themes.deleteCustom(theme.id())) {
                        if (Velora.get().getConfigManager().getThemeId().equalsIgnoreCase(theme.id())) {
                            Velora.get().getConfigManager().applyTheme(Themes.DEFAULT);
                        }
                        setStatus("Deleted " + theme.name(), 0xFF4ADE80);
                        recalculateLayout();
                    }
                    return true;
                }
                if (click.button() == 0) {
                    Velora.get().getConfigManager().applyTheme(theme);
                    setStatus("Applied " + theme.name(), 0xFF4ADE80);
                }
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void saveCurrentTheme() {
        var config = Velora.get().getConfigManager();
        String name = "Custom " + (Themes.CUSTOM.size() + 1);
        Theme saved = Themes.saveCustom(name,
                config.getAccentColor(), config.getAccentSecondary(),
                config.getBackgroundColor(), config.getTextColor(), config.getMutedTextColor());
        if (saved == null) {
            setStatus("Could not save the theme", 0xFFFF6B6B);
            return;
        }
        // Saving only writes a theme file; the active theme stays untouched.
        setStatus("Saved " + saved.name(), 0xFF4ADE80);
        recalculateLayout();
    }

    private void setStatus(String message, int color) {
        status = message;
        statusColor = color;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
