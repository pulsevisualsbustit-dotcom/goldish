package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import dev.velora.util.PlayerUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Target cooldowns: an estimate of what the player under the crosshair cannot
 * use right now.
 *
 * <p>Same compact card as {@link CooldownsWidget}, fed by watching the target:
 * an active use animation (a gapple, a chorus fruit) or a pearl the target just
 * threw starts a timer with the vanilla duration. A target is remembered for
 * three seconds after it leaves the crosshair, so the card does not flicker
 * while the aim slides off.</p>
 */
public class CooldownsTargetWidget extends HudWidget {

    private static final float MIN_WIDTH = 80f;
    private static final float PAD = 6f;
    private static final float ICON = 8f;
    /** How long the last target is kept after leaving the crosshair. */
    private static final long TARGET_MEMORY_MS = 3000L;
    /** Radius around the target checked for freshly thrown pearls. */
    private static final double PEARL_RANGE = 8.0;
    private static final long BLINK_MS = 5_000L;

    /** Vanilla cooldowns in seconds; an item missing here is not tracked. */
    private static final Map<Item, Integer> DURATIONS = Map.of(
            Items.ENDER_PEARL, 16,
            Items.GOLDEN_APPLE, 30,
            Items.ENCHANTED_GOLDEN_APPLE, 60,
            Items.CHORUS_FRUIT, 12);

    private final BooleanSetting showPearls = register(new BooleanSetting("TargetCooldowns Pearls", true));
    private final BooleanSetting showApples = register(new BooleanSetting("TargetCooldowns Apples", true));
    private final BooleanSetting showChorus = register(new BooleanSetting("TargetCooldowns Chorus", true));
    private final BooleanSetting background = register(new BooleanSetting("TargetCooldowns Background", true));

    private LivingEntity target;
    private long lastSeen;
    /** Item to {start millis, duration millis}. */
    private final Map<Item, long[]> timers = new HashMap<>();
    /** Pearls already counted, so one throw starts one timer. */
    private final Set<Integer> trackedPearls = new HashSet<>();

    public CooldownsTargetWidget() {
        super("TargetCooldowns", "Target cooldowns");
        topRow("right");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        long now = System.currentTimeMillis();
        observe(now);

        List<Row> rows = rows(now);
        boolean editor = hudEditorOpen();
        if (rows.isEmpty() && editor) {
            rows = previewRows();
        }
        if (rows.isEmpty()) {
            setSize(0, 0);
            return;
        }

        String title = "Target Cooldowns";
        float width = Math.max(MIN_WIDTH, PAD + Render2D.textWidth(title) + 26f);
        for (Row row : rows) {
            width = Math.max(width, PAD + 11f + Render2D.smallWidth(row.name())
                    + 10f + Render2D.smallWidth(row.time()) + PAD);
        }
        float height = HudStyle.HEADER + rows.size() * HudStyle.ROW_HEIGHT + 4f;

        setSize(width, height);
        anchorTopRight(5f, 8f);
        place();

        float x = screenX();
        float y = screenY();
        int accent = HudStyle.accent();

        if (background.get() || editor) {
            HudStyle.card(context, x, y, width, height, 4f);
        }

        HudStyle.header(context, x, y, width, title);
        HudIcons.clock(context, x + width - 16f, y + 5.5f, 8f,
                ColorUtil.withAlpha(accent, 235));

        for (int i = 0; i < rows.size(); i++) {
            drawRow(context, rows.get(i), x, y + HudStyle.HEADER + i * HudStyle.ROW_HEIGHT,
                    width, accent);
        }

        setSize(width, height);
    }

    /** Watches the crosshair target and starts timers for items it uses. */
    private void observe(long now) {
        var client = Velora.mc();
        // The entity under the crosshair, the same pick the attack would hit.
        LivingEntity hit = PlayerUtil.getCrosshairTarget(true, true);
        if (hit != null) {
            if (target != hit) {
                timers.clear();
                trackedPearls.clear();
            }
            target = hit;
            lastSeen = now;
        } else if (target != null && now - lastSeen > TARGET_MEMORY_MS) {
            target = null;
            timers.clear();
            trackedPearls.clear();
        }

        if (!(target instanceof PlayerEntity player) || client.world == null) {
            return;
        }

        // A use animation exposes the item another player is eating / drinking.
        if (player.isUsingItem()) {
            Item item = player.getActiveItem().getItem();
            Integer duration = DURATIONS.get(item);
            if (duration != null && enabled(item)) {
                long[] timer = timers.get(item);
                if (timer == null || now > timer[0] + timer[1]) {
                    timers.put(item, new long[]{now, duration * 1000L});
                }
            }
        }

        // A pearl thrown by the target: the projectile remembers its owner.
        if (showPearls.get()) {
            Box around = player.getBoundingBox().expand(PEARL_RANGE);
            for (Entity entity : client.world.getOtherEntities(player, around,
                    candidate -> candidate.getType() == EntityType.ENDER_PEARL)) {
                if (!(entity instanceof ProjectileEntity projectile) || projectile.getOwner() != player) {
                    continue;
                }
                if (trackedPearls.add(entity.getId())) {
                    timers.put(Items.ENDER_PEARL, new long[]{now, DURATIONS.get(Items.ENDER_PEARL) * 1000L});
                }
            }
        }

        // Drop finished timers, and timers for items the user switched off.
        timers.entrySet().removeIf(entry -> now > entry.getValue()[0] + entry.getValue()[1]
                || !enabled(entry.getKey()));
    }

    private List<Row> rows(long now) {
        List<Row> rows = new ArrayList<>();
        for (var entry : timers.entrySet()) {
            long[] timer = entry.getValue();
            long left = timer[0] + timer[1] - now;
            if (left <= 0) {
                continue;
            }
            rows.add(new Row(entry.getKey(), entry.getKey().getName().getString(),
                    1f - left / (float) Math.max(1L, timer[1]), format(left), left));
        }
        // Shortest remaining first: what becomes available next stays on top.
        rows.sort((first, second) -> Long.compare(first.left(), second.left()));
        return rows;
    }

    private static List<Row> previewRows() {
        return List.of(
                new Row(Items.ENDER_PEARL, "Ender Pearl", 0.40f, "0:10", 10_000L),
                new Row(Items.CHORUS_FRUIT, "Chorus Fruit", 0.85f, "0:02", 2_000L));
    }

    private void drawRow(DrawContext context, Row row, float x, float rowY, float width, int accent) {
        float centerY = rowY + HudStyle.ROW_HEIGHT / 2f;
        float textY = centerY - Render2D.smallHeight() / 2f;

        HudStyle.item(context, new ItemStack(row.item()), x + 4f, centerY - ICON / 2f, ICON);
        HudStyle.rowDivider(context, x + 15f, centerY);

        Render2D.smallText(context, row.name(), x + 18f, textY, HudStyle.text());

        float alpha = 1f;
        if (row.left() <= BLINK_MS) {
            alpha = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 120.0));
        }
        Render2D.smallRight(context, row.time(), x + width - PAD, textY,
                ColorUtil.withAlpha(accent, Math.round(255 * alpha)));
    }

    private boolean enabled(Item item) {
        if (item == Items.ENDER_PEARL) {
            return showPearls.get();
        }
        if (item == Items.CHORUS_FRUIT) {
            return showChorus.get();
        }
        return showApples.get();
    }

    /** {@code m:ss}, rounding up so a running timer never shows 0:00. */
    private static String format(long millis) {
        long seconds = (millis + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private record Row(Item item, String name, float progress, String time, long left) {
    }
}
