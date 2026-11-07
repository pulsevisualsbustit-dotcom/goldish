package dev.velora.render;

import dev.velora.Velora;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-centre notification pills.
 *
 * <p>It is a normal {@link HudWidget}, so it follows the active theme, appears
 * in the HUD editor as a draggable preview and remembers the position the user
 * picks. Pulses arrive from {@link Hud#toast(String, boolean)}, which is called
 * whenever a module is toggled, and are kept in a short stack: a bind that
 * toggles several modules at once therefore shows every single one instead of
 * the last one overwriting the rest.</p>
 */
public class NotificationsWidget extends HudWidget {

    /** How long a notification stays on screen. */
    private static final long DURATION_MS = 1800L;
    private static final float HEIGHT = 17f;
    private static final float GAP = 3f;
    /** Older pulses are dropped once the stack would grow past this. */
    private static final int MAX_VISIBLE = 6;

    private record Entry(String text, Boolean state, long shownAt) {
    }

    /** Every recent toggle, newest last; shared because toasts are static. */
    private static final List<Entry> entries = new ArrayList<>();

    public NotificationsWidget() {
        super("Notifications", "Notifications");
        // Top centre, just below the top edge.
        anchorTopCenter(9f);
    }

    /** Shows the "module toggled" notification. */
    static void notify(String moduleName, boolean enabled) {
        long now = System.currentTimeMillis();
        // Toggling the same module again within one pulse updates that pulse
        // instead of leaving a stale duplicate in the stack.
        entries.removeIf(entry -> entry.text.equals(moduleName)
                && entry.state != null && entry.state == enabled && now - entry.shownAt < 400L);
        entries.add(new Entry(moduleName, enabled, now));
        while (entries.size() > MAX_VISIBLE) {
            entries.remove(0);
        }
    }

    /** Shows a neutral message without the enabled/disabled suffix. */
    static void message(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.removeIf(entry -> entry.text.equals(text) && entry.state == null
                && now - entry.shownAt < 400L);
        entries.add(new Entry(text, null, now));
        while (entries.size() > MAX_VISIBLE) {
            entries.remove(0);
        }
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        boolean editor = hudEditorOpen();
        long now = System.currentTimeMillis();
        entries.removeIf(entry -> now - entry.shownAt > DURATION_MS);

        List<Entry> visible = new ArrayList<>(entries);
        if (visible.isEmpty()) {
            if (!editor) {
                setSize(0f, 0f);
                return;
            }
            // Preview while arranging the HUD: same shape as a real toast, so
            // the user can see what is being moved.
            visible.add(new Entry("Notifications", true, now));
        }

        float width = 0f;
        for (Entry entry : visible) {
            width = Math.max(width, Render2D.smallWidth(label(entry)) + 34f);
        }
        float height = visible.size() * HEIGHT + (visible.size() - 1) * GAP;
        setSize(width, height);
        place();

        float baseX = screenX();
        float baseY = screenY();
        var config = Velora.get().getConfigManager();

        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            String label = label(entry);
            int alpha = editor ? 255 : fade(entry, now);
            if (alpha <= 0) {
                continue;
            }

            // Rows are centred inside the stack, so short names stay centred
            // under long ones instead of hugging the left edge.
            float rowWidth = Render2D.smallWidth(label) + 34f;
            float x = baseX + (width - rowWidth) / 2f;
            float y = baseY + i * (HEIGHT + GAP);
            int dot = entry.state == null || entry.state
                    ? HudStyle.accent() : HudStyle.RED;

            Render2D.shadow(context, x, y + 1f, rowWidth, HEIGHT, 4f,
                    ColorUtil.withAlpha(0x000000, Math.round(120 * alpha / 255f)));
            Render2D.roundRect(context, x, y, rowWidth, HEIGHT, HEIGHT / 2f,
                    ColorUtil.withAlpha(HudStyle.CARD_BLACK, Math.round(235 * alpha / 255f)));
            Render2D.roundRect(context, x + 0.5f, y + 0.5f, rowWidth - 1f, HEIGHT - 1f,
                    HEIGHT / 2f - 0.5f,
                    ColorUtil.withAlpha(HudStyle.accent(), Math.round(110 * alpha / 255f)));
            Render2D.circle(context, x + 12f, y + HEIGHT / 2f, 5f,
                    ColorUtil.withAlpha(dot, alpha));
            Render2D.smallText(context, label, x + 21f,
                    y + (HEIGHT - Render2D.smallHeight()) / 2f + 0.5f,
                    ColorUtil.withAlpha(config.getTextColor(), alpha));
        }
    }

    private static String label(Entry entry) {
        if (entry.state == null) {
            return entry.text;
        }
        return entry.text + "  " + (entry.state ? "enabled" : "disabled");
    }

    /** Fade in over 140 ms, hold, fade out over the last 300 ms. */
    private static int fade(Entry entry, long now) {
        long age = now - entry.shownAt;
        float factor = age < 140L ? age / 140f
                : age > DURATION_MS - 300L ? (DURATION_MS - age) / 300f : 1f;
        return Math.round(255f * Math.max(0f, Math.min(1f, factor)));
    }
}
