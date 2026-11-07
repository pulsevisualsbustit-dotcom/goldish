package dev.velora.render;

import dev.velora.Velora;
import dev.velora.config.ConfigManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * Shared design language of the Velora HUD.
 *
 * <p>The panels follow the compact glass style of the reference HUD: a dark
 * rounded card with a vertical alpha fade, a soft top highlight and no heavy
 * outline. Every card keeps a 23px header strip with its title on the left and
 * a small square icon badge on the right, rows are 11px tall and use a hairline
 * divider between the icon and the text. Accent colours always come from the
 * active theme, so a theme change restyles the whole HUD at once.</p>
 */
public final class HudStyle {

    public static final int GOLD_PRIMARY = 0xFFFFD25A;
    public static final int GOLD_SECONDARY = 0xFFFF8A00;
    /** Flat, fully opaque black used by every HUD surface: no gradients, no tint. */
    public static final int CARD_BLACK = 0xFF000000;
    public static final int CARD_GLASS = CARD_BLACK;
    public static final int BORDER_LINE = 0x38FFC85A;

    public static final int TRACK = 0x1AFFFFFF;
    public static final int GREEN = 0xFF7EE07A;
    public static final int RED = 0xFFFF6A4A;
    public static final int CYAN = 0xFF6FE3DC;

    public static final int CARD_TOP = CARD_BLACK;
    public static final int CARD_BOTTOM = CARD_BLACK;
    /** Header icon badge. */
    public static final int ICON_BOX = 0xFF080808;
    public static final int ICON_BOX_BORDER = 0xFF2E2E2E;
    /** Hairline between the row icon and the row text. */
    public static final int ROW_LINE = 0x2EFFFFFF;
    public static final int ROW_TRACK = 0xFF000000;

    /** Header height: title baseline + icon badge. */
    public static final float HEADER = 23f;
    /** Vertical pitch of one row. */
    public static final float ROW_HEIGHT = 11f;

    private HudStyle() {
    }

    /** Seconds since the client process started; used for all subtle motion. */
    public static float time() {
        return (System.currentTimeMillis() % 86_400_000L) / 1000f;
    }

    public static int accent() {
        ConfigManager manager = config();
        return manager == null ? GOLD_PRIMARY : manager.getAccentColor();
    }

    public static int accentSecondary() {
        ConfigManager manager = config();
        return manager == null ? GOLD_SECONDARY : manager.getAccentSecondary();
    }

    /** Primary HUD text: always the active theme's text colour. */
    public static int text() {
        ConfigManager manager = config();
        return manager == null ? 0xFFF4F4F7 : manager.getTextColor();
    }

    /** Secondary HUD text: the theme's muted colour. */
    public static int textMuted() {
        ConfigManager manager = config();
        return manager == null ? 0xFF9B9BA7 : manager.getMutedTextColor();
    }

    public static int textDim() {
        return ColorUtil.withAlpha(textMuted(), 215);
    }

    public static int textFaint() {
        return ColorUtil.withAlpha(textMuted(), 155);
    }

    private static ConfigManager config() {
        Velora client = Velora.get();
        return client == null ? null : client.getConfigManager();
    }

    /** Slowly breathing colour, used by live accents and glows. */
    public static int breathing(int color, float speed, int minAlpha, int maxAlpha) {
        float wave = 0.5f + 0.5f * (float) Math.sin(time() * speed);
        return ColorUtil.withAlpha(color, Math.round(minAlpha + (maxAlpha - minAlpha) * wave));
    }

    /**
     * The standard HUD card: one solid black rounded rectangle. Completely
     * opaque, no gradient, no highlight and no halo, so the world never shows
     * through a HUD panel; every piece of information on top uses the active
     * theme's text colour.
     */
    public static void card(DrawContext context, float x, float y,
                            float width, float height, float radius) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        Render2D.roundRect(context, x, y, width, height, r, CARD_BLACK);
    }

    /** Backwards-compatible overload with the old accent/glow arguments. */
    public static void card(DrawContext context, float x, float y,
                            float width, float height, float radius, int accent, boolean glow) {
        card(context, x, y, width, height, radius);
    }

    /**
     * Panel header: title in white on the left, a rounded icon badge on the
     * right. The badge is drawn empty; the caller paints its glyph inside.
     */
    public static void header(DrawContext context, float x, float y, float width, String title) {
        Render2D.text(context, title, x + 6f, y + 4.8f, text(), false);
        iconBox(context, x + width - 18f, y + 3.5f, 12f);
    }

    /** Dark rounded badge with a thin grey outline, used by panel headers. */
    public static void iconBox(DrawContext context, float x, float y, float size) {
        Render2D.roundRect(context, x, y, size, size, 3f, ICON_BOX);
        Render2D.roundRect(context, x + 0.5f, y + 0.5f, size - 1f, size - 1f, 2.5f, ICON_BOX_BORDER);
        Render2D.roundRect(context, x + 1f, y + 1f, size - 2f, size - 2f, 2.2f, ICON_BOX);
    }

    /** Hairline that separates a row icon from its label. */
    public static void rowDivider(DrawContext context, float x, float centerY) {
        Render2D.rect(context, x, centerY - 3.5f, 0.5f, 7f, ROW_LINE);
    }

    /** Rounded progress bar with a track and a gradient fill. */
    public static void bar(DrawContext context, float x, float y,
                           float width, float height, float progress, int from, int to) {
        float radius = height / 2f;
        Render2D.roundRect(context, x, y, width, height, radius, TRACK);
        barFill(context, x, y, width, height, progress, from, to);
    }

    /** The filled part of a {@link #bar} on top of an already drawn track. */
    public static void barFill(DrawContext context, float x, float y,
                               float width, float height, float progress, int from, int to) {
        float filled = Math.max(0f, Math.min(1f, progress)) * width;
        if (filled <= 0.5f) {
            return;
        }
        Render2D.roundRectGradient(context, x, y, filled, height, height / 2f, from, to);
        Render2D.roundRect(context, x + height * 0.3f, y + 0.55f,
                Math.max(0.5f, filled - height * 0.6f), Math.max(0.5f, height * 0.28f),
                height * 0.14f, 0x34FFFFFF);
    }

    /**
     * Draws a 16px item icon scaled to {@code size} at the given position.
     * The matrix is restored afterwards, so callers can keep drawing normally.
     */
    public static void item(DrawContext context, ItemStack stack, float x, float y, float size) {
        if (stack == null || stack.isEmpty() || size <= 0f) {
            return;
        }
        var matrices = context.getMatrices();
        float scale = size / 16f;
        matrices.pushMatrix();
        matrices.translate(x, y);
        matrices.scale(scale, scale);
        context.drawItem(stack, 0, 0);
        matrices.popMatrix();
    }

    /** The Velora mark: a diamond split by a diagonal notch, like a stylised "G". */
    public static void mark(DrawContext context, float centerX, float centerY, float size,
                            int from, int to) {
        float h = size / 2f;
        Render2D.quad(context,
                centerX, centerY - h,
                centerX + h, centerY - h * 0.05f,
                centerX + h * 0.34f, centerY,
                centerX, centerY,
                from);
        Render2D.quad(context,
                centerX + h * 0.34f, centerY,
                centerX + h, centerY + h * 0.05f,
                centerX, centerY + h,
                centerX, centerY,
                to);
    }
}
