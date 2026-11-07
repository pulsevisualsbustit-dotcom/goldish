package dev.velora.module;

import dev.velora.Velora;
import dev.velora.combat.AimAssist;
import dev.velora.combat.AutoSwap;
import dev.velora.combat.KillAura;
import dev.velora.combat.MaceHelper;
import dev.velora.combat.NoFriendDamage;
import dev.velora.combat.TriggerBot;
import dev.velora.misc.ClientSounds;
import dev.velora.misc.FunTimeHelper;
import dev.velora.misc.RichPresence;
import dev.velora.movement.AutoSprint;
import dev.velora.render.EntityEsp;
import dev.velora.render.FullBright;
import dev.velora.render.Hud;
import dev.velora.render.NameTags;
import dev.velora.render.Removals;
import dev.velora.render.TargetEsp;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModuleManager {

    private final List<Module> modules = new ArrayList<>();

    public void init() {
        // Combat
        register(new AimAssist());
        register(new KillAura());
        register(new TriggerBot());
        register(new NoFriendDamage());
        register(new MaceHelper());
        register(new AutoSwap());

        // Movement
        register(new AutoSprint());

        register(new FullBright()); // RENDER category: brightens the lightmap

        // Render / HUD
        register(new Hud());
        register(new Removals());
        register(new TargetEsp());
        register(new NameTags());
        register(new EntityEsp());

        // Misc
        register(new ClientSounds());
        register(new FunTimeHelper());
        register(new RichPresence());

        modules.sort(Comparator.comparing(module -> module.getName().toLowerCase()));
    }

    private void register(Module module) {
        modules.add(module);
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getModules(Category category) {
        return modules.stream().filter(module -> module.getCategory() == category).toList();
    }

    public List<Module> getEnabledModules() {
        return modules.stream().filter(Module::isEnabled).toList();
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) {
        for (Module module : modules) {
            if (type.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }

    public Module getByName(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    public boolean isEnabled(Class<? extends Module> type) {
        Module module = get(type);
        return module != null && module.isEnabled();
    }

    public void onTick() {
        for (Module module : modules) {
            if (module.isEnabled() && !module.ticksBeforeMovement()) {
                try {
                    module.onTick();
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while ticking", module.getName(), throwable);
                }
            }
            if (module.getAnimation() != (module.isExpanded() ? 1f : 0f)) {
                module.updateAnimation(1f);
            }
        }
    }

    /**
     * Runs the modules that must send their packets before the movement packet
     * of this tick (vanilla attack/use order, avoids GrimAC's "Post" flag).
     */
    public void onPreMovement() {
        for (Module module : modules) {
            if (module.isEnabled() && module.ticksBeforeMovement()) {
                try {
                    module.onTick();
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while ticking before movement",
                            module.getName(), throwable);
                }
            }
        }
    }

    /**
     * Tail of the movement-packet window: sendMovementPackets has sent the
     * packet of this tick. Broadcast to every enabled module; KillAura uses
     * it to restore the real camera rotation after a silent rotation.
     */
    public void onPostMovement() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    module.onPostMovement();
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed after the movement packet",
                            module.getName(), throwable);
                }
            }
        }
    }

    /**
     * Broadcasts an inventory click to the modules. Used by the combat helpers
     * that swap items: the server answer is still pending at this moment, so
     * they must not inspect the predicted inventory yet.
     */
    public void onSlotClick(net.minecraft.screen.slot.SlotActionType actionType) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    module.onSlotClick(actionType);
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while handling a slot click",
                            module.getName(), throwable);
                }
            }
        }
    }

    public void onFrame(float deltaTicks) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    module.onFrame(deltaTicks);
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while updating the frame", module.getName(), throwable);
                }
            }
        }
    }

    /**
     * Renders the HUD. While the chat screen is open every HUD element is drawn,
     * even disabled ones, so the editor can show and move all of them.
     */
    public void onRender2D(DrawContext context, float tickDelta) {
        boolean hudEditor = Velora.mc().currentScreen instanceof ChatScreen;
        for (Module module : modules) {
            boolean render = module.isEnabled() || (hudEditor && module instanceof Hud);
            if (!render) {
                continue;
            }
            try {
                module.onRender2D(context, tickDelta);
            } catch (Throwable throwable) {
                Velora.LOGGER.error("Module {} failed while rendering", module.getName(), throwable);
            }
        }
    }

    /** World 3D pass: every enabled module gets a camera-relative matrix stack. */
    public void onRenderWorld(MatrixStack matrices, Camera camera) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    module.onRenderWorld(matrices, camera);
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while rendering the world pass", module.getName(), throwable);
                }
            }
        }
    }

    public boolean onAttack(Entity target) {
        boolean cancel = false;
        for (Module module : modules) {
            if (module.isEnabled()) {
                try {
                    cancel |= module.onAttack(target);
                } catch (Throwable throwable) {
                    Velora.LOGGER.error("Module {} failed while handling an attack", module.getName(), throwable);
                }
            }
        }
        return cancel;
    }
}

