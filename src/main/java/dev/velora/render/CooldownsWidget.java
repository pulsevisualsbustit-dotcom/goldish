package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cooldowns: cooldown timers for the items the player uses in a fight.
 *
 * <p>Follows the reference "CoolDowns" panel: a card with a clock badge in the
 * header and one 11px row per item. The row shows the item icon, a hairline
 * divider, the item name and the remaining time in the accent colour, which
 * blinks during the last ten seconds. The game only exposes the remaining
 * fraction, so the full duration is estimated from how fast the bar moves and
 * kept for the rest of that cooldown.</p>
 */
public class CooldownsWidget extends HudWidget {

    private static final float MIN_WIDTH = 80f;
    private static final float PAD = 6f;
    private static final float ICON = 8f;
    /** Ignore estimates above two minutes (a bad sample, not a real cooldown). */
    private static final long MAX_ESTIMATED_MS = 120_000L;
    /** Remaining seconds below which the readout blinks. */
    private static final long BLINK_MS = 10_000L;

    private final BooleanSetting showPearls = register(new BooleanSetting("Cooldowns Pearls", true));
    private final BooleanSetting showGapples = register(new BooleanSetting("Cooldowns Gapples", true));
    private final BooleanSetting showShields = register(new BooleanSetting("Cooldowns Shields", true));
    private final BooleanSetting background = register(new BooleanSetting("Cooldowns Background", true));

    /** Item to the estimated start and full duration of its current cooldown. */
    private final Map<Item, Estimate> estimates = new HashMap<>();

    public CooldownsWidget() {
        super("Cooldowns", "Cooldowns");
        topRow("right");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        PlayerEntity player = Velora.mc().player;
        if (player == null && !hudEditorOpen()) {
            setSize(0, 0);
            return;
        }

        long now = System.currentTimeMillis();
        List<Row> cooldowns = new ArrayList<>();

        if (player != null) {
            if (showPearls.get()) {
                add(cooldowns, row(player, Items.ENDER_PEARL, "Ender Pearl", now));
            }
            if (showGapples.get()) {
                // Both apples share the same slot in the HUD; the longer one wins.
                Row normal = row(player, Items.GOLDEN_APPLE, "Golden Apple", now);
                Row enchanted = row(player, Items.ENCHANTED_GOLDEN_APPLE, "Enchanted Apple", now);
                if (normal != null && (enchanted == null || normal.remaining() > enchanted.remaining())) {
                    add(cooldowns, normal);
                } else {
                    add(cooldowns, enchanted);
                }
            }
            if (showShields.get()) {
                add(cooldowns, row(player, Items.SHIELD, "Shield", now));
            }
        }

        // Preview in editor, so the widget can be moved with nothing on cooldown.
        if (hudEditorOpen() && cooldowns.isEmpty()) {
            cooldowns.add(new Row("Ender Pearl", Items.ENDER_PEARL, 0.70f, 9_000L));
            cooldowns.add(new Row("Golden Apple", Items.GOLDEN_APPLE, 0.40f, 21_000L));
        }

        if (cooldowns.isEmpty()) {
            setSize(0, 0);
            return;
        }

        float width = MIN_WIDTH;
        for (Row row : cooldowns) {
            width = Math.max(width, PAD + 11f + Render2D.smallWidth(row.name())
                    + 10f + Render2D.smallWidth(row.time()) + PAD);
        }
        float height = HudStyle.HEADER + cooldowns.size() * HudStyle.ROW_HEIGHT + 4f;

        setSize(width, height);
        anchorTopRight(5f, 8f);
        place();

        float x = screenX();
        float y = screenY();
        int accent = HudStyle.accent();

        if (background.get() || hudEditorOpen()) {
            HudStyle.card(context, x, y, width, height, 4f);
        }

        HudStyle.header(context, x, y, width, "Cooldowns");
        HudIcons.clock(context, x + width - 16f, y + 5.5f, 8f,
                ColorUtil.withAlpha(accent, 235));

        for (int i = 0; i < cooldowns.size(); i++) {
            drawRow(context, cooldowns.get(i), x, y + HudStyle.HEADER + i * HudStyle.ROW_HEIGHT,
                    width, accent);
        }

        setSize(width, height);
    }

    private void drawRow(DrawContext context, Row entry, float x, float rowY, float width, int accent) {
        float centerY = rowY + HudStyle.ROW_HEIGHT / 2f;
        float textY = centerY - Render2D.smallHeight() / 2f;

        HudStyle.item(context, new ItemStack(entry.item()), x + 4f, centerY - ICON / 2f, ICON);
        HudStyle.rowDivider(context, x + 15f, centerY);

        Render2D.smallText(context, entry.name(), x + 18f, textY, HudStyle.text());

        int timeColor = accent;
        float alpha = 1f;
        if (entry.remainingMillis() >= 0L && entry.remainingMillis() <= BLINK_MS) {
            alpha = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 120.0));
        }
        Render2D.smallRight(context, entry.time(), x + width - PAD, textY,
                ColorUtil.withAlpha(timeColor, Math.round(255 * alpha)));
    }

    private static void add(List<Row> rows, Row row) {
        if (row != null) {
            rows.add(row);
        }
    }

    /**
     * Builds one row, or {@code null} when the item is not on cooldown. The
     * full duration is inferred from the elapsed time and the fraction done.
     */
    private Row row(PlayerEntity player, Item item, String name, long now) {
        float remaining = getItemCooldown(player, item);
        if (remaining <= 0f) {
            estimates.remove(item);
            return null;
        }

        Estimate estimate = estimates.computeIfAbsent(item, key -> new Estimate(now));
        float done = 1f - remaining;
        if (done > 0.05f) {
            long elapsed = now - estimate.start;
            if (elapsed > 100L) {
                long total = Math.round(elapsed / done);
                if (total > estimate.total && total < MAX_ESTIMATED_MS) {
                    // Keep the largest sane value: the first sample of a new
                    // cooldown is usually taken a little late and undershoots.
                    estimate.total = total;
                }
            }
        }

        long remainingMillis = estimate.total > 0 ? Math.round(remaining * estimate.total) : -1L;
        return new Row(name, item, remaining, remainingMillis);
    }

    private float getItemCooldown(PlayerEntity player, Item item) {
        var manager = player.getItemCooldownManager();
        ItemStack stack = new ItemStack(item);
        if (manager.isCoolingDown(stack)) {
            // Remaining fraction: 1 right after use, 0 when ready.
            return manager.getCooldownProgress(stack, 0f);
        }
        return 0f;
    }

    /** {@code m:ss}, rounding up so a running cooldown never shows 0:00. */
    private static String formatTime(long millis) {
        long seconds = (millis + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private record Row(String name, Item item, float remaining, long remainingMillis) {
        String time() {
            return remainingMillis < 0L ? "..." : formatTime(remainingMillis);
        }
    }

    private static final class Estimate {
        private final long start;
        private long total;

        private Estimate(long start) {
            this.start = start;
        }
    }
}
