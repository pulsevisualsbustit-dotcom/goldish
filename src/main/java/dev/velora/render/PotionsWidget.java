package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Potions: the player's active status effects.
 *
 * <p>Follows the reference "Effects" panel: a card with a potion badge in the
 * header and one 11px row per effect. Each row has a bottle tinted with the
 * effect colour, a hairline divider, the name (red for harmful effects, with a
 * red amplifier) and the remaining time in the accent colour, followed by six
 * dots that drain as the effect runs out. An effect about to expire blinks.</p>
 */
public class PotionsWidget extends HudWidget {

    private static final float MIN_WIDTH = 80f;
    private static final float PAD = 6f;
    private static final float ICON_X = 5f;
    private static final float DIVIDER_X = 14f;
    private static final float NAME_X = 18f;
    private static final float ICON = 8f;
    private static final int DOTS = 6;
    private static final float DOT = 3f;
    private static final float DOT_GAP = 2f;
    private static final float DOTS_WIDTH = DOTS * DOT + (DOTS - 1) * DOT_GAP;

    private final BooleanSetting showDuration = register(new BooleanSetting("Potions Duration", true));
    private final BooleanSetting background = register(new BooleanSetting("Potions Background", true));
    private final ModeSetting sort = register(new ModeSetting("Potions Sort", "Duration", "Duration", "Name"));

    /** Longest duration seen per effect, so the dots have a reference. */
    private final Map<Integer, Integer> maxDurations = new HashMap<>();

    public PotionsWidget() {
        super("Potions", "Potions");
        topRow();
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        var client = Velora.mc();
        if (client.player == null && !hudEditorOpen()) {
            setSize(0, 0);
            return;
        }

        List<Row> rows = new ArrayList<>();
        if (client.player == null || client.player.getStatusEffects().isEmpty()) {
            if (!hudEditorOpen()) {
                setSize(0, 0);
                return;
            }
            rows.add(new Row(1, "Speed", "II", "0:11", 0.62f, 0xFF4FD6FF, false, false));
            rows.add(new Row(2, "Strength", "I", "0:01", 0.10f, 0xFFE0503A, true, true));
            rows.add(new Row(3, "Regeneration", "II", "0:05", 0.28f, 0xFFE06BFF, false, true));
            rows.add(new Row(4, "Fire Resistance", "", "6:16", 1f, 0xFFFFC24A, false, false));
        } else {
            List<StatusEffectInstance> effects = new ArrayList<>(client.player.getStatusEffects());
            if (sort.is("Name")) {
                effects.sort(Comparator.comparing(instance ->
                        instance.getEffectType().value().getName().getString()));
            } else {
                effects.sort(Comparator.comparingInt(PotionsWidget::sortKey));
            }

            Set<Integer> alive = new HashSet<>();
            for (StatusEffectInstance effect : effects) {
                int id = idOf(effect);
                alive.add(id);
                int duration = effect.getDuration();
                if (!effect.isInfinite() && duration > 0) {
                    maxDurations.merge(id, duration, Math::max);
                }
                rows.add(rowOf(effect, id));
            }
            maxDurations.keySet().removeIf(id -> !alive.contains(id));
        }

        float width = MIN_WIDTH;
        for (Row row : rows) {
            float left = NAME_X + Render2D.smallWidth(row.name())
                    + (row.level().isEmpty() ? 0f : 2f + Render2D.smallWidth(row.level()));
            float right = 4f + Render2D.smallWidth(row.duration()) + 3f + DOTS_WIDTH + PAD;
            width = Math.max(width, left + right);
        }
        float height = HudStyle.HEADER + rows.size() * HudStyle.ROW_HEIGHT + 4f;

        setSize(width, height);
        anchorTopLeft(6f, 8f);
        place();

        float x = screenX();
        float y = screenY();
        int accent = HudStyle.accent();

        if (background.get() || hudEditorOpen()) {
            HudStyle.card(context, x, y, width, height, 4f);
        }

        HudStyle.header(context, x, y, width, "Potions");
        HudIcons.potion(context, x + width - 16f, y + 5.5f, 8f,
                ColorUtil.withAlpha(accent, 235));

        for (int i = 0; i < rows.size(); i++) {
            drawRow(context, rows.get(i), x, y + HudStyle.HEADER + i * HudStyle.ROW_HEIGHT,
                    width, accent);
        }

        setSize(width, height);
    }

    private void drawRow(DrawContext context, Row row, float x, float rowY, float width, int accent) {
        float centerY = rowY + HudStyle.ROW_HEIGHT / 2f;
        float textY = centerY - Render2D.smallHeight() / 2f;
        float alpha = row.blinking() ? blink() : 1f;
        int textAlpha = Math.round(255 * alpha);

        // Bottle tinted with the effect colour.
        HudIcons.potion(context, x + ICON_X, centerY - ICON / 2f, ICON,
                ColorUtil.withAlpha(row.color(), textAlpha));
        HudStyle.rowDivider(context, x + DIVIDER_X, centerY);

        float nameX = x + NAME_X;
        int nameColor = row.harmful() ? 0xFFFF7373 : HudStyle.text();
        Render2D.smallText(context, row.name(), nameX, textY,
                ColorUtil.withAlpha(nameColor, textAlpha));
        if (!row.level().isEmpty()) {
            Render2D.smallText(context, " " + row.level(),
                    nameX + Render2D.smallWidth(row.name()), textY,
                    ColorUtil.withAlpha(0xFFFF6B6B, textAlpha));
        }

        float right = x + width - PAD;
        float dotsX = right - DOTS_WIDTH;
        if (showDuration.get()) {
            Render2D.smallRight(context, row.duration(), dotsX - 3f, textY,
                    ColorUtil.withAlpha(accent, textAlpha));

            int filled = Math.max(0, Math.min(DOTS, Math.round(row.progress() * DOTS)));
            float dotY = centerY - DOT / 2f;
            for (int dot = 0; dot < DOTS; dot++) {
                int color = dot < filled
                        ? ColorUtil.withAlpha(accent, textAlpha)
                        : ColorUtil.withAlpha(0xFFFFFF, Math.round(42 * alpha));
                Render2D.circle(context, dotsX + dot * (DOT + DOT_GAP) + DOT / 2f, dotY + DOT / 2f,
                        DOT, color);
            }
        }
    }

    /** 0.35 .. 1.0 pulse used by effects that are about to run out. */
    private static float blink() {
        return 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 120.0));
    }

    private Row rowOf(StatusEffectInstance effect, int id) {
        var type = effect.getEffectType();
        int color = effectColor(effect);
        int duration = effect.getDuration();
        boolean harmful = type.value().getCategory() == StatusEffectCategory.HARMFUL;
        int max = effect.isInfinite() ? 0 : Math.max(1, maxDurations.getOrDefault(id, duration));
        float progress = effect.isInfinite() || duration <= 0
                ? 1f : Math.max(0f, Math.min(1f, duration / (float) max));
        boolean blinking = !effect.isInfinite() && duration > 0 && duration <= 120;
        return new Row(id, type.value().getName().getString(), level(effect.getAmplifier()),
                formatDuration(effect), progress, color, harmful, blinking);
    }

    private static int idOf(StatusEffectInstance effect) {
        return effect.getEffectType().getKey()
                .map(key -> key.getValue().hashCode())
                .orElseGet(() -> effect.getEffectType().value().getName().getString().hashCode());
    }

    /** Infinite effects first, then the longest remaining duration. */
    private static int sortKey(StatusEffectInstance effect) {
        return effect.isInfinite() ? Integer.MAX_VALUE : effect.getDuration();
    }

    private static int effectColor(StatusEffectInstance instance) {
        int color = instance.getEffectType().value().getColor();
        if ((color & 0xFFFFFF) == 0) {
            return instance.getEffectType().value().isBeneficial() ? HudStyle.GREEN : HudStyle.RED;
        }
        return 0xFF000000 | color;
    }

    private static String formatDuration(StatusEffectInstance effect) {
        if (effect.isInfinite() || effect.getDuration() < 0) {
            return "**:**";
        }
        int seconds = effect.getDuration() / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Roman numeral appended to the effect name, e.g. "Speed II". */
    private static String level(int amplifier) {
        if (amplifier <= 0) {
            return "";
        }
        String[] numerals = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return amplifier < numerals.length ? numerals[amplifier] : String.valueOf(amplifier + 1);
    }

    private record Row(int id, String name, String level, String duration, float progress,
                       int color, boolean harmful, boolean blinking) {
    }
}
