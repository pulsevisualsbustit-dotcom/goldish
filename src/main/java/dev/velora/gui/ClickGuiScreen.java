package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.misc.ClientSounds;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.render.ColorUtil;
import dev.velora.render.HudStyle;
import dev.velora.render.Render2D;
import dev.velora.render.SystemFont;
import dev.velora.setting.BindSetting;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ColorSetting;
import dev.velora.setting.ItemBindSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.MultiBooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.setting.Setting;
import dev.velora.setting.TextSetting;
import dev.velora.util.Keys;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The classic Velora ClickGUI: one draggable panel per category with animated
 * module expansion, inline sliders, mode pickers and key bind capture.
 *
 * <p>The layout, spacing and colours are the ones the client has always used;
 * only the wordmark and the logo asset are Velora's. Panels remember where the
 * user dragged them, and every setting is drawn directly under its module, so
 * the whole configuration is visible without switching tabs.</p>
 */
public class ClickGuiScreen extends Screen {

    /** Preferred panel width; narrowed on small windows so all panels fit. */
    private static final float PANEL_WIDTH_MAX = 148;
    /** Column gap between the category panels. */
    private static final float PANEL_GAP = 14;
    private float panelWidth = PANEL_WIDTH_MAX;
    private static final float HEADER_HEIGHT = 24;
    private static final float ROW_HEIGHT = 20;
    private static final float SETTING_HEIGHT = 18;
    private static final float PADDING = 5;

    private static final float CHIP_WIDTH = 52;
    private static final float CHIP_HEIGHT = 18;
    private static final float CHIP_GAP = 6;
    private static final String[] CHIP_LABELS = {"THEMES", "CONFIG", "ALTS"};

    private final List<CategoryPanel> panels = new ArrayList<>();

    private long lastFrameNanos;
    private float openProgress;

    private Module bindingModule;
    private BindSetting bindingSetting;
    private ItemBindSetting bindingItem;
    private NumberSetting draggingSlider;
    private float sliderLeft;
    private float sliderWidth;

    /** Inline text editor, used by settings that are neither a slider nor a chip. */
    private TextSetting editingText;
    private final StringBuilder textBuffer = new StringBuilder();

    public ClickGuiScreen() {
        super(Text.literal(Velora.NAME));
    }

    @Override
    protected void init() {
        super.init();
        if (!panels.isEmpty()) {
            return;
        }

        // Lay the panels out in one centred row that always fits the screen, so a
        // narrow window never pushes the last category off the right edge.
        Category[] categories = Category.values();
        float gap = PANEL_GAP;
        // Shrink the panels on small screens instead of letting them run off-screen.
        float available = width - 24f - (categories.length - 1) * gap;
        panelWidth = MathHelper.clamp(available / categories.length, 112f, PANEL_WIDTH_MAX);

        float totalWidth = categories.length * panelWidth + (categories.length - 1) * gap;
        float startX = Math.max(10f, (width - totalWidth) / 2f);
        float startY = Math.min(58f, Math.max(34f, height * 0.1f));

        float x = startX;
        for (Category category : categories) {
            float[] position = Velora.get().getConfigManager().getPanelPosition(category, x, startY);
            CategoryPanel panel = new CategoryPanel(category, position[0], position[1]);
            // A saved position may come from another resolution: pull it back inside.
            panel.x = MathHelper.clamp(panel.x, 0f, Math.max(0f, width - panelWidth));
            panel.y = MathHelper.clamp(panel.y, 0f, Math.max(0f, height - 120f));
            panels.add(panel);
            x += panelWidth + gap;
        }
        // combat first, render second
        panels.sort(Comparator.comparing(panel -> panel.category.ordinal()));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        long now = System.nanoTime();
        float frameFactor = lastFrameNanos == 0 ? 1f
                : MathHelper.clamp((now - lastFrameNanos) / 1_000_000f / 16.6f, 0f, 4f);
        lastFrameNanos = now;

        // A quick, snappy open: the whole GUI is fully in place in about six frames.
        openProgress = MathHelper.clamp(openProgress + frameFactor * 0.3f, 0f, 1f);
        float ease = 1f - (1f - openProgress) * (1f - openProgress);

        // Screen#renderWithTooltip already draws the dimmed/blurred background before render().
        Render2D.ensureInit();
        // A flat scrim keeps the panels readable over a bright sky; the old GUI
        // relied on the vanilla blur, which this version no longer draws here.
        Render2D.rect(context, 0f, 0f, width, height, 0x6E000000);

        float alpha = 0.4f + ease * 0.6f;
        int accent = Velora.get().getConfigManager().getAccentColor();
        int accentSecondary = Velora.get().getConfigManager().getAccentSecondary();

        renderHeader(context, alpha, accent, accentSecondary);

        for (CategoryPanel panel : panels) {
            panel.render(context, mouseX, mouseY, frameFactor, alpha);
        }

        renderChips(context, mouseX, mouseY, alpha, accent);

        // hovered setting description
        String description = null;
        for (CategoryPanel panel : panels) {
            String found = panel.getHoveredDescription(mouseX, mouseY);
            if (found != null && !found.isEmpty()) {
                description = found;
                break;
            }
        }
        if (description != null) {
            float boxWidth = SystemFont.SMALL.width(description) + 14;
            float boxX = MathHelper.clamp(mouseX + 9, 2, width - boxWidth - 2);
            float boxY = MathHelper.clamp(mouseY + 13, 2, height - 22);
            Render2D.roundRect(context, boxX, boxY, boxWidth, 16, 4f, 0xF20E0E12);
            Render2D.roundRect(context, boxX + 0.5f, boxY + 0.5f, boxWidth - 1, 15, 3.5f, 0x14FFFFFF);
            Render2D.smallText(context, description, boxX + 7, boxY + 4, 0xFFC9C9D4);
        }

        if (bindingModule != null || bindingSetting != null || bindingItem != null) {
            String target = bindingItem != null ? bindingItem.getName()
                    : bindingSetting != null ? bindingSetting.getName() : bindingModule.getName();
            String hint = "PRESS A KEY FOR " + target.toUpperCase() + "   ESC TO CANCEL";
            float hintWidth = SystemFont.SMALL.width(hint) + 22;
            float hintX = (width - hintWidth) / 2f;
            Render2D.roundRect(context, hintX, height - 34, hintWidth, 20, 10, 0xF20E0E12);
            Render2D.roundRect(context, hintX + 0.5f, height - 33.5f, hintWidth - 1, 19, 9.5f, 0x16FFFFFF);
            Render2D.smallCentered(context, hint, width / 2f, height - 28,
                    ColorUtil.withAlpha(accent, 235));
        } else if (editingText != null) {
            String hint = "TYPE A VALUE   ENTER TO SAVE   ESC TO CANCEL";
            float hintWidth = SystemFont.SMALL.width(hint) + 22;
            float hintX = (width - hintWidth) / 2f;
            Render2D.roundRect(context, hintX, height - 34, hintWidth, 20, 10, 0xF20E0E12);
            Render2D.roundRect(context, hintX + 0.5f, height - 33.5f, hintWidth - 1, 19, 9.5f, 0x16FFFFFF);
            Render2D.smallCentered(context, hint, width / 2f, height - 28,
                    ColorUtil.withAlpha(accent, 235));
        }
    }

    /** Top bar: the wordmark on the left, the action chips on the right. */
    private void renderHeader(DrawContext context, float alpha, int accent, int accentSecondary) {
        // A thin glass strip across the top separates the GUI from the game and
        // keeps the header readable over a bright sky.
        float barHeight = 32f;
        Render2D.roundRectGradient(context, 0, 0, width, barHeight, 0f,
                ColorUtil.withAlpha(0xFF101014, Math.round(215 * alpha)),
                ColorUtil.withAlpha(0xFF101014, Math.round(120 * alpha)));
        Render2D.gradientHorizontal(context, 0, barHeight - 1, width, 1,
                ColorUtil.withAlpha(accent, 0), ColorUtil.withAlpha(accent, Math.round(90 * alpha)));
        Render2D.gradientHorizontal(context, width * 0.4f, barHeight - 1, width * 0.6f, 1,
                ColorUtil.withAlpha(accentSecondary, Math.round(90 * alpha)),
                ColorUtil.withAlpha(accentSecondary, 0));

        float x = 14;
        float centerY = 16;

        // The client logo in the top-left corner of the bar.
        float tile = 26;
        if (!Render2D.logo(context, x, centerY - tile / 2f, tile)) {
            Render2D.circle(context, x + tile / 2f, centerY, tile * 0.5f,
                    ColorUtil.withAlpha(accent, 60));
            Render2D.circle(context, x + tile / 2f, centerY, tile * 0.24f,
                    ColorUtil.withAlpha(accent, 245));
        }

        float textX = x + tile + 8;
        Render2D.gradientText(context, Velora.NAME.toUpperCase(), textX, centerY - 6,
                ColorUtil.withAlpha(ColorUtil.lerp(accent, 0xFFFFFFFF, 0.25f), Math.round(255 * alpha)),
                ColorUtil.withAlpha(ColorUtil.lerp(accentSecondary, 0xFFFFFFFF, 0.25f), Math.round(255 * alpha)),
                true);
        float nameWidth = Render2D.textWidth(Velora.NAME.toUpperCase());
        Render2D.smallText(context, "v" + Velora.VERSION, textX + nameWidth + 6, centerY - 4,
                ColorUtil.withAlpha(0xFF6E6E7E, Math.round(255 * alpha)));
    }

    private float chipX(int indexFromRight) {
        return width - CHIP_WIDTH - 12 - indexFromRight * (CHIP_WIDTH + CHIP_GAP);
    }

    private float chipY() {
        return 7;
    }

    private void renderChips(DrawContext context, int mouseX, int mouseY, float alpha, int accent) {
        for (int i = 0; i < CHIP_LABELS.length; i++) {
            float x = chipX(i);
            float y = chipY();
            String label = CHIP_LABELS[i];
            boolean hovered = mouseX >= x && mouseX <= x + CHIP_WIDTH && mouseY >= y && mouseY <= y + CHIP_HEIGHT;

            Render2D.roundRect(context, x, y, CHIP_WIDTH, CHIP_HEIGHT, 6f,
                    hovered ? ColorUtil.withAlpha(ColorUtil.scale(accent, 0.92f), Math.round(235 * alpha))
                            : 0xF20E0E12);
            if (!hovered) {
                Render2D.roundRect(context, x + 0.5f, y + 0.5f, CHIP_WIDTH - 1, CHIP_HEIGHT - 1, 5.5f,
                        0x18FFFFFF);
                Render2D.roundRect(context, x + 0.5f, y + 0.5f, CHIP_WIDTH - 1, CHIP_HEIGHT - 1, 5.5f,
                        ColorUtil.withAlpha(accent, Math.round(55 * alpha)));
            }
            Render2D.smallCentered(context, label, x + CHIP_WIDTH / 2f, y + 5,
                    hovered ? 0xFF14100A : ColorUtil.withAlpha(accent, Math.round(255 * alpha)));
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Clicking anywhere else finishes an inline text edit; the value is
        // written back before the click is processed.
        commitTextEdit();

        if (bindingItem != null && button >= 2) {
            bindingItem.setBind(Keys.mouse(button));
            bindingItem = null;
            Velora.get().getConfigManager().save();
            return true;
        }

        if (bindingSetting != null && button >= 2) {
            bindingSetting.setValue(Keys.mouse(button));
            bindingSetting = null;
            Velora.get().getConfigManager().save();
            return true;
        }

        float chipY = chipY();
        if (button == 0 && mouseY >= chipY && mouseY <= chipY + CHIP_HEIGHT) {
            for (int i = 0; i < CHIP_LABELS.length; i++) {
                float chipX = chipX(i);
                if (mouseX < chipX || mouseX > chipX + CHIP_WIDTH) {
                    continue;
                }
                switch (CHIP_LABELS[i]) {
                    case "THEMES" -> client.setScreen(new ThemeScreen(this));
                    case "CONFIG" -> client.setScreen(new ConfigScreen(this));
                    case "ALTS" -> client.setScreen(new AltManagerScreen(this));
                    default -> {
                    }
                }
                return true;
            }
        }

        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (panel.mouseClicked(mouseX, mouseY, button)) {
                panels.remove(panel);
                panels.add(panel);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingSlider = null;
        for (CategoryPanel panel : panels) {
            panel.mouseReleased();
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingSlider != null) {
            draggingSlider.setFromSlider(click.x(), sliderLeft, sliderWidth);
            return true;
        }
        for (CategoryPanel panel : panels) {
            if (panel.dragging) {
                panel.drag(click.x(), click.y());
                return true;
            }
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (CategoryPanel panel : panels) {
            if (panel.isMouseOver(mouseX, mouseY)) {
                panel.scroll(verticalAmount);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.getKeycode();
        if (editingText != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                editingText = null;
                textBuffer.setLength(0);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commitTextEdit();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (textBuffer.length() > 0) {
                    textBuffer.deleteCharAt(textBuffer.length() - 1);
                }
                return true;
            }
            // Every other key belongs to the editor, never to a module bind.
            return true;
        }
        if (bindingModule != null || bindingSetting != null || bindingItem != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                bindingModule = null;
                bindingSetting = null;
                bindingItem = null;
                return true;
            }
            int bind = key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE
                    ? Keys.NONE : key;
            if (bindingItem != null) {
                bindingItem.setBind(bind);
                bindingItem = null;
            } else if (bindingSetting != null) {
                bindingSetting.setValue(bind);
                bindingSetting = null;
            } else {
                bindingModule.setKey(bind);
                bindingModule = null;
            }
            Velora.get().getConfigManager().save();
            return true;
        }
        // The bind that opened this screen is handled in Velora#tick, so only ESC
        // closes it from here. Handling RIGHT SHIFT twice would close and
        // immediately reopen the GUI on the same tick.
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (editingText != null) {
            String typed = input.asString();
            if (typed != null && !typed.isEmpty()) {
                for (int i = 0; i < typed.length() && textBuffer.length() < editingText.getMaxLength(); i++) {
                    char character = typed.charAt(i);
                    if (character < 32) {
                        continue;
                    }
                    if (editingText.isDigitsOnly() && !Character.isDigit(character)) {
                        continue;
                    }
                    textBuffer.append(character);
                }
            }
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        commitTextEdit();
        Velora.get().getConfigManager().save();
        ClientSounds.guiToggled(false);
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void beginTextEdit(TextSetting setting) {
        commitTextEdit();
        editingText = setting;
        textBuffer.setLength(0);
        textBuffer.append(setting.getValue());
    }

    private void commitTextEdit() {
        TextSetting setting = editingText;
        if (setting == null) {
            return;
        }
        editingText = null;
        String value = textBuffer.toString();
        textBuffer.setLength(0);
        if (!value.equals(setting.getValue())) {
            setting.setValue(value);
            Velora.get().getConfigManager().save();
        }
    }

    private final class CategoryPanel {

        private final Category category;
        private final List<Module> modules;
        private float x;
        private float y;
        private float scroll;

        private boolean dragging;
        private float dragOffsetX;
        private float dragOffsetY;

        private Module hoveredModule;
        private Setting<?> hoveredSetting;

        /**
         * Chip hitboxes registered by the last draw pass of this panel, used to
         * route clicks on individual options of a multi-select setting.
         */
        private final List<MultiChip> multiChipRects = new ArrayList<>();

        CategoryPanel(Category category, float x, float y) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.modules = new ArrayList<>(Velora.get().getModuleManager().getModules(category));
        }

        float contentHeight() {
            float height = HEADER_HEIGHT + PADDING;
            for (Module module : modules) {
                height += ROW_HEIGHT;
                height += module.getSettingsHeight();
            }
            return height + PADDING;
        }

        /**
         * Visible panel height: content, but never taller than the free screen
         * space. The old 320 px cap made an expanded module (AimAssist has 14
         * rows, Target ESP 15) push the rest of the module list below the fold,
         * which looked like the whole column had disappeared. Using the full
         * available height keeps the list visible; anything that still does not
         * fit is reachable with the mouse wheel.
         */
        float panelHeight() {
            float available = Math.max(120f, height - y - 10f);
            return Math.min(contentHeight(), available);
        }

        void render(DrawContext context, int mouseX, int mouseY, float frameFactor, float alpha) {
            for (Module module : modules) {
                module.updateAnimation(frameFactor);
            }

            float panelHeight = panelHeight();
            // Content can shrink (a block collapsed, another column was opened),
            // which would otherwise leave the list scrolled past its end.
            scroll = MathHelper.clamp(scroll, 0f, Math.max(0f, contentHeight() - panelHeight));
            int background = ColorUtil.multiplyAlpha(Velora.get().getConfigManager().getBackgroundColor(), alpha);
            int accent = Velora.get().getConfigManager().getAccentColor();
            int accentSecondary = Velora.get().getConfigManager().getAccentSecondary();

            // Glass card + accent header band, the same recipe as the HUD cards.
            Render2D.shadow(context, x, y + 1, panelWidth, panelHeight, 7, 0x50000000);
            Render2D.roundRectGradient(context, x, y, panelWidth, panelHeight, 6f,
                    ColorUtil.withAlpha(0xFF1B1B22, Math.round(238 * alpha)),
                    ColorUtil.withAlpha(0xFF101014, Math.round(242 * alpha)));
            // A light tint of the theme background keeps the body neutral but themed.
            Render2D.roundRect(context, x, y, panelWidth, panelHeight, 6f,
                    ColorUtil.multiplyAlpha(background, 0.45f));
            Render2D.roundRect(context, x + 0.5f, y + 0.5f, panelWidth - 1, panelHeight - 1, 5.5f, 0x1CFFFFFF);
            // Header wash: a short accent gradient instead of a flat band.
            Render2D.roundRect(context, x, y, panelWidth, HEADER_HEIGHT, 6f,
                    ColorUtil.withAlpha(ColorUtil.scale(accent, 0.16f), Math.round(190 * alpha)));
            Render2D.roundRectGradient(context, x + 8, y + HEADER_HEIGHT - 1.25f, panelWidth - 16,
                    1.25f, 0.6f,
                    ColorUtil.withAlpha(accent, Math.round(230 * alpha)),
                    ColorUtil.withAlpha(accentSecondary, Math.round(120 * alpha)));

            Render2D.text(context, category.getDisplayName(), x + 10, y + 7, 0xFFFFFFFF, true);
            String count = String.valueOf(modules.stream().filter(Module::isEnabled).count());
            float countWidth = Render2D.smallWidth(count) + 10;
            Render2D.roundRect(context, x + panelWidth - 8 - countWidth, y + 6.5f, countWidth, 11, 5.5f,
                    ColorUtil.withAlpha(accent, Math.round(38 * alpha)));
            Render2D.smallCentered(context, count, x + panelWidth - 8 - countWidth / 2f, y + 8,
                    ColorUtil.withAlpha(accent, Math.round(240 * alpha)));

            Render2D.scissor(context, x, y + HEADER_HEIGHT, panelWidth, panelHeight - HEADER_HEIGHT);

            float cursorY = y + HEADER_HEIGHT + PADDING - scroll;
            boolean inside = isMouseOver(mouseX, mouseY);

            hoveredModule = null;
            hoveredSetting = null;

            for (Module module : modules) {
                boolean hovered = inside && mouseX >= x + 4 && mouseX <= x + panelWidth - 4
                        && mouseY >= cursorY && mouseY <= cursorY + ROW_HEIGHT;

                if (hovered) {
                    hoveredModule = module;
                }

                boolean enabled = module.isEnabled();
                float rowCenter = cursorY + (ROW_HEIGHT - 2) / 2f;
                if (hovered) {
                    Render2D.roundRect(context, x + 4, cursorY, panelWidth - 8, ROW_HEIGHT - 2, 4,
                            0x11FFFFFF);
                }
                if (enabled) {
                    // Only the accent bar marks an enabled module: no filled boxes,
                    // so a full column of "on" modules still reads calmly.
                    Render2D.roundRect(context, x + 4, cursorY + 3.5f, 2f, ROW_HEIGHT - 9f, 1f,
                            ColorUtil.withAlpha(accent, 235));
                }

                int nameColor = enabled ? 0xFFFFFFFF : 0xFF8E8E9C;
                Render2D.smallText(context, module.getName(), x + 11,
                        rowCenter - Render2D.smallHeight() / 2f + 0.5f, nameColor);

                String keyLabel = keyLabel(module);
                if (!keyLabel.isEmpty()) {
                    int keyColor = bindingModule == module ? accent : 0xFF6F6F7C;
                    Render2D.smallRight(context, keyLabel, x + panelWidth - 9,
                            rowCenter - Render2D.smallHeight() / 2f + 0.5f, keyColor);
                }

                cursorY += ROW_HEIGHT;

                if (module.getAnimation() > 0.01f) {
                    cursorY = renderSettings(context, module, cursorY, mouseX, mouseY, inside, frameFactor);
                }
            }

            Render2D.unScissor(context);

            float maxScroll = Math.max(0, contentHeight() - panelHeight);
            if (maxScroll > 0) {
                float trackY = y + HEADER_HEIGHT + 3;
                float trackHeight = panelHeight - HEADER_HEIGHT - 6;
                float thumbHeight = Math.max(18f, trackHeight * (panelHeight / contentHeight()));
                float thumbY = trackY + (trackHeight - thumbHeight) * (scroll / maxScroll);
                // A wider, brighter track makes it obvious that the rest of the
                // column is reachable with the wheel.
                Render2D.roundRect(context, x + panelWidth - 4, trackY, 3, trackHeight, 1.5f, 0x3DFFFFFF);
                Render2D.roundRect(context, x + panelWidth - 4, thumbY, 3, thumbHeight, 1.5f,
                        ColorUtil.withAlpha(accent, 210));
            }
        }

        private float renderSettings(DrawContext context, Module module, float cursorY,
                                     int mouseX, int mouseY, boolean inside, float frameFactor) {
            float startY = cursorY;
            Render2D.scissor(context, x, Math.max(y + HEADER_HEIGHT, startY),
                    panelWidth, Math.max(0f, panelHeight() - (startY - y)));

            for (Setting<?> setting : module.getSettings()) {
                if (!setting.isVisible()) {
                    continue;
                }
                float settingY = cursorY;
                boolean hovered = inside && mouseX >= x + 12 && mouseX <= x + panelWidth - 8
                        && mouseY >= settingY && mouseY <= settingY + SETTING_HEIGHT;
                if (hovered) {
                    hoveredSetting = setting;
                }

                int accent = Velora.get().getConfigManager().getAccentColor();
                float labelY = settingY + (SETTING_HEIGHT - Render2D.smallHeight()) / 2f + 0.5f;
                if (!(setting instanceof ItemBindSetting)) {
                    // Item rows draw their own icon and clipped name below.
                    Render2D.smallText(context, setting.getName(), x + 13, labelY, 0xFF9E9EAC);
                }

                if (setting instanceof BooleanSetting toggle) {
                    float switchWidth = 17;
                    float switchHeight = 9;
                    float switchX = x + panelWidth - 12 - switchWidth;
                    float switchY = settingY + (SETTING_HEIGHT - switchHeight) / 2f;
                    boolean on = toggle.get();
                    Render2D.roundRect(context, switchX, switchY, switchWidth, switchHeight, 4.5f,
                            on ? ColorUtil.withAlpha(accent, 225) : 0xFF2A2A33);
                    if (on) {
                        Render2D.circle(context, switchX + switchWidth - 4.5f, switchY + switchHeight / 2f,
                                8.5f, ColorUtil.withAlpha(accent, 60));
                    }
                    Render2D.circle(context, switchX + (on ? switchWidth - 4.5f : 4.5f),
                            switchY + switchHeight / 2f, 7.4f, 0xFFFFFFFF);
                } else if (setting instanceof NumberSetting number) {
                    float barX = x + 13;
                    float barWidth = panelWidth - 26;
                    float barY = settingY + SETTING_HEIGHT - 4f;
                    float progress = (float) ((number.get() - number.getMin()) / (number.getMax() - number.getMin()));
                    Render2D.roundRect(context, barX, barY, barWidth, 2.5f, 1.25f, 0xFF2A2A33);
                    Render2D.roundRect(context, barX, barY, Math.max(2, barWidth * progress), 2.5f, 1.25f,
                            ColorUtil.withAlpha(accent, 235));
                    // The handle: a white dot with a soft accent halo while hovered.
                    float handleX = barX + barWidth * progress;
                    if (hovered) {
                        Render2D.circle(context, handleX, barY + 1.25f, 9, ColorUtil.withAlpha(accent, 70));
                    }
                    Render2D.circle(context, handleX, barY + 1.25f, 5.6f, 0xFFFFFFFF);
                    String value = number.getDisplayValue();
                    Render2D.smallRight(context, value, x + panelWidth - 11, labelY, 0xFFD6D6E0);
                } else if (setting instanceof ModeSetting mode) {
                    String value = mode.getValue();
                    float chipWidth = Render2D.smallWidth(value) + 11;
                    float chipX = x + panelWidth - 11 - chipWidth;
                    Render2D.roundRect(context, chipX, settingY + 3.5f, chipWidth, SETTING_HEIGHT - 7, 3.5f,
                            0xFF23232B);
                    Render2D.smallText(context, value, chipX + 5.5f, labelY,
                            hovered ? 0xFFFFFFFF : 0xFFD6D6E0);
                } else if (setting instanceof MultiBooleanSetting multi) {
                    renderMultiSelect(context, multi, settingY, labelY, accent);
                } else if (setting instanceof ColorSetting color) {
                    int rgb = 0xFF000000 | color.getRgb();
                    Render2D.roundRect(context, x + panelWidth - 27, settingY + 4, 16, SETTING_HEIGHT - 8, 3.5f,
                            rgb);
                    Render2D.roundRect(context, x + panelWidth - 27, settingY + 4, 16, SETTING_HEIGHT - 8, 3.5f,
                            0x33000000);
                } else if (setting instanceof BindSetting bind) {
                    String value = bindingSetting == bind ? "..." : Keys.label(bind.get());
                    float chipWidth = Render2D.smallWidth(value) + 11;
                    float chipX = x + panelWidth - 11 - chipWidth;
                    Render2D.roundRect(context, chipX, settingY + 3.5f, chipWidth, SETTING_HEIGHT - 7, 3.5f,
                            bindingSetting == bind ? ColorUtil.withAlpha(accent, 55) : 0xFF23232B);
                    Render2D.smallText(context, value, chipX + 5.5f, labelY,
                            bindingSetting == bind ? accent : 0xFFD6D6E0);
                } else if (setting instanceof ItemBindSetting itemBind) {
                    renderItemBindSetting(context, itemBind, settingY, labelY, hovered, accent);
                } else if (setting instanceof TextSetting text) {
                    renderTextSetting(context, text, settingY, labelY, hovered, accent);
                }
                cursorY += SETTING_HEIGHT;
            }

            Render2D.unScissor(context);
            Render2D.roundRect(context, x + 10, cursorY + 2, panelWidth - 20, 1, 0.5f, 0x12FFFFFF);
            return cursorY + 5;
        }

        /**
         * Chip row for a multi-select setting. Chips fill the right side of the
         * row and wrap onto the following lines, so the module's reported
         * settings height stays a multiple of {@code SETTING_HEIGHT}.
         */
        private void renderMultiSelect(DrawContext context, MultiBooleanSetting setting,
                                       float settingY, float labelY, int accent) {
            multiChipRects.clear();
            float rightEdge = x + panelWidth - 11f;
            float leftEdge = x + 13f + Render2D.smallWidth(setting.getName()) + 8f;
            float chipX = leftEdge;
            float chipY = settingY + 4f;
            float chipHeight = SETTING_HEIGHT - 8f;
            for (String option : setting.getOptionNames()) {
                float chipWidth = Render2D.smallWidth(option) + 12f;
                // Wrap before the chip would overflow the row.
                if (chipX + chipWidth > rightEdge && chipX > leftEdge) {
                    chipX = leftEdge;
                    chipY += chipHeight + 3f;
                }
                boolean on = setting.isSelected(option);
                Render2D.roundRect(context, chipX, chipY, chipWidth, chipHeight, 3.5f,
                        on ? ColorUtil.withAlpha(accent, 60) : 0xFF23232B);
                Render2D.smallText(context, option, chipX + 6f,
                        chipY + (chipHeight - Render2D.smallHeight()) / 2f + 0.5f,
                        on ? accent : 0xFF8A8A98);
                multiChipRects.add(new MultiChip(setting, option, chipX, chipY, chipWidth, chipHeight));
                chipX += chipWidth + 4f;
            }
        }

        /** A hitbox registered by the last multi-select draw pass. */
        private record MultiChip(MultiBooleanSetting setting, String option,
                                 float x, float y, float width, float height) {
            boolean contains(double mouseX, double mouseY) {
                return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
            }
        }

        /** Row for a FunTime item hotkey: item icon + name on the left, the bound key in a rounded chip. */
        private void renderItemBindSetting(DrawContext context, ItemBindSetting setting, float settingY,
                                           float labelY, boolean hovered, int accent) {
            boolean binding = bindingItem == setting;
            float iconSize = SETTING_HEIGHT - 8f;

            // Item icon with its own small rounded backdrop so FunTime textures read clearly.
            Render2D.roundRect(context, x + 12f, settingY + 4f, iconSize + 2f, iconSize, 3.5f, 0xFF23232B);
            HudStyle.item(context, setting.icon(), x + 13f, settingY + 5f, iconSize);

            String name = setting.getName();
            float nameX = x + 18f + iconSize + 2f;
            float nameWidth = x + panelWidth - 11f - nameX;
            Render2D.scissor(context, nameX, settingY, nameWidth, SETTING_HEIGHT);
            Render2D.smallText(context, name, nameX, labelY, hovered ? 0xFFFFFFFF : 0xFFD6D6E0);
            Render2D.unScissor(context);
            // unScissor disables clipping entirely; restore the panel clip for later rows.
            Render2D.scissor(context, x, y + HEADER_HEIGHT, panelWidth, panelHeight() - HEADER_HEIGHT);

            // Rounded chip that always shows what the item is actually bound to.
            String value = binding ? "..." : Keys.label(setting.getBind());
            float chipWidth = Math.max(Render2D.smallWidth(value) + 11f, 24f);
            float chipX = x + panelWidth - 11f - chipWidth;
            boolean bound = setting.isSet();
            int chipBg = binding ? ColorUtil.withAlpha(accent, 55)
                    : bound ? ColorUtil.withAlpha(accent, 28) : 0xFF23232B;
            Render2D.roundRect(context, chipX, settingY + 3.5f, chipWidth, SETTING_HEIGHT - 7f, 3.5f, chipBg);
            float textX = chipX + (chipWidth - Render2D.smallWidth(value)) / 2f;
            Render2D.smallText(context, value, textX, labelY,
                    binding ? accent : bound ? accent : 0xFF6F6F7C);
            if (binding) {
                // While arming, tint the chip edge so it is obvious the click registered.
                Render2D.roundRect(context, x + panelWidth - 11f - chipWidth - 1f, settingY + 2.5f,
                        chipWidth + 2f, SETTING_HEIGHT - 5f, 4.5f, ColorUtil.withAlpha(accent, 35));
            }
        }

        /** Editable chip for free-form values such as the Discord app id. */
        private void renderTextSetting(DrawContext context, TextSetting setting, float settingY,
                                       float labelY, boolean hovered, int accent) {
            boolean editing = editingText == setting;
            String value = editing ? textBuffer.toString() : setting.getValue();
            String shown = value.isEmpty() ? "App ID" : value;
            // Start the chip after the label so long names such as "Logo asset"
            // never run underneath it, then use every pixel up to the panel edge.
            float chipX = Math.max(x + 46f,
                    x + 18f + SystemFont.SMALL.width(setting.getName()));
            float chipWidth = x + panelWidth - 11f - chipX;
            float chipHeight = SETTING_HEIGHT - 7;

            Render2D.roundRect(context, chipX, settingY + 3.5f, chipWidth, chipHeight, 3.5f,
                    editing ? ColorUtil.withAlpha(accent, 55) : 0xFF23232B);

            Render2D.scissor(context, chipX + 3f, settingY, chipWidth - 6f, SETTING_HEIGHT);
            int color = value.isEmpty() ? 0xFF6F6F7C : (editing ? accent : 0xFFD6D6E0);
            Render2D.smallText(context, shown, chipX + 5.5f, labelY, color);
            if (editing) {
                float caretX = chipX + 5.5f + SystemFont.SMALL.width(value) + 1f;
                Render2D.rect(context, caretX, settingY + 4.5f, 1f, chipHeight - 2f, accent);
            }
            Render2D.unScissor(context);
            // unScissor disables clipping entirely, so restore the panel clip for
            // the rows that follow this one.
            Render2D.scissor(context, x, y + HEADER_HEIGHT, panelWidth, panelHeight() - HEADER_HEIGHT);
        }

        String getHoveredDescription(int mouseX, int mouseY) {
            if (hoveredModule != null && hoveredSetting == null) {
                return hoveredModule.getDescription();
            }
            if (hoveredSetting != null) {
                // Show the real Russian hint; fall back to the old usage hint only
                // for settings that genuinely have no description.
                String hint = hoveredSetting.getDescription();
                return hint == null || hint.isEmpty()
                        ? hoveredSetting.getName() + " — тяни / клик, чтобы изменить"
                        : hint;
            }
            return null;
        }

        boolean isMouseOver(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + panelWidth && mouseY >= y && mouseY <= y + panelHeight();
        }

        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isMouseOver(mouseX, mouseY)) {
                return false;
            }

            if (mouseY <= y + HEADER_HEIGHT) {
                if (button == 0) {
                    dragging = true;
                    dragOffsetX = (float) (mouseX - x);
                    dragOffsetY = (float) (mouseY - y);
                }
                return true;
            }

            float cursorY = y + HEADER_HEIGHT + PADDING - scroll;
            for (Module module : modules) {
                boolean rowHovered = mouseX >= x + 4 && mouseX <= x + panelWidth - 4
                        && mouseY >= cursorY && mouseY <= cursorY + ROW_HEIGHT;
                if (rowHovered) {
                    if (button == 0) {
                        module.toggle();
                    } else if (button == 1) {
                        // One open module per column: with two long settings
                        // blocks open the list would still outgrow the screen.
                        boolean expand = !module.isExpanded();
                        collapseAllExcept(module);
                        module.setExpanded(expand);
                    } else if (button == 2) {
                        bindingModule = module;
                        bindingSetting = null;
                    }
                    return true;
                }
                cursorY += ROW_HEIGHT;

                if (module.getAnimation() <= 0.01f) {
                    continue;
                }

                for (Setting<?> setting : module.getSettings()) {
                    if (!setting.isVisible()) {
                        continue;
                    }
                    boolean settingHovered = mouseX >= x + 12 && mouseX <= x + panelWidth - 8
                            && mouseY >= cursorY && mouseY <= cursorY + SETTING_HEIGHT;
                    if (settingHovered) {
                        // Multi-select rows are edited per chip, not by the row.
                        if (setting instanceof MultiBooleanSetting) {
                            handleMultiChipClick(setting, mouseX, mouseY);
                        } else {
                            handleSettingClick(setting, mouseX, mouseY, button);
                        }
                        return true;
                    }
                    cursorY += SETTING_HEIGHT;
                }
                cursorY += 5;
            }
            return true;
        }

        /** Toggles the option whose chip was clicked in a multi-select row. */
        private void handleMultiChipClick(Setting<?> setting, double mouseX, double mouseY) {
            for (MultiChip chip : multiChipRects) {
                if (chip.setting() != setting || !chip.contains(mouseX, mouseY)) {
                    continue;
                }
                chip.setting().toggleOption(chip.option());
                Velora.get().getConfigManager().save();
                return;
            }
        }

        private void handleSettingClick(Setting<?> setting, double mouseX, double mouseY, int button) {
            if (setting instanceof MultiBooleanSetting) {
                // Chips have individual hitboxes, resolved before the generic row click.
                return;
            }
            if (setting instanceof BooleanSetting toggle && button == 0) {
                toggle.toggle();
            } else if (setting instanceof NumberSetting number) {
                sliderLeft = x + 14;
                sliderWidth = panelWidth - 28;
                draggingSlider = number;
                number.setFromSlider(mouseX, sliderLeft, sliderWidth);
            } else if (setting instanceof ModeSetting mode) {
                if (button == 1) {
                    mode.cycleBack();
                } else {
                    mode.cycle();
                }
            } else if (setting instanceof BindSetting bind && (button == 0 || button == 1)) {
                bindingSetting = bind;
                bindingModule = null;
            } else if (setting instanceof ItemBindSetting itemBind && (button == 0 || button == 1)) {
                // Left click arms the bind, right click clears it so the chip never lies.
                if (button == 1 && itemBind.isSet()) {
                    itemBind.clearBind();
                    bindingItem = null;
                    Velora.get().getConfigManager().save();
                } else {
                    bindingItem = itemBind;
                    bindingSetting = null;
                    bindingModule = null;
                }
            } else if (setting instanceof TextSetting text) {
                if (button == 0) {
                    beginTextEdit(text);
                } else if (button == 1) {
                    text.setValue("");
                    Velora.get().getConfigManager().save();
                }
            } else if (setting instanceof ColorSetting color && button == 0) {
                int rgb = color.getRgb();
                int red = Math.min(255, ((rgb >> 16) & 0xFF) + 24);
                int green = Math.min(255, ((rgb >> 8) & 0xFF) + 24);
                int blue = Math.min(255, (rgb & 0xFF) + 24);
                if (red >= 255 && green >= 255 && blue >= 255) {
                    red = green = blue = 40;
                }
                color.setValue(0xFF000000 | (red << 16) | (green << 8) | blue);
            }
        }

        void mouseReleased() {
            dragging = false;
        }

        void drag(double mouseX, double mouseY) {
            x = (float) (mouseX - dragOffsetX);
            y = (float) (mouseY - dragOffsetY);
            x = MathHelper.clamp(x, 0, Math.max(0, width - panelWidth));
            // Keep the whole (possibly shortened) panel on screen, so it never hangs off the bottom.
            y = MathHelper.clamp(y, 0, Math.max(0, height - 120));
            Velora.get().getConfigManager().setPanelPosition(category, x, y);
        }

        /** Folds every settings block in this column except the given module. */
        void collapseAllExcept(Module keep) {
            for (Module module : modules) {
                if (module != keep) {
                    module.setExpanded(false);
                }
            }
        }

        void scroll(double amount) {
            float maxScroll = Math.max(0, contentHeight() - panelHeight());
            scroll = MathHelper.clamp(scroll - (float) amount * 14f, 0, maxScroll);
        }

        private String keyLabel(Module module) {
            if (bindingModule == module) {
                return "...";
            }
            int key = module.getKey();
            if (key == GLFW.GLFW_KEY_UNKNOWN) {
                return "";
            }
            return Keys.label(key);
        }
    }
}
