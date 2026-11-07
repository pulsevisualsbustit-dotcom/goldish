package dev.velora.util;

import com.mojang.authlib.yggdrasil.ProfileResult;
import dev.velora.Velora;
import dev.velora.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Stores the alt list and applies an account by replacing the live session.
 *
 * <p>Only offline accounts are supported: premium (Microsoft) logins need a full
 * OAuth flow and a real access token, which a client mod cannot fetch safely, so
 * those accounts keep working through the official launcher instead.</p>
 */
public final class AltManager {

    /** Result of {@link #add(String)}, so the UI and the command can explain failures. */
    public enum AddResult {
        ADDED,
        INVALID_NAME,
        ALREADY_EXISTS
    }

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final List<Alt> alts = new ArrayList<>();

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public List<Alt> getAlts() {
        return Collections.unmodifiableList(alts);
    }

    public Alt find(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        for (Alt alt : alts) {
            if (alt.name().equalsIgnoreCase(trimmed)) {
                return alt;
            }
        }
        return null;
    }

    public AddResult add(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!isValidName(trimmed)) {
            return AddResult.INVALID_NAME;
        }
        if (find(trimmed) != null) {
            return AddResult.ALREADY_EXISTS;
        }
        alts.add(Alt.offline(trimmed));
        save();
        return AddResult.ADDED;
    }

    public boolean remove(String name) {
        Alt alt = find(name);
        if (alt == null) {
            return false;
        }
        alts.remove(alt);
        save();
        return true;
    }

    public boolean login(String name) {
        Alt alt = find(name);
        return alt != null && login(alt);
    }

    /**
     * Replaces the running session with {@code alt}.
     *
     * <p>The already-open connection keeps its old profile, so a reconnect is
     * needed before the new nickname is used by a server.</p>
     */
    public boolean login(Alt alt) {
        if (alt == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }

        Session session = new Session(alt.name(), alt.uuid(), "", Optional.empty(), Optional.empty());
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) client;
        accessor.velora$setSession(session);
        CompletableFuture<ProfileResult> emptyProfile = CompletableFuture.completedFuture(null);
        accessor.velora$setGameProfileFuture(emptyProfile);

        Velora.LOGGER.info("Switched session to offline account {}", alt.name());
        return true;
    }

    /** True when the live session already belongs to {@code alt}. */
    public boolean isCurrent(Alt alt) {
        if (alt == null) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getSession().getUsername().toLowerCase(Locale.ROOT)
                .equals(alt.name().toLowerCase(Locale.ROOT));
    }

    public String getCurrentUsername() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? "?" : client.getSession().getUsername();
    }

    public void setAlts(Collection<Alt> loaded) {
        alts.clear();
        for (Alt alt : loaded) {
            if (alt != null && isValidName(alt.name()) && find(alt.name()) == null) {
                alts.add(alt);
            }
        }
    }

    private void save() {
        if (Velora.get() != null) {
            Velora.get().getConfigManager().save();
        }
    }
}
