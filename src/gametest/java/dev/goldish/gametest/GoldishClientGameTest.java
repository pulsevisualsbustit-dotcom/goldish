package dev.velora.gametest;

import dev.velora.Velora;
import dev.velora.combat.AimAssist;
import dev.velora.combat.TriggerBot;
import dev.velora.config.ConfigManager;
import dev.velora.config.ShareCode;
import dev.velora.gui.AltManagerScreen;
import dev.velora.gui.ClickGuiScreen;
import dev.velora.gui.ConfigScreen;
import dev.velora.gui.ThemeScreen;
import dev.velora.gui.widget.VeloraButton;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.movement.AutoSprint;
import dev.velora.render.EntityEsp;
import dev.velora.render.Hud;
import dev.velora.render.HudWidget;
import dev.velora.render.NameTags;
import dev.velora.render.TargetEsp;
import dev.velora.render.Themes;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.setting.Setting;
import dev.velora.util.AltManager;
import dev.velora.util.Easing;
import dev.velora.util.MathUtil;
import dev.velora.util.Noise;
import dev.velora.util.RotationUtil;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.UUID;

/**
 * Renders the Velora HUD and ClickGUI inside a real singleplayer world and saves
 * screenshots, so the 2D stack can be verified visually instead of by guesswork.
 */
public class VeloraClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1280, 720);

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();

            // Turn every module on so the HUD list, suffixes and overlay code all run.
            context.runOnClient(client -> {
                for (Module module : Velora.get().getModuleManager().getModules()) {
                    module.setEnabled(true);
                }
                client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 2400, 1));
                client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 6000, 0));
            });

            context.waitTicks(40);
            log(context.takeScreenshot("velora-hud"));

            // Expanded ClickGUI so settings rows, sliders, toggles and chips are drawn.
            context.runOnClient(client -> {
                AimAssist aimAssist = Velora.get().getModuleManager().get(AimAssist.class);
                if (aimAssist != null) {
                    aimAssist.setExpanded(true);
                }
                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                if (hud != null) {
                    hud.setExpanded(true);
                }
            });
            context.setScreen(ClickGuiScreen::new);
            context.waitTicks(40);
            log(context.takeScreenshot("velora-clickgui"));

            // Resource reload (like F3+T) must not lose the generated corner/shadow textures.
            context.setScreen(() -> null);
            context.runOnClient(client -> client.reloadResources());
            context.waitTicks(80);
            context.takeScreenshot("velora-after-reload");

            // Client commands must be registered and reach the managers.
            context.runOnClient(client -> {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("velora friend add Alex");
                    client.getNetworkHandler().sendChatCommand("velora list");
                }
            });
            context.waitTicks(20);
            context.runOnClient(client -> {
                if (!Velora.get().getFriendManager().isFriend("Alex")) {
                    throw new AssertionError("/velora friend add Alex did not register the friend");
                }
            });
            log(context.takeScreenshot("velora-command"));

            // --- Combat / visuals ------------------------------------------------
            // TriggerBot must expose the three crit modes.
            context.runOnClient(client -> {
                TriggerBot triggerBot = Velora.get().getModuleManager().get(TriggerBot.class);
                ModeSetting crits = triggerBot == null ? null : findMode(triggerBot, "Crits");
                if (crits == null
                        || !crits.getModes().contains("Off")
                        || !crits.getModes().contains("Smart")
                        || !crits.getModes().contains("Only")) {
                    throw new AssertionError("TriggerBot is missing the Off/Smart/Only crit modes");
                }
                triggerBot.setEnabled(false);
            });

            // AimAssist must pull the crosshair onto a real entity, not jitter in place.
            // Entities have to be spawned on the integrated server: World#spawnEntity is
            // a no-op on the client, so a client-side spawn never reaches the world.
            float[] initialAngle = {180f};
            UUID[] targetId = new UUID[1];
            context.runOnClient(client -> {
                AimAssist aimAssist = Velora.get().getModuleManager().get(AimAssist.class);
                aimAssist.setEnabled(false);
                setBoolean(aimAssist, "Mobs", true);
                setBoolean(aimAssist, "Through walls", true);
                setNumber(aimAssist, "Speed", 10);

                client.player.setYaw(0f);
                client.player.setPitch(0f);
            });
            context.waitTicks(3);
            world.getServer().runOnServer(server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                ServerWorld serverWorld = player.getEntityWorld();
                ZombieEntity zombie = new ZombieEntity(serverWorld);
                double offset = Math.toRadians(20.0);
                zombie.refreshPositionAndAngles(
                        player.getX() + Math.sin(offset) * 2.5, player.getY(),
                        player.getZ() + Math.cos(offset) * 2.5, 0f, 0f);
                zombie.setAiDisabled(true);
                zombie.setPersistent();
                // Flat test world + daytime would burn the zombie away before the assertion.
                zombie.setInvulnerable(true);
                // Equipment + custom name so NameTags has armour icons, a held item and
                // a label to suppress.
                zombie.setCustomName(Text.literal("Sparring Dummy"));
                zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                zombie.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                zombie.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
                zombie.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
                zombie.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                serverWorld.spawnEntity(zombie);
                targetId[0] = zombie.getUuid();
            });
            context.waitTicks(20);
            context.runOnClient(client -> {
                ZombieEntity zombie = findZombie(client, targetId[0]);
                if (zombie == null) {
                    throw new AssertionError("Test zombie did not reach the client");
                }
                initialAngle[0] = angleToCenter(client.player, zombie);
                if (initialAngle[0] < 6f) {
                    throw new AssertionError("Test setup is wrong: the crosshair already points at the zombie");
                }
                Velora.get().getModuleManager().get(AimAssist.class).setEnabled(true);
            });
            context.waitTicks(40);
            context.runOnClient(client -> {
                ZombieEntity zombie = findZombie(client, targetId[0]);
                if (zombie == null) {
                    throw new AssertionError("Test zombie was not spawned");
                }
                float angle = angleToCenter(client.player, zombie);
                if (angle >= initialAngle[0] - 5f) {
                    throw new AssertionError("AimAssist did not move towards the target ("
                            + initialAngle[0] + " -> " + angle + ")");
                }
                AimAssist aimAssist = Velora.get().getModuleManager().get(AimAssist.class);
                if (aimAssist.getTarget() != zombie) {
                    throw new AssertionError("AimAssist target is not the entity in front of the player");
                }
            });

            // TargetHUD + every TargetESP style, using the same zombie as the target.
            context.runOnClient(client -> {
                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                TargetEsp targetEsp = Velora.get().getModuleManager().get(TargetEsp.class);
                hud.setEnabled(true);
                targetEsp.setEnabled(true);
                setBoolean(hud, "Target HUD", true);
                setBoolean(hud, "Target Mobs", true);
                setBoolean(hud, "Target Through walls", true);
                setBoolean(targetEsp, "Mobs", true);
                setBoolean(targetEsp, "Through walls", true);
            });
            context.waitTicks(10);
            log(context.takeScreenshot("velora-targethud"));

            context.runOnClient(client -> setMode(Velora.get().getModuleManager().get(TargetEsp.class),
                    "Mode", "Square"));
            context.waitTicks(15);
            log(context.takeScreenshot("velora-targetesp-square"));

            context.runOnClient(client -> setMode(Velora.get().getModuleManager().get(TargetEsp.class),
                    "Mode", "Souls"));
            context.waitTicks(15);
            log(context.takeScreenshot("velora-targetesp-souls"));

            // Constellation: a ring of linked stars with its own settings.
            context.runOnClient(client -> {
                TargetEsp esp = Velora.get().getModuleManager().get(TargetEsp.class);
                ModeSetting targetMode = findMode(esp, "Mode");
                if (targetMode == null || !targetMode.getModes().contains("Constellation")) {
                    throw new AssertionError("TargetESP is missing the Constellation mode");
                }
                for (String name : new String[]{"Stars", "Star orbit", "Star size", "Star glow", "Star lines"}) {
                    if (esp.getSettings().stream().noneMatch(setting -> setting.getName().equals(name))) {
                        throw new AssertionError("TargetESP is missing the \"" + name + "\" setting");
                    }
                }
                targetMode.setValue("Constellation");
            });
            context.waitTicks(20);
            log(context.takeScreenshot("velora-targetesp-constellation"));

            // --- NameTags + ESP -------------------------------------------------
            // Pull the dummy back so its box fits on screen for the screenshots.
            context.runOnClient(client -> {
                ZombieEntity zombie = findZombie(client, targetId[0]);
                if (zombie != null) {
                    zombie.setSprinting(false);
                }
            });
            world.getServer().runOnServer(server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                var entity = player.getEntityWorld().getEntity(targetId[0]);
                if (entity != null) {
                    double offset = Math.toRadians(20.0);
                    entity.refreshPositionAndAngles(
                            player.getX() + Math.sin(offset) * 7.0, player.getY(),
                            player.getZ() + Math.cos(offset) * 7.0, 180f, 0f);
                }
            });
            context.waitTicks(10);

            context.runOnClient(client -> {
                // The TargetHUD sits exactly where the dummy is; hide it so the box is visible.
                setBoolean(Velora.get().getModuleManager().get(Hud.class), "Target HUD", false);
                Velora.get().getModuleManager().get(TargetEsp.class).setEnabled(false);
                NameTags nameTags = Velora.get().getModuleManager().get(NameTags.class);
                nameTags.setEnabled(true);
                setBoolean(nameTags, "Mobs", true);
                setBoolean(nameTags, "Through walls", true);
                setNumber(nameTags, "Range", 48);
                EntityEsp esp = Velora.get().getModuleManager().get(EntityEsp.class);
                esp.setEnabled(true);
                setBoolean(esp, "Mobs", true);
                setBoolean(esp, "Through walls", true);
                setMode(esp, "Mode", "Box");
                setNumber(esp, "Range", 48);
            });
            context.waitTicks(15);
            log(context.takeScreenshot("velora-nametags-esp"));

            context.runOnClient(client -> setMode(Velora.get().getModuleManager().get(EntityEsp.class),
                    "Mode", "Chams"));
            context.waitTicks(15);
            log(context.takeScreenshot("velora-esp-chams"));

            context.runOnClient(client -> {
                ZombieEntity zombie = findZombie(client, targetId[0]);
                if (zombie == null) {
                    throw new AssertionError("NameTags/ESP dummy is missing");
                }
                if (!zombie.hasCustomName()) {
                    throw new AssertionError("NameTags test needs a named entity to suppress the vanilla label");
                }
                if (!NameTags.shouldHideVanillaLabel(zombie)) {
                    throw new AssertionError("NameTags did not claim the vanilla label of a tagged entity");
                }
                if (NameTags.shouldHideVanillaLabel(client.player)) {
                    throw new AssertionError("NameTags must never tag the local player");
                }
            });

            // --- Themes ---------------------------------------------------------
            context.runOnClient(client -> {
                ConfigManager config = Velora.get().getConfigManager();
                config.applyTheme(Themes.OCEAN);
                if (config.getAccentColor() != Themes.OCEAN.accent()
                        || !config.getThemeId().equals(Themes.OCEAN.id())) {
                    throw new AssertionError("Applying the Ocean theme did not change the accent colour");
                }
                try {
                    String json = Files.readString(FabricLoader.getInstance().getConfigDir()
                            .resolve("velora/config.json"));
                    if (!json.contains("\"theme\": \"ocean\"")) {
                        throw new AssertionError("The selected theme was not saved to the config");
                    }
                } catch (java.io.IOException exception) {
                    throw new AssertionError("Could not read the saved config", exception);
                }
            });
            context.setScreen(() -> new ThemeScreen(null));
            context.waitTicks(20);
            log(context.takeScreenshot("velora-themes"));

            // Click the third card (Violet) to prove the picker is interactive.
            float[] themeCursor = new float[2];
            context.runOnClient(client -> {
                // ThemeScreen is designed at 502x290 and scaled to fit the window.
                float designWidth = 3 * 150 + 2 * 10 + 32;
                float designHeight = 42 + 3 * 62 + 2 * 10 + 42;
                float windowWidth = client.getWindow().getScaledWidth();
                float windowHeight = client.getWindow().getScaledHeight();
                float uiScale = Math.min(1f, Math.min((windowWidth - 12f) / designWidth,
                        (windowHeight - 12f) / designHeight));
                float offsetX = (windowWidth - designWidth * uiScale) / 2f;
                float offsetY = (windowHeight - designHeight * uiScale) / 2f;
                double scaleX = client.getWindow().getWidth() / (double) windowWidth;
                double scaleY = client.getWindow().getHeight() / (double) windowHeight;
                themeCursor[0] = (float) ((offsetX + (16 + 2 * 160 + 75) * uiScale) * scaleX);
                themeCursor[1] = (float) ((offsetY + (42 + 31) * uiScale) * scaleY);
            });
            context.getInput().setCursorPos(themeCursor[0], themeCursor[1]);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(5);
            context.runOnClient(client -> {
                String themeId = Velora.get().getConfigManager().getThemeId();
                if (!Themes.VIOLET.id().equals(themeId)) {
                    throw new AssertionError("Clicking a theme card did not apply the theme (got "
                            + themeId + ")");
                }
            });
            context.setScreen(() -> null);
            context.waitTicks(5);

            // --- Config sharing -------------------------------------------------
            context.runOnClient(client -> {
                ConfigManager config = Velora.get().getConfigManager();
                String key = config.getShareKey();
                if (!ShareCode.isValidKey(key)) {
                    throw new AssertionError("The share key was not generated automatically: \"" + key + "\"");
                }
                if (!ShareCode.isValidKey(ShareCode.generateKey())
                        || ShareCode.generateKey().equals(ShareCode.generateKey())) {
                    throw new AssertionError("Generated share keys are not random");
                }

                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                hud.setEnabled(false);
                String code = config.exportShareCode();
                if (!code.startsWith("GS1:") || !code.contains(key)) {
                    throw new AssertionError("The share code does not embed the installation key: " + code);
                }

                // Re-enable, then import the code: the exported state must come back.
                hud.setEnabled(true);
                String error = config.importShareCode(code);
                if (error != null) {
                    throw new AssertionError("Importing a freshly exported code failed: " + error);
                }
                if (hud.isEnabled()) {
                    throw new AssertionError("The imported code did not restore the exported module state");
                }
                if (!config.getThemeId().equals(Themes.VIOLET.id())) {
                    throw new AssertionError("The imported code did not restore the theme");
                }
                hud.setEnabled(true);

                // The new HUD settings must be registered on the Hud module and
                // survive the config round-trip, so the look is fully adjustable.
                ModeSetting watermarkStyle = findMode(hud, "Watermark Style");
                if (watermarkStyle == null) {
                    throw new AssertionError("The Hud module does not expose the Watermark Style setting");
                }
                for (String mode : new String[]{"Card", "Slim", "Full", "Graph"}) {
                    if (!watermarkStyle.getModes().contains(mode)) {
                        throw new AssertionError("Watermark Style is missing the " + mode + " mode");
                    }
                }
                if (!hud.getSettings().stream().anyMatch(setting ->
                        setting instanceof BooleanSetting toggle && toggle.getName().equals("HUD Animation"))) {
                    throw new AssertionError("The Hud module does not expose the HUD Animation toggle");
                }

                String damaged = code.substring(0, code.length() - 4) + "AAAA";
                if (config.importShareCode(damaged) == null) {
                    throw new AssertionError("A corrupted share code was accepted");
                }
                if (config.importShareCode("GS1:NOT-A-KEY:00000000:AAAA") == null) {
                    throw new AssertionError("A share code with an invalid key was accepted");
                }
            });
            context.setScreen(() -> new ConfigScreen(null));
            context.waitTicks(20);
            log(context.takeScreenshot("velora-config"));
            context.setScreen(() -> null);
            context.waitTicks(5);

            // --- AutoSprint / Keep Sprint ---------------------------------------
            context.runOnClient(client -> {
                AutoSprint autoSprint = Velora.get().getModuleManager().get(AutoSprint.class);
                autoSprint.setEnabled(true);
                setBoolean(autoSprint, "Keep Sprint", true);
                setBoolean(autoSprint, "Only Moving", false);
                setBoolean(autoSprint, "While Using Item", true);
                client.player.setSprinting(false);
            });
            context.waitFor(client -> client.player != null && client.player.isSprinting(), 40);
            context.runOnClient(client -> {
                AutoSprint autoSprint = Velora.get().getModuleManager().get(AutoSprint.class);
                if (!"Keep".equals(autoSprint.getSuffix())) {
                    throw new AssertionError("AutoSprint suffix does not report Keep Sprint");
                }
                if (autoSprint.getCategory() != Category.MOVEMENT) {
                    throw new AssertionError("AutoSprint is not in the Movement category");
                }
                if (!client.player.isSprinting()) {
                    throw new AssertionError("Keep Sprint did not keep the sprint flag set");
                }
            });

            // --- Math foundation -------------------------------------------------
            context.runOnClient(client -> {
                if (Math.abs(MathUtil.lerp(0f, 10f, 0.5f) - 5f) > 1.0E-4f) {
                    throw new AssertionError("MathUtil.lerp is wrong");
                }
                if (Math.abs(MathUtil.map(5f, 0f, 10f, 0f, 100f) - 50f) > 1.0E-4f) {
                    throw new AssertionError("MathUtil.map is wrong");
                }
                if (Math.abs(MathUtil.smoothstep(0f, 1f, 0.5f) - 0.5f) > 1.0E-4f
                        || MathUtil.clamp01(1.5f) != 1f
                        || MathUtil.clamp(-1, 0, 10) != 0) {
                    throw new AssertionError("MathUtil clamping helpers are wrong");
                }
                if (!MathUtil.roughlyEquals(MathUtil.wrapDegrees(190f), -170f, 1.0E-3f)) {
                    throw new AssertionError("MathUtil.wrapDegrees is wrong");
                }
                if (Math.abs(Easing.QUAD_OUT.ease(0.5f) - 0.75f) > 1.0E-4f) {
                    throw new AssertionError("Easing.QUAD_OUT is wrong");
                }
                if (Math.abs(Easing.BOUNCE_OUT.ease(1f) - 1f) > 1.0E-4f
                        || Math.abs(Easing.LINEAR.ease(0.25f) - 0.25f) > 1.0E-4f) {
                    throw new AssertionError("Easing curves do not end where they should");
                }
                Noise noise = new Noise(1234L);
                float sample = noise.noise(0.37f, 1.21f);
                if (!(sample >= -1f && sample <= 1f)) {
                    throw new AssertionError("Noise is not normalised: " + sample);
                }
                if (Math.abs(noise.noise(3.3f) - new Noise(1234L).noise(3.3f)) > 1.0E-6f) {
                    throw new AssertionError("Noise is not deterministic for a fixed seed");
                }
                if (Math.abs(new Noise(99L).noise(3.3f) - noise.noise(3.3f)) < 1.0E-6f) {
                    throw new AssertionError("Different Noise seeds produce identical output");
                }
            });

            // --- HUD editor: drag the watermark while chat is open -----------------
            context.runOnClient(client -> setBoolean(Velora.get().getModuleManager().get(Hud.class),
                    "Watermark", false));
            float[] before = new float[2];
            float[] cursor = new float[2];
            context.getInput().pressKey(GLFW.GLFW_KEY_T);
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (!(client.currentScreen instanceof ChatScreen)) {
                    throw new AssertionError("Chat screen did not open, HUD editor is unreachable");
                }
                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                HudWidget watermark = hud.getWidget("Watermark");
                if (watermark.isEnabled()) {
                    throw new AssertionError("The watermark should be disabled for this test");
                }
                // Every HUD element must be visible while the chat is open, disabled or not.
                if (watermark.hudWidth() <= 0f || watermark.hudHeight() <= 0f) {
                    throw new AssertionError("A disabled HUD element was not rendered while chat was open ("
                            + watermark.hudWidth() + "x" + watermark.hudHeight() + ")");
                }
                before[0] = watermark.hudX();
                before[1] = watermark.hudY();
                double scaleX = client.getWindow().getWidth() / (double) client.getWindow().getScaledWidth();
                double scaleY = client.getWindow().getHeight() / (double) client.getWindow().getScaledHeight();
                cursor[0] = (float) ((watermark.hudX() + 10) * scaleX);
                cursor[1] = (float) ((watermark.hudY() + 8) * scaleY);
            });
            context.getInput().setCursorPos(cursor[0], cursor[1]);
            context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(2);
            context.getInput().moveCursor(72, 48);
            context.waitTicks(3);
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            context.waitTicks(3);
            context.runOnClient(client -> {
                HudWidget watermark = Velora.get().getModuleManager().get(Hud.class).getWidget("Watermark");
                if (watermark.hudX() <= before[0] + 5f || watermark.hudY() <= before[1] + 3f) {
                    throw new AssertionError("HUD element was not dragged by the chat-screen editor ("
                            + before[0] + "," + before[1] + " -> "
                            + watermark.hudX() + "," + watermark.hudY() + ")");
                }
                try {
                    String json = Files.readString(FabricLoader.getInstance().getConfigDir()
                            .resolve("velora/config.json"));
                    if (!json.contains("Watermark")) {
                        throw new AssertionError("Dragged HUD position was not saved to the config");
                    }
                } catch (java.io.IOException exception) {
                    throw new AssertionError("Could not read the saved config", exception);
                }
            });
            log(context.takeScreenshot("velora-hud-editor"));
            context.setScreen(() -> null);
            context.waitTicks(5);
            context.runOnClient(client -> setBoolean(Velora.get().getModuleManager().get(Hud.class),
                    "Watermark", true));

            // --- HUD style: every watermark style must lay out, and the "Full"
            // style (including coordinates) gets its own screenshot.
            for (String style : new String[]{"Graph", "Full", "Slim", "Card"}) {
                context.runOnClient(client -> setMode(Velora.get().getModuleManager().get(Hud.class),
                        "Watermark Style", style));
                context.waitTicks(4);
                context.runOnClient(client -> {
                    HudWidget watermark = Velora.get().getModuleManager().get(Hud.class)
                            .getWidget("Watermark");
                    if (watermark == null || watermark.hudWidth() <= 0f || watermark.hudHeight() <= 0f) {
                        throw new AssertionError("The watermark has no layout in this style");
                    }
                });
            }
            context.runOnClient(client -> {
                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                setMode(hud, "Watermark Style", "Full");
                setBoolean(hud, "Watermark Coordinates", true);
            });
            context.waitTicks(6);
            log(context.takeScreenshot("velora-hud-full"));
            context.runOnClient(client -> {
                Hud hud = Velora.get().getModuleManager().get(Hud.class);
                setBoolean(hud, "Watermark Coordinates", false);
                setMode(hud, "Watermark Style", "Card");
            });
            context.waitTicks(3);

            // Alt manager: commands store accounts, login swaps the live offline session.
            context.runOnClient(client -> {
                if (client.getNetworkHandler() == null) {
                    return;
                }
                client.getNetworkHandler().sendChatCommand("velora alt add TestAlt");
                client.getNetworkHandler().sendChatCommand("velora alt add SecondAlt");
                client.getNetworkHandler().sendChatCommand("velora alt list");
                client.getNetworkHandler().sendChatCommand("velora alt login TestAlt");
            });
            context.waitTicks(20);
            context.runOnClient(client -> {
                AltManager manager = Velora.get().getAltManager();
                if (manager.find("TestAlt") == null || manager.find("SecondAlt") == null) {
                    throw new AssertionError("/velora alt add did not store the accounts");
                }
                if (!client.getSession().getUsername().equals("TestAlt")) {
                    throw new AssertionError("Offline alt login did not swap the session");
                }
                if (!manager.isCurrent(manager.find("TestAlt"))) {
                    throw new AssertionError("Active alt was not detected");
                }
            });

            context.setScreen(() -> new AltManagerScreen(null));
            context.waitTicks(30);
            log(context.takeScreenshot("velora-altmanager"));

            // The screen itself: type a nickname, Enter adds it, Enter again logs in.
            context.getInput().typeChars("ClickAlt");
            context.waitTicks(5);
            context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            context.waitTicks(10);
            context.runOnClient(client -> {
                if (Velora.get().getAltManager().find("ClickAlt") == null) {
                    throw new AssertionError("Typing a nickname and pressing Enter did not add an alt");
                }
            });
            context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            context.waitTicks(10);
            context.runOnClient(client -> {
                if (!client.getSession().getUsername().equals("ClickAlt")) {
                    throw new AssertionError("Enter on the selected alt did not log in");
                }
            });
            log(context.takeScreenshot("velora-altmanager-login"));

            // The ALTS button must also exist on the vanilla menus, where accounts are switched.
            context.setScreen(TitleScreen::new);
            context.waitTicks(30);
            context.runOnClient(client -> {
                boolean found = client.currentScreen != null && client.currentScreen.children().stream()
                        .anyMatch(child -> child instanceof VeloraButton);
                if (!found) {
                    throw new AssertionError("ALTS button was not added to the title screen");
                }
            });
            log(context.takeScreenshot("velora-title-alts"));

            // Removing works through the command as well.
            context.setScreen(() -> null);
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("velora alt remove SecondAlt");
                }
            });
            context.waitTicks(10);
            context.runOnClient(client -> {
                if (Velora.get().getAltManager().find("SecondAlt") != null) {
                    throw new AssertionError("/velora alt remove did not delete the account");
                }
            });
        }
    }

    /** Angle between the view ray and the body centre, independent of the hitbox aim point. */
    private static float angleToCenter(net.minecraft.client.network.ClientPlayerEntity player,
                                       net.minecraft.entity.LivingEntity target) {
        RotationUtil.Rotation rotation = RotationUtil.look(player.getEyePos(),
                target.getBoundingBox().getCenter());
        float yawDiff = Math.abs(MathHelper.wrapDegrees(player.getYaw() - rotation.yaw()));
        float pitchDiff = Math.abs(player.getPitch() - rotation.pitch());
        return Math.max(yawDiff, pitchDiff);
    }

    private static ZombieEntity findZombie(MinecraftClient client, UUID uuid) {
        if (client.world == null || client.player == null) {
            return null;
        }
        return client.world.getEntitiesByClass(ZombieEntity.class,
                        client.player.getBoundingBox().expand(16.0), entity -> entity.getUuid().equals(uuid))
                .stream().findFirst().orElse(null);
    }

    private static void setBoolean(Module module, String name, boolean value) {
        if (module == null) {
            return;
        }
        for (Setting<?> setting : module.getSettings()) {
            if (setting instanceof BooleanSetting toggle && setting.getName().equals(name)) {
                toggle.setValue(value);
            }
        }
    }

    private static void setNumber(Module module, String name, double value) {
        if (module == null) {
            return;
        }
        for (Setting<?> setting : module.getSettings()) {
            if (setting instanceof NumberSetting number && setting.getName().equals(name)) {
                number.setValue(value);
            }
        }
    }

    private static void setMode(Module module, String name, String value) {
        if (module == null) {
            return;
        }
        for (Setting<?> setting : module.getSettings()) {
            if (setting instanceof ModeSetting mode && setting.getName().equals(name)) {
                mode.setValue(value);
            }
        }
    }

    private static ModeSetting findMode(Module module, String name) {
        if (module == null) {
            return null;
        }
        for (Setting<?> setting : module.getSettings()) {
            if (setting instanceof ModeSetting mode && setting.getName().equals(name)) {
                return mode;
            }
        }
        return null;
    }

    private static void log(java.nio.file.Path path) {
        Velora.LOGGER.info("Screenshot written to {}", path.toAbsolutePath());
    }
}
