package dev.velora.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Desktop-style text renderer.
 *
 * <p>Minecraft's bitmap font is charming, but it looks out of place inside the
 * modern TargetHUD. This class rasterises a real system font (Segoe UI on
 * Windows, with sane fallbacks) into an anti-aliased glyph atlas once, uploads
 * it as a GUI texture and draws text as tinted quads. The result matches the
 * rest of the OS and stays crisp because the atlas is rendered at 4x the
 * displayed size.</p>
 */
public final class SystemFont {

    /* Three sizes: the target name, the supporting text and the compact HUD rows. */
    public static final SystemFont TITLE = new SystemFont("title", 40, 4.0f, Font.BOLD);
    public static final SystemFont BODY = new SystemFont("body", 34, 4.0f, Font.BOLD);
    public static final SystemFont SMALL = new SystemFont("small", 28, 4.0f, Font.BOLD);
    /** Regular weight: labels, values and chips, which the reference sets lighter than headings. */
    public static final SystemFont REGULAR = new SystemFont("regular", 34, 4.0f, Font.PLAIN);

    private static final int ATLAS_SIZE = 1024;
    private static final String FAMILY = pickFamily();

    private final String name;
    private final Identifier identifier;
    private final int rasterSize;
    private final float scale;
    private final int style;

    private final Map<Character, Glyph> glyphs = new HashMap<>();
    private int lineHeight;
    private float displayedLineHeight;

    private BufferedImage atlas;
    private NativeImageBackedTexture texture;

    private SystemFont(String name, int rasterSize, float scale, int style) {
        this.name = name;
        this.identifier = Identifier.of("velora", "font/" + name);
        this.rasterSize = rasterSize;
        this.scale = scale;
        this.style = style;
    }

    /** Text width in scaled GUI pixels. */
    public float width(String text) {
        build();
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            Glyph glyph = glyphs.get(text.charAt(i));
            if (glyph != null) {
                total += glyph.advance;
            }
        }
        return total / scale;
    }

    /** Line height in scaled GUI pixels. */
    public float height() {
        build();
        return displayedLineHeight;
    }

    /** Draws a single line with the given ARGB tint. */
    public void draw(DrawContext context, String text, float x, float y, int color) {
        draw(context, text, x, y, color, 1f);
    }

    /** Draws a single line with an additional scale factor. */
    public void draw(DrawContext context, String text, float x, float y, int color, float sizeScale) {
        build();
        ensureTexture();
        float factor = Math.max(0.05f, sizeScale);

        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            int code = text.charAt(i);
            Glyph glyph = glyphs.get((char) code);
            if (glyph == null) {
                continue;
            }
            if (code != ' ') {
                // glyph.y is the atlas row; the destination baseline is always the
                // requested line top. Only the region (u/v) uses the atlas position.
                Render2D.imageRegion(context, identifier, cursor, y,
                        glyph.width / scale * factor, glyph.height / scale * factor,
                        glyph.x, glyph.y, glyph.width, glyph.height,
                        ATLAS_SIZE, ATLAS_SIZE, color);
            }
            cursor += glyph.advance / scale * factor;
        }
    }

    /** Text width in scaled GUI pixels. */
    public float width(String text, float sizeScale) {
        return width(text) * Math.max(0.05f, sizeScale);
    }

    /** Line height in scaled GUI pixels. */
    public float height(float sizeScale) {
        return height() * Math.max(0.05f, sizeScale);
    }

    /** Draws a single line horizontally centred on {@code centerX}. */
    public void drawCentered(DrawContext context, String text, float centerX, float y, int color) {
        draw(context, text, centerX - width(text) / 2f, y, color);
    }

    /** Draws a single line right-aligned to {@code rightX}. */
    public void drawRight(DrawContext context, String text, float rightX, float y, int color) {
        draw(context, text, rightX - width(text), y, color);
    }

    // ------------------------------------------------------------------ build

    private void build() {
        if (atlas != null) {
            return;
        }

        Font font = new Font(FAMILY, style, rasterSize);
        BufferedImage image = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);

        FontMetrics metrics = graphics.getFontMetrics();
        int ascent = metrics.getAscent();
        lineHeight = metrics.getHeight() + 2;
        displayedLineHeight = lineHeight / scale;

        int[] cursor = {2, 2};
        for (int code = 32; code <= 126; code++) {
            bake(graphics, metrics, ascent, (char) code, cursor);
        }
        for (int code = 0x400; code <= 0x45F; code++) {
            bake(graphics, metrics, ascent, (char) code, cursor);
        }
        for (char extra : new char[]{'±', '°', '∞', '–', '—', '…', '×', '✓', '•', '·'}) {
            bake(graphics, metrics, ascent, extra, cursor);
        }
        graphics.dispose();
        atlas = image;
    }

    private void bake(Graphics2D graphics, FontMetrics metrics, int ascent, char character, int[] cursor) {
        int advance = Math.max(1, metrics.charWidth(character));
        int cellWidth = advance + 8;
        if (cursor[0] + cellWidth + 2 > ATLAS_SIZE || cursor[1] + lineHeight > ATLAS_SIZE) {
            cursor[0] = 2;
            cursor[1] += lineHeight;
        }
        if (cursor[1] + lineHeight > ATLAS_SIZE) {
            // Should never happen with a 1024x1024 atlas, but a glyph missing at
            // the very edge is better than an index crash.
            return;
        }
        // 4px padding on both sides: bold glyphs can overhang the advance width by
        // a couple of pixels, and the extra ink must stay inside this glyph's cell
        // so neighbouring glyph regions never contain foreign strokes.
        graphics.drawString(String.valueOf(character), cursor[0] + 4, cursor[1] + ascent);
        glyphs.put(character, new Glyph(cursor[0], cursor[1], cellWidth, lineHeight, advance));
        cursor[0] += cellWidth;
    }

    private void ensureTexture() {
        MinecraftClient client = MinecraftClient.getInstance();
        var manager = client.getTextureManager();
        if (texture != null && manager.getTexture(identifier) == texture) {
            return;
        }

        NativeImage image = new NativeImage(NativeImage.Format.RGBA, ATLAS_SIZE, ATLAS_SIZE, false);
        for (int y = 0; y < ATLAS_SIZE; y++) {
            for (int x = 0; x < ATLAS_SIZE; x++) {
                int argb = atlas.getRGB(x, y);
                if ((argb >>> 24) != 0) {
                    image.setColorArgb(x, y, argb);
                }
            }
        }

        texture = new LinearTexture("velora_font_" + name, image);
        manager.registerTexture(identifier, texture);
    }

    private static String pickFamily() {
        try {
            Set<String> available = Set.of(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            String[] preferred = {
                    "Segoe UI", "Segoe UI Variable", "SF Pro Text", "SF Pro Display",
                    "Helvetica Neue", "Inter", "Arial", "Noto Sans", "DejaVu Sans",
                    "Liberation Sans", "Roboto"
            };
            for (String candidate : preferred) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Headless / restricted environments: fall through to the logical font.
        }
        return Font.SANS_SERIF;
    }

    private record Glyph(int x, int y, int width, int height, int advance) {
    }

    /** Texture with linear filtering so the glyph atlas stays smooth when scaled. */
    private static final class LinearTexture extends NativeImageBackedTexture {

        LinearTexture(String name, NativeImage image) {
            super(() -> name, image);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }
}
