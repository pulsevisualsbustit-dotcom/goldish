package dev.velora.render;

import dev.velora.Velora;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

import java.io.InputStream;

/**
 * The 2D rendering stack of Velora.
 *
 * <p>Everything goes through {@link DrawContext} so the drawing is submitted to
 * the vanilla 1.21.11 GUI batch (correct scissor handling, correct order, works
 * inside both HUD and screens). Rounded shapes are drawn with procedurally
 * generated, anti-aliased 9-slice textures, which keeps the API simple and the
 * result crisp on any GUI scale.</p>
 */
public final class Render2D {

    private static final Identifier TEX_ROUNDED = Identifier.of("velora", "gui/rounded");
    private static final Identifier TEX_SOFT = Identifier.of("velora", "gui/soft");
    private static final Identifier TEX_CIRCLE = Identifier.of("velora", "gui/circle");
    private static final Identifier TEX_RING = Identifier.of("velora", "gui/ring");
    private static final Identifier TEX_RING_GLOW = Identifier.of("velora", "gui/ring_glow");
    private static final Identifier TEX_LOGO = Identifier.of("velora", "gui/logo_mask");
    /** Higher-contrast logo mask used for the small HUD slots. */
    private static final Identifier TEX_LOGO_SMALL = Identifier.of("velora", "gui/logo_small");

    private static final int ROUNDED_SIZE = 33;
    private static final int ROUNDED_CORNER = 16;
    private static final int SOFT_SIZE = 64;
    private static final int CIRCLE_SIZE = 64;
    private static final int RING_SIZE = 64;

    private static boolean initialized;

    private static SmoothTexture roundedTexture;
    private static SmoothTexture softTexture;
    private static SmoothTexture circleTexture;
    private static SmoothTexture ringTexture;
    private static SmoothTexture ringGlowTexture;
    /** The client logo, loaded from {@code assets/velora/textures/gui/logo_mask.png}. */
    private static SmoothTexture logoTexture;
    private static SmoothTexture logoSmallTexture;
    private static int logoSize;
    private static boolean logoLoadAttempted;
    /** Below this drawn size the crisp mask is used instead of the glowing one. */
    private static final float LOGO_SMALL_LIMIT = 30f;

    private Render2D() {
    }

    /** Builds and registers the generated textures. Safe to call on the render thread only. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        registerTextures();
    }

    /**
     * Called before every generated-texture draw. Generates the textures on first
     * use and rebuilds them if they were dropped (resource reload, world change),
     * so the client never renders with the missing texture.
     */
    public static void ensureInit() {
        if (!initialized) {
            init();
            return;
        }
        if (!texturesAlive()) {
            registerTextures();
        }
    }

    /** {@code true} while the generated shape textures are registered and usable. */
    public static boolean ready() {
        return initialized && texturesAlive();
    }

    // ------------------------------------------------------------------- logo

    /**
     * The Velora logo (the neon kobold-on-a-block mark) drawn into a square box.
     *
     * <p>The texture is a white mask, so the mark always takes the accent of the
     * active theme; a second, weaker pass adds the hot white core of a real neon
     * tube. The source PNG is loaded straight from the mod resources and kept in
     * a linear-filtered texture, so it stays soft at every size.</p>
     *
     * @return {@code true} when the logo was drawn (callers can fall back to a
     *         drawn mark when the asset is missing)
     */
    public static boolean logo(DrawContext context, float x, float y, float size, int tint) {
        if (!ensureLogo() || size <= 0.5f) {
            return false;
        }
        drawLogoPass(context, x, y, size, tint);
        if (size >= 30f) {
            // Hot core, only where the strokes are wide enough to show it:
            // on a small mark the white pass just washes the accent out.
            drawLogoPass(context, x, y, size, ColorUtil.withAlpha(0xFFFFFF, 90));
        }
        return true;
    }

    /** Logo in the theme accent, the default look of the mark. */
    public static boolean logo(DrawContext context, float x, float y, float size) {
        int accent = HudStyle.accent();
        int accentSecondary = HudStyle.accentSecondary();
        // A single tint that leans towards the secondary colour keeps the mark on
        // the same gradient family as the wordmark without per-column draws.
        int tint = ColorUtil.lerp(accent, accentSecondary, 0.35f);
        return logo(context, x, y, size, tint);
    }

    private static void drawLogoPass(DrawContext context, float x, float y, float size, int color) {
        int pixels = Math.max(1, Math.round(size));
        // Small slots get the crisp mask: at ~50 px the soft glow would merge the
        // thin neon lines into one blob.
        Identifier texture = size < LOGO_SMALL_LIMIT ? TEX_LOGO_SMALL : TEX_LOGO;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                Math.round(x), Math.round(y), 0f, 0f,
                pixels, pixels, logoSize, logoSize, logoSize, logoSize, color);
    }

    private static boolean ensureLogo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        var manager = client.getTextureManager();
        if (logoTexture != null && manager.getTexture(TEX_LOGO) == logoTexture) {
            if (logoSmallTexture == null || manager.getTexture(TEX_LOGO_SMALL) != null) {
                return true;
            }
            manager.registerTexture(TEX_LOGO_SMALL, logoSmallTexture);
            return true;
        }
        if (logoLoadAttempted && logoTexture != null) {
            // Reloaded resources dropped it: register the same image again.
            manager.registerTexture(TEX_LOGO, logoTexture);
            registerSmallLogo(manager);
            return true;
        }
        logoLoadAttempted = true;
        try (InputStream stream = Render2D.class.getResourceAsStream(
                "/assets/velora/textures/gui/logo_mask.png")) {
            if (stream == null) {
                return false;
            }
            NativeImage image = NativeImage.read(stream);
            logoSize = image.getWidth();
            logoTexture = new SmoothTexture("velora_logo", image);
            manager.registerTexture(TEX_LOGO, logoTexture);
            registerSmallLogo(manager);
            return true;
        } catch (Throwable exception) {
            // A missing or broken logo must never break the HUD: callers fall back.
            return false;
        }
    }

    /** The crisp mask for the small slots; optional, the soft one is the fallback. */
    private static void registerSmallLogo(net.minecraft.client.texture.TextureManager manager) {
        if (logoSmallTexture != null) {
            manager.registerTexture(TEX_LOGO_SMALL, logoSmallTexture);
            return;
        }
        try (InputStream stream = Render2D.class.getResourceAsStream(
                "/assets/velora/textures/gui/logo_small.png")) {
            if (stream == null) {
                return;
            }
            logoSmallTexture = new SmoothTexture("velora_logo_small", NativeImage.read(stream));
            manager.registerTexture(TEX_LOGO_SMALL, logoSmallTexture);
        } catch (Throwable ignored) {
            // Falls back to the soft mask, which is already registered.
        }
    }

    private static boolean texturesAlive() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return true;
        }
        var manager = client.getTextureManager();
        return roundedTexture != null && softTexture != null && circleTexture != null
                && ringTexture != null && ringGlowTexture != null
                && manager.getTexture(TEX_ROUNDED) == roundedTexture
                && manager.getTexture(TEX_SOFT) == softTexture
                && manager.getTexture(TEX_CIRCLE) == circleTexture
                && manager.getTexture(TEX_RING) == ringTexture
                && manager.getTexture(TEX_RING_GLOW) == ringGlowTexture;
    }

    private static void registerTextures() {
        MinecraftClient client = MinecraftClient.getInstance();
        var manager = client.getTextureManager();

        roundedTexture = new SmoothTexture("velora_rounded", createRoundedImage());
        softTexture = new SmoothTexture("velora_soft", createSoftImage());
        circleTexture = new SmoothTexture("velora_circle", createCircleImage());
        ringTexture = new SmoothTexture("velora_ring", createRingImage());
        ringGlowTexture = new SmoothTexture("velora_ring_glow", createRingGlowImage());

        manager.registerTexture(TEX_ROUNDED, roundedTexture);
        manager.registerTexture(TEX_SOFT, softTexture);
        manager.registerTexture(TEX_CIRCLE, circleTexture);
        manager.registerTexture(TEX_RING, ringTexture);
        manager.registerTexture(TEX_RING_GLOW, ringGlowTexture);
    }

    // ------------------------------------------------------------------ basics

    public static void rect(DrawContext context, float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.fill(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height), color);
    }

    /** Vertical gradient (top to bottom). */
    public static void gradient(DrawContext context, float x, float y, float width, float height, int top, int bottom) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.fillGradient(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height), top, bottom);
    }

    /** Horizontal gradient (left to right). */
    public static void gradientHorizontal(DrawContext context, float x, float y, float width, float height, int left, int right) {
        gradientQuad(context, x, y, width, height, left, right, right, left);
    }

    /** Quad with a colour per corner: TL, TR, BR, BL. */
    public static void gradientQuad(DrawContext context, float x, float y, float width, float height,
                                    int topLeft, int topRight, int bottomRight, int bottomLeft) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ScreenRect scissor = context.scissorStack.peekLast();
        SimpleGuiElementRenderState element = new GradientQuadElement(
                RenderPipelines.GUI,
                new Matrix3x2f(context.getMatrices()),
                Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height),
                topLeft, topRight, bottomRight, bottomLeft,
                scissor
        );
        context.state.addSimpleElement(element);
    }

    /**
     * Arbitrary four-vertex quad. Needed for perspective-correct ESP faces,
     * which cannot be expressed as an axis-aligned rectangle.
     */
    public static void quad(DrawContext context, float x0, float y0, float x1, float y1,
                            float x2, float y2, float x3, float y3, int color) {
        ScreenRect scissor = context.scissorStack.peekLast();
        context.state.addSimpleElement(new QuadElement(
                RenderPipelines.GUI,
                new Matrix3x2f(context.getMatrices()),
                x0, y0, x1, y1, x2, y2, x3, y3,
                color, scissor
        ));
    }

    /** Thick line drawn as a rotated quad; used by the ESP box and crosshair helpers. */
    public static void line(DrawContext context, float x1, float y1, float x2, float y2,
                            float thickness, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 0.01f) {
            return;
        }
        float half = Math.max(0.35f, thickness) / 2f;
        float nx = -dy / length * half;
        float ny = dx / length * half;
        quad(context,
                x1 + nx, y1 + ny,
                x2 + nx, y2 + ny,
                x2 - nx, y2 - ny,
                x1 - nx, y1 - ny,
                color);
    }

    // ------------------------------------------------------------ rounded rect

    public static void roundRect(DrawContext context, float x, float y, float width, float height, float radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r < 0.5f) {
            rect(context, x, y, width, height, color);
            return;
        }
        ensureInit();
        nineSlice(context, TEX_ROUNDED, ROUNDED_SIZE, ROUNDED_CORNER, x, y, width, height, r, color);
    }

    /** Rounded rectangle where the top and bottom halves have different colours. */
    public static void roundRectGradient(DrawContext context, float x, float y, float width, float height,
                                         float radius, int top, int bottom) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r < 0.5f) {
            gradient(context, x, y, width, height, top, bottom);
            return;
        }
        if (height <= r * 2.2f) {
            roundRect(context, x, y, width, height, r, ColorUtil.lerp(top, bottom, 0.5f));
            return;
        }

        ensureInit();
        gradient(context, x + r, y + r, width - r * 2, height - r * 2, top, bottom);
        rect(context, x + r, y, width - r * 2, r, top);
        rect(context, x + r, y + height - r, width - r * 2, r, bottom);

        // Left/right straight edges: the corners above only cover the rounded ends.
        int edgeTop = ColorUtil.lerp(top, bottom, r / height);
        int edgeBottom = ColorUtil.lerp(top, bottom, (height - r) / height);
        if (height - r * 2 > 0) {
            gradient(context, x, y + r, r, height - r * 2, edgeTop, edgeBottom);
            gradient(context, x + width - r, y + r, r, height - r * 2, edgeTop, edgeBottom);
        }

        int topCorner = ColorUtil.lerp(top, bottom, .15f);
        int bottomCorner = ColorUtil.lerp(top, bottom, .85f);
        cornerQuad(context, x, y, r, true, true, topCorner);
        cornerQuad(context, x + width - r, y, r, true, false, topCorner);
        cornerQuad(context, x, y + height - r, r, false, true, bottomCorner);
        cornerQuad(context, x + width - r, y + height - r, r, false, false, bottomCorner);
    }

    /** Filled rounded rectangle with a 1px (or thicker) border. */
    public static void panel(DrawContext context, float x, float y, float width, float height,
                             float radius, int fill, int border, float borderWidth) {
        if (borderWidth > 0) {
            roundRect(context, x, y, width, height, radius, border);
            roundRect(context, x + borderWidth, y + borderWidth,
                    width - borderWidth * 2, height - borderWidth * 2,
                    Math.max(0.5f, radius - borderWidth), fill);
        } else {
            roundRect(context, x, y, width, height, radius, fill);
        }
    }

    public static void shadow(DrawContext context, float x, float y, float width, float height, float spread, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        ensureInit();
        texture(context, TEX_SOFT, SOFT_SIZE, x - spread, y - spread, width + spread * 2, height + spread * 2,
                0, 0, SOFT_SIZE, SOFT_SIZE, color);
    }

    public static void circle(DrawContext context, float centerX, float centerY, float diameter, int color) {
        if (diameter <= 0) {
            return;
        }
        ensureInit();
        texture(context, TEX_CIRCLE, CIRCLE_SIZE,
                centerX - diameter / 2f, centerY - diameter / 2f, diameter, diameter,
                0, 0, CIRCLE_SIZE, CIRCLE_SIZE, color);
    }

    /** Smooth neon ring, used by the TargetESP scan mode. */
    public static void ring(DrawContext context, float centerX, float centerY, float radius, int color) {
        if (radius <= 0) {
            return;
        }
        ensureInit();
        texture(context, TEX_RING, RING_SIZE,
                centerX - radius, centerY - radius, radius * 2f, radius * 2f,
                0, 0, RING_SIZE, RING_SIZE, color);
    }

    /** Soft halo around a ring. */
    public static void ringGlow(DrawContext context, float centerX, float centerY, float radius, int color) {
        if (radius <= 0) {
            return;
        }
        ensureInit();
        texture(context, TEX_RING_GLOW, RING_SIZE,
                centerX - radius, centerY - radius, radius * 2f, radius * 2f,
                0, 0, RING_SIZE, RING_SIZE, color);
    }

    /** Dotted ring, used by TargetESP. */
    public static void dottedCircle(DrawContext context, float centerX, float centerY,
                                    float radius, int dots, float dotSize, int color) {
        if (radius <= 0 || dots <= 0) {
            return;
        }
        for (int i = 0; i < dots; i++) {
            double angle = Math.PI * 2.0 * i / dots;
            circle(context, centerX + (float) Math.cos(angle) * radius,
                    centerY + (float) Math.sin(angle) * radius, dotSize, color);
        }
    }

    /** Dotted line, used for the square TargetESP outline. */
    public static void dottedLine(DrawContext context, float x1, float y1, float x2, float y2,
                                  float step, float dotSize, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 0.01f) {
            return;
        }
        int dots = Math.max(1, Math.round(length / Math.max(1f, step)));
        for (int i = 0; i <= dots; i++) {
            float t = i / (float) dots;
            circle(context, x1 + dx * t, y1 + dy * t, dotSize, color);
        }
    }

    private static void cornerQuad(DrawContext context, float x, float y, float size,
                                   boolean top, boolean left, int color) {
        float u = left ? 0 : ROUNDED_CORNER + 1;
        float v = top ? 0 : ROUNDED_CORNER + 1;
        texture(context, TEX_ROUNDED, ROUNDED_SIZE, x, y, size, size,
                u, v, ROUNDED_CORNER, ROUNDED_CORNER, color);
    }

    // ---------------------------------------------------------------- textures

    private static void nineSlice(DrawContext context, Identifier texture, int textureSize, int corner,
                                  float x, float y, float width, float height, float radius, int color) {
        float r = radius;
        int srcCorner = corner;
        int srcEdge = 1;

        // corners
        texture(context, texture, textureSize, x, y, r, r, 0, 0, srcCorner, srcCorner, color);
        texture(context, texture, textureSize, x + width - r, y, r, r, srcCorner + srcEdge, 0, srcCorner, srcCorner, color);
        texture(context, texture, textureSize, x, y + height - r, r, r, 0, srcCorner + srcEdge, srcCorner, srcCorner, color);
        texture(context, texture, textureSize, x + width - r, y + height - r, r, r,
                srcCorner + srcEdge, srcCorner + srcEdge, srcCorner, srcCorner, color);

        // edges
        float innerW = width - r * 2;
        float innerH = height - r * 2;
        if (innerW > 0) {
            texture(context, texture, textureSize, x + r, y, innerW, r, srcCorner, 0, srcEdge, srcCorner, color);
            texture(context, texture, textureSize, x + r, y + height - r, innerW, r,
                    srcCorner, srcCorner + srcEdge, srcEdge, srcCorner, color);
            texture(context, texture, textureSize, x + r, y + r, innerW, innerH,
                    srcCorner, srcCorner, srcEdge, srcEdge, color);
        }
        if (innerH > 0) {
            texture(context, texture, textureSize, x, y + r, r, innerH, 0, srcCorner, srcCorner, srcEdge, color);
            texture(context, texture, textureSize, x + width - r, y + r, r, innerH,
                    srcCorner + srcEdge, srcCorner, srcCorner, srcEdge, color);
        }
    }

    private static void texture(DrawContext context, Identifier texture, int textureSize,
                                float x, float y, float width, float height,
                                float u, float v, float uWidth, float vHeight, int color) {
        if (width <= 0 || height <= 0 || uWidth <= 0 || vHeight <= 0) {
            return;
        }
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                Math.round(x), Math.round(y), u, v,
                Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                Math.round(uWidth), Math.round(vHeight), textureSize, textureSize, color);
    }

    // -------------------------------------------------------------------- text

    /** All client UI text is rendered with the desktop-style {@link SystemFont}. */
    public static void text(DrawContext context, String text, float x, float y, int color, boolean shadow) {
        SystemFont.BODY.draw(context, text, x, y, color);
    }

    public static void text(DrawContext context, net.minecraft.text.Text text, float x, float y, int color, boolean shadow) {
        SystemFont.BODY.draw(context, text.getString(), x, y, color);
    }

    public static void centeredText(DrawContext context, String text, float centerX, float y, int color, boolean shadow) {
        text(context, text, centerX - textWidth(text) / 2f, y, color, shadow);
    }

    public static int textWidth(String text) {
        return (int) Math.ceil(SystemFont.BODY.width(text));
    }

    public static int textHeight() {
        return (int) Math.ceil(SystemFont.BODY.height());
    }

    // ------------------------------------------------------------- small text
    // The compact HUD font: used by the watermark chips, the module list rows
    // and the potion rows, so a dense HUD still reads like a desktop overlay.

    public static void smallText(DrawContext context, String text, float x, float y, int color) {
        SystemFont.SMALL.draw(context, text, x, y, color);
    }

    public static int smallWidth(String text) {
        return (int) Math.ceil(SystemFont.SMALL.width(text));
    }

    public static int smallHeight() {
        return (int) Math.ceil(SystemFont.SMALL.height());
    }

    /** Small text horizontally centred on {@code centerX}. */
    public static void smallCentered(DrawContext context, String text, float centerX, float y, int color) {
        smallText(context, text, centerX - SystemFont.SMALL.width(text) / 2f, y, color);
    }

    /** Small text right-aligned to {@code rightX}. */
    public static void smallRight(DrawContext context, String text, float rightX, float y, int color) {
        smallText(context, text, rightX - SystemFont.SMALL.width(text), y, color);
    }

    /** Small text with a left-to-right gradient across its glyphs. */
    public static void smallGradient(DrawContext context, String text, float x, float y, int from, int to) {
        if (text.isEmpty()) {
            return;
        }
        float total = Math.max(1f, SystemFont.SMALL.width(text));
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            int color = ColorUtil.lerp(from, to, (cursor - x) / total);
            SystemFont.SMALL.draw(context, character, cursor, y, color);
            cursor += SystemFont.SMALL.width(character);
        }
    }

    /** Left-to-right gradient text, glyph by glyph with the system font. */
    public static void gradientText(DrawContext context, String text, float x, float y,
                                    int from, int to, boolean shadow) {
        if (text.isEmpty()) {
            return;
        }
        float width = Math.max(1f, SystemFont.BODY.width(text));
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            int color = ColorUtil.lerp(from, to, (cursor - x) / width);
            SystemFont.BODY.draw(context, character, cursor, y, color);
            cursor += SystemFont.BODY.width(character);
        }
    }

    /** Same gradient, but in the large title font used by the watermark. */
    public static void titleGradient(DrawContext context, String text, float x, float y,
                                     int from, int to) {
        if (text.isEmpty()) {
            return;
        }
        float width = Math.max(1f, SystemFont.TITLE.width(text));
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            int color = ColorUtil.lerp(from, to, (cursor - x) / width);
            SystemFont.TITLE.draw(context, character, cursor, y, color);
            cursor += SystemFont.TITLE.width(character);
        }
    }

    /**
     * Draws a region of a texture at an explicit destination size. Used by the
     * system-font glyph atlas and for player skin heads.
     */
    public static void image(DrawContext context, Identifier texture, float x, float y,
                             float width, float height, float u, float v, int textureSize, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                Math.round(x), Math.round(y), u, v,
                Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                textureSize, textureSize, color);
    }

    /** Draws a sub-region of a texture (e.g. an 8x8 skin face) at an arbitrary size. */
    public static void imageRegion(DrawContext context, Identifier texture, float x, float y,
                                   float width, float height, float u, float v,
                                   int regionWidth, int regionHeight,
                                   int textureWidth, int textureHeight, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                Math.round(x), Math.round(y), u, v,
                Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                regionWidth, regionHeight, textureWidth, textureHeight, color);
    }

    // ----------------------------------------------------------------- scissor

    public static void scissor(DrawContext context, float x, float y, float width, float height) {
        context.enableScissor(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height));
    }

    public static void unScissor(DrawContext context) {
        context.disableScissor();
    }

    // ------------------------------------------------------------- image build

    private static NativeImage createRoundedImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, ROUNDED_SIZE, ROUNDED_SIZE, false);
        float half = ROUNDED_SIZE / 2f;
        float radius = ROUNDED_CORNER;
        for (int py = 0; py < ROUNDED_SIZE; py++) {
            for (int px = 0; px < ROUNDED_SIZE; px++) {
                float distance = roundedRectDistance(px + 0.5f - half, py + 0.5f - half, half - 0.5f, radius);
                int alpha = Math.round(Math.max(0f, Math.min(1f, 0.5f - distance)) * 255f);
                image.setColorArgb(px, py, (alpha << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    private static NativeImage createSoftImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, SOFT_SIZE, SOFT_SIZE, false);
        float half = SOFT_SIZE / 2f;
        float radius = 10f;
        float blur = 12f;
        for (int py = 0; py < SOFT_SIZE; py++) {
            for (int px = 0; px < SOFT_SIZE; px++) {
                float distance = roundedRectDistance(px + 0.5f - half, py + 0.5f - half, half - blur, radius);
                float alpha = Math.max(0f, Math.min(1f, 0.5f - distance / blur));
                // slight curve for a softer falloff
                alpha = alpha * alpha * (3f - 2f * alpha);
                image.setColorArgb(px, py, (Math.round(alpha * 255f) << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    private static NativeImage createCircleImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, CIRCLE_SIZE, CIRCLE_SIZE, false);
        float center = CIRCLE_SIZE / 2f;
        float radius = center - 0.5f;
        for (int py = 0; py < CIRCLE_SIZE; py++) {
            for (int px = 0; px < CIRCLE_SIZE; px++) {
                float dx = px + 0.5f - center;
                float dy = py + 0.5f - center;
                float distance = (float) Math.sqrt(dx * dx + dy * dy) - radius;
                int alpha = Math.round(Math.max(0f, Math.min(1f, 0.5f - distance)) * 255f);
                image.setColorArgb(px, py, (alpha << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    /** Thin, softly anti-aliased ring. */
    private static NativeImage createRingImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, RING_SIZE, RING_SIZE, false);
        float center = RING_SIZE / 2f;
        float radius = center - 4.5f;
        float band = 2.2f;
        for (int py = 0; py < RING_SIZE; py++) {
            for (int px = 0; px < RING_SIZE; px++) {
                float dx = px + 0.5f - center;
                float dy = py + 0.5f - center;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float alpha = Math.max(0f, Math.min(1f, 1f - Math.abs(distance - radius) / band));
                alpha = alpha * alpha * (3f - 2f * alpha);
                image.setColorArgb(px, py, (Math.round(alpha * 255f) << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    /** Wide, soft halo used behind the scan ring. */
    private static NativeImage createRingGlowImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, RING_SIZE, RING_SIZE, false);
        float center = RING_SIZE / 2f;
        float radius = center - 9f;
        float falloff = 9f;
        for (int py = 0; py < RING_SIZE; py++) {
            for (int px = 0; px < RING_SIZE; px++) {
                float dx = px + 0.5f - center;
                float dy = py + 0.5f - center;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float normalized = (distance - radius) / falloff;
                float alpha = (float) Math.exp(-normalized * normalized) * 0.75f;
                image.setColorArgb(px, py, (Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f) << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    /** Signed distance to a rounded rectangle centred at the origin. */
    private static float roundedRectDistance(float x, float y, float halfExtent, float radius) {
        float qx = Math.abs(x) - (halfExtent - radius);
        float qy = Math.abs(y) - (halfExtent - radius);
        float outside = (float) Math.sqrt(Math.max(qx, 0) * Math.max(qx, 0) + Math.max(qy, 0) * Math.max(qy, 0));
        float inside = Math.min(Math.max(qx, qy), 0);
        return outside + inside - radius;
    }

    /** Texture with linear filtering so generated shapes stay smooth. */
    private static final class SmoothTexture extends NativeImageBackedTexture {

        SmoothTexture(String name, NativeImage image) {
            super(() -> name, image);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                    .get(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        }
    }
}
