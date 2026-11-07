package dev.velora.gui;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.render.ColorUtil;
import dev.velora.render.HudIcons;
import dev.velora.render.HudStyle;
import dev.velora.render.Render2D;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Lite-style Velora ClickGUI.
 *
 * <p>One fixed, centred window with a category sidebar on the left and a
 * two-column card grid on the right. Modules expand in place; every setting
 * type keeps its own editor (animated switch, inline slider, dropdown,
 * multi-select group, HSV colour picker and key bind). The layout is authored
 * in a 600x340 design space and scaled as one block, so it stays identical at
 * every resolution and GUI scale.</p>
 */
public class LiteClickGuiScreen extends Screen {

    private static final float DESIGN_W = 600f;
    private static final float DESIGN_H = 340f;
    private static final float SIDEBAR = 130f;
    private static final float MAIN_X = SIDEBAR + 14f;
    private static final float MAIN_W = DESIGN_W - MAIN_X - 14f;
    private static final float HEADER_H = 42f;
    private static final float VIEW_TOP = 44f;
    private static final float VIEW_BOTTOM = DESIGN_H - 10f;
    private static final float ROW_GAP = 5f;
    private static final float BIND_W = 158f;
    private static final float BIND_H = 70f;

    private static final int WHITE = 0xFFF1F1F1;
    private static final int TEXT = 0xFFC9C9CB;
    private static final int MUTED = 0xFF73757B;
    private static final int PANEL = 0xFA090A0C;
    private static final int SIDE = 0xFC0D0E10;
    private static final int CARD = 0xFF101113;
    private static final int CARD_HOVER = 0xFF111319;
    private static final int CARD_OPEN = 0xFF12141A;
    private static final int BORDER = 0xFF191A1E;

    private static int rememberedCategory;

    private final Category[] categories = Category.values();
    private int categoryIndex = Math.min(rememberedCategory, Category.values().length - 1);
    private Module expanded;

    private NumberSetting dragging;
    private float dragTrackX;
    private float dragTrackW;

    private ModeSetting openMode;
    private float modeX;
    private float modeY;
    private float modeW;
    private float modeRowH;

    private MultiBooleanSetting openMulti;
    private float multiX;
    private float multiY;
    private float multiW;
    private float multiRowH;

    private ColorSetting openColor;
    private float colorX;
    private float colorY;
    private float colorW;
    private float colorH;
    private int colorDragPart;

    private BindSetting bindingBind;
    private ItemBindSetting bindingItem;
    private TextSetting editingText;
    private final StringBuilder textBuffer = new StringBuilder();

    /** Module bind popup opened with the middle mouse button. */
    private Module bindModule;
    private Module capturingModule;
    private float bindX;
    private float bindY;

    private boolean searchFocused;
    private boolean twoColumns = true;
    private String search = "";

    private float scrollOffset;
    private float scrollTarget;
    private float maxScroll;

    /** Описание настройки, показанное под курсором (для нижней подсказки). */
    private String hoveredHint;

    private long lastFrameNanos;
    private float navIndex = rememberedCategory;

    private final Map<Module, Anim> moduleAnims = new HashMap<>();
    private final Map<Setting<?>, Anim> settingAnims = new HashMap<>();

    public LiteClickGuiScreen() {
        super(Text.literal(Velora.NAME));
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f
                : MathHelper.clamp((now - lastFrameNanos) / 1_000_000_000f, 0f, 0.1f);
        lastFrameNanos = now;

        Render2D.ensureInit();
        Render2D.rect(context, 0f, 0f, width, height, 0xC9000000);

        float s = scale();
        float ox = (width - DESIGN_W * s) / 2f;
        float oy = (height - DESIGN_H * s) / 2f;
        float mx = (mouseX - ox) / s;
        float my = (mouseY - oy) / s;

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(ox, oy);
        context.getMatrices().scale(s, s);

        int accent = Velora.get().getConfigManager().getAccentColor();
        hoveredHint = null;
        Category category = categories[categoryIndex];
        drawWindow(context);
        drawSidebar(context, mx, my, dt, accent);
        drawHeader(context, category, mx, my, accent);

        drawGrid(context, category, mx, my, dt, accent);

        drawModeDropdown(context, mx, my, accent);
        drawMultiDropdown(context, mx, my, accent);
        drawColorPicker(context, mx, my, accent);
        drawBindPopup(context, mx, my, accent);

        context.getMatrices().popMatrix();

        if (capturingModule != null) {
            drawBottomHint(context, "PRESS A KEY FOR "
                    + capturingModule.getName().toUpperCase(Locale.ROOT)
                    + "   ESC TO CANCEL", accent);
        } else if (bindingBind != null || bindingItem != null) {
            String hint = "PRESS A KEY FOR "
                    + (bindingItem != null ? bindingItem.getName() : bindingBind.getName()).toUpperCase(Locale.ROOT)
                    + "   ESC TO CANCEL";
            drawBottomHint(context, hint, accent);
        } else if (editingText != null) {
            drawBottomHint(context, "TYPE A VALUE   ENTER TO SAVE   ESC TO CANCEL", accent);
        } else if (hoveredHint != null) {
            drawBottomHint(context, hoveredHint, accent);
        }
    }

    private void drawWindow(DrawContext context) {
        Render2D.roundRect(context, 0f, 0f, DESIGN_W, DESIGN_H, 8f, BORDER);
        Render2D.roundRect(context, 0.7f, 0.7f, DESIGN_W - 1.4f, DESIGN_H - 1.4f, 7.4f, PANEL);
        Render2D.roundRect(context, 0f, 0f, SIDEBAR, DESIGN_H, 8f, SIDE);
        Render2D.rect(context, SIDEBAR - 8f, 8f, 8f, DESIGN_H - 16f, SIDE);
        Render2D.rect(context, SIDEBAR - 0.6f, 10f, 0.6f, DESIGN_H - 20f, BORDER);
    }

    private void drawSidebar(DrawContext context, float mx, float my, float dt, int accent) {
        // Brand row.
        float bx = 15f;
        float brandY = 24f;
        if (!Render2D.logo(context, bx, brandY - 8f, 16f)) {
            Render2D.circle(context, bx + 8f, brandY, 8f, ColorUtil.withAlpha(accent, 60));
            Render2D.circle(context, bx + 8f, brandY, 3.6f, accent);
        }
        Render2D.gradientText(context, "VELORA", bx + 22f, brandY - 5f,
                ColorUtil.lerp(accent, 0xFFFFFFFF, 0.25f),
                ColorUtil.lerp(accent, 0xFFFFFFFF, 0.55f), true);

        float rowH = 27f;
        float gap = 2f;
        float x = 9f;
        float w = SIDEBAR - 18f;

        // Animate the navigation highlight to the selected category.
        navIndex += (categoryIndex - navIndex) * Math.min(1f, dt * 18f);
        float indicatorY = categoryY(navIndex, rowH, gap);
        Render2D.roundRect(context, x, indicatorY, w, rowH, 6f, 0xFF171920);
        Render2D.roundRect(context, x, indicatorY + 7f, 2f, rowH - 14f, 1f, accent);

        for (int i = 0; i < categories.length; i++) {
            Category category = categories[i];
            float iy = categoryY(i, rowH, gap);
            boolean hovered = hit(mx, my, x, iy, w, rowH);
            int color = i == categoryIndex ? WHITE : hovered ? TEXT : MUTED;
            HudIcons.category(context, category.name(), x + 14f, iy + rowH / 2f - 5.5f, 11f, color);
            Render2D.smallText(context, category.getDisplayName(), x + 28f,
                    iy + (rowH - Render2D.smallHeight()) / 2f + 0.5f, color);
        }

        // Footer card.
        float fy = DESIGN_H - 35f;
        Render2D.line(context, 11f, fy - 10f, SIDEBAR - 11f, fy - 10f, 0.6f, BORDER);
        Render2D.circle(context, 22f, fy + 2f, 16f, 0xFF1D1F27);
        if (!Render2D.logo(context, 14f, fy - 6f, 16f)) {
            Render2D.circle(context, 22f, fy + 2f, 4f, accent);
        }
        Render2D.smallText(context, "Velora", 36f, fy - 4f, WHITE);
        Render2D.smallText(context, "v" + Velora.VERSION, 36f, fy + 6f, MUTED);
    }

    private void drawHeader(DrawContext context, Category category, float mx, float my, int accent) {
        float y = 23f;
        int count = Velora.get().getModuleManager().getModules(category).size();
        Render2D.text(context, category.getDisplayName(), MAIN_X, 11f, WHITE, false);
        float titleWidth = Render2D.textWidth(category.getDisplayName());
        Render2D.smallText(context, count + (count == 1 ? " module" : " modules"),
                MAIN_X + titleWidth + 9f, 15f, MUTED);
        float countWidth = Render2D.smallWidth(count + (count == 1 ? " module" : " modules"));
        Render2D.smallText(context, "MMB to bind", MAIN_X + titleWidth + 18f + countWidth, 15f,
                0xFF5A5E69);

        float sx = MAIN_X + MAIN_W - 116f;
        float sw = 88f;
        boolean focused = searchFocused;
        Render2D.roundRect(context, sx, y - 10f, sw, 20f, 5f, focused ? 0xFF181A1D : 0xFF111214);
        Render2D.roundRect(context, sx + 0.5f, y - 9.5f, sw - 1f, 19f, 4.5f,
                focused ? 0xFF3A3B40 : BORDER);
        Render2D.roundRect(context, sx + 1f, y - 9f, sw - 2f, 18f, 4.3f, focused ? 0xFF181A1D : 0xFF111214);

        String shown = search.isEmpty() ? "Search modules..." : search;
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            shown += "_";
        }
        Render2D.smallText(context, truncate(shown, sw - 14f), sx + 7f,
                y - Render2D.smallHeight() / 2f + 1f, search.isEmpty() ? MUTED : TEXT);

        float gx = MAIN_X + MAIN_W - 18f;
        boolean gridHovered = hit(mx, my, gx - 8f, y - 9f, 17f, 18f);
        drawGridIcon(context, gx, y, gridHovered ? WHITE : MUTED,
                twoColumns ? accent : MUTED);

        Render2D.line(context, MAIN_X, HEADER_H, MAIN_X + MAIN_W, HEADER_H, 0.6f, BORDER);
    }

    private void drawGridIcon(DrawContext context, float cx, float cy, int color, int accent) {
        Render2D.roundRect(context, cx - 5f, cy - 4f, 4f, 4f, 1f, color);
        Render2D.roundRect(context, cx + 1f, cy - 4f, 4f, 4f, 1f, twoColumns ? accent : color);
        Render2D.roundRect(context, cx - 5f, cy + 2f, 4f, 4f, 1f, color);
        Render2D.roundRect(context, cx + 1f, cy + 2f, 4f, 4f, 1f, twoColumns ? accent : color);
    }

    private void drawGrid(DrawContext context, Category category, float mx, float my, float dt, int accent) {
        List<Module> visible = visibleModules(category);
        int columns = twoColumns ? 2 : 1;
        float gap = 13f;
        float columnW = (MAIN_W - gap * (columns - 1)) / columns;

        scrollOffset += (scrollTarget - scrollOffset) * Math.min(1f, dt * 18f);
        float[] ys = new float[columns];
        for (int i = 0; i < columns; i++) {
            ys[i] = VIEW_TOP + 5f - scrollOffset;
        }

        // enableScissor() applies the current matrix transform, so the clip is
        // given in the same design coordinates as the cards.
        Render2D.scissor(context, MAIN_X, VIEW_TOP,
                MAIN_W, VIEW_BOTTOM - VIEW_TOP);

        boolean pointerInView = hit(mx, my, MAIN_X, VIEW_TOP, MAIN_W, VIEW_BOTTOM - VIEW_TOP);
        for (int i = 0; i < visible.size(); i++) {
            Module module = visible.get(i);
            int column = columns == 1 ? 0 : i % 2;
            float x = MAIN_X + column * (columnW + gap);
            float y = ys[column];
            float settingsH = expanded == module ? settingsHeight(module) : 0f;
            float cardH = 31f + settingsH;
            boolean over = pointerInView && hit(mx, my, x, y, columnW, cardH);

            Render2D.roundRect(context, x, y, columnW, cardH, 5f,
                    expanded == module ? CARD_OPEN : over ? CARD_HOVER : CARD);
            if (expanded == module) {
                Render2D.roundRect(context, x + 0.5f, y + 0.5f, columnW - 1f, cardH - 1f,
                        4.5f, ColorUtil.withAlpha(accent, 38));
                Render2D.roundRect(context, x + 1f, y + 1f, columnW - 2f, cardH - 2f, 4.3f,
                        CARD_OPEN);
            }

            int boundKey = module.getKey();
            float keyReserve = 0f;
            if (boundKey != Keys.NONE) {
                String label = Keys.label(boundKey);
                keyReserve = Render2D.smallWidth(label) + 8f;
                Render2D.smallText(context, label, x + columnW - 32f - Render2D.smallWidth(label),
                        y + 15f - Render2D.smallHeight() / 2f,
                        module.isHoldMode() ? accent : MUTED);
            }
            Render2D.text(context, truncateBody(module.getName(), columnW - 48f - keyReserve), x + 9f,
                    y + 15f - Render2D.textHeight() / 2f, expanded == module ? WHITE : TEXT, false);

            Anim anim = moduleAnims.computeIfAbsent(module, ignored -> new Anim(module.isEnabled() ? 1f : 0f));
            anim.setTarget(module.isEnabled() ? 1f : 0f);
            float p = anim.update(dt);
            drawSwitch(context, x + columnW - 25f, y + 9f, 21f, 13f, p, accent);

            if (expanded == module) {
                drawSettings(context, module, x + 8f, y + 35f, columnW - 16f,
                        pointerInView ? mx : -100000f, pointerInView ? my : -100000f, dt, accent);
            }
            ys[column] += cardH + ROW_GAP;
        }

        float contentBottom = ys[0];
        for (int i = 1; i < columns; i++) {
            contentBottom = Math.max(contentBottom, ys[i]);
        }
        maxScroll = Math.max(0f, contentBottom + scrollOffset - VIEW_BOTTOM);
        scrollTarget = MathHelper.clamp(scrollTarget, 0f, maxScroll);

        Render2D.unScissor(context);

        if (maxScroll > 1f) {
            float trackH = VIEW_BOTTOM - VIEW_TOP;
            float thumbH = Math.max(18f, trackH * trackH / (trackH + maxScroll));
            float thumbY = VIEW_TOP + (trackH - thumbH) * (scrollOffset / maxScroll);
            Render2D.roundRect(context, MAIN_X + MAIN_W - 3f, thumbY, 3f, thumbH,
                    1.5f, 0x9973757B);
        }
        if (visible.isEmpty()) {
            Render2D.centeredText(context, "No modules found", MAIN_X + MAIN_W / 2f,
                    DESIGN_H / 2f, MUTED, false);
        }
    }

    private List<Module> visibleModules(Category category) {
        String query = search.toLowerCase(Locale.ROOT).trim();
        List<Module> result = new ArrayList<>();
        if (query.isEmpty()) {
            result.addAll(Velora.get().getModuleManager().getModules(category));
            return result;
        }
        for (Category source : categories) {
            for (Module module : Velora.get().getModuleManager().getModules(source)) {
                if (module.getName().toLowerCase(Locale.ROOT).contains(query)
                        || module.getDescription().toLowerCase(Locale.ROOT).contains(query)) {
                    result.add(module);
                }
            }
        }
        return result;
    }

    // --------------------------------------------------------------- settings

    private void drawSettings(DrawContext context, Module module, float x, float y, float w,
                              float mx, float my, float dt, int accent) {
        for (Setting<?> setting : module.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            float rowH = settingRowHeight(setting);
            boolean hovered = hit(mx, my, x, y, w, rowH);
            String hint = setting.getDescription();
            // Подсказка показывается только при наведении — иначе список настроек
            // превращается в стену текста (именно это и бесило).
            boolean showHint = hovered && hint != null && !hint.isEmpty();
            if (showHint) {
                hoveredHint = hint;
            }

            if (setting instanceof BooleanSetting toggle) {
                Render2D.smallText(context, setting.getName(), x, y + 7f, hovered ? WHITE : TEXT);
                Anim anim = settingAnims.computeIfAbsent(setting,
                        ignored -> new Anim(toggle.get() ? 1f : 0f));
                anim.setTarget(toggle.get() ? 1f : 0f);
                drawSwitch(context, x + w - 18f, y + 4f, 18f, 10f, anim.update(dt), accent);
            } else if (setting instanceof NumberSetting number) {
                Render2D.smallText(context, setting.getName(), x, y + 6f, TEXT);
                Render2D.smallRight(context, number.getDisplayValue(), x + w, y + 6f, TEXT);
                float tx = x;
                float tw = Math.max(20f, w - 6f);
                float ty = y + 16f;
                float progress = (float) ((number.get() - number.getMin())
                        / (number.getMax() - number.getMin()));
                Render2D.roundRect(context, tx, ty, tw, 3f, 1.5f, 0xFF292C35);
                Render2D.roundRect(context, tx, ty, Math.max(1.5f, tw * progress), 3f, 1.5f, accent);
                Render2D.circle(context, tx + tw * progress, ty + 1.5f, 7f, WHITE);
            } else if (setting instanceof ModeSetting mode) {
                Render2D.smallText(context, setting.getName(), x, y + 9f, hovered ? WHITE : TEXT);
                float sw = Math.min(73f, w * 0.42f);
                float sx = x + w - sw;
                drawChip(context, sx, y, sw, 20f, mode.getValue(),
                        openMode == mode ? accent : TEXT, openMode == mode, accent);
                Render2D.smallText(context, openMode == mode ? "^" : "v", sx + sw - 8f,
                        y + 9f - Render2D.smallHeight() / 2f, openMode == mode ? accent : MUTED);
            } else if (setting instanceof MultiBooleanSetting multi) {
                Render2D.smallText(context, setting.getName(), x, y + 9f, hovered ? WHITE : TEXT);
                float sw = Math.min(73f, w * 0.42f);
                float sx = x + w - sw;
                drawChip(context, sx, y, sw, 20f, multi.getDisplayValue(),
                        openMulti == multi ? accent : TEXT, openMulti == multi, accent);
                Render2D.smallText(context, openMulti == multi ? "^" : "v", sx + sw - 8f,
                        y + 9f - Render2D.smallHeight() / 2f, openMulti == multi ? accent : MUTED);
            } else if (setting instanceof ColorSetting color) {
                Render2D.smallText(context, setting.getName(), x, y + 9f, hovered ? WHITE : TEXT);
                Render2D.smallRight(context, String.format("#%06X", color.getRgb()),
                        x + w - 20f, y + 9f, MUTED);
                Render2D.roundRect(context, x + w - 15f, y + 3f, 12f, 12f, 3f, color.getArgb());
                Render2D.roundRect(context, x + w - 15f, y + 3f, 12f, 12f, 3f,
                        openColor == color ? 0xAAFFFFFF : 0x55FFFFFF);
            } else if (setting instanceof BindSetting bind) {
                Render2D.smallText(context, setting.getName(), x, y + 9f, hovered ? WHITE : TEXT);
                String value = bindingBind == bind ? "..." : Keys.label(bind.get());
                float kw = Math.max(38f, Render2D.smallWidth(value) + 16f);
                drawChip(context, x + w - kw, y, kw, 19f, value,
                        bindingBind == bind ? accent : TEXT, bindingBind == bind, accent);
            } else if (setting instanceof ItemBindSetting item) {
                HudStyle.item(context, item.icon(), x, y + 3f, 12f);
                Render2D.smallText(context, truncate(setting.getName(), w - 56f), x + 16f,
                        y + 9f, hovered ? WHITE : TEXT);
                float kw = Math.max(30f, Render2D.smallWidth(
                        bindingItem == item ? "..." : Keys.label(item.getBind())) + 16f);
                String value = bindingItem == item ? "..." : Keys.label(item.getBind());
                drawChip(context, x + w - kw, y, kw, 19f, value,
                        bindingItem == item ? accent : item.isSet() ? accent : MUTED,
                        bindingItem == item, accent);
            } else if (setting instanceof TextSetting text) {
                Render2D.smallText(context, setting.getName(), x, y + 9f, hovered ? WHITE : TEXT);
                boolean editing = editingText == text;
                String value = editing ? textBuffer.toString() : text.getValue();
                float chipX = Math.max(x + 46f, x + 4f + Render2D.smallWidth(setting.getName()) + 6f);
                float chipW = x + w - chipX;
                Render2D.roundRect(context, chipX, y + 2f, chipW, 19f, 4f,
                        editing ? ColorUtil.withAlpha(accent, 55) : 0xFF191B22);
                Render2D.smallText(context, truncate(value.isEmpty() ? "..." : value, chipW - 10f),
                        chipX + 5f, y + 9f, value.isEmpty() ? MUTED : editing ? accent : TEXT);
            }

            y += rowH;
        }
    }

    private void drawChip(DrawContext context, float x, float y, float w, float h,
                          String value, int color, boolean open, int accent) {
        Render2D.roundRect(context, x, y, w, h, 4f, 0xFF191B22);
        if (open) {
            Render2D.roundRect(context, x, y, w, h, 4f, ColorUtil.withAlpha(accent, 45));
        }
        Render2D.smallText(context, truncate(value, w - 16f), x + 6f,
                y + (h - Render2D.smallHeight()) / 2f + 0.5f, color);
    }

    private void drawSwitch(DrawContext context, float x, float y, float w, float h,
                            float progress, int accent) {
        float p = MathHelper.clamp(progress, 0f, 1f);
        int track = mix(0xFF292C34, accent, p);
        Render2D.roundRect(context, x, y, w, h, h / 2f, track);
        float knob = h / 2f - 2f;
        Render2D.circle(context, x + h / 2f + (w - h) * p, y + h / 2f, knob * 2f,
                mix(WHITE, 0xFF111216, p));
    }

    // ---------------------------------------------------------------- popups

    private void drawModeDropdown(DrawContext context, float mx, float my, int accent) {
        if (openMode == null) {
            return;
        }
        List<String> values = openMode.getModes();
        float rowH = 17f;
        float listY = modeY + modeRowH + 2f;
        float h = values.size() * rowH + 4f;
        modeX = MathHelper.clamp(modeX, MAIN_X, MAIN_X + MAIN_W - modeW);
        listY = Math.min(listY, DESIGN_H - 8f - h);

        Render2D.roundRect(context, modeX - 0.5f, modeY - 0.5f, modeW + 1f, modeRowH + h + 3f,
                4.5f, 0xFF2A2D38);
        Render2D.roundRect(context, modeX, modeY, modeW, modeRowH + h + 2f, 4f, 0xFF101116);
        Render2D.roundRect(context, modeX, modeY, modeW, modeRowH, 4f, 0xFF191B22);
        Render2D.smallText(context, openMode.getValue(), modeX + 6f,
                modeY + (modeRowH - Render2D.smallHeight()) / 2f + 0.5f, WHITE);

        for (int i = 0; i < values.size(); i++) {
            float ry = listY + 2f + i * rowH;
            boolean selected = values.get(i).equals(openMode.getValue());
            boolean hover = hit(mx, my, modeX + 2f, ry, modeW - 4f, rowH);
            if (selected || hover) {
                Render2D.roundRect(context, modeX + 2f, ry, modeW - 4f, rowH, 3f,
                        selected ? ColorUtil.withAlpha(accent, 55) : 0xFF17191F);
            }
            Render2D.smallText(context, truncate(values.get(i), modeW - 16f), modeX + 7f,
                    ry + (rowH - Render2D.smallHeight()) / 2f + 0.5f, selected ? WHITE : TEXT);
        }
    }

    private void drawMultiDropdown(DrawContext context, float mx, float my, int accent) {
        if (openMulti == null) {
            return;
        }
        List<String> values = openMulti.getOptionNames();
        float rowH = 18f;
        float listY = multiY + multiRowH + 2f;
        float h = values.size() * rowH + 4f;
        multiX = MathHelper.clamp(multiX, MAIN_X, MAIN_X + MAIN_W - multiW);
        listY = Math.min(listY, DESIGN_H - 8f - h);

        Render2D.roundRect(context, multiX - 0.5f, multiY - 0.5f, multiW + 1f,
                multiRowH + h + 3f, 4.5f, 0xFF2A2D38);
        Render2D.roundRect(context, multiX, multiY, multiW, multiRowH + h + 2f, 4f, 0xFF101116);
        Render2D.roundRect(context, multiX, multiY, multiW, multiRowH, 4f, 0xFF191B22);
        Render2D.smallText(context, openMulti.getName(), multiX + 6f,
                multiY + (multiRowH - Render2D.smallHeight()) / 2f + 0.5f, WHITE);
        Render2D.smallRight(context, openMulti.getDisplayValue(), multiX + multiW - 6f,
                multiY + (multiRowH - Render2D.smallHeight()) / 2f + 0.5f, MUTED);

        for (int i = 0; i < values.size(); i++) {
            String option = values.get(i);
            float ry = listY + 2f + i * rowH;
            boolean selected = openMulti.isSelected(option);
            boolean hover = hit(mx, my, multiX + 2f, ry, multiW - 4f, rowH);
            if (hover) {
                Render2D.roundRect(context, multiX + 2f, ry, multiW - 4f, rowH, 3f, 0xFF17191F);
            }
            Render2D.smallText(context, truncate(option, multiW - 34f), multiX + 7f,
                    ry + (rowH - Render2D.smallHeight()) / 2f + 0.5f, selected ? WHITE : TEXT);
            float boxX = multiX + multiW - 16f;
            Render2D.roundRect(context, boxX, ry + 5f, 8f, 8f, 2f,
                    selected ? accent : 0xFF24262E);
            if (selected) {
                Render2D.circle(context, boxX + 4f, ry + 9f, 3.2f, 0xFF111216);
            }
        }
    }

    private void drawColorPicker(DrawContext context, float mx, float my, int accent) {
        if (openColor == null) {
            return;
        }
        float pad = 7f;
        float sbX = colorX + pad;
        float sbY = colorY + 17f;
        float sbW = colorW - pad * 2f;
        float sbH = 46f;
        float hueY = colorY + 69f;
        float hueH = 7f;
        float alphaY = colorY + 82f;
        float alphaH = 7f;

        float[] hsb = hsb(openColor.getArgb());
        int hueColor = 0xFF000000 | (java.awt.Color.HSBtoRGB(hsb[0], 1f, 1f) & 0xFFFFFF);

        Render2D.roundRect(context, colorX - 0.5f, colorY - 0.5f, colorW + 1f, colorH + 1f,
                5f, 0xFF2A2D38);
        Render2D.roundRect(context, colorX, colorY, colorW, colorH, 4.5f, 0xFF111216);
        Render2D.smallText(context, "Color", colorX + pad, colorY + 6f, WHITE);
        Render2D.smallRight(context, String.format("#%08X", openColor.getArgb()),
                colorX + colorW - pad, colorY + 6f, TEXT);

        Render2D.roundRect(context, sbX, sbY, sbW, sbH, 3f, hueColor);
        Render2D.gradientHorizontal(context, sbX, sbY, sbW, sbH, 0xFFFFFFFF, 0x00FFFFFF);
        Render2D.gradient(context, sbX, sbY, sbW, sbH, 0x00000000, 0xFF000000);

        float cursorX = sbX + sbW * hsb[1];
        float cursorY = sbY + sbH * (1f - hsb[2]);
        Render2D.circle(context, cursorX, cursorY, 6f, WHITE);
        Render2D.circle(context, cursorX, cursorY, 3.6f, 0xFF000000 | (openColor.getRgb() & 0xFFFFFF));

        int[] rainbow = {0xFFFF4D4D, 0xFFFFFF4D, 0xFF4DFF70, 0xFF4DE8FF,
                0xFF596BFF, 0xFFC44DFF, 0xFFFF4D7F};
        for (int i = 0; i < rainbow.length - 1; i++) {
            float rx = sbX + sbW * i / (rainbow.length - 1f);
            float rw = sbW / (rainbow.length - 1f) + 0.5f;
            Render2D.gradientHorizontal(context, rx, hueY, rw, hueH, rainbow[i], rainbow[i + 1]);
        }
        Render2D.roundRect(context, sbX + sbW * hsb[0] - 1.2f, hueY - 1.5f, 2.4f, hueH + 3f,
                1.2f, WHITE);

        // Alpha bar on a two-row checkerboard.
        float tile = 3.5f;
        int cols = (int) Math.ceil(sbW / tile);
        for (int yy = 0; yy < 2; yy++) {
            for (int xx = 0; xx < cols; xx++) {
                float px = sbX + xx * tile;
                float py = alphaY + yy * tile;
                Render2D.rect(context, px, py, Math.min(tile, sbX + sbW - px),
                        Math.min(tile, alphaY + alphaH - py),
                        ((xx + yy) & 1) == 0 ? 0xFF34363D : 0xFF1E2026);
            }
        }
        int rgb = openColor.getRgb();
        Render2D.gradientHorizontal(context, sbX, alphaY, sbW, alphaH,
                0xFF000000 | rgb, rgb);
        Render2D.roundRect(context, sbX + sbW * hsb[3] - 1.2f, alphaY - 1.5f, 2.4f,
                alphaH + 3f, 1.2f, WHITE);
        Render2D.smallText(context, "Opacity", sbX, colorY + 95f, MUTED);
        Render2D.smallRight(context, Math.round(hsb[3] * 100f) + "%", sbX + sbW,
                colorY + 95f, TEXT);
    }

    // ------------------------------------------------------------- module bind

    /**
     * Small popup opened with the middle mouse button on a module card: picks
     * between Hold and Toggle and captures the bind itself. The bound key is
     * also drawn at the right end of the module card.
     */
    private void drawBindPopup(DrawContext context, float mx, float my, int accent) {
        if (bindModule == null) {
            return;
        }
        Render2D.roundRect(context, bindX - 0.5f, bindY - 0.5f, BIND_W + 1f, BIND_H + 1f,
                5f, 0xFF2A2D38);
        Render2D.roundRect(context, bindX, bindY, BIND_W, BIND_H, 4.5f, 0xFF101116);

        Render2D.smallText(context, truncate(bindModule.getName(), BIND_W - 18f),
                bindX + 9f, bindY + 7f, WHITE);

        float chipW = (BIND_W - 22f) / 2f;
        float chipY = bindY + 22f;
        drawChip(context, bindX + 9f, chipY, chipW, 18f, "Hold",
                bindModule.isHoldMode() ? WHITE : TEXT, bindModule.isHoldMode(), accent);
        drawChip(context, bindX + 13f + chipW, chipY, chipW, 18f, "Toggle",
                !bindModule.isHoldMode() ? WHITE : TEXT, !bindModule.isHoldMode(), accent);

        Render2D.smallText(context, "Bind", bindX + 9f, bindY + 49f, MUTED);
        boolean capturing = capturingModule == bindModule;
        String value = capturing ? "..." : Keys.label(bindModule.getKey());
        float kw = bindChipWidth(bindModule);
        float kx = bindX + BIND_W - 9f - kw;
        Render2D.roundRect(context, kx, bindY + 42f, kw, 18f, 4f,
                capturing ? ColorUtil.withAlpha(accent, 55) : 0xFF191B22);
        Render2D.smallCentered(context, value, kx + kw / 2f,
                bindY + 51f - Render2D.smallHeight() / 2f,
                capturing ? accent : bindModule.getKey() == Keys.NONE ? MUTED : TEXT);
    }

    private static float bindChipWidth(Module module) {
        String label = module.getKey() == Keys.NONE ? "NONE" : Keys.label(module.getKey());
        return Math.max(40f, Render2D.smallWidth(label) + 16f);
    }

    private void openBindPopup(Module module, float cardX, float cardY, float cardW) {
        bindModule = module;
        capturingModule = null;
        bindX = MathHelper.clamp(cardX + cardW - BIND_W, MAIN_X, MAIN_X + MAIN_W - BIND_W);
        bindY = MathHelper.clamp(cardY + 34f, VIEW_TOP, VIEW_BOTTOM - BIND_H - 2f);
    }

    /** @return {@code true} when the click was consumed by the popup. */
    private boolean clickBindPopup(float mx, float my, int button) {
        if (bindModule == null) {
            return false;
        }
        float chipW = (BIND_W - 22f) / 2f;
        float chipY = bindY + 22f;
        if (hit(mx, my, bindX + 9f, chipY, chipW, 18f)) {
            bindModule.setHoldMode(true);
            save();
            return true;
        }
        if (hit(mx, my, bindX + 13f + chipW, chipY, chipW, 18f)) {
            bindModule.setHoldMode(false);
            save();
            return true;
        }
        float kw = bindChipWidth(bindModule);
        if (hit(mx, my, bindX + BIND_W - 9f - kw, bindY + 42f, kw, 18f)) {
            if (button == 1) {
                bindModule.setKey(Keys.NONE);
                capturingModule = null;
                save();
            } else {
                capturingModule = bindModule;
            }
            return true;
        }
        if (hit(mx, my, bindX, bindY, BIND_W, BIND_H)) {
            return true;
        }
        // A click anywhere else closes the popup and keeps going.
        bindModule = null;
        capturingModule = null;
        return false;
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double screenX = click.x();
        double screenY = click.y();
        int button = click.button();
        float s = scale();
        float ox = (width - DESIGN_W * s) / 2f;
        float oy = (height - DESIGN_H * s) / 2f;
        float mx = (float) ((screenX - ox) / s);
        float my = (float) ((screenY - oy) / s);

        if (capturingModule != null) {
            // Any key or mouse button (except the cancel key handled in
            // keyPressed) becomes the module bind.
            capturingModule.setKey(Keys.mouse(button));
            capturingModule = null;
            save();
            return true;
        }
        if (bindingBind != null && button >= 2) {
            bindingBind.setValue(Keys.mouse(button));
            bindingBind = null;
            save();
            return true;
        }
        if (bindingItem != null && button >= 2) {
            bindingItem.setBind(Keys.mouse(button));
            bindingItem = null;
            save();
            return true;
        }

        if (bindModule != null && clickBindPopup(mx, my, button)) {
            return true;
        }

        // A popup swallows the click, so the row underneath cannot toggle too.
        boolean popupWasOpen = openMode != null || openMulti != null || openColor != null;
        if (clickModeDropdown(mx, my, button) || clickMultiDropdown(mx, my, button)
                || clickColorPicker(mx, my, button)) {
            return true;
        }
        if (popupWasOpen) {
            return true;
        }

        float searchX = MAIN_X + MAIN_W - 116f;
        if (hit(mx, my, searchX, 13f, 88f, 20f)) {
            searchFocused = true;
            return true;
        }
        searchFocused = false;

        if (hit(mx, my, MAIN_X + MAIN_W - 26f, 14f, 17f, 18f)) {
            twoColumns = !twoColumns;
            return true;
        }

        float rowH = 27f;
        float gap = 2f;
        for (int i = 0; i < categories.length; i++) {
            if (hit(mx, my, 9f, categoryY(i, rowH, gap), SIDEBAR - 18f, rowH)) {
                categoryIndex = i;
                rememberedCategory = i;
                expanded = null;
                dragging = null;
                openMode = null;
                openMulti = null;
                openColor = null;
                bindModule = null;
                capturingModule = null;
                scrollOffset = 0f;
                scrollTarget = 0f;
                return true;
            }
        }

        List<Module> visible = visibleModules(categories[categoryIndex]);
        int columns = twoColumns ? 2 : 1;
        float columnGap = 13f;
        float columnW = (MAIN_W - columnGap * (columns - 1)) / columns;
        float[] ys = new float[columns];
        for (int i = 0; i < columns; i++) {
            ys[i] = VIEW_TOP + 5f - scrollOffset;
        }
        for (int i = 0; i < visible.size(); i++) {
            Module module = visible.get(i);
            int column = columns == 1 ? 0 : i % 2;
            float x = MAIN_X + column * (columnW + columnGap);
            float y = ys[column];
            float settingsH = expanded == module ? settingsHeight(module) : 0f;
            float cardH = 31f + settingsH;
            boolean inView = my >= VIEW_TOP && my <= VIEW_BOTTOM;

            if (inView && hit(mx, my, x, y, columnW, 31f)) {
                if (button == 0) {
                    module.toggle();
                } else if (button == 1) {
                    expanded = expanded == module ? null : module;
                    if (expanded == null) {
                        dragging = null;
                    }
                } else if (button == 2) {
                    // Middle click: bind popup (Hold/Toggle + key capture).
                    if (bindModule == module) {
                        bindModule = null;
                        capturingModule = null;
                    } else {
                        openBindPopup(module, x, y, columnW);
                    }
                }
                return true;
            }

            if (inView && expanded == module && my >= y + 31f && my <= y + cardH) {
                if (clickSetting(module, x + 8f, y + 35f, columnW - 16f, mx, my, button)) {
                    return true;
                }
            }
            ys[column] += cardH + ROW_GAP;
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean clickSetting(Module module, float x, float y, float w,
                                 float mx, float my, int button) {
        for (Setting<?> setting : module.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            float rowH = settingRowHeight(setting);
            if (!hit(mx, my, x, y, w, rowH)) {
                y += rowH;
                continue;
            }

            if (setting instanceof BooleanSetting toggle) {
                toggle.toggle();
                save();
            } else if (setting instanceof NumberSetting number) {
                dragging = number;
                dragTrackX = x;
                dragTrackW = Math.max(20f, w - 6f);
                number.setFromSlider(mx, dragTrackX, dragTrackW);
                save();
            } else if (setting instanceof ModeSetting mode) {
                if (button == 1) {
                    mode.cycleBack();
                    openMode = null;
                    save();
                } else {
                    openMode = openMode == mode ? null : mode;
                    openMulti = null;
                    openColor = null;
                    modeX = x + w - Math.min(73f, w * 0.42f);
                    modeY = y;
                    modeW = Math.min(73f, w * 0.42f);
                    modeRowH = 20f;
                }
            } else if (setting instanceof MultiBooleanSetting multi) {
                openMulti = openMulti == multi ? null : multi;
                openMode = null;
                openColor = null;
                multiW = Math.max(104f, Math.min(150f, w * 0.7f));
                multiX = x + w - multiW;
                multiY = y;
                multiRowH = 20f;
            } else if (setting instanceof ColorSetting color) {
                openColor = openColor == color ? null : color;
                openMode = null;
                openMulti = null;
                colorDragPart = 0;
                colorW = 126f;
                colorH = 105f;
                colorX = Math.max(MAIN_X, Math.min(MAIN_X + MAIN_W - colorW, x + w - colorW));
                colorY = Math.min(DESIGN_H - colorH - 5f, y + 20f);
            } else if (setting instanceof BindSetting bind) {
                bindingBind = bind;
                bindingItem = null;
                editingText = null;
            } else if (setting instanceof ItemBindSetting item) {
                if (button == 1 && item.isSet()) {
                    item.clearBind();
                    bindingItem = null;
                    save();
                } else {
                    bindingItem = item;
                    bindingBind = null;
                    editingText = null;
                }
            } else if (setting instanceof TextSetting text) {
                if (button == 1) {
                    text.setValue("");
                    save();
                } else {
                    editingText = text;
                    textBuffer.setLength(0);
                    textBuffer.append(text.getValue());
                    bindingBind = null;
                    bindingItem = null;
                }
            }
            return true;
        }
        return false;
    }

    private boolean clickModeDropdown(float mx, float my, int button) {
        if (openMode == null) {
            return false;
        }
        List<String> values = openMode.getModes();
        float rowH = 17f;
        float listY = modeY + modeRowH + 2f;
        float h = values.size() * rowH + 4f;
        listY = Math.min(listY, DESIGN_H - 8f - h);
        if (hit(mx, my, modeX, modeY, modeW, modeRowH + h + 2f)) {
            if (my >= listY + 2f) {
                int index = (int) ((my - listY - 2f) / rowH);
                if (index >= 0 && index < values.size()) {
                    openMode.setValue(values.get(index));
                    openMode = null;
                    save();
                }
            } else {
                // Clicking the header again closes the dropdown.
                openMode = null;
            }
            return true;
        }
        openMode = null;
        return false;
    }

    private boolean clickMultiDropdown(float mx, float my, int button) {
        if (openMulti == null) {
            return false;
        }
        List<String> values = openMulti.getOptionNames();
        float rowH = 18f;
        float listY = multiY + multiRowH + 2f;
        float h = values.size() * rowH + 4f;
        listY = Math.min(listY, DESIGN_H - 8f - h);
        if (hit(mx, my, multiX, multiY, multiW, multiRowH + h + 2f)) {
            if (my >= listY + 2f) {
                int index = (int) ((my - listY - 2f) / rowH);
                if (index >= 0 && index < values.size()) {
                    openMulti.toggleOption(values.get(index));
                    save();
                }
            } else {
                openMulti = null;
            }
            return true;
        }
        openMulti = null;
        return false;
    }

    private boolean clickColorPicker(float mx, float my, int button) {
        if (openColor == null) {
            return false;
        }
        float pad = 7f;
        float sbX = colorX + pad;
        float sbY = colorY + 17f;
        float sbW = colorW - pad * 2f;
        float sbH = 46f;
        float hueY = colorY + 69f;
        float hueH = 7f;
        float alphaY = colorY + 82f;
        float alphaH = 7f;

        if (hit(mx, my, sbX, sbY, sbW, sbH)) {
            colorDragPart = 1;
            updateColorDrag(mx, my);
            return true;
        }
        if (hit(mx, my, sbX, hueY, sbW, hueH)) {
            colorDragPart = 2;
            updateColorDrag(mx, my);
            return true;
        }
        if (hit(mx, my, sbX, alphaY, sbW, alphaH)) {
            colorDragPart = 3;
            updateColorDrag(mx, my);
            return true;
        }
        if (hit(mx, my, colorX, colorY, colorW, colorH)) {
            return true;
        }
        openColor = null;
        colorDragPart = 0;
        return false;
    }

    private void updateColorDrag(float mx, float my) {
        if (openColor == null || colorDragPart == 0) {
            return;
        }
        float pad = 7f;
        float sbX = colorX + pad;
        float sbY = colorY + 17f;
        float sbW = colorW - pad * 2f;
        float sbH = 46f;
        float hueY = colorY + 69f;
        float hueH = 7f;
        float alphaY = colorY + 82f;
        float alphaH = 7f;

        float[] hsb = hsb(openColor.getArgb());
        if (colorDragPart == 1) {
            hsb[1] = clamp01((mx - sbX) / Math.max(1f, sbW));
            hsb[2] = 1f - clamp01((my - sbY) / Math.max(1f, sbH));
        } else if (colorDragPart == 2) {
            hsb[0] = clamp01((mx - sbX) / Math.max(1f, sbW));
        } else {
            hsb[3] = clamp01((mx - sbX) / Math.max(1f, sbW));
        }
        setHsb(openColor, hsb[0], hsb[1], hsb[2], hsb[3]);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        float s = scale();
        float ox = (width - DESIGN_W * s) / 2f;
        float oy = (height - DESIGN_H * s) / 2f;
        float mx = (float) ((click.x() - ox) / s);
        float my = (float) ((click.y() - oy) / s);

        if (dragging != null) {
            dragging.setFromSlider(mx, dragTrackX, dragTrackW);
            return true;
        }
        if (openColor != null && colorDragPart != 0) {
            updateColorDrag(mx, my);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            save();
            dragging = null;
        }
        if (colorDragPart != 0) {
            save();
            colorDragPart = 0;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
                                 double verticalAmount) {
        float s = scale();
        float ox = (width - DESIGN_W * s) / 2f;
        float oy = (height - DESIGN_H * s) / 2f;
        float mx = (float) ((mouseX - ox) / s);
        float my = (float) ((mouseY - oy) / s);
        if (hit(mx, my, MAIN_X, VIEW_TOP, MAIN_W, VIEW_BOTTOM - VIEW_TOP)) {
            scrollTarget = MathHelper.clamp(scrollTarget - (float) verticalAmount * 36f, 0f, maxScroll);
            openMode = null;
            openMulti = null;
            openColor = null;
            bindModule = null;
            capturingModule = null;
            return true;
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
                commitText();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (textBuffer.length() > 0) {
                    textBuffer.deleteCharAt(textBuffer.length() - 1);
                }
                return true;
            }
            return true;
        }
        if (capturingModule != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                capturingModule = null;
                return true;
            }
            int bind = key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE
                    ? Keys.NONE : key;
            capturingModule.setKey(bind);
            capturingModule = null;
            save();
            return true;
        }
        if (bindingBind != null || bindingItem != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                bindingBind = null;
                bindingItem = null;
                return true;
            }
            int bind = key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE
                    ? Keys.NONE : key;
            if (bindingBind != null) {
                bindingBind.setValue(bind);
                bindingBind = null;
            } else {
                bindingItem.setBind(bind);
                bindingItem = null;
            }
            save();
            return true;
        }
        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!search.isEmpty()) {
                    search = search.substring(0, search.offsetByCodePoints(search.length(), -1));
                    scrollOffset = 0f;
                    scrollTarget = 0f;
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
        }
        if (bindModule != null) {
            bindModule = null;
            capturingModule = null;
            return true;
        }
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
                    if (character < 32 || (editingText.isDigitsOnly() && !Character.isDigit(character))) {
                        continue;
                    }
                    textBuffer.append(character);
                }
            }
            return true;
        }
        if (searchFocused) {
            String typed = input.asString();
            if (typed != null && !typed.isEmpty() && search.length() < 28) {
                for (int i = 0; i < typed.length() && search.length() < 28; i++) {
                    char character = typed.charAt(i);
                    if (character >= 32) {
                        search += character;
                    }
                }
                scrollOffset = 0f;
                scrollTarget = 0f;
                return true;
            }
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        commitText();
        bindModule = null;
        capturingModule = null;
        save();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void commitText() {
        TextSetting text = editingText;
        if (text == null) {
            return;
        }
        editingText = null;
        String value = textBuffer.toString();
        textBuffer.setLength(0);
        if (!value.equals(text.getValue())) {
            text.setValue(value);
            save();
        }
    }

    // ------------------------------------------------------------------ helper

    private void drawBottomHint(DrawContext context, String hint, int accent) {
        float hintWidth = Render2D.smallWidth(hint) + 22f;
        float hintX = (width - hintWidth) / 2f;
        Render2D.roundRect(context, hintX, height - 34f, hintWidth, 20f, 10f, 0xF20E0E12);
        Render2D.roundRect(context, hintX + 0.5f, height - 33.5f, hintWidth - 1f, 19f, 9.5f, 0x16FFFFFF);
        Render2D.smallCentered(context, hint, width / 2f, height - 28f,
                ColorUtil.withAlpha(accent, 235));
    }

    private float scale() {
        float margin = 18f;
        float fit = Math.min((width - margin * 2f) / DESIGN_W, (height - margin * 2f) / DESIGN_H);
        // The whole window (layout + fonts) is drawn through one matrix, so a
        // single factor shrinks the GUI by ~15% without touching the layout.
        return MathHelper.clamp(fit * 0.85f, 0.46f, 0.90f);
    }

    private static float categoryY(float index, float rowH, float gap) {
        return 48f + index * (rowH + gap);
    }

    private float settingRowHeight(Setting<?> setting) {
        if (setting instanceof NumberSetting) {
            return 25f;
        }
        if (setting instanceof BooleanSetting) {
            return 25f;
        }
        if (setting instanceof BindSetting) {
            return 23f;
        }
        return 24f;
    }

    private float settingsHeight(Module module) {
        float height = 12f;
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isVisible()) {
                height += settingRowHeight(setting);
            }
        }
        return height;
    }

    private void save() {
        if (Velora.get() != null) {
            Velora.get().getConfigManager().save();
        }
    }

    private static boolean hit(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String truncate(String text, float width) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (Render2D.smallWidth(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && Render2D.smallWidth(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private static String truncateBody(String text, float width) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (Render2D.textWidth(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && Render2D.textWidth(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int mix(int from, int to, float t) {
        float p = clamp01(t);
        int a = Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * p);
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * p);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * p);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * p);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static float[] hsb(int argb) {
        float[] values = java.awt.Color.RGBtoHSB((argb >> 16) & 255, (argb >> 8) & 255,
                argb & 255, null);
        return new float[]{values[0], values[1], values[2], ((argb >>> 24) & 255) / 255f};
    }

    private static void setHsb(ColorSetting setting, float hue, float saturation,
                               float brightness, float alpha) {
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        int a = Math.round(clamp01(alpha) * 255f);
        setting.setValue((a << 24) | (rgb & 0xFFFFFF));
    }

    /** Exponential smoothing used by switches and the category highlight. */
    private static final class Anim {
        private float value;
        private float target;

        Anim(float initial) {
            value = initial;
            target = initial;
        }

        void setTarget(float next) {
            target = next;
        }

        float update(float dt) {
            float amount = 1f - (float) Math.exp(-20f * Math.max(0f, Math.min(dt, 0.1f)));
            value += (target - value) * amount;
            return value;
        }
    }
}
