package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import dev.velora.util.ServerStats;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Player Info: coordinates, horizontal speed and the real server tick rate.
 *
 * <p>Follows the reference HUD: a single 16px tall pill with small icon + value
 * segments separated by hairline dividers. A pin marks the coordinates, motion
 * lines the BPS and a clock the TPS; any segment can be switched off.</p>
 */
public class PlayerInfoWidget extends HudWidget {

    private static final float HEIGHT = 16f;
    private static final float PAD = 5f;
    private static final float ICON = 8f;
    private static final float INNER_GAP = 3f;
    private static final float BETWEEN_GAP = 10f;

    private final BooleanSetting showXyz = register(new BooleanSetting("PlayerInfo XYZ", true));
    private final BooleanSetting showBps = register(new BooleanSetting("PlayerInfo BPS", true));
    private final BooleanSetting showTps = register(new BooleanSetting("PlayerInfo TPS", true));
    private final BooleanSetting background = register(new BooleanSetting("PlayerInfo Background", true));

    private Vec3d lastPos = Vec3d.ZERO;
    private double bps = 0.0;
    private long lastTime = System.currentTimeMillis();

    public PlayerInfoWidget() {
        super("PlayerInfo", "Player info");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        PlayerEntity player = Velora.mc().player;
        boolean editor = hudEditorOpen();
        if (player == null && !editor) {
            setSize(0, 0);
            return;
        }

        // Horizontal speed over real time, so it is already blocks per second.
        if (player != null) {
            long now = System.currentTimeMillis();
            long dt = now - lastTime;
            if (dt >= 100) {
                Vec3d currentPos = player.getEyePos();
                double dx = currentPos.x - lastPos.x;
                double dz = currentPos.z - lastPos.z;
                bps = Math.sqrt(dx * dx + dz * dz) / (dt / 1000.0);
                lastPos = currentPos;
                lastTime = now;
            }
        }

        String coords = player != null
                ? String.format(Locale.ROOT, "%d %d %d",
                player.getBlockX(), player.getBlockY(), player.getBlockZ())
                : "0 0 0";
        String speed = String.format(Locale.ROOT, "%.1f", bps);
        String tps = String.format(Locale.ROOT, "%.1f", ServerStats.tps());

        List<Segment> segments = new ArrayList<>();
        if (showXyz.get() || editor) {
            segments.add(new Segment("location", coords));
        }
        if (showBps.get() || editor) {
            segments.add(new Segment("speed", speed));
        }
        if (showTps.get() || editor) {
            segments.add(new Segment("clock", tps));
        }
        if (segments.isEmpty()) {
            setSize(0, 0);
            return;
        }

        float width = PAD * 2f;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            width += ICON + INNER_GAP + SystemFont.BODY.width(segment.value());
            if (i > 0) {
                width += BETWEEN_GAP;
            }
        }

        setSize(width, HEIGHT);
        anchorBottomLeft(6f, 6f);
        place();

        float x = screenX();
        float y = screenY();
        int accent = HudStyle.accent();

        if (background.get() || editor) {
            HudStyle.card(context, x, y, width, HEIGHT, 3f);
        }

        float textY = y + (HEIGHT - SystemFont.BODY.height()) / 2f - 0.5f;
        float iconY = y + (HEIGHT - ICON) / 2f;
        float cursor = x + PAD;

        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (i > 0) {
                float dividerX = cursor - BETWEEN_GAP / 2f - 0.25f;
                Render2D.rect(context, dividerX, y + 4f, 0.5f, HEIGHT - 8f, HudStyle.ROW_LINE);
            }

            switch (segment.icon()) {
                case "speed" -> HudIcons.speed(context, cursor, iconY, ICON,
                        ColorUtil.withAlpha(accent, 235));
                case "clock" -> HudIcons.clock(context, cursor, iconY, ICON,
                        ColorUtil.withAlpha(accent, 235));
                default -> HudIcons.location(context, cursor, iconY, ICON,
                        ColorUtil.withAlpha(accent, 235));
            }

            float valueX = cursor + ICON + INNER_GAP;
            Render2D.text(context, segment.value(), valueX, textY, HudStyle.text(), false);
            cursor = valueX + SystemFont.BODY.width(segment.value());
            if (i < segments.size() - 1) {
                cursor += BETWEEN_GAP;
            }
        }

        setSize(width, HEIGHT);
    }

    private record Segment(String icon, String value) {
    }
}
