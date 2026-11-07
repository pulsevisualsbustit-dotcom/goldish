package dev.velora.render;

import dev.velora.setting.BooleanSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * Media Player: displays currently playing music from system media sessions.
 *
 * <p>Follows the reference HUD: one compact 116x40 card with the album cover on
 * the left, the scrolling track title and artist next to it, small transport
 * controls, the elapsed / total time and a thin accent progress bar along the
 * bottom. Tracks media playback from Spotify, Yandex Music, VK Music and other
 * system media players on Windows.</p>
 */
public class MediaPlayerWidget extends HudWidget {

    private static final float WIDTH = 116f;
    private static final float HEIGHT = 40f;
    private static final float COVER = 32f;
    private static final float COVER_GAP = 8f;
    private static final float PAD = 4f;

    private final BooleanSetting showProgress = register(new BooleanSetting("Player Progress", true));
    private final BooleanSetting background = register(new BooleanSetting("Player Background", true));

    private final SystemMediaTracker mediaTracker = new SystemMediaTracker();

    public MediaPlayerWidget() {
        super("MediaPlayer", "Media Player");
        topRow("right");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        MediaInfo media = mediaTracker.getCurrentMedia();

        if (!media.isPlaying && !hudEditorOpen()) {
            setSize(0, 0);
            return;
        }

        // Preview in editor
        if (hudEditorOpen() && !media.isPlaying) {
            media = new MediaInfo("Gold Rush Radio", "Velora", "0:42", "3:24", 0.21, true);
        }

        float width = WIDTH;
        setSize(width, HEIGHT);
        anchorTopRight(5f, 8f);
        place();

        float x = screenX();
        float y = screenY();
        int accent = HudStyle.accent();
        int accentSecondary = HudStyle.accentSecondary();

        if (background.get() || hudEditorOpen()) {
            HudStyle.card(context, x, y, width, HEIGHT, 4f);
        }

        float textX = x + PAD + COVER + COVER_GAP;
        float textWidth = x + width - PAD - textX;

        // Album cover (or an accent placeholder when the system exposes no art).
        drawCover(context, x + PAD, y + PAD, COVER,
                media.coverColor != 0 ? media.coverColor : accent);

        Render2D.text(context, trimText(media.title, textWidth, SystemFont.BODY), textX,
                y + 4.5f, HudStyle.text(), false);
        Render2D.smallText(context, trimText(media.artist, textWidth, SystemFont.SMALL), textX,
                y + 13.5f, HudStyle.textMuted());

        // Transport controls, centred in the text column.
        float controlsCenterX = textX + textWidth / 2f;
        drawControls(context, controlsCenterX, y + 23.5f, media.isPlaying, accent);

        if (showProgress.get()) {
            Render2D.smallText(context, media.currentTime, textX, y + 27.5f, HudStyle.textFaint());
            Render2D.smallRight(context, media.totalTime, x + width - PAD, y + 27.5f,
                    HudStyle.textFaint());

            float barY = y + HEIGHT - 5f;
            float barWidth = width - PAD * 2f - COVER - COVER_GAP;
            HudStyle.bar(context, textX, barY, barWidth, 2f, (float) media.progress,
                    accent, accentSecondary);
        }

        setSize(width, HEIGHT);
    }

    private void drawCover(DrawContext context, float x, float y, float size, int color) {
        Render2D.roundRectGradient(context, x, y, size, size, 3f,
                ColorUtil.lerp(color, 0xFFFFFFFF, 0.18f), ColorUtil.scale(color, 0.55f));
        Render2D.roundRect(context, x + 0.5f, y + 0.5f, size - 1f, size - 1f, 2.5f, 0x18FFFFFF);
        HudIcons.note(context, x + size * 0.30f, y + size * 0.24f, size * 0.44f,
                ColorUtil.withAlpha(0xFFFFFF, 220));
    }

    private void drawControls(DrawContext context, float centerX, float centerY, boolean playing,
                              int accent) {
        float gap = 17f;
        int color = ColorUtil.withAlpha(HudStyle.text(), 225);
        drawPrevious(context, centerX - gap, centerY, color);

        // Play / pause.
        if (playing) {
            Render2D.rect(context, centerX - 2.4f, centerY - 3f, 1.8f, 6f, accent);
            Render2D.rect(context, centerX + 0.6f, centerY - 3f, 1.8f, 6f, accent);
        } else {
            Render2D.quad(context, centerX - 2f, centerY - 3.2f,
                    centerX - 2f, centerY + 3.2f,
                    centerX + 3.2f, centerY,
                    centerX + 3.2f, centerY,
                    accent);
        }
        drawNext(context, centerX + gap, centerY, color);
    }

    private void drawPrevious(DrawContext context, float centerX, float centerY, int color) {
        Render2D.rect(context, centerX - 4.5f, centerY - 3.2f, 1.4f, 6.4f, color);
        Render2D.quad(context, centerX + 2.5f, centerY - 3.2f,
                centerX + 2.5f, centerY + 3.2f,
                centerX - 2.5f, centerY,
                centerX - 2.5f, centerY,
                color);
    }

    private void drawNext(DrawContext context, float centerX, float centerY, int color) {
        Render2D.rect(context, centerX + 3.1f, centerY - 3.2f, 1.4f, 6.4f, color);
        Render2D.quad(context, centerX - 2.5f, centerY - 3.2f,
                centerX - 2.5f, centerY + 3.2f,
                centerX + 2.5f, centerY,
                centerX + 2.5f, centerY,
                color);
    }

    private String trimText(String text, float maxWidth, SystemFont font) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "\u2026";
        for (int i = text.length() - 1; i > 0; i--) {
            String trimmed = text.substring(0, i) + ellipsis;
            if (font.width(trimmed) <= maxWidth) {
                return trimmed;
            }
        }
        return ellipsis;
    }

    /**
     * Holds current media information.
     */
    public static class MediaInfo {
        public final String title;
        public final String artist;
        public final String currentTime;
        public final String totalTime;
        public final double progress;
        public final boolean isPlaying;
        public final int coverColor;

        public MediaInfo(String title, String artist, String currentTime, String totalTime,
                         double progress, boolean isPlaying) {
            this(title, artist, currentTime, totalTime, progress, isPlaying, 0);
        }

        public MediaInfo(String title, String artist, String currentTime, String totalTime,
                         double progress, boolean isPlaying, int coverColor) {
            this.title = title != null ? title : "Unknown";
            this.artist = artist != null ? artist : "";
            this.currentTime = currentTime != null ? currentTime : "0:00";
            this.totalTime = totalTime != null ? totalTime : "0:00";
            this.progress = Math.max(0, Math.min(1, progress));
            this.isPlaying = isPlaying;
            this.coverColor = coverColor;
        }
    }
}
