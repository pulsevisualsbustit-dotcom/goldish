package dev.velora.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A single stored account.
 *
 * <p>Velora stores offline ("cracked") accounts: the session is built locally
 * from the nickname and the vanilla offline UUID, so those accounts can only
 * join servers that run with {@code online-mode=false}.</p>
 */
public record Alt(String name, UUID uuid) {

    /** Builds an offline account, using the same UUID the vanilla server would use. */
    public static Alt offline(String name) {
        return new Alt(name, offlineUuid(name));
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** First eight characters of the UUID, handy as a short identifier in the UI. */
    public String shortUuid() {
        return uuid.toString().replace("-", "").substring(0, 8);
    }
}
