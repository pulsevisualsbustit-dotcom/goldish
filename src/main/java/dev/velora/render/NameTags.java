package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.MathUtil;
import dev.velora.util.PlayerUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Name tags with health, armour and the held item.
 *
 * <p>Everything is projected into the 2D HUD, which keeps the tags crisp at any
 * GUI scale and lets them use the same system font as the TargetHUD. Vanilla
 * player labels are hidden while this module is enabled (see
 * {@code EntityRendererMixin}), so only one tag per entity is visible.</p>
 */
public class NameTags extends Module {

    private final BooleanSetting players = register(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", false));
    private final NumberSetting range = register(new NumberSetting("Range", 64.0, 8.0, 128.0, 1.0));
    private final NumberSetting scale = register(new NumberSetting("Scale", 1.0, 0.6, 1.8, 0.05));
    private final BooleanSetting health = register(new BooleanSetting("Health", true));
    private final BooleanSetting armor = register(new BooleanSetting("Armor", true));
    private final BooleanSetting heldItem = register(new BooleanSetting("Held Item", true));
    private final BooleanSetting background = register(new BooleanSetting("Background", true));
    private final BooleanSetting throughWalls = register(new BooleanSetting("Through walls", false));

    private final VisibilityCache visibility = new VisibilityCache();
    public NameTags() {
        super("NameTags", "Name, health, armour and held item above players", Category.RENDER);
    }

    @Override
    public void onRender2D(DrawContext context, float tickDelta) {
        var client = Velora.mc();
        if (client.player == null || client.world == null) {
            return;
        }

        Entity cameraEntity = client.getCameraEntity();
        double rangeValue = range.get();

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == cameraEntity || living == client.player) {
                continue;
            }
            if (!matches(living)) {
                continue;
            }
            double distance = client.player.getEyePos().distanceTo(living.getBoundingBox().getCenter());
            if (distance > rangeValue) {
                continue;
            }
            if (!throughWalls.get() && !visibility.isVisible(living)) {
                continue;
            }
            renderTag(context, living, tickDelta, distance);
        }
    }

    /** Whether this entity should get a Velora tag (and lose the vanilla one). */
    public boolean matches(LivingEntity living) {
        // NOTE: invisible players are no longer skipped — their tag keeps the
        // name/health/armor info visible, which is the whole point of a tag
        // module in a fight (spectators stay hidden).
        if (living == null || !living.isAlive()) {
            return false;
        }
        if (living == Velora.mc().player) {
            return false;
        }
        if (living instanceof PlayerEntity player) {
            return players.get() && !player.isSpectator();
        }
        return living instanceof MobEntity && mobs.get();
    }

    /** Called by the entity-render mixin to suppress duplicate vanilla labels. */
    public static boolean shouldHideVanillaLabel(Entity entity) {
        if (!(entity instanceof LivingEntity living) || Velora.get() == null) {
            return false;
        }
        NameTags module = Velora.get().getModuleManager().get(NameTags.class);
        return module != null && module.isEnabled() && module.matches(living);
    }

    // ------------------------------------------------------------------ render

    private void renderTag(DrawContext context, LivingEntity living, float tickDelta, double distance) {
        Box box = Projection.interpolatedBox(living, tickDelta);
        Vec3d center = box.getCenter();
        float[] head = Projection.project(new Vec3d(center.x, box.maxY + 0.42, center.z));
        if (head == null) {
            return;
        }

        float sizeScale = scale.getFloat()
                * MathUtil.clamp((float) (6.0 / Math.max(1.0, distance)), 0.8f, 1.25f);
        String name = living.getName().getString();
        float maxHealth = Math.max(1f, living.getMaxHealth());
        float totalHealth = living.getHealth() + living.getAbsorptionAmount();
        float healthPercent = MathUtil.clamp01(totalHealth / maxHealth);
        String healthText = String.format(Locale.ROOT, "%.1f", totalHealth);

        float fontHeight = SystemFont.BODY.height(sizeScale);
        float paddingX = 4.5f * sizeScale;
        float paddingY = 3f * sizeScale;
        float gap = 5f * sizeScale;

        float nameWidth = SystemFont.BODY.width(name, sizeScale);
        float healthWidth = SystemFont.BODY.width(healthText, sizeScale);
        float cardWidth = paddingX * 2f + nameWidth + gap + healthWidth;
        float cardHeight = paddingY * 2f + fontHeight;
        float cardX = head[0] - cardWidth / 2f;
        float cardY = head[1] - cardHeight;

        var config = Velora.get().getConfigManager();

        // A plain semi-transparent rectangle: name on the left, health number on
        // the right. No outline, no gradient and no health bar.
        if (background.get()) {
            Render2D.rect(context, cardX, cardY, cardWidth, cardHeight,
                    ColorUtil.withAlpha(HudStyle.CARD_BLACK, 140));
        }

        boolean friend = living instanceof PlayerEntity player && Velora.get().getFriendManager().isFriend(player);
        int nameColor = friend ? 0xFF4ADE80 : config.getTextColor();
        SystemFont.BODY.draw(context, name, cardX + paddingX, cardY + paddingY, nameColor, sizeScale);

        int healthColor = ColorUtil.lerp(0xFFFF5252, 0xFF4ADE80, healthPercent);
        SystemFont.BODY.draw(context, healthText,
                cardX + cardWidth - paddingX - healthWidth, cardY + paddingY, healthColor, sizeScale);

        // Small armour icons float just above the plate, without their own card.
        if (armor.get()) {
            List<ItemStack> pieces = armorPieces(living);
            if (!pieces.isEmpty()) {
                float iconSize = 12f * sizeScale;
                float iconGap = 1.5f * sizeScale;
                float rowWidth = pieces.size() * (iconSize + iconGap) - iconGap;
                float rowX = head[0] - rowWidth / 2f;
                float rowY = cardY - iconSize - 2.5f * sizeScale;
                for (int i = 0; i < pieces.size(); i++) {
                    HudStyle.item(context, pieces.get(i), rowX + i * (iconSize + iconGap), rowY, iconSize);
                }
            }
        }

        // Main-hand item is shown below the feet.
        if (heldItem.get()) {
            ItemStack stack = living.getEquippedStack(EquipmentSlot.MAINHAND);
            if (!stack.isEmpty()) {
                float[] feet = Projection.project(new Vec3d(center.x, box.minY - 0.12, center.z));
                if (feet != null) {
                    drawItem(context, stack, feet[0] - 8f, feet[1] + 3f);
                }
            }
        }
    }

    private void drawItem(DrawContext context, ItemStack stack, float x, float y) {
        int itemX = Math.round(x);
        int itemY = Math.round(y);
        context.drawItem(stack, itemX, itemY);
        context.drawStackOverlay(Velora.mc().textRenderer, stack, itemX, itemY);
    }

    private static List<ItemStack> armorPieces(LivingEntity living) {
        List<ItemStack> pieces = new ArrayList<>(4);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = living.getEquippedStack(slot);
            if (!stack.isEmpty()) {
                pieces.add(stack);
            }
        }
        return pieces;
    }
}
