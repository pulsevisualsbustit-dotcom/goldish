package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A single HUD widget rendered by the {@link Hud} module.
 *
 * <p>All HUD widgets live inside one "Hud" module instead of being separate
 * modules: they have no bind or module-list entry of their own, their settings
 * are flattened into the Hud module (prefixed with the widget name so config
 * keys stay unique) and they can still be dragged around in the HUD editor
 * like before. ({@link #hudWidth()} / {@link #hudHeight()} return the
 * effective, scale-applied size so hit tests stay correct.)</p>
 *
 * <p>Position: every widget declares where it belongs with one of the
 * {@code anchor…} calls (a corner or an edge plus an offset). The Hud wraps the
 * render in a scale matrix around the resulting corner, so the default layout
 * is identical at every GUI scale. A widget the user dragged keeps its own
 * saved position instead and ignores the anchor.</p>
 */
public abstract class HudWidget implements HudElement {

    /** Horizontal side of the screen an anchor is measured from. */
    private enum Side {
        LEFT, RIGHT, CENTER
    }

    /** Vertical side of the screen an anchor is measured from. */
    private enum Edge {
        TOP, MIDDLE, BOTTOM
    }

    private final String id;
    private final String label;
    private final List<Setting<?>> settings = new ArrayList<>();

    /** Wired by the Hud module: whether the widget is switched on right now. */
    private BooleanSupplier visible = () -> false;

    /** Top-left corner on screen; {@code NaN} until the first layout. */
    private float x = Float.NaN;
    private float y = Float.NaN;

    private float width;
    private float height;
    /** On-screen size of the last frame, used for hit tests and the corner stack. */
    private float renderedWidth;
    private float renderedHeight;
    /** Set once the user dragged the widget or a saved position was loaded. */
    private boolean customPosition;

    private Side side = Side.LEFT;
    private Edge edge = Edge.TOP;
    /** Distance from the anchored sides, in unscaled HUD pixels. */
    private float anchorDx;
    private float anchorDy;

    /** Rendered at the top of the screen and stacked with overlapping neighbours. */
    private boolean topRow;
    /** Widgets only stack with neighbours in the same group. */
    private String stackGroup = "default";
    /** Top edge the corner stack forces this frame (NaN = none). */
    private float stackedTop = Float.NaN;

    private final List<Integer> shownItems = new ArrayList<>();
    private long lastFrameNanos;
    private float lastFrameFactor = 1f;

    protected HudWidget(String id, String label) {
        this.id = id;
        this.label = label;
    }

    protected <T extends Setting<?>> T register(T setting) {
        settings.add(setting);
        return setting;
    }

    List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    void setVisible(BooleanSupplier supplier) {
        this.visible = supplier;
    }

    public String getId() {
        return id;
    }

    public abstract void render(DrawContext context, float tickDelta);

    // ------------------------------------------------------------- layout api

    /** Current HUD scale applied around the widget's top-left corner. */
    protected final float scale() {
        return Hud.getScale();
    }

    /** Marks the widget as part of the top edge; neighbours never overlap it. */
    protected final void topRow() {
        topRow = true;
    }

    /** Same, but only stacks with widgets that declared the same group. */
    protected final void topRow(String group) {
        topRow = true;
        stackGroup = group;
    }

    boolean isTopRow() {
        return topRow;
    }

    /** {@code true} when both widgets are anchored to the same top corner. */
    boolean sharesTopCorner(HudWidget other) {
        return topRow && other.topRow && edge == Edge.TOP && other.edge == Edge.TOP
                && side == other.side && stackGroup.equals(other.stackGroup);
    }

    /** Top-left corner: {@code dx} from the left edge, {@code dy} from the top. */
    protected final void anchorTopLeft(float dx, float dy) {
        this.side = Side.LEFT;
        this.edge = Edge.TOP;
        this.anchorDx = dx;
        this.anchorDy = dy;
    }

    /** Top-right corner: {@code dx} from the right edge, {@code dy} from the top. */
    protected final void anchorTopRight(float dx, float dy) {
        this.side = Side.RIGHT;
        this.edge = Edge.TOP;
        this.anchorDx = dx;
        this.anchorDy = dy;
    }

    /** Top-right corner, kept clear of the vanilla status-effect icons. */
    protected final void anchorTopRightClear(float dy) {
        anchorTopRight(5f + EffectIcons.width(Velora.mc()), dy);
    }

    /** Top edge, horizontally centred, {@code dy} below the top. */
    protected final void anchorTopCenter(float dy) {
        this.side = Side.CENTER;
        this.edge = Edge.TOP;
        this.anchorDx = 0f;
        this.anchorDy = dy;
    }

    /** Bottom-left corner: {@code dx} from the left, {@code dy} above the bottom. */
    protected final void anchorBottomLeft(float dx, float dy) {
        this.side = Side.LEFT;
        this.edge = Edge.BOTTOM;
        this.anchorDx = dx;
        this.anchorDy = dy;
    }

    /** Bottom edge, horizontally centred, {@code dy} above the bottom. */
    protected final void anchorBottomCenter(float dy) {
        this.side = Side.CENTER;
        this.edge = Edge.BOTTOM;
        this.anchorDx = 0f;
        this.anchorDy = dy;
    }

    /** Horizontally centred, {@code dy} below the middle of the screen. */
    protected final void anchorCenter(float dy) {
        this.side = Side.CENTER;
        this.edge = Edge.MIDDLE;
        this.anchorDx = 0f;
        this.anchorDy = dy;
    }

    /** Left edge of the widget, on screen (what the render draws at). */
    protected final float screenX() {
        return MathHelper.clamp(x, 0f, screenWidth());
    }

    /** Top edge of the widget, on screen (what the render draws at). */
    protected final float screenY() {
        return MathHelper.clamp(y, 0f, screenHeight());
    }

    private static float screenWidth() {
        return Velora.mc().getWindow().getScaledWidth();
    }

    private static float screenHeight() {
        return Velora.mc().getWindow().getScaledHeight();
    }

    /**
     * Resolves the position for this frame: a dragged widget keeps its saved
     * spot, every other widget sits at the corner its anchor describes.
     */
    protected final void place() {
        frameFactor();
        var config = Velora.get().getConfigManager();
        if (!customPosition && config.hasHudPosition(id)) {
            float[] stored = config.getHudPosition(id, 0f, 0f);
            x = stored[0];
            y = stored[1];
            customPosition = true;
        }
        if (customPosition) {
            float maxX = Math.max(0f, screenWidth() - Math.max(1f, hudWidth()) / 2f);
            float maxY = Math.max(0f, screenHeight() - 12f);
            x = MathHelper.clamp(x, 0f, maxX);
            y = MathHelper.clamp(y, 0f, maxY);
            return;
        }

        float s = scale();
        float scaledWidth = width * s;
        float scaledHeight = height * s;
        x = switch (side) {
            case LEFT -> anchorDx * s;
            case RIGHT -> screenWidth() - anchorDx * s - scaledWidth;
            case CENTER -> (screenWidth() - scaledWidth) / 2f;
        };
        y = switch (edge) {
            case TOP -> anchorDy * s;
            case MIDDLE -> (screenHeight() - scaledHeight) / 2f + anchorDy * s;
            case BOTTOM -> screenHeight() - anchorDy * s - scaledHeight;
        };
        if (!Float.isNaN(stackedTop) && y < stackedTop) {
            // The corner is crowded: this widget waits below its neighbour.
            y = stackedTop;
        }
    }

    /** @return {@code true} while the widget keeps a user-saved position. */
    boolean hasCustomPosition() {
        return customPosition || Velora.get().getConfigManager().hasHudPosition(id);
    }

    /** Forces the widget below {@code top} for this frame (corner stack). */
    void setStackedTop(float top) {
        stackedTop = top;
    }

    void clearStackedTop() {
        stackedTop = Float.NaN;
    }

    /** Records the raw size in unscaled pixels. */
    protected final void setSize(float width, float height) {
        this.width = width;
        this.height = height;
    }

    /** Called by the Hud before every render, once the scale is known. */
    void prepareFrame() {
        renderedWidth = width * scale();
        renderedHeight = height * scale();
    }

    /** {@code true} while a HUD editing surface is open and widgets are editable. */
    protected static boolean hudEditorOpen() {
        var screen = Velora.mc().currentScreen;
        return screen instanceof ChatScreen || screen instanceof HudEditorScreen;
    }

    /**
     * Reads a widget-wide HUD setting (e.g. "HUD Animation") by name, so every
     * widget can follow one switch registered on the Hud module.
     */
    protected static boolean hudToggle(String name) {
        Velora client = Velora.get();
        Hud hud = client == null ? null : client.getModuleManager().get(Hud.class);
        if (hud == null) {
            return true;
        }
        for (Setting<?> setting : hud.getSettings()) {
            if (setting instanceof dev.velora.setting.BooleanSetting toggle
                    && setting.getName().equals(name)) {
                return toggle.get();
            }
        }
        return true;
    }

    // ------------------------------------------------------------ animation

    /**
     * Frame weight for the row/list animations: 1.0 at 60 fps, clamped so a long
     * stall does not make every widget jump. Widgets run their animation in
     * {@link #render}, which happens every frame, so this measures real frames
     * rather than ticks.
     */
    protected final float frameFactor() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            lastFrameFactor = 1f;
            return lastFrameFactor;
        }
        // Several calls happen inside one render (measuring, drawing, rows), so
        // only a call that really starts a new frame advances the timer.
        if (now - lastFrameNanos > 2_000_000L) {
            float factor = (now - lastFrameNanos) / 1_000_000f / (1000f / 60f);
            lastFrameNanos = now;
            lastFrameFactor = MathHelper.clamp(factor, 0f, 3f);
        }
        return lastFrameFactor;
    }

    /**
     * Registers the rows currently drawn and starts/advances their animation.
     * Rows that disappear keep their state (with a falling progress) until the
     * exit animation has finished, and new rows start at zero and rise in, so
     * lists grow and shrink smoothly instead of popping.
     *
     * @param ids ids of the rows that should be visible, in display order
     * @return the ids that are on screen right now, including leaving rows
     */
    protected final int[] animateRows(int[] ids) {
        if (!Hud.animationsEnabled()) {
            // No animation: rows snap in and out, which is also what the HUD
            // editor wants while a widget is being positioned.
            shownItems.clear();
            int[] sorted = ids.clone();
            Arrays.sort(sorted);
            for (int id : sorted) {
                shownItems.add(id);
                ItemAnimation.progress(id, 1f);
            }
            return ids.clone();
        }

        float step = frameFactor() * ROW_FADE;
        int[] sorted = ids.clone();
        Arrays.sort(sorted);

        for (int i = shownItems.size() - 1; i >= 0; i--) {
            int id = shownItems.get(i);
            if (Arrays.binarySearch(sorted, id) < 0) {
                float progress = ItemAnimation.progress(id) - step;
                if (progress <= 0f) {
                    shownItems.remove(i);
                    ItemAnimation.progress(id, 0f);
                } else {
                    ItemAnimation.progress(id, progress);
                }
            }
        }
        for (int id : sorted) {
            if (ItemAnimation.progress(id) <= 0f) {
                if (!shownItems.contains(id)) {
                    shownItems.add(id);
                }
                ItemAnimation.progress(id, 0f);
            }
        }
        int[] shown = new int[shownItems.size()];
        for (int i = 0; i < shown.length; i++) {
            shown[i] = shownItems.get(i);
        }
        return shown;
    }

    /** Animation progress per frame: 0.34 makes a row appear in about four frames. */
    private static final float ROW_FADE = 0.34f;
    /** Horizontal re-sort glide per frame. */
    static final float ROW_SLIDE = 0.42f;

    /** Animation progress (0..1) of a row passed to {@link #animateRows}. */
    protected final float rowProgress(int id) {
        float progress = ItemAnimation.progress(id);
        if (shownItems.contains(id) && progress < 1f && Hud.animationsEnabled()) {
            progress = Math.min(1f, progress + frameFactor() * ROW_FADE);
            ItemAnimation.progress(id, progress);
        }
        return progress;
    }

    /** @return {@code true} when the row is part of the animated list. */
    protected final boolean rowShown(int id) {
        return shownItems.contains(id);
    }

    /** Ease-out curve used by every HUD row animation. */
    protected static float easeOut(float t) {
        float clamped = MathHelper.clamp(t, 0f, 1f);
        return 1f - (1f - clamped) * (1f - clamped);
    }

    // ---------------------------------------------------------------- HudElement

    @Override
    public final float hudX() {
        return screenX();
    }

    @Override
    public final float hudY() {
        return screenY();
    }

    @Override
    public final float hudWidth() {
        return renderedWidth > 0f ? renderedWidth : width * scale();
    }

    @Override
    public final float hudHeight() {
        return renderedHeight > 0f ? renderedHeight : height * scale();
    }

    @Override
    public final void moveHud(float newX, float newY) {
        float maxX = Math.max(0f, screenWidth() - Math.max(1f, hudWidth()) / 2f);
        float maxY = Math.max(0f, screenHeight() - 12f);
        x = MathHelper.clamp(newX, 0f, maxX);
        y = MathHelper.clamp(newY, 0f, maxY);
        customPosition = true;
        Velora.get().getConfigManager().setHudPosition(id, x, y);
    }

    @Override
    public final boolean isEnabled() {
        return visible.getAsBoolean();
    }

    @Override
    public final String getName() {
        return label;
    }

    /**
     * Width taken by the vanilla effect icon rows (beneficial effects on the first
     * row, harmful on the second), so a widget can sit next to them.
     */
    static final class EffectIcons {

        private EffectIcons() {
        }

        static float width(MinecraftClient client) {
            if (client.player == null) {
                return 0f;
            }
            int beneficial = 0;
            int harmful = 0;
            for (var effect : client.player.getStatusEffects()) {
                if (!effect.shouldShowIcon()) {
                    continue;
                }
                if (effect.getEffectType().value().isBeneficial()) {
                    beneficial++;
                } else {
                    harmful++;
                }
            }
            int icons = Math.max(beneficial, harmful);
            // Vanilla: 24px icon + 1px gap, drawn from the right edge.
            return icons == 0 ? 0f : icons * 25f;
        }
    }
}
