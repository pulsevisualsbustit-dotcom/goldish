package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * Watermark: one compact pill with the brand and the live client stats.
 *
 * <p>Follows the reference HUD: a 16px tall dark card, the "Velora" wordmark in
 * the theme gradient and the username, ping and FPS separated by bullets. The
 * pill grows with its contents and hides nothing on small screens.</p>
 */
public class WatermarkWidget extends HudWidget {

    private static final float HEIGHT = 16f;
    private static final float PAD = 5f;
    private static final String SEPARATOR = " \u2022 ";

    private final BooleanSetting showStats = register(new BooleanSetting("Watermark Stats", true));
    private final BooleanSetting showVersion = register(new BooleanSetting("Watermark Version", false));

    public WatermarkWidget() {
        super("Watermark", "Watermark");
        topRow();
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        var client = Velora.mc();

        String brand = "Velora";
        String version = " " + Velora.VERSION;
        String userPart = SEPARATOR + username();
        String pingPart = SEPARATOR + ping() + "ms";
        String fpsPart = SEPARATOR + client.getCurrentFps() + "fps";

        float width = PAD * 2 + SystemFont.BODY.width(brand);
        if (showVersion.get()) {
            width += SystemFont.BODY.width(version);
        }
        width += SystemFont.BODY.width(userPart);
        if (showStats.get()) {
            width += SystemFont.BODY.width(pingPart) + SystemFont.BODY.width(fpsPart);
        }

        setSize(width, HEIGHT);
        anchorTopLeft(6f, 8f);
        place();

        float x = screenX();
        float y = screenY();
        float textY = y + (HEIGHT - SystemFont.BODY.height()) / 2f - 0.5f;

        HudStyle.card(context, x, y, width, HEIGHT, 3f);

        float cursor = x + PAD;
        Render2D.gradientText(context, brand, cursor, textY,
                HudStyle.accent(), HudStyle.accentSecondary(), false);
        cursor += SystemFont.BODY.width(brand);

        if (showVersion.get()) {
            Render2D.text(context, version, cursor, textY,
                    ColorUtil.withAlpha(HudStyle.textMuted(), 220), false);
            cursor += SystemFont.BODY.width(version);
        }

        Render2D.text(context, userPart, cursor, textY, HudStyle.text(), false);
        cursor += SystemFont.BODY.width(userPart);

        if (showStats.get()) {
            Render2D.text(context, pingPart, cursor, textY, HudStyle.text(), false);
            cursor += SystemFont.BODY.width(pingPart);
            Render2D.text(context, fpsPart, cursor, textY, HudStyle.text(), false);
        }

        setSize(width, HEIGHT);
    }

    private static String username() {
        String name = Velora.mc().getSession().getUsername();
        return name == null || name.isBlank() ? "Player" : name;
    }

    private static int ping() {
        var client = Velora.mc();
        if (client.getNetworkHandler() == null || client.player == null) {
            return 0;
        }
        var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return entry == null ? 0 : Math.max(entry.getLatency(), 0);
    }
}
