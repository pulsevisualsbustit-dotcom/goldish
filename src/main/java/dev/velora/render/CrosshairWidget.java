package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.TargetUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;

/**
 * Crosshair: custom crosshair with target information.
 *
 * <p>A dynamic crosshair that shows target information when hovering over
 * an entity. Displays the target's name, health bar, distance and armor.
 * Uses the velora color scheme with smooth animations.</p>
 */
public class CrosshairWidget extends HudWidget {

    private static final float GAP = 2f;
    private static final float LENGTH = 7f;
    private static final float WIDTH = 2f;

    private final ModeSetting style = register(new ModeSetting("Crosshair Style", "Dot", "Dot", "Cross", "Gap"));
    private final NumberSetting size = register(new NumberSetting("Crosshair Size", 14.0, 4.0, 24.0, 1.0));
    private final BooleanSetting showTarget = register(new BooleanSetting("Crosshair Target", true));
    private final BooleanSetting dynamic = register(new BooleanSetting("Crosshair Dynamic", true));

    private float lastTargetHealth = -1f;

    public CrosshairWidget() {
        super("Crosshair", "Crosshair");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        var client = Velora.mc();
        if (client.player == null) {
            setSize(0, 0);
            return;
        }

        // Center of screen
        float screenWidth = client.getWindow().getScaledWidth();
        float screenHeight = client.getWindow().getScaledHeight();
        float centerX = screenWidth / 2f;
        float centerY = screenHeight / 2f;

        int accent = HudStyle.accent();
        int color = ColorUtil.withAlpha(0xFFFFFFFF, 255);

        // Draw crosshair
        float gap = GAP + (dynamic.get() ? (client.options.attackKey.isPressed() ? 3f : 0f) : 0f);
        float len = LENGTH;
        float thickness = WIDTH;

        if (style.is("Dot")) {
            Render2D.circle(context, centerX, centerY, 1.5f, color);
            // Glow effect
            Render2D.circle(context, centerX, centerY, 2f, ColorUtil.withAlpha(accent, 60));
        } else if (style.is("Cross")) {
            // Top
            Render2D.rect(context, centerX - thickness / 2f, centerY - gap - len, thickness, len, color);
            // Bottom
            Render2D.rect(context, centerX - thickness / 2f, centerY + gap, thickness, len, color);
            // Left
            Render2D.rect(context, centerX - gap - len, centerY - thickness / 2f, len, thickness, color);
            // Right
            Render2D.rect(context, centerX + gap, centerY - thickness / 2f, len, thickness, color);
        } else if (style.is("Gap")) {
            // Top
            Render2D.rect(context, centerX - thickness / 2f, centerY - gap - len, thickness, len, color);
            // Bottom
            Render2D.rect(context, centerX - thickness / 2f, centerY + gap, thickness, len, color);
        }

        // Target information
        if (showTarget.get()) {
            LivingEntity target = TargetUtil.findBest(6.0, 180.0, true, false, false);
            if (target == null && hudEditorOpen()) {
                target = client.player;
            }

            if (target != null) {
                drawTargetInfo(context, target, centerX, centerY + 36f, accent);
            }
        }

        // Widget size (for dragging in editor)
        float s = size.getFloat();
        setSize(s * 2, s * 2);
        anchorBottomCenter(screenHeight / 2f - s);
        place();
    }

    private void drawTargetInfo(DrawContext context, LivingEntity target, float centerX, float y, int accent) {
        float health = target.getHealth();
        float maxHealth = target.getMaxHealth();
        float healthPercent = Math.min(1f, health / maxHealth);

        // Smooth health animation
        if (lastTargetHealth < 0f) {
            lastTargetHealth = health;
        } else {
            lastTargetHealth += (health - lastTargetHealth) * 0.22f;
            if (Math.abs(lastTargetHealth - health) < 0.02f) {
                lastTargetHealth = health;
            }
        }

        String name = target.getName().getString();
        float nameWidth = Render2D.smallWidth(name);
        float cardWidth = Math.max(140f, nameWidth + 60f);
        float cardHeight = 32f;
        float cardX = centerX - cardWidth / 2f;

        // Card background
        HudStyle.card(context, cardX, y, cardWidth, cardHeight, 7f, accent, true);

        // Accent line at bottom
        Render2D.roundRectGradient(context, cardX + 7f, y + cardHeight - 2.5f, (cardWidth - 14f) * 0.5f, 2.5f, 1.25f,
                ColorUtil.withAlpha(accent, 200), ColorUtil.withAlpha(accent, 0));

        // Name
        float textX = cardX + 8f;
        Render2D.smallText(context, name, textX, y + 4f, HudStyle.text());

        // Health value
        String healthText = String.format("%.1f", lastTargetHealth);
        int healthColor = ColorUtil.lerp(0xFFFF6B6B, 0xFF4ADE80, healthPercent);
        Render2D.smallRight(context, healthText, cardX + cardWidth - 8f, y + 4f, healthColor);

        // Health bar
        float barY = y + 18f;
        float barWidth = cardWidth - 16f;
        HudStyle.bar(context, textX, barY, barWidth, 4f, healthPercent,
                ColorUtil.lerp(0xFFFF4D4D, 0xFF86EFAC, 0.55f + healthPercent * 0.45f),
                ColorUtil.lerp(0xFF4ADE80, 0xFFBBF7D0, 0.35f));

        // Distance
        if (Velora.mc().player != null) {
            float distance = Velora.mc().player.distanceTo(target);
            String distText = String.format("%.1f m", distance);
            Render2D.smallText(context, distText, cardX + cardWidth - 8f - Render2D.smallWidth(distText), y + cardHeight - 12f, HudStyle.textFaint());
        }
    }
}
