package dev.velora.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.velora.Velora;
import dev.velora.gui.AltManagerScreen;
import dev.velora.gui.ConfigScreen;
import dev.velora.module.Module;
import dev.velora.render.Theme;
import dev.velora.render.Themes;
import dev.velora.util.Alt;
import dev.velora.util.AltManager;
import dev.velora.util.ChatUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.stream.Stream;

/**
 * Client-side {@code /velora} command: friend list management and module toggles,
 * so the client is usable without opening the GUI.
 */
public final class VeloraCommand {

    private static final SuggestionProvider<FabricClientCommandSource> MODULE_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(
                    Velora.get().getModuleManager().getModules().stream().map(Module::getName),
                    builder);

    private static final SuggestionProvider<FabricClientCommandSource> THEME_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(
                    Themes.ALL.stream().map(Theme::id),
                    builder);

    private VeloraCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
                    ClientCommandManager.literal("velora")
                            .then(ClientCommandManager.literal("friend")
                                    .then(ClientCommandManager.literal("add")
                                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                                    .executes(context -> {
                                                        String name = StringArgumentType.getString(context, "name");
                                                        boolean added = Velora.get().getFriendManager().add(name);
                                                        ChatUtil.send(added
                                                                ? Text.literal("Added " + name + " to friends").formatted(Formatting.GREEN)
                                                                : Text.literal(name + " is already a friend").formatted(Formatting.GRAY));
                                                        return 1;
                                                    })))
                                    .then(ClientCommandManager.literal("remove")
                                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                                    .executes(context -> {
                                                        String name = StringArgumentType.getString(context, "name");
                                                        boolean removed = Velora.get().getFriendManager().remove(name);
                                                        ChatUtil.send(removed
                                                                ? Text.literal("Removed " + name + " from friends").formatted(Formatting.GREEN)
                                                                : Text.literal(name + " is not a friend").formatted(Formatting.GRAY));
                                                        return 1;
                                                    })))
                                    .then(ClientCommandManager.literal("list")
                                            .executes(context -> {
                                                var friends = Velora.get().getFriendManager().getFriends();
                                                if (friends.isEmpty()) {
                                                    ChatUtil.info("Friend list is empty");
                                                } else {
                                                    ChatUtil.info("Friends: " + String.join(", ", friends));
                                                }
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("alt")
                                    .then(ClientCommandManager.literal("add")
                                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                                    .executes(context -> {
                                                        String name = StringArgumentType.getString(context, "name");
                                                        AltManager manager = Velora.get().getAltManager();
                                                        switch (manager.add(name)) {
                                                            case ADDED -> ChatUtil.send(Text.literal("Added alt " + name
                                                                    + " (offline)").formatted(Formatting.GREEN));
                                                            case ALREADY_EXISTS -> ChatUtil.send(Text.literal(name
                                                                    + " is already in the alt list").formatted(Formatting.GRAY));
                                                            case INVALID_NAME -> ChatUtil.error(
                                                                    "Invalid nickname: use 3-16 characters (a-z, 0-9, _)");
                                                        }
                                                        return 1;
                                                    })))
                                    .then(ClientCommandManager.literal("remove")
                                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                                    .executes(context -> {
                                                        String name = StringArgumentType.getString(context, "name");
                                                        boolean removed = Velora.get().getAltManager().remove(name);
                                                        ChatUtil.send(removed
                                                                ? Text.literal("Removed alt " + name).formatted(Formatting.GREEN)
                                                                : Text.literal(name + " is not in the alt list").formatted(Formatting.GRAY));
                                                        return 1;
                                                    })))
                                    .then(ClientCommandManager.literal("list")
                                            .executes(context -> {
                                                var alts = Velora.get().getAltManager().getAlts();
                                                if (alts.isEmpty()) {
                                                    ChatUtil.info("Alt list is empty");
                                                } else {
                                                    String names = alts.stream()
                                                            .map(alt -> Velora.get().getAltManager().isCurrent(alt)
                                                                    ? alt.name() + " (active)" : alt.name())
                                                            .reduce((a, b) -> a + ", " + b)
                                                            .orElse("none");
                                                    ChatUtil.info("Alts (" + alts.size() + "): " + names);
                                                }
                                                return 1;
                                            }))
                                    .then(ClientCommandManager.literal("login")
                                            .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                                    .executes(context -> {
                                                        String name = StringArgumentType.getString(context, "name");
                                                        AltManager manager = Velora.get().getAltManager();
                                                        Alt alt = manager.find(name);
                                                        boolean created = false;
                                                        if (alt == null) {
                                                            switch (manager.add(name)) {
                                                                case INVALID_NAME -> {
                                                                    ChatUtil.error("Invalid nickname: use 3-16 characters (a-z, 0-9, _)");
                                                                    return 0;
                                                                }
                                                                case ALREADY_EXISTS -> {
                                                                    // Unreachable, but keeps the switch exhaustive.
                                                                }
                                                                case ADDED -> created = true;
                                                            }
                                                            alt = manager.find(name);
                                                        }
                                                        if (alt == null || !manager.login(alt)) {
                                                            ChatUtil.error("Could not log in as " + name);
                                                            return 0;
                                                        }
                                                        ChatUtil.send(Text.literal((created ? "Created and logged in as "
                                                                : "Logged in as ") + alt.name() + " (offline)")
                                                                .formatted(Formatting.GREEN));
                                                        if (MinecraftClient.getInstance().world != null) {
                                                            ChatUtil.info("Reconnect to a server to apply the new name");
                                                        }
                                                        return 1;
                                                    })))
                                    .then(ClientCommandManager.literal("gui")
                                            .executes(context -> {
                                                MinecraftClient client = MinecraftClient.getInstance();
                                                client.execute(() -> client.setScreen(new AltManagerScreen(null)));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("config")
                                    .then(ClientCommandManager.literal("key")
                                            .executes(context -> {
                                                String key = Velora.get().getConfigManager().getShareKey();
                                                MinecraftClient client = MinecraftClient.getInstance();
                                                client.keyboard.setClipboard(key);
                                                ChatUtil.info("Share key: " + key + " (copied)");
                                                return 1;
                                            }))
                                    .then(ClientCommandManager.literal("export")
                                            .executes(context -> {
                                                String code = Velora.get().getConfigManager().exportShareCode();
                                                MinecraftClient.getInstance().keyboard.setClipboard(code);
                                                ChatUtil.send(Text.literal("Share code copied to clipboard ("
                                                                + code.length() + " chars)")
                                                        .formatted(Formatting.GREEN));
                                                return 1;
                                            }))
                                    .then(ClientCommandManager.literal("import")
                                            .executes(context -> {
                                                MinecraftClient client = MinecraftClient.getInstance();
                                                String clipboard = client.keyboard.getClipboard();
                                                if (clipboard == null || clipboard.isBlank()) {
                                                    ChatUtil.error("Clipboard is empty");
                                                    return 0;
                                                }
                                                String error = Velora.get().getConfigManager().importShareCode(clipboard);
                                                if (error != null) {
                                                    ChatUtil.error("Import failed: " + error);
                                                    return 0;
                                                }
                                                ChatUtil.send(Text.literal("Config imported")
                                                        .formatted(Formatting.GREEN));
                                                return 1;
                                            }))
                                    .then(ClientCommandManager.literal("gui")
                                            .executes(context -> {
                                                MinecraftClient client = MinecraftClient.getInstance();
                                                client.execute(() -> client.setScreen(new ConfigScreen(null)));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("theme")
                                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                            .suggests(THEME_SUGGESTIONS)
                                            .executes(context -> {
                                                String name = StringArgumentType.getString(context, "name");
                                                if ("list".equalsIgnoreCase(name)) {
                                                    String list = Themes.ALL.stream()
                                                            .map(Theme::id)
                                                            .reduce((a, b) -> a + ", " + b)
                                                            .orElse("none");
                                                    ChatUtil.info("Themes: " + list);
                                                    return 1;
                                                }
                                                Theme theme = Themes.find(name);
                                                if (theme == null) {
                                                    ChatUtil.error("Unknown theme: " + name);
                                                    return 0;
                                                }
                                                Velora.get().getConfigManager().applyTheme(theme);
                                                ChatUtil.send(Text.literal("Theme: " + theme.name())
                                                        .formatted(Formatting.GREEN));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("toggle")
                                    .then(ClientCommandManager.argument("module", StringArgumentType.greedyString())
                                            .suggests(MODULE_SUGGESTIONS)
                                            .executes(context -> {
                                                String name = StringArgumentType.getString(context, "module");
                                                Module module = Velora.get().getModuleManager().getByName(name);
                                                if (module == null) {
                                                    ChatUtil.error("Unknown module: " + name);
                                                    return 0;
                                                }
                                                module.toggle();
                                                ChatUtil.send(Text.literal(module.getName() + (module.isEnabled() ? " enabled" : " disabled"))
                                                        .formatted(module.isEnabled() ? Formatting.GREEN : Formatting.RED));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("list")
                                    .executes(context -> {
                                        String modules = Stream.concat(
                                                        Velora.get().getModuleManager().getEnabledModules().stream()
                                                                .map(Module::getName),
                                                        Velora.get().getModuleManager().getModules().stream()
                                                                .filter(module -> !module.isEnabled())
                                                                .map(module -> "§8" + module.getName()))
                                                .reduce((a, b) -> a + "§7, " + b)
                                                .orElse("none");
                                        ChatUtil.send(Text.literal(modules));
                                        return 1;
                                    })));
            dispatcher.register(ClientCommandManager.literal("gs").redirect(root));
        });
    }
}
