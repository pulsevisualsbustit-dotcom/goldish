package dev.velora.command;

import dev.velora.Velora;
import dev.velora.gui.AltManagerScreen;
import dev.velora.gui.LiteClickGuiScreen;
import dev.velora.gui.ConfigScreen;
import dev.velora.gui.ThemeScreen;
import dev.velora.misc.ClientSounds;
import dev.velora.module.Module;
import dev.velora.render.Theme;
import dev.velora.render.Themes;
import dev.velora.util.Alt;
import dev.velora.util.AltManager;
import dev.velora.util.ChatUtil;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * The client's own chat commands. They are sent in normal chat with the
 * {@link Velora#COMMAND_PREFIX} prefix ({@code .velora help}) and never reach the
 * server: the chat message is cancelled before it is sent.
 */
public final class ClientChatCommands {

    private ClientChatCommands() {
    }

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String prefix = prefixOf(message);
            if (prefix == null) {
                return true;
            }
            String body = message.substring(prefix.length()).trim();
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> handle(body));
            return false;
        });
    }

    /** @return the matched command prefix, or {@code null} for a normal chat message. */
    private static String prefixOf(String message) {
        if (message == null) {
            return null;
        }
        if (message.regionMatches(true, 0, Velora.COMMAND_PREFIX, 0, Velora.COMMAND_PREFIX.length())
                && (message.length() == Velora.COMMAND_PREFIX.length()
                || !Character.isLetterOrDigit(message.charAt(Velora.COMMAND_PREFIX.length())))) {
            return Velora.COMMAND_PREFIX;
        }
        if (message.regionMatches(true, 0, Velora.LEGACY_COMMAND_PREFIX, 0,
                Velora.LEGACY_COMMAND_PREFIX.length())
                && (message.length() == Velora.LEGACY_COMMAND_PREFIX.length()
                || !Character.isLetterOrDigit(message.charAt(Velora.LEGACY_COMMAND_PREFIX.length())))) {
            return Velora.LEGACY_COMMAND_PREFIX;
        }
        return null;
    }

    private static void handle(String body) {
        if (Velora.get() == null) {
            return;
        }
        String[] parts = body.isEmpty() ? new String[0] : body.split("\\s+");
        if (parts.length == 0) {
            help();
            return;
        }
        switch (parts[0].toLowerCase(java.util.Locale.ROOT)) {
            case "help" -> help();
            case "gui", "clickgui" -> open(new LiteClickGuiScreen(), true);
            case "hud" -> open(new dev.velora.render.HudEditorScreen(), false);
            case "themes" -> open(new ThemeScreen(null), false);
            case "configs" -> open(new ConfigScreen(null), false);
            case "alts" -> open(new AltManagerScreen(null), false);
            case "toggle", "t" -> toggle(rest(parts, 1));
            case "list", "modules" -> listModules();
            case "theme" -> theme(parts);
            case "config" -> config(parts);
            case "friend", "friends" -> friend(parts);
            case "alt" -> alt(parts);
            case "sounds" -> sounds(parts);
            case "bind" -> bind(parts);
            default -> {
                ChatUtil.error("Unknown command: " + parts[0]);
                ChatUtil.info("Use " + Velora.COMMAND_PREFIX + " help");
            }
        }
    }

    private static void help() {
        ChatUtil.send(Text.literal(Velora.NAME + " commands").formatted(Formatting.GOLD));
        ChatUtil.info(".velora toggle <module>      .velora list");
        ChatUtil.info(".velora theme <id> | list | save <name> | delete <id>");
        ChatUtil.info(".velora config save|load|list|delete <name>");
        ChatUtil.info(".velora config export|import|key");
        ChatUtil.info(".velora friend add|remove|list [name]");
        ChatUtil.info(".velora alt add|remove|list|login [name]");
        ChatUtil.info(".velora bind <module> <key>  .velora sounds on|off");
        ChatUtil.info(".velora gui | hud | themes | configs | alts");
    }

    private static void open(net.minecraft.client.gui.screen.Screen screen, boolean clickGui) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (clickGui) {
            ClientSounds.guiToggled(true);
        }
        client.setScreen(screen);
    }

    private static void toggle(String name) {
        if (name.isEmpty()) {
            ChatUtil.error("Usage: .velora toggle <module>");
            return;
        }
        Module module = Velora.get().getModuleManager().getByName(name);
        if (module == null) {
            ChatUtil.error("Unknown module: " + name);
            return;
        }
        module.toggle();
        ChatUtil.send(Text.literal(module.getName() + (module.isEnabled() ? " enabled" : " disabled"))
                .formatted(module.isEnabled() ? Formatting.GREEN : Formatting.RED));
    }

    private static void listModules() {
        StringBuilder builder = new StringBuilder();
        for (Module module : Velora.get().getModuleManager().getModules()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(module.isEnabled() ? "§a" : "§8").append(module.getName());
        }
        ChatUtil.send(Text.literal(builder.length() == 0 ? "No modules" : builder.toString()));
    }

    private static void theme(String[] parts) {
        var config = Velora.get().getConfigManager();
        if (parts.length < 2) {
            ChatUtil.info("Current theme: " + config.getTheme().name());
            ChatUtil.info("Themes: " + String.join(", ", Themes.ALL.stream().map(Theme::id).toList()));
            return;
        }
        String action = parts[1].toLowerCase(java.util.Locale.ROOT);
        if (action.equals("list")) {
            StringBuilder builder = new StringBuilder();
            for (Theme theme : Themes.ALL) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(Themes.isCustom(theme.id()) ? "§b" : "§7").append(theme.id());
            }
            ChatUtil.info("Themes: " + builder);
            return;
        }
        if (action.equals("reload")) {
            Themes.loadCustom();
            ChatUtil.success("Custom themes reloaded (" + Themes.CUSTOM.size() + ")");
            return;
        }
        if (action.equals("save")) {
            String name = rest(parts, 2);
            if (name.isEmpty()) {
                ChatUtil.error("Usage: .velora theme save <name>");
                return;
            }
            Theme saved = Themes.saveCustom(name,
                    config.getAccentColor(), config.getAccentSecondary(),
                    config.getBackgroundColor(), config.getTextColor(), config.getMutedTextColor());
            if (saved == null) {
                ChatUtil.error("Could not save the theme");
                return;
            }
            ChatUtil.success("Saved custom theme " + saved.name() + " (" + saved.id() + ")");
            return;
        }
        if (action.equals("delete")) {
            String id = rest(parts, 2);
            if (id.isEmpty()) {
                ChatUtil.error("Usage: .velora theme delete <id>");
                return;
            }
            if (!Themes.deleteCustom(id)) {
                ChatUtil.error("No custom theme with that id");
                return;
            }
            if (config.getThemeId().equalsIgnoreCase(id)) {
                config.applyTheme(Themes.DEFAULT);
            }
            ChatUtil.success("Deleted theme " + id);
            return;
        }

        Theme theme = Themes.find(rest(parts, 1));
        if (theme == null) {
            ChatUtil.error("Unknown theme: " + rest(parts, 1));
            return;
        }
        config.applyTheme(theme);
        ChatUtil.success("Theme: " + theme.name());
    }

    private static void config(String[] parts) {
        var config = Velora.get().getConfigManager();
        if (parts.length < 2) {
            ChatUtil.info("Profiles: " + String.join(", ", config.listProfiles()));
            return;
        }
        switch (parts[1].toLowerCase(java.util.Locale.ROOT)) {
            case "list" -> {
                List<String> profiles = config.listProfiles();
                ChatUtil.info(profiles.isEmpty() ? "No saved configs"
                        : "Configs (" + profiles.size() + "): " + String.join(", ", profiles));
            }
            case "save" -> {
                String name = rest(parts, 2);
                if (name.isEmpty()) {
                    ChatUtil.error("Usage: .velora config save <name>");
                    return;
                }
                if (config.saveProfile(name)) {
                    ChatUtil.success("Saved config " + dev.velora.config.ConfigManager.normalizeProfile(name));
                } else {
                    ChatUtil.error("Could not save the config");
                }
            }
            case "load" -> {
                String name = rest(parts, 2);
                if (name.isEmpty()) {
                    ChatUtil.error("Usage: .velora config load <name>");
                    return;
                }
                if (config.loadProfile(name)) {
                    ChatUtil.success("Loaded config " + dev.velora.config.ConfigManager.normalizeProfile(name));
                } else {
                    ChatUtil.error("Config not found: " + name);
                }
            }
            case "delete", "remove" -> {
                String name = rest(parts, 2);
                if (name.isEmpty()) {
                    ChatUtil.error("Usage: .velora config delete <name>");
                    return;
                }
                if (config.deleteProfile(name)) {
                    ChatUtil.success("Deleted config " + dev.velora.config.ConfigManager.normalizeProfile(name));
                } else {
                    ChatUtil.error("Config not found: " + name);
                }
            }
            case "export" -> {
                String code = config.exportShareCode();
                MinecraftClient.getInstance().keyboard.setClipboard(code);
                ChatUtil.success("Share code copied (" + code.length() + " characters)");
            }
            case "import" -> {
                String clipboard = MinecraftClient.getInstance().keyboard.getClipboard();
                if (clipboard == null || clipboard.isBlank()) {
                    ChatUtil.error("Clipboard is empty");
                    return;
                }
                String error = config.importShareCode(clipboard);
                if (error != null) {
                    ChatUtil.error("Import failed: " + error);
                    return;
                }
                ChatUtil.success("Config imported");
            }
            case "key" -> {
                MinecraftClient.getInstance().keyboard.setClipboard(config.getShareKey());
                ChatUtil.success("Share key: " + config.getShareKey() + " (copied)");
            }
            default -> ChatUtil.error("Usage: .velora config save|load|list|delete|export|import|key");
        }
    }

    private static void friend(String[] parts) {
        var friends = Velora.get().getFriendManager();
        if (parts.length < 2 || parts[1].equalsIgnoreCase("list")) {
            ChatUtil.info(friends.getFriends().isEmpty() ? "Friend list is empty"
                    : "Friends: " + String.join(", ", friends.getFriends()));
            return;
        }
        String name = rest(parts, 2);
        if (name.isEmpty()) {
            ChatUtil.error("Usage: .velora friend add|remove <name>");
            return;
        }
        if (parts[1].equalsIgnoreCase("add")) {
            ChatUtil.success(friends.add(name) ? "Added " + name : name + " is already a friend");
        } else if (parts[1].equalsIgnoreCase("remove")) {
            ChatUtil.success(friends.remove(name) ? "Removed " + name : name + " is not a friend");
        } else {
            ChatUtil.error("Usage: .velora friend add|remove|list");
        }
    }

    private static void alt(String[] parts) {
        AltManager manager = Velora.get().getAltManager();
        if (parts.length < 2 || parts[1].equalsIgnoreCase("list")) {
            List<Alt> alts = manager.getAlts();
            String names = alts.stream()
                    .map(alt -> manager.isCurrent(alt) ? alt.name() + " (active)" : alt.name())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none");
            ChatUtil.info(alts.isEmpty() ? "Alt list is empty" : "Alts: " + names);
            return;
        }
        String action = parts[1].toLowerCase(java.util.Locale.ROOT);
        String name = rest(parts, 2);
        if (!action.equals("list") && name.isEmpty()) {
            ChatUtil.error("Usage: .velora alt add|remove|login <name>");
            return;
        }
        switch (action) {
            case "add" -> {
                switch (manager.add(name)) {
                    case ADDED -> ChatUtil.success("Added alt " + name + " (offline)");
                    case ALREADY_EXISTS -> ChatUtil.info(name + " is already in the alt list");
                    case INVALID_NAME -> ChatUtil.error("Invalid nickname: use 3-16 characters (a-z, 0-9, _)");
                }
            }
            case "remove" -> ChatUtil.send(manager.remove(name)
                    ? Text.literal("Removed alt " + name).formatted(Formatting.GREEN)
                    : Text.literal(name + " is not in the alt list").formatted(Formatting.GRAY));
            case "login" -> {
                Alt alt = manager.find(name);
                if (alt == null) {
                    if (manager.add(name) == AltManager.AddResult.INVALID_NAME) {
                        ChatUtil.error("Invalid nickname: use 3-16 characters (a-z, 0-9, _)");
                        return;
                    }
                    alt = manager.find(name);
                }
                if (alt == null || !manager.login(alt)) {
                    ChatUtil.error("Could not log in as " + name);
                    return;
                }
                ChatUtil.success("Logged in as " + alt.name() + " (offline)");
            }
            default -> ChatUtil.error("Usage: .velora alt add|remove|list|login");
        }
    }

    private static void sounds(String[] parts) {
        Module module = Velora.get().getModuleManager().get(ClientSounds.class);
        if (module == null) {
            ChatUtil.error("ClientSounds is not available");
            return;
        }
        if (parts.length < 2) {
            module.toggle();
        } else if (parts[1].equalsIgnoreCase("on")) {
            module.setEnabled(true);
        } else if (parts[1].equalsIgnoreCase("off")) {
            module.setEnabled(false);
        } else {
            module.toggle();
        }
        ChatUtil.success("Client sounds " + (module.isEnabled() ? "enabled" : "disabled"));
    }

    private static void bind(String[] parts) {
        if (parts.length < 3) {
            ChatUtil.error("Usage: .velora bind <module> <key>");
            return;
        }
        String key = parts[parts.length - 1];
        String name = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        Module module = Velora.get().getModuleManager().getByName(name);
        if (module == null) {
            ChatUtil.error("Unknown module: " + name);
            return;
        }
        int code = dev.velora.util.Keys.parse(key);
        if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN && !key.equalsIgnoreCase("none")) {
            ChatUtil.error("Unknown key: " + key);
            return;
        }
        module.setKey(code);
        Velora.get().getConfigManager().save();
        ChatUtil.success(module.getName() + " bound to " + dev.velora.util.Keys.label(code));
    }

    private static String rest(String[] parts, int from) {
        if (from >= parts.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length)).trim();
    }
}
