package dev.velora.render;

import dev.velora.Velora;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.TargetUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.Locale;

/**
 * TargetHUD: a compact target card under the crosshair.
 *
 * <p>Follows the reference HUD: a 36px tall glass card with the target's face on
 * the left, the name in white and the health readout with a heart on the right,
 * a six-slot equipment row and a thin accent health bar along the bottom.
 * Absorption is drawn as a gold bar that grows from the right side of the same
 * track. Mobs without a skin fall back to a simple person silhouette.</p>
 */
public class TargetHudWidget extends HudWidget {

    private static final float HEIGHT = 36f;
    private static final float AVATAR = 25f;
    private static final float AVATAR_X = 5f;
    private static final float AVATAR_Y = 5.5f;
    private static final float CONTENT_X = 34f;
    private static final float ITEM = 8f;
    private static final float ITEM_STEP = 10f;
    private static final float BAR_Y = 28.5f;
    private static final float BAR_HEIGHT = 2f;
    private static final float MIN_WIDTH = 100f;

    private final BooleanSetting showHealth = register(new BooleanSetting("Target Health", true));
    private final BooleanSetting showArmor = register(new BooleanSetting("Target Armor", true));
    private final BooleanSetting background = register(new BooleanSetting("Target Background", true));
    private final NumberSetting range = register(new NumberSetting("Target Range", 8.0, 2.0, 32.0, 1.0));
    private final BooleanSetting players = register(new BooleanSetting("Target Players", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Target Mobs", false));
    private final BooleanSetting throughWalls = register(new BooleanSetting("Target Through walls", false));

    private float displayedHealth = -1f;

    public TargetHudWidget() {
        super("TargetHUD", "Target hud");
    }

    @Override
    public void render(DrawContext context, float tickDelta) {
        LivingEntity target = TargetUtil.findBest(range.get(), 360.0, players.get(), mobs.get(), throughWalls.get());
        if (target == null && hudEditorOpen() && Velora.mc().player != null) {
            // Preview while the HUD editor is open, so the widget is always
            // visible and can be positioned even without a real target.
            target = Velora.mc().player;
        }
        if (target == null) {
            displayedHealth = -1f;
            setSize(0, 0);
            return;
        }

        String name = target.getName().getString();
        float health = target.getHealth();
        float absorption = target.getAbsorptionAmount();
        float totalHealth = health + absorption;
        float maxHealth = Math.max(1f, target.getMaxHealth());

        if (displayedHealth < 0f) {
            displayedHealth = totalHealth;
        } else {
            displayedHealth += (totalHealth - displayedHealth) * 0.22f;
            if (Math.abs(displayedHealth - totalHealth) < 0.02f) {
                displayedHealth = totalHealth;
            }
        }

        float healthPercent = MathHelper.clamp(displayedHealth / maxHealth, 0f, 1f);
        int accent = HudStyle.accent();

        // The readout sits on the top line, the name gets whatever is left.
        String healthText = String.format(Locale.ROOT, "%.1f", Math.max(0f, totalHealth));
        float heartWidth = 7f;
        float healthWidth = SystemFont.BODY.width(healthText);
        float width = MathHelper.clamp(CONTENT_X + SystemFont.BODY.width(name) + 10f
                + healthWidth + heartWidth + 10f, MIN_WIDTH, 190f);

        setSize(width, HEIGHT);
        anchorCenter(26f);
        place();

        float x = screenX();
        float y = screenY();

        if (background.get()) {
            HudStyle.card(context, x, y, width, HEIGHT, 5f);
        }

        // ------------------------------------------------------------- avatar
        float avatarX = x + AVATAR_X;
        float avatarY = y + AVATAR_Y;
        Render2D.roundRect(context, avatarX, avatarY, AVATAR, AVATAR, 3f, 0xFF17171D);
        if (!drawFace(context, target, avatarX, avatarY, AVATAR, tickDelta)) {
            drawPersonIcon(context, avatarX + AVATAR / 2f, avatarY + AVATAR / 2f);
        }

        // -------------------------------------------------------------- texts
        float rightEdge = x + width - 6f;
        float heartX = rightEdge - heartWidth;
        float healthRight = heartX - 3f;
        float contentX = x + CONTENT_X;
        String trimmedName = trim(name, Math.max(10f, healthRight - contentX - 4f));

        Render2D.text(context, trimmedName, contentX, y + 5.5f, HudStyle.text(), false);
        Render2D.text(context, healthText, healthRight - healthWidth, y + 5.5f,
                HudStyle.text(), false);
        HudIcons.heart(context, heartX, y + 6.5f, heartWidth,
                ColorUtil.withAlpha(HudStyle.RED, 235));

        // ------------------------------------------------------------ health bar
        float barX = contentX;
        float barWidth = Math.max(8f, rightEdge - barX);
        Render2D.roundRect(context, barX, y + BAR_Y, barWidth, BAR_HEIGHT, BAR_HEIGHT / 2f,
                HudStyle.ROW_TRACK);
        float healthWidthPx = barWidth * MathHelper.clamp(health / maxHealth, 0f, 1f);
        if (healthWidthPx > 0.5f) {
            Render2D.roundRect(context, barX, y + BAR_Y, healthWidthPx, BAR_HEIGHT,
                    BAR_HEIGHT / 2f, accent);
        }
        if (absorption > 0.01f) {
            float absorptionWidth = barWidth
                    * MathHelper.clamp(absorption / maxHealth, 0f, 1f - health / maxHealth);
            if (absorptionWidth > 0.5f) {
                Render2D.roundRect(context, barX + barWidth - absorptionWidth, y + BAR_Y,
                        absorptionWidth, BAR_HEIGHT, BAR_HEIGHT / 2f, 0xFFFFC847);
            }
        }

        // ------------------------------------------------------------ armor row
        if (showArmor.get()) {
            List<ItemStack> stacks = equipment(target);
            float iconY = y + 16.5f;
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                HudStyle.item(context, stack, contentX + i * ITEM_STEP, iconY, ITEM);
            }
        }

        setSize(width, HEIGHT);
    }

    /** {@code true} when the real face texture was drawn. */
    private boolean drawFace(DrawContext context, LivingEntity target, float x, float y, float size,
                             float tickDelta) {
        if (target instanceof AbstractClientPlayerEntity) {
            return drawSkinHead(context, target, x, y, size);
        }
        // Mobs have no fixed skin on the entity: ask their renderer for the
        // texture it is about to use, exactly like the reference HUD does.
        try {
            var base = Velora.mc().getEntityRenderDispatcher().getRenderer(target);
            if (!(base instanceof LivingEntityRenderer<?, ?, ?> living)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?> renderer =
                    (LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>) living;
            LivingEntityRenderState state = renderer.getAndUpdateRenderState(target, tickDelta);
            Identifier texture = renderer.getTexture(state);
            if (texture == null) {
                return false;
            }
            Render2D.imageRegion(context, texture, x, y, size, size, 8f, 8f, 8, 8, 64, 64, 0xFFFFFFFF);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code true} when the real player skin head was drawn. */
    private boolean drawSkinHead(DrawContext context, LivingEntity target, float x, float y, float size) {
        if (!(target instanceof AbstractClientPlayerEntity player)) {
            return false;
        }
        Identifier skin;
        try {
            skin = player.getSkin().body().id();
        } catch (Throwable ignored) {
            return false;
        }
        if (skin == null) {
            return false;
        }
        // Face (8x8 at 8,8) plus the hat / hair overlay (8x8 at 40,8).
        Render2D.imageRegion(context, skin, x, y, size, size, 8f, 8f, 8, 8, 64, 64, 0xFFFFFFFF);
        Render2D.imageRegion(context, skin, x, y, size, size, 40f, 8f, 8, 8, 64, 64, 0xFFFFFFFF);
        return true;
    }

    /** Desktop-style person icon used when no face texture is available. */
    private void drawPersonIcon(DrawContext context, float centerX, float centerY) {
        int body = 0xFFE8E8EF;
        Render2D.circle(context, centerX, centerY - 3.5f, 6.5f, body);
        Render2D.roundRect(context, centerX - 6.5f, centerY + 2.5f, 13f, 8f, 4f, body);
    }

    /** Head, chest, legs, feet, main hand and off hand; empty slots are kept. */
    private static List<ItemStack> equipment(LivingEntity living) {
        return List.of(
                living.getEquippedStack(EquipmentSlot.HEAD),
                living.getEquippedStack(EquipmentSlot.CHEST),
                living.getEquippedStack(EquipmentSlot.LEGS),
                living.getEquippedStack(EquipmentSlot.FEET),
                living.getMainHandStack(),
                living.getOffHandStack());
    }

    private String trim(String text, float maxWidth) {
        if (SystemFont.BODY.width(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && SystemFont.BODY.width(result + "\u2026") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "\u2026";
    }
}
