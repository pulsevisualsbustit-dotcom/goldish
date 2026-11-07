package dev.velora.module;

import dev.velora.setting.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {

    protected final MinecraftClient mc = MinecraftClient.getInstance();

    private final String name;
    private final String description;
    private final Category category;

    private final List<Setting<?>> settings = new ArrayList<>();

    private boolean enabled;
    private int key = GLFW.GLFW_KEY_UNKNOWN;
    /** {@code true} while the bind has to be held instead of toggling. */
    private boolean holdMode;

    /** Used by the ClickGUI for the expand animation. */
    private boolean expanded;
    private float animation;

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    protected <T extends Setting<?>> T register(T setting) {
        settings.add(setting);
        return setting;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean state) {
        if (this.enabled == state) {
            return;
        }
        this.enabled = state;
        if (state) {
            onEnable();
        } else {
            onDisable();
        }
        if (dev.velora.Velora.get() != null) {
            var config = dev.velora.Velora.get().getConfigManager();
            config.save();
            // A config load toggles a lot of modules; only real user actions
            // should pop the "module on/off" toast.
            if (!config.isLoading()) {
                dev.velora.render.Hud.toast(name, state);
                dev.velora.misc.ClientSounds.moduleToggled(state);
            }
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /**
     * Starts the module enabled on a fresh config (the saved config still wins
     * on load). Used by the merged HUD module so updating clients that used
     * the old separate HUD modules does not make the whole HUD disappear.
     */
    protected void startEnabled() {
        this.enabled = true;
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    public boolean isHoldMode() {
        return holdMode;
    }

    public void setHoldMode(boolean holdMode) {
        this.holdMode = holdMode;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public float getAnimation() {
        return animation;
    }

    public void updateAnimation(float delta) {
        float target = expanded ? 1f : 0f;
        // 0.7 per frame at 60 fps: the settings fold out in about four frames.
        animation += (target - animation) * Math.min(1f, delta * 0.7f);
        if (Math.abs(animation - target) < 0.001f) {
            animation = target;
        }
    }

    /** Height of the settings block, animated. */
    public float getSettingsHeight() {
        float height = 0;
        for (Setting<?> setting : settings) {
            if (setting.isVisible()) {
                height += 18f;
            }
        }
        return height * animation;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onTick() {
    }

    /**
     * {@code true} for modules whose packets must be sent before the movement
     * packet of the tick (vanilla input order). GrimAC's "Post" check kicks
     * clients that send attacks/uses after the flying packet, so those modules
     * are ticked from {@code ClientPlayerEntity.sendMovementPackets} instead of
     * {@code END_CLIENT_TICK}.
     */
    public boolean ticksBeforeMovement() {
        return false;
    }

    /**
     * Called right after the movement packet of this tick has been sent
     * (tail of ClientPlayerEntity.sendMovementPackets). Modules that swap
     * the player rotation for the packet window must restore the real camera
     * rotation here, before the rest of the tick or any frame can observe
     * it.
     */
    public void onPostMovement() {
    }

    /**
     * Called right after an inventory click was sent to the server. The answer
     * is still in flight, so the client's stacks are only a prediction; modules
     * that depend on the real content should keep their own state until the
     * screen handler revision changes.
     */
    public void onSlotClick(net.minecraft.screen.slot.SlotActionType actionType) {
    }

    /** Called once per rendered frame, before the camera is updated. */
    public void onFrame(float deltaTicks) {
    }

    public void onRender2D(DrawContext context, float tickDelta) {
    }

    /**
     * Called once per rendered frame during the world 3D pass, after entities
     * have been drawn. Coordinates are camera-relative world space, same as
     * vanilla entity rendering.
     */
    public void onRenderWorld(MatrixStack matrices, Camera camera) {
    }

    /**
     * @return {@code true} when the attack has to be cancelled.
     */
    public boolean onAttack(Entity target) {
        return false;
    }

    /** Suffix shown in the HUD, e.g. the current mode. */
    public String getSuffix() {
        return "";
    }

    @Override
    public String toString() {
        return name;
    }
}
