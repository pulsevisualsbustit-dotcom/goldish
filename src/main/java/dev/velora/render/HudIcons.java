package dev.velora.render;

import net.minecraft.client.gui.DrawContext;

/**
 * Tiny vector icons used by the HUD panel headers and rows.
 *
 * <p>Everything is drawn from the same rounded rect / circle / line primitives
 * as the rest of the HUD, so the icons stay crisp at any GUI scale and always
 * follow the active theme colour without needing a texture atlas.</p>
 */
public final class HudIcons {

    private HudIcons() {
    }

    /** Clock: dotted ring with two hands. */
    public static void clock(DrawContext context, float x, float y, float size, int color) {
        float centerX = x + size / 2f;
        float centerY = y + size / 2f;
        float radius = size / 2f - 0.8f;
        int dots = 12;
        for (int i = 0; i < dots; i++) {
            double angle = Math.PI * 2 * i / dots;
            Render2D.circle(context, centerX + (float) Math.cos(angle) * radius,
                    centerY + (float) Math.sin(angle) * radius, 1.15f, color);
        }
        Render2D.line(context, centerX, centerY, centerX, centerY - radius * 0.5f, 1f, color);
        Render2D.line(context, centerX, centerY, centerX + radius * 0.42f, centerY, 1f, color);
    }

    /** Person: head and shoulders. */
    public static void person(DrawContext context, float x, float y, float size, int color) {
        float centerX = x + size / 2f;
        Render2D.circle(context, centerX, y + size * 0.28f, size * 0.32f, color);
        Render2D.roundRect(context, centerX - size * 0.36f, y + size * 0.56f,
                size * 0.72f, size * 0.40f, size * 0.20f, color);
    }

    /** Potion bottle with a cork. */
    public static void potion(DrawContext context, float x, float y, float size, int color) {
        Render2D.roundRect(context, x + size * 0.26f, y + size * 0.36f,
                size * 0.48f, size * 0.54f, size * 0.16f, color);
        Render2D.rect(context, x + size * 0.41f, y + size * 0.20f,
                size * 0.18f, size * 0.20f, color);
        Render2D.roundRect(context, x + size * 0.34f, y + size * 0.08f,
                size * 0.32f, size * 0.14f, size * 0.05f, color);
    }

    /** Keyboard: solid body with punched keys, legible even at 8px. */
    public static void keyboard(DrawContext context, float x, float y, float size, int color) {
        float height = size * 0.68f;
        float top = y + (size - height) / 2f;
        Render2D.roundRect(context, x, top, size, height, size * 0.18f, color);
        int hole = HudStyle.ICON_BOX;
        float keyY = top + height * 0.26f;
        for (int i = 0; i < 3; i++) {
            Render2D.roundRect(context, x + size * (0.16f + i * 0.25f), keyY,
                    size * 0.14f, height * 0.20f, size * 0.05f, hole);
        }
        Render2D.roundRect(context, x + size * 0.30f, top + height * 0.60f,
                size * 0.40f, height * 0.18f, size * 0.05f, hole);
    }

    /** Stacked list: three lines, the first one shorter. */
    public static void list(DrawContext context, float x, float y, float size, int color) {
        float line = Math.max(1f, size * 0.14f);
        Render2D.roundRect(context, x + 0.5f, y + size * 0.18f, size * 0.62f, line, line / 2f, color);
        Render2D.roundRect(context, x + 0.5f, y + size * 0.44f, size * 0.86f, line, line / 2f, color);
        Render2D.roundRect(context, x + 0.5f, y + size * 0.70f, size * 0.74f, line, line / 2f, color);
    }

    /** Music note: head, stem and flag. */
    public static void note(DrawContext context, float x, float y, float size, int color) {
        Render2D.circle(context, x + size * 0.34f, y + size * 0.74f, size * 0.26f, color);
        Render2D.rect(context, x + size * 0.52f, y + size * 0.16f, Math.max(1f, size * 0.09f),
                size * 0.62f, color);
        Render2D.rect(context, x + size * 0.52f, y + size * 0.16f, size * 0.24f,
                Math.max(1f, size * 0.09f), color);
    }

    /** Heart: two lobes and a pointed bottom. */
    public static void heart(DrawContext context, float x, float y, float size, int color) {
        float unit = size / 10f;
        Render2D.circle(context, x + unit * 3f, y + unit * 3.2f, unit * 3.2f, color);
        Render2D.circle(context, x + unit * 7f, y + unit * 3.2f, unit * 3.2f, color);
        Render2D.quad(context,
                x + unit * 0.9f, y + unit * 3.6f,
                x + unit * 9.1f, y + unit * 3.6f,
                x + unit * 5f, y + unit * 9.4f,
                x + unit * 5f, y + unit * 9.4f,
                color);
    }

    /** Location pin: round head with a tapered point. */
    public static void location(DrawContext context, float x, float y, float size, int color) {
        float unit = size / 10f;
        Render2D.circle(context, x + unit * 5f, y + unit * 4f, unit * 7.4f, color);
        Render2D.quad(context,
                x + unit * 1.4f, y + unit * 5.6f,
                x + unit * 5f, y + unit * 9.8f,
                x + unit * 5f, y + unit * 9.8f,
                x + unit * 8.6f, y + unit * 5.6f,
                color);
        Render2D.circle(context, x + unit * 5f, y + unit * 4f, unit * 3f, 0x66000000);
    }

    /** Speed: three motion lines with a chevron head. */
    public static void speed(DrawContext context, float x, float y, float size, int color) {
        float unit = size / 10f;
        Render2D.line(context, x + unit * 0.5f, y + unit * 2.5f,
                x + unit * 6.5f, y + unit * 2.5f, Math.max(1f, unit * 1.2f), color);
        Render2D.line(context, x + unit * 2f, y + unit * 5f,
                x + unit * 7.5f, y + unit * 5f, Math.max(1f, unit * 1.2f), color);
        Render2D.line(context, x + unit * 3.5f, y + unit * 7.5f,
                x + unit * 8.5f, y + unit * 7.5f, Math.max(1f, unit * 1.2f), color);
    }

    /** Per-module glyph for ClickGUI cards; falls back to a simple node. */
    public static void module(DrawContext context, String module, float x, float y,
                              float size, int color) {
        String name = module == null ? "" : module;
        float u = size / 24f;
        float w = Math.max(1.4f, size * 0.092f);
        float cx = x + 12f * u;
        float cy = y + 12f * u;
        switch (name) {
            case "AimAssist", "TriggerBot" -> {
                // Crosshair: ring, centre dot and four ticks.
                circleStroke(context, cx, cy, 6.1f * u, w, color);
                Render2D.circle(context, cx, cy, 2.4f * u, color);
                stroke(context, cx, y + 2.4f * u, cx, y + 6.2f * u, w, color);
                stroke(context, cx, y + 17.8f * u, cx, y + 21.6f * u, w, color);
                stroke(context, x + 2.4f * u, cy, x + 6.2f * u, cy, w, color);
                stroke(context, x + 17.8f * u, cy, x + 21.6f * u, cy, w, color);
            }
            case "AutoSwap" -> {
                // Two opposing arrows: swap.
                stroke(context, x + 3.2f * u, y + 8.2f * u, x + 20.8f * u, y + 8.2f * u, w, color);
                polyline(context, w, color,
                        x + 17.2f * u, y + 4.8f * u,
                        x + 20.8f * u, y + 8.2f * u,
                        x + 17.2f * u, y + 11.6f * u);
                stroke(context, x + 3.2f * u, y + 15.8f * u, x + 20.8f * u, y + 15.8f * u, w, color);
                polyline(context, w, color,
                        x + 6.8f * u, y + 12.4f * u,
                        x + 3.2f * u, y + 15.8f * u,
                        x + 6.8f * u, y + 19.2f * u);
            }
            case "MaceHelper" -> category(context, "COMBAT", x, y, size, color);
            case "NoFriendDamage" -> {
                // Shield: pointed outline with a centre seam.
                polyline(context, w, color,
                        cx, y + 2.8f * u,
                        x + 20f * u, y + 5.8f * u,
                        x + 20f * u, y + 12f * u,
                        cx, y + 21.2f * u,
                        x + 4f * u, y + 12f * u,
                        x + 4f * u, y + 5.8f * u,
                        cx, y + 2.8f * u);
                stroke(context, cx, y + 7.4f * u, cx, y + 16.2f * u, w * 0.8f, color);
            }
            case "AutoSprint" -> category(context, "MOVEMENT", x, y, size, color);
            case "FullBright" -> {
                // Sun: ring plus eight rays.
                circleStroke(context, cx, cy, 4.6f * u, w, color);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.toRadians(i * 45.0);
                    float cos = (float) Math.cos(angle);
                    float sin = (float) Math.sin(angle);
                    stroke(context, cx + cos * 7.4f * u, cy + sin * 7.4f * u,
                            cx + cos * 10.2f * u, cy + sin * 10.2f * u, w * 0.9f, color);
                }
            }
            case "Hud" -> category(context, "RENDER", x, y, size, color);
            case "Removals" -> {
                // Eye outline with pupil.
                polyline(context, w, color,
                        x + 2.6f * u, y + 12f * u,
                        x + 7.6f * u, y + 6f * u,
                        x + 16.4f * u, y + 6f * u,
                        x + 21.4f * u, y + 12f * u,
                        x + 16.4f * u, y + 18f * u,
                        x + 7.6f * u, y + 18f * u,
                        x + 2.6f * u, y + 12f * u);
                Render2D.circle(context, cx, cy, 4.2f * u, color);
            }
            case "TargetEsp" -> {
                // Four corner brackets and a centre dot.
                stroke(context, x + 3.4f * u, y + 3.4f * u, x + 10.8f * u, y + 3.4f * u, w, color);
                stroke(context, x + 3.4f * u, y + 3.4f * u, x + 3.4f * u, y + 10.8f * u, w, color);
                stroke(context, x + 20.6f * u, y + 3.4f * u, x + 13.2f * u, y + 3.4f * u, w, color);
                stroke(context, x + 20.6f * u, y + 3.4f * u, x + 20.6f * u, y + 10.8f * u, w, color);
                stroke(context, x + 3.4f * u, y + 20.6f * u, x + 10.8f * u, y + 20.6f * u, w, color);
                stroke(context, x + 3.4f * u, y + 20.6f * u, x + 3.4f * u, y + 13.2f * u, w, color);
                stroke(context, x + 20.6f * u, y + 20.6f * u, x + 13.2f * u, y + 20.6f * u, w, color);
                stroke(context, x + 20.6f * u, y + 20.6f * u, x + 20.6f * u, y + 13.2f * u, w, color);
                Render2D.circle(context, cx, cy, 3.4f * u, color);
            }
            case "NameTags" -> {
                // Tag: rounded label with a punched hole.
                polyline(context, w, color,
                        x + 3.2f * u, y + 6.6f * u,
                        x + 14.2f * u, y + 6.6f * u,
                        x + 21f * u, y + 12f * u,
                        x + 14.2f * u, y + 17.4f * u,
                        x + 3.2f * u, y + 17.4f * u,
                        x + 3.2f * u, y + 6.6f * u);
                Render2D.circle(context, x + 7.2f * u, y + 12f * u, 2.2f * u, color);
            }
            case "EntityEsp" -> category(context, "ALTS", x, y, size, color);
            case "ClientSounds" -> {
                // Speaker outline with two sound waves.
                polyline(context, w, color,
                        x + 3.6f * u, y + 9.4f * u,
                        x + 7.8f * u, y + 9.4f * u,
                        x + 12.6f * u, y + 5.2f * u,
                        x + 12.6f * u, y + 18.8f * u,
                        x + 7.8f * u, y + 14.6f * u,
                        x + 3.6f * u, y + 14.6f * u,
                        x + 3.6f * u, y + 9.4f * u);
                arc(context, x + 13.2f * u, cy, 4.2f * u, -55f, 55f, w * 0.85f, color);
                arc(context, x + 13.2f * u, cy, 7.2f * u, -48f, 48f, w * 0.85f, color);
            }
            case "RichPresence" -> {
                // Game controller: body, d-pad and two buttons.
                Render2D.roundRect(context, x + 2.6f * u, y + 7.4f * u,
                        18.8f * u, 9.6f * u, 4.6f * u, color);
                Render2D.rect(context, x + 6.6f * u, y + 11.4f * u, 4.2f * u, 1.6f * u,
                        0x99000000);
                Render2D.rect(context, x + 7.9f * u, y + 10.1f * u, 1.6f * u, 4.2f * u,
                        0x99000000);
                Render2D.circle(context, x + 16.4f * u, y + 11.2f * u, 1.9f * u, 0x99000000);
                Render2D.circle(context, x + 18.8f * u, y + 13.6f * u, 1.9f * u, 0x99000000);
            }
            default -> {
                Render2D.roundRect(context, x + 4f * u, y + 4f * u,
                        16f * u, 16f * u, 4f * u, color);
                Render2D.circle(context, cx, cy, 4f * u, 0x66000000);
            }
        }
    }

    /** Category glyph used by the ClickGUI navigation and module cards. */
    public static void category(DrawContext context, String category, float x, float y,
                                float size, int color) {
        String name = category == null ? "" : category.toUpperCase(java.util.Locale.ROOT);
        // Every glyph is authored on a 24x24 grid and scaled to the requested box,
        // so the line weight stays identical across navigation, cards and rows.
        float u = size / 24f;
        float w = Math.max(1.4f, size * 0.092f);
        int soft = withAlpha(color, 0.55f);
        switch (name) {
            case "COMBAT" -> {
                // Sword: blade, cross guard, grip and a round pommel.
                stroke(context, x + 7.4f * u, y + 16.6f * u,
                        x + 18.9f * u, y + 5.1f * u, w, color);
                stroke(context, x + 4.6f * u, y + 14.2f * u,
                        x + 9.8f * u, y + 19.4f * u, w, color);
                stroke(context, x + 7.4f * u, y + 16.6f * u,
                        x + 4.6f * u, y + 19.4f * u, w * 0.95f, color);
                Render2D.circle(context, x + 3.9f * u, y + 20.1f * u, w * 1.45f, color);
            }
            case "MOVEMENT" -> {
                // Two separated chevrons: fast forward.
                polyline(context, w, color,
                        x + 4.2f * u, y + 5.6f * u,
                        x + 11.4f * u, y + 12f * u,
                        x + 4.2f * u, y + 18.4f * u);
                polyline(context, w, color,
                        x + 13.0f * u, y + 5.6f * u,
                        x + 20.2f * u, y + 12f * u,
                        x + 13.0f * u, y + 18.4f * u);
            }
            case "RENDER" -> {
                // Monitor: screen frame, neck and foot.
                float left = 3.6f;
                float right = 20.4f;
                float top = 4.6f;
                float bottom = 14.6f;
                stroke(context, x + left * u, y + top * u, x + right * u, y + top * u, w, color);
                stroke(context, x + left * u, y + top * u, x + left * u, y + bottom * u, w, color);
                stroke(context, x + right * u, y + top * u, x + right * u, y + bottom * u, w, color);
                stroke(context, x + left * u, y + bottom * u, x + right * u, y + bottom * u, w, color);
                stroke(context, x + 12f * u, y + bottom * u, x + 12f * u, y + 18.4f * u, w, color);
                stroke(context, x + 7.6f * u, y + 19.4f * u, x + 16.4f * u, y + 19.4f * u, w, color);
            }
            case "MISC" -> {
                // Four rounded tiles: the generic "everything else" grid.
                float tile = 7.4f;
                float gap = 2.0f;
                float origin = 3.6f;
                float radius = 2.1f * u;
                Render2D.roundRect(context, x + origin * u, y + origin * u,
                        tile * u, tile * u, radius, color);
                Render2D.roundRect(context, x + (origin + tile + gap) * u, y + origin * u,
                        tile * u, tile * u, radius, color);
                Render2D.roundRect(context, x + origin * u, y + (origin + tile + gap) * u,
                        tile * u, tile * u, radius, color);
                Render2D.roundRect(context, x + (origin + tile + gap) * u,
                        y + (origin + tile + gap) * u, tile * u, tile * u, radius, color);
            }
            case "THEMES" -> {
                // Four-point sparkle outline: theme / appearance.
                starOutline(context, x + 11.6f * u, y + 12.4f * u, 9.8f * u, w, color);
            }
            case "CONFIGS" -> {
                // Two sliders: dim track, bright thumb.
                float knob = Math.max(5.2f, size * 0.21f);
                stroke(context, x + 3.4f * u, y + 7.8f * u, x + 20.6f * u, y + 7.8f * u,
                        w * 0.8f, soft);
                stroke(context, x + 3.4f * u, y + 16.2f * u, x + 20.6f * u, y + 16.2f * u,
                        w * 0.8f, soft);
                Render2D.circle(context, x + 9.2f * u, y + 7.8f * u, knob, color);
                Render2D.circle(context, x + 15.4f * u, y + 16.2f * u, knob, color);
            }
            case "ALTS" -> {
                // Person: outlined head and shoulders, matching the stroke style.
                circleStroke(context, x + 12f * u, y + 7.4f * u, 3.9f * u, w, color);
                arc(context, x + 12f * u, y + 20.8f * u, 7.4f * u, 196f, 344f, w, color);
            }
            default -> Render2D.circle(context, x + size * 0.5f, y + size * 0.5f,
                    size * 0.22f, color);
        }
    }

    /** Capsule stroke with round caps, the building block of every icon above. */
    private static void stroke(DrawContext context, float x1, float y1, float x2, float y2,
                               float width, int color) {
        Render2D.line(context, x1, y1, x2, y2, width, color);
        Render2D.circle(context, x1, y1, width, color);
        Render2D.circle(context, x2, y2, width, color);
    }

    private static void polyline(DrawContext context, float width, int color, float... points) {
        for (int i = 0; i + 3 < points.length; i += 2) {
            stroke(context, points[i], points[i + 1], points[i + 2], points[i + 3], width, color);
        }
    }

    private static void circleStroke(DrawContext context, float centerX, float centerY,
                                     float radius, float width, int color) {
        arc(context, centerX, centerY, radius, 0f, 360f, width, color);
    }

    /** Outline arc; angles are in degrees, {@code 0} pointing right and growing clockwise. */
    private static void arc(DrawContext context, float centerX, float centerY, float radius,
                            float startDegrees, float endDegrees, float width, int color) {
        int steps = Math.max(4, Math.round(Math.abs(endDegrees - startDegrees) / 12f));
        float[] points = new float[(steps + 1) * 2];
        for (int i = 0; i <= steps; i++) {
            double angle = Math.toRadians(startDegrees
                    + (endDegrees - startDegrees) * i / (double) steps);
            points[i * 2] = centerX + (float) Math.cos(angle) * radius;
            points[i * 2 + 1] = centerY + (float) Math.sin(angle) * radius;
        }
        polyline(context, width, color, points);
    }

    /** Scales the alpha channel of {@code color}; used for the dimmer parts of an icon. */
    private static int withAlpha(int color, float factor) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * factor);
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Four-point sparkle drawn as a closed outline through eight alternating
     * outer and inner points. Built from capsule strokes so it matches the line
     * weight of the other category glyphs.
     */
    private static void starOutline(DrawContext context, float centerX, float centerY,
                                    float radius, float width, int color) {
        float inner = radius * 0.36f;
        float[] points = new float[16];
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(-90.0 + i * 45.0);
            float r = (i % 2 == 0) ? radius : inner;
            points[i * 2] = centerX + (float) Math.cos(angle) * r;
            points[i * 2 + 1] = centerY + (float) Math.sin(angle) * r;
        }
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) % 8;
            stroke(context, points[i * 2], points[i * 2 + 1],
                    points[next * 2], points[next * 2 + 1], width, color);
        }
    }
}
