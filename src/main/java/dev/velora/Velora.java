package dev.velora;

import dev.velora.command.ClientChatCommands;
import dev.velora.command.VeloraCommand;
import dev.velora.config.ConfigManager;
import dev.velora.gui.AltManagerScreen;
import dev.velora.gui.LiteClickGuiScreen;
import dev.velora.gui.widget.VeloraButton;
import dev.velora.misc.ClientSounds;
import dev.velora.mixin.ScreenAccessor;
import dev.velora.module.Module;
import dev.velora.module.ModuleManager;
import dev.velora.render.HudEditor;
import dev.velora.render.Themes;
import dev.velora.render.lightning.LightningLinesPipeline;
import dev.velora.util.AltManager;
import dev.velora.util.FriendManager;
import dev.velora.util.Keys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Main client entry point.
 *
 * <p>Everything is client side only: the mod never touches the server except
 * through normal player actions (movement, attacks, rotations).</p>
 */
public final class Velora implements ClientModInitializer {

    public static final String NAME = "Velora";
    public static final String VERSION = "1.0.0";
    /** Chat prefix for client commands; change it here when the client is renamed. */
    public static final String COMMAND_PREFIX = ".velora";
    /** Older prefix kept as an alias so existing muscle memory keeps working. */
    public static final String LEGACY_COMMAND_PREFIX = ".gold";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAME);
    /** Client start time, shown as the session length in the ClickGUI user card. */
    public static final long START_TIME = System.currentTimeMillis();

    private static Velora instance;

    private ModuleManager moduleManager;
    private ConfigManager configManager;
    private FriendManager friendManager;
    private AltManager altManager;

    private KeyBinding clickGuiKey;
    private KeyBinding hudEditorKey;
    private final Set<Module> heldModules = new HashSet<>();

    public static Velora get() {
        return instance;
    }

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    /**
     * Adds the small "ALTS" entry point to the title and multiplayer screens, so
     * accounts can be switched before joining a server.
     *
     * <p>Safe to call more than once: the button is only added if the screen does
     * not already contain one.</p>
     */
    public static void attachAltButton(MinecraftClient client, Screen screen) {
        if (!(screen instanceof TitleScreen) && !(screen instanceof MultiplayerScreen)) {
            return;
        }
        for (Element child : screen.children()) {
            if (child instanceof VeloraButton) {
                return;
            }
        }
        VeloraButton button = new VeloraButton(screen.width - 74, 6, 68, 18, Text.literal("ALTS"),
                () -> client.setScreen(new AltManagerScreen(screen)));
        ((ScreenAccessor) screen).velora$addDrawableChild(button);
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        friendManager = new FriendManager();
        altManager = new AltManager();
        moduleManager = new ModuleManager();
        configManager = new ConfigManager();

        moduleManager.init();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("velora", "main"));

        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.velora.clickgui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                category
        ));

        hudEditorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.velora.hudeditor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> attachAltButton(client, screen));

        // World 3D pass for render modules (Target ESP lightning, etc.). The
        // lines pipelines need the shader loader up, so they are registered
        // once the client has fully started rather than at mod entry.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> LightningLinesPipeline.init());
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
            moduleManager.onRenderWorld(context.matrices(), context.gameRenderer().getCamera()));

        VeloraCommand.register();
        ClientChatCommands.register();

        migrateLegacyDirectory();
        Themes.loadCustom();
        configManager.load();

        LOGGER.info("{} v{} loaded with {} modules", NAME, VERSION, moduleManager.getModules().size());
    }

    /**
     * The client used to be called Goldish and stored its data in
     * {@code config/goldish}. On the first Velora start the old folder is copied
     * over so themes, profiles, friends and settings survive the rename.
     */
    private static void migrateLegacyDirectory() {
        try {
            var configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            java.nio.file.Path legacy = configDir.resolve("goldish");
            java.nio.file.Path target = configDir.resolve("velora");
            if (!java.nio.file.Files.isDirectory(legacy) || java.nio.file.Files.exists(target)) {
                return;
            }
            try (var paths = java.nio.file.Files.walk(legacy)) {
                for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) paths::iterator) {
                    java.nio.file.Path destination = target.resolve(legacy.relativize(source).toString());
                    if (java.nio.file.Files.isDirectory(source)) {
                        java.nio.file.Files.createDirectories(destination);
                    } else {
                        java.nio.file.Files.createDirectories(destination.getParent());
                        java.nio.file.Files.copy(source, destination,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            LOGGER.info("Migrated the legacy Goldish config folder to config/velora");
        } catch (Exception exception) {
            LOGGER.warn("Could not migrate the legacy config folder", exception);
        }
    }

    private void tick() {
        while (clickGuiKey.wasPressed()) {
            if (mc().currentScreen == null) {
                ClientSounds.guiToggled(true);
                mc().setScreen(new LiteClickGuiScreen());
            } else if (mc().currentScreen instanceof LiteClickGuiScreen) {
                mc().currentScreen.close();
            }
        }

        // A dedicated editor, so the whole HUD can be arranged without chat.
        while (hudEditorKey.wasPressed()) {
            if (mc().currentScreen == null) {
                mc().setScreen(new dev.velora.render.HudEditorScreen());
            } else if (mc().currentScreen instanceof dev.velora.render.HudEditorScreen) {
                mc().currentScreen.close();
            }
        }

        if (mc().player == null || mc().world == null) {
            // Modules such as the Discord presence still need a tick in menus.
            moduleManager.onGlobalTick();
            releaseHeldModules();
            return;
        }

        handleModuleKeys();
        moduleManager.onGlobalTick();
        moduleManager.onTick();
    }

    private void handleModuleKeys() {
        if (mc().currentScreen != null) {
            releaseHeldModules();
            return;
        }
        for (Module module : moduleManager.getModules()) {
            int key = module.getKey();
            if (key == GLFW.GLFW_KEY_UNKNOWN) {
                if (heldModules.remove(module) && module.isHoldMode()) {
                    module.setEnabled(false);
                }
                continue;
            }
            boolean down = Keys.isDown(key);
            if (module.isHoldMode()) {
                // Hold mode: on while the key/mouse button is down.
                if (down && heldModules.add(module)) {
                    module.setEnabled(true);
                } else if (!down && heldModules.remove(module)) {
                    module.setEnabled(false);
                }
            } else if (down && heldModules.add(module)) {
                module.toggle();
            } else if (!down) {
                heldModules.remove(module);
            }
        }
    }

    /** Stops modules that are currently held by their bind. */
    private void releaseHeldModules() {
        for (Module module : heldModules) {
            if (module.isHoldMode() && module.isEnabled()) {
                module.setEnabled(false);
            }
        }
        heldModules.clear();
    }

    /** Called from the attack mixin. Returns {@code true} if the attack must be cancelled. */
    public boolean onAttack(Entity target) {
        return moduleManager.onAttack(target);
    }

    /** Called at the tail of {@code InGameHud#render}. */
    public void onRender2D(DrawContext context, float tickDelta) {
        if (mc().player == null || mc().world == null) {
            return;
        }
        // With a GUI open the HUD would bleed through the panels and make the
        // screen noisy. The chat is the exception: it is the HUD editor.
        if (mc().currentScreen != null
                && !(mc().currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
            return;
        }
        moduleManager.onRender2D(context, tickDelta);
    }

    /** Called at the head of {@code GameRenderer#render}, before the camera update. */
    public void onFrame(float deltaTicks) {
        if (mc().player == null || mc().world == null) {
            return;
        }
        moduleManager.onFrame(deltaTicks);
    }

    /**
     * Called right before {@code ClientPlayerEntity#sendMovementPackets}, so
     * attacks and item uses go out in vanilla packet order (before the flying
     * packet). GrimAC's "Post" check kicks clients that send them after it.
     */
    public void onPreMovement() {
        if (mc().player == null || mc().world == null || moduleManager == null) {
            return;
        }
        moduleManager.onPreMovement();
    }

    /**
     * Tail of ClientPlayerEntity.sendMovementPackets: the movement packet of
     * the tick is out. Modules that swapped the player rotation for the
     * packet window (silent rotations) restore the real camera rotation
     * here, before the rest of the tick or any frame can observe it.
     */
    public void onPostMovement() {
        if (mc().player == null || mc().world == null || moduleManager == null) {
            return;
        }
        moduleManager.onPostMovement();
    }

    /**
     * Called after every inventory click. Vanilla hands the click to the server
     * and only applies the result when the response comes back, so modules that
     * inspect stacks right after swapping must wait for that answer instead of
     * trusting the predicted (still unchanged) inventory.
     */
    public void onSlotClick(net.minecraft.screen.slot.SlotActionType actionType) {
        if (mc().player == null || moduleManager == null) {
            return;
        }
        moduleManager.onSlotClick(actionType);
    }

    public void renderHudEditor(DrawContext context, int mouseX, int mouseY) {
        HudEditor.render(context, mouseX, mouseY);
    }

    public boolean hudMouseClicked(double mouseX, double mouseY, int button) {
        return HudEditor.mouseClicked(mouseX, mouseY, button);
    }

    public boolean hudMouseDragged(double mouseX, double mouseY) {
        return HudEditor.mouseDragged(mouseX, mouseY);
    }

    public boolean hudMouseReleased() {
        return HudEditor.mouseReleased();
    }

    public void cancelHudDrag() {
        HudEditor.cancel();
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public FriendManager getFriendManager() {
        return friendManager;
    }

    public AltManager getAltManager() {
        return altManager;
    }

    public KeyBinding getClickGuiKey() {
        return clickGuiKey;
    }
}
