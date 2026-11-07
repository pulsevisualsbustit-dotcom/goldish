package dev.velora.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velora.Velora;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Built-in Velora themes plus user themes loaded from {@code config/velora/themes}. */
public final class Themes {

    public static final Theme AMBER = new Theme("amber", "Amber",
            0xFFFFC64A, 0xFFFF9A2E, 0xE6101014, 0xFFF2F2F5, 0xFF9A9AA5);

    public static final Theme OCEAN = new Theme("ocean", "Ocean",
            0xFF4ADEDE, 0xFF2E9AFF, 0xE60A1318, 0xFFEFFBFC, 0xFF8FA9B3);

    public static final Theme VIOLET = new Theme("violet", "Violet",
            0xFFB98CFF, 0xFFFF5EC4, 0xE6110B18, 0xFFF6F0FF, 0xFFA997BE);

    public static final Theme CRIMSON = new Theme("crimson", "Crimson",
            0xFFFF5A5A, 0xFFFF9E3D, 0xE6160B0E, 0xFFFFF1F1, 0xFFB79A9A);

    public static final Theme MINT = new Theme("mint", "Mint",
            0xFF4ADE80, 0xFF22C1A6, 0xE60B1512, 0xFFF0FFF6, 0xFF94B4A5);

    public static final Theme MONO = new Theme("mono", "Mono",
            0xFFE9E9EF, 0xFF9A9AA5, 0xE60E0E11, 0xFFF7F7FA, 0xFF9C9CA8);

    public static final Theme SUNSET = new Theme("sunset", "Sunset",
            0xFFFF8A3D, 0xFFFF4D8D, 0xE6170F0B, 0xFFFFF4EC, 0xFFC0A08F);

    public static final Theme ICE = new Theme("ice", "Ice",
            0xFF9AD8FF, 0xFF6A8CFF, 0xE60B101A, 0xFFF2F8FF, 0xFF93A5BD);

    public static final Theme VELORA = new Theme("velora", "Velora",
            0xFFF2F2F5, 0xFFC9C9D2, 0xF00B0B0D, 0xFFF7F7FA, 0xFF9C9CA8);

    private static final List<Theme> BUILT_IN = List.of(
            AMBER, OCEAN, VIOLET, CRIMSON, MINT, MONO, SUNSET, ICE, VELORA);

    /**
     * Every selectable theme: the built-ins first, then the user files. The list
     * is mutated in place so screens holding a reference stay valid after a
     * reload.
     */
    public static final List<Theme> ALL = new ArrayList<>(BUILT_IN);

    /** Only the themes that were loaded from disk. */
    public static final List<Theme> CUSTOM = new ArrayList<>();

    public static final Theme DEFAULT = VELORA;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Themes() {
    }

    /** Directory that holds one {@code .json} file per custom theme. */
    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("velora/themes");
    }

    /**
     * (Re)loads every {@code config/velora/themes/*.json} file. Files that fail
     * to parse are skipped, so a typo never breaks the client.
     */
    public static void loadCustom() {
        CUSTOM.clear();
        Path directory = directory();
        try {
            Files.createDirectories(directory);
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
                stream.forEach(files::add);
            }
            files.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (Path path : files) {
                Theme theme = readTheme(path);
                if (theme == null) {
                    continue;
                }
                if (findBuiltIn(theme.id()) != null) {
                    Velora.LOGGER.warn("Theme {} shadows a built-in theme id and was skipped", path.getFileName());
                    continue;
                }
                CUSTOM.add(theme);
            }
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to read custom themes", exception);
        }

        ALL.clear();
        ALL.addAll(BUILT_IN);
        ALL.addAll(CUSTOM);
    }

    /** @return the theme with this id, or {@code null} when unknown. */
    public static Theme find(String id) {
        if (id == null) {
            return null;
        }
        Theme builtIn = findBuiltIn(id);
        if (builtIn != null) {
            return builtIn;
        }
        for (Theme theme : CUSTOM) {
            if (theme.id().equalsIgnoreCase(id) || theme.name().equalsIgnoreCase(id)) {
                return theme;
            }
        }
        // The client used to be called Goldish: keep the old built-in id working.
        if (id.equalsIgnoreCase("goldish")) {
            return VELORA;
        }
        return null;
    }

    private static Theme findBuiltIn(String id) {
        for (Theme theme : BUILT_IN) {
            if (theme.id().equalsIgnoreCase(id) || theme.name().equalsIgnoreCase(id)) {
                return theme;
            }
        }
        return null;
    }

    /** @return the theme with this id, falling back to {@link #DEFAULT}. */
    public static Theme byId(String id) {
        Theme theme = find(id);
        return theme != null ? theme : DEFAULT;
    }

    public static boolean isCustom(String id) {
        if (id == null) {
            return false;
        }
        return findBuiltIn(id) == null && find(id) != null;
    }

    /**
     * Writes a theme file and reloads the theme list.
     *
     * @return the saved theme, or {@code null} when the file could not be written
     */
    public static Theme saveCustom(String name, int accent, int accentSecondary,
                                   int background, int text, int muted) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            cleanName = "Custom";
        }
        String id = cleanName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        if (id.isBlank()) {
            id = "custom";
        }
        Theme existing = findBuiltIn(id);
        if (existing != null) {
            // Never overwrite a built-in id; suffix the copy instead.
            id = id + "-custom";
        }

        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("name", cleanName);
        object.addProperty("accent", accent);
        object.addProperty("accentSecondary", accentSecondary);
        object.addProperty("background", background);
        object.addProperty("text", text);
        object.addProperty("muted", muted);

        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve(id + ".json"), GSON.toJson(object));
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to save theme {}", cleanName, exception);
            return null;
        }
        loadCustom();
        return find(id);
    }

    /** @return {@code true} when the theme file was removed. */
    public static boolean deleteCustom(String id) {
        if (!isCustom(id)) {
            return false;
        }
        try {
            Files.deleteIfExists(directory().resolve(id + ".json"));
            loadCustom();
            return true;
        } catch (IOException exception) {
            Velora.LOGGER.error("Failed to delete theme {}", id, exception);
            return false;
        }
    }

    private static Theme readTheme(Path path) {
        try {
            JsonObject object = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (object == null) {
                return null;
            }
            String fallbackId = path.getFileName().toString().replaceFirst("(?i)\\.json$", "");
            String id = object.has("id") ? object.get("id").getAsString() : fallbackId;
            String name = object.has("name") ? object.get("name").getAsString() : id;
            if (id == null || id.isBlank()) {
                id = fallbackId;
            }
            return new Theme(id, name,
                    color(object, "accent", DEFAULT.accent()),
                    color(object, "accentSecondary", DEFAULT.accentSecondary()),
                    color(object, "background", DEFAULT.background()),
                    color(object, "text", DEFAULT.text()),
                    color(object, "muted", DEFAULT.muted()));
        } catch (Exception exception) {
            Velora.LOGGER.warn("Skipping invalid theme file {}: {}", path, exception.getMessage());
            return null;
        }
    }

    /** Accepts either a JSON number ({@code -12345}) or a hex string ({@code "#RRGGBB"}). */
    private static int color(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            String value = element.getAsString().trim()
                    .replace("#", "")
                    .replace("0x", "")
                    .replace("0X", "");
            long parsed = Long.parseLong(value, 16);
            if (value.length() <= 6) {
                parsed |= 0xFF000000L;
            }
            return (int) parsed;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
