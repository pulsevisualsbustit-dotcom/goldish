package dev.velora.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.render.Theme;
import dev.velora.render.Themes;
import dev.velora.setting.Setting;
import dev.velora.util.Alt;
import dev.velora.util.AltManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single JSON config: theme, module states, every setting value, HUD/GUI layout
 * and the friend list. It also owns the automatically generated share key used
 * by {@link ShareCode} to exchange configs between users.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("velora/config.json");
    private final Path profilesDirectory = FabricLoader.getInstance().getConfigDir().resolve("velora/configs");

    private final Map<String, float[]> panelPositions = new HashMap<>();
    private final Map<String, float[]> hudPositions = new HashMap<>();

    private String themeId = Themes.DEFAULT.id();
    private int accentColor = Themes.DEFAULT.accent();
    private int accentSecondary = Themes.DEFAULT.accentSecondary();
    private int backgroundColor = Themes.DEFAULT.background();
    private int textColor = Themes.DEFAULT.text();
    private int mutedTextColor = Themes.DEFAULT.muted();

    private String shareKey = "";

    /** While {@code true}, saves are ignored so loading / importing does not rewrite the file. */
    private boolean loading;

    /** {@code true} while a config (file or share code) is being applied. */
    public boolean isLoading() {
        return loading;
    }

    public void load() {
        if (!Files.exists(file)) {
            enableDefaults();
            ensureShareKey();
            save();
            return;
        }
        loading = true;
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                return;
            }

            if (root.has("shareKey") && ShareCode.isValidKey(root.get("shareKey").getAsString())) {
                shareKey = root.get("shareKey").getAsString().toUpperCase(java.util.Locale.ROOT);
            }

            applySettings(root);
            applySocial(root);
        } catch (Exception exception) {
            Velora.LOGGER.error("Failed to load config", exception);
        } finally {
            loading = false;
        }

        if (!ShareCode.isValidKey(shareKey)) {
            shareKey = ShareCode.generateKey();
            save();
        }
    }

    /** First launch: show the HUD modules, so the client is visible without opening the GUI. */
    private void enableDefaults() {
        for (Module module : Velora.get().getModuleManager().getModules()) {
            if (module.getCategory() == Category.RENDER && !(module instanceof dev.velora.render.Removals)) {
                module.setEnabled(true);
            }
        }
    }

    /** Applies theme, colours, layout and module settings from a settings object. */
    private void applySettings(JsonObject root) {
        if (root.has("theme")) {
            applyTheme(Themes.byId(root.get("theme").getAsString()), false);
        }
        if (root.has("accent")) {
            accentColor = root.get("accent").getAsInt();
        }
        if (root.has("accentSecondary")) {
            accentSecondary = root.get("accentSecondary").getAsInt();
        }
        if (root.has("background")) {
            backgroundColor = root.get("background").getAsInt();
        }
        if (root.has("text")) {
            textColor = root.get("text").getAsInt();
        }
        if (root.has("mutedText")) {
            mutedTextColor = root.get("mutedText").getAsInt();
        }

        if (root.has("panels")) {
            JsonObject panels = root.getAsJsonObject("panels");
            panelPositions.clear();
            for (String key : panels.keySet()) {
                JsonArray array = panels.getAsJsonArray(key);
                if (array.size() >= 2) {
                    panelPositions.put(key, new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat()});
                }
            }
        }

        if (root.has("hud")) {
            JsonObject hud = root.getAsJsonObject("hud");
            hudPositions.clear();
            for (String key : hud.keySet()) {
                JsonArray array = hud.getAsJsonArray(key);
                if (array.size() >= 2) {
                    hudPositions.put(key, new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat()});
                }
            }
        }

        if (root.has("modules")) {
            JsonObject modules = root.getAsJsonObject("modules");
            for (Module module : Velora.get().getModuleManager().getModules()) {
                JsonElement element = modules.get(module.getName());
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (object.has("key")) {
                    module.setKey(object.get("key").getAsInt());
                }
                if (object.has("hold")) {
                    module.setHoldMode(object.get("hold").getAsBoolean());
                }
                if (object.has("settings")) {
                    JsonObject settings = object.getAsJsonObject("settings");
                    for (Setting<?> setting : module.getSettings()) {
                        JsonElement value = settings.get(setting.getName());
                        if (value != null) {
                            setting.fromJson(value);
                        }
                    }
                }
                module.setEnabled(object.has("enabled") && object.get("enabled").getAsBoolean());
            }
        }
    }

    /** Serialisable settings shared by the config file and the share codes. */
    private JsonObject buildSettings() {
        JsonObject root = new JsonObject();
        root.addProperty("theme", themeId);
        root.addProperty("accent", accentColor);
        root.addProperty("accentSecondary", accentSecondary);
        root.addProperty("background", backgroundColor);
        root.addProperty("text", textColor);
        root.addProperty("mutedText", mutedTextColor);

        JsonObject panels = new JsonObject();
        panelPositions.forEach((key, value) -> {
            JsonArray array = new JsonArray();
            array.add(value[0]);
            array.add(value[1]);
            panels.add(key, array);
        });
        root.add("panels", panels);

        JsonObject hud = new JsonObject();
        hudPositions.forEach((key, value) -> {
            JsonArray array = new JsonArray();
            array.add(value[0]);
            array.add(value[1]);
            hud.add(key, array);
        });
        root.add("hud", hud);

        JsonObject modules = new JsonObject();
        for (Module module : Velora.get().getModuleManager().getModules()) {
            JsonObject object = new JsonObject();
            object.addProperty("enabled", module.isEnabled());
            object.addProperty("key", module.getKey());
            object.addProperty("hold", module.isHoldMode());

            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                settings.add(setting.getName(), setting.toJson());
            }
            object.add("settings", settings);

            modules.add(module.getName(), object);
        }
        root.add("modules", modules);
        return root;
    }

    public void save() {
        if (loading || Velora.get() == null) {
            return;
        }
        JsonObject root = buildSettings();
        root.addProperty("shareKey", getShareKey());
        appendSocial(root);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to save config", exception);
        }
    }

    /** Loads friends and alts from a config-shaped JSON object. */
    private void applySocial(JsonObject root) {
        if (root.has("friends")) {
            var friends = new java.util.LinkedHashSet<String>();
            for (JsonElement element : root.getAsJsonArray("friends")) {
                friends.add(element.getAsString());
            }
            Velora.get().getFriendManager().setFriends(friends);
        }

        if (root.has("alts")) {
            List<Alt> alts = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("alts")) {
                if (element.isJsonPrimitive()) {
                    alts.add(Alt.offline(element.getAsString()));
                    continue;
                }
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (!object.has("name")) {
                    continue;
                }
                String name = object.get("name").getAsString();
                UUID uuid = object.has("uuid") ? parseUuid(object.get("uuid").getAsString()) : null;
                alts.add(uuid == null ? Alt.offline(name) : new Alt(name, uuid));
            }
            Velora.get().getAltManager().setAlts(alts);
        }
    }

    /** Writes the friend list and the alt list into a config-shaped JSON object. */
    private void appendSocial(JsonObject root) {
        JsonArray friends = new JsonArray();
        Velora.get().getFriendManager().getFriends().forEach(friends::add);
        root.add("friends", friends);

        JsonArray alts = new JsonArray();
        for (Alt alt : Velora.get().getAltManager().getAlts()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", alt.name());
            object.addProperty("uuid", alt.uuid().toString());
            alts.add(object);
        }
        root.add("alts", alts);
    }

    // --------------------------------------------------------------- profiles

    /** Directory that stores the named config profiles. */
    public Path getProfilesDirectory() {
        return profilesDirectory;
    }

    public List<String> listProfiles() {
        if (!Files.isDirectory(profilesDirectory)) {
            return List.of();
        }
        try (var stream = Files.list(profilesDirectory)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".json"))
                    .map(path -> path.getFileName().toString()
                            .replaceFirst("(?i)\\.json$", ""))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to list config profiles", exception);
            return List.of();
        }
    }

    public boolean profileExists(String name) {
        return !normalizeProfile(name).isEmpty() && Files.exists(profileFile(name));
    }

    /** Saves the current settings as {@code config/velora/configs/<name>.json}. */
    public boolean saveProfile(String name) {
        String clean = normalizeProfile(name);
        if (clean.isEmpty()) {
            return false;
        }
        JsonObject root = buildSettings();
        appendSocial(root);
        try {
            Files.createDirectories(profilesDirectory);
            Files.writeString(profilesDirectory.resolve(clean + ".json"), GSON.toJson(root));
            return true;
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to save profile {}", clean, exception);
            return false;
        }
    }

    /** Applies a saved profile and makes it the active config. */
    public boolean loadProfile(String name) {
        String clean = normalizeProfile(name);
        Path profile = profilesDirectory.resolve(clean + ".json");
        if (clean.isEmpty() || !Files.exists(profile)) {
            return false;
        }
        loading = true;
        try {
            JsonObject root = GSON.fromJson(Files.readString(profile), JsonObject.class);
            if (root == null) {
                return false;
            }
            applySettings(root);
            applySocial(root);
        } catch (Exception exception) {
            Velora.LOGGER.error("Failed to load profile {}", clean, exception);
            return false;
        } finally {
            loading = false;
        }
        save();
        return true;
    }

    public boolean deleteProfile(String name) {
        String clean = normalizeProfile(name);
        if (clean.isEmpty()) {
            return false;
        }
        try {
            return Files.deleteIfExists(profilesDirectory.resolve(clean + ".json"));
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to delete profile {}", clean, exception);
            return false;
        }
    }

    private Path profileFile(String name) {
        return profilesDirectory.resolve(normalizeProfile(name) + ".json");
    }

    /** Keeps profile names filename-safe so {@code .velora config load my cfg} works. */
    public static String normalizeProfile(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_\\- ]", "_");
    }

    // ------------------------------------------------------------- share codes

    /** Key generated automatically on first launch; embedded in exported codes. */
    public String getShareKey() {
        ensureShareKey();
        return shareKey;
    }

    public void setShareKey(String key) {
        if (ShareCode.isValidKey(key)) {
            shareKey = key.toUpperCase(java.util.Locale.ROOT);
        }
    }

    private void ensureShareKey() {
        if (!ShareCode.isValidKey(shareKey)) {
            shareKey = ShareCode.generateKey();
        }
    }

    /** Serialises the current settings (without alts/friends) into a share code. */
    public String exportShareCode() {
        return ShareCode.encode(getShareKey(), buildSettings());
    }

    /**
     * Applies a share code.
     *
     * @return {@code null} on success, otherwise a human readable error
     */
    public String importShareCode(String code) {
        JsonObject payload;
        try {
            payload = ShareCode.decode(code);
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }

        loading = true;
        try {
            applySettings(payload);
        } finally {
            loading = false;
        }
        save();
        return null;
    }

    public String previewShareCode(String code) {
        try {
            ShareCode.decode(code);
            return ShareCode.keyOf(code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    // ---------------------------------------------------------------- themes

    public Theme getTheme() {
        return Themes.byId(themeId);
    }

    public String getThemeId() {
        return themeId;
    }

    public void applyTheme(Theme theme) {
        applyTheme(theme, true);
    }

    private void applyTheme(Theme theme, boolean persist) {
        if (theme == null) {
            return;
        }
        themeId = theme.id();
        accentColor = theme.accent();
        accentSecondary = theme.accentSecondary();
        backgroundColor = theme.background();
        textColor = theme.text();
        mutedTextColor = theme.muted();
        if (persist && !loading) {
            save();
        }
    }

    // --------------------------------------------------------------- positions

    public float[] getPanelPosition(Category category, float defaultX, float defaultY) {
        return panelPositions.getOrDefault(category.name(), new float[]{defaultX, defaultY});
    }

    public void setPanelPosition(Category category, float x, float y) {
        panelPositions.put(category.name(), new float[]{x, y});
    }

    public float[] getHudPosition(String name, float defaultX, float defaultY) {
        float[] stored = hudPositions.get(name);
        return stored != null ? stored : new float[]{defaultX, defaultY};
    }

    public boolean hasHudPosition(String name) {
        return hudPositions.containsKey(name);
    }

    public void setHudPosition(String name, float x, float y) {
        hudPositions.put(name, new float[]{x, y});
    }

    // ---------------------------------------------------------------- colours

    public int getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(int accentColor) {
        this.accentColor = accentColor;
    }

    public int getAccentSecondary() {
        return accentSecondary;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public int getTextColor() {
        return textColor;
    }

    public int getMutedTextColor() {
        return mutedTextColor;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // Accept undashed UUIDs as well.
            try {
                String hex = value.replace("-", "");
                if (hex.length() != 32) {
                    return null;
                }
                return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16),
                        Long.parseUnsignedLong(hex.substring(16), 16));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
