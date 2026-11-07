package dev.velora.render;

import dev.velora.Velora;
import dev.velora.util.PlayerUtil;
import net.minecraft.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived line-of-sight cache shared by NameTags and ESP.
 *
 * <p>Ray-casting every entity on every frame is wasteful, and the result barely
 * changes between frames. A hit is cached for 120 ms, which is long enough to
 * remove the cost and short enough to react when a player steps out from behind
 * a wall.</p>
 */
public final class VisibilityCache {

    private static final long CACHE_MILLIS = 120L;

    private final Map<UUID, Vision> cache = new HashMap<>();

    public boolean isVisible(LivingEntity entity) {
        UUID id = entity.getUuid();
        long now = System.currentTimeMillis();
        Vision cached = cache.get(id);
        if (cached != null && now < cached.until()) {
            return cached.visible();
        }

        var player = Velora.mc().player;
        boolean visible = player != null && PlayerUtil.canSeePoint(player.getEyePos(),
                entity.getBoundingBox().getCenter());
        cache.put(id, new Vision(visible, now + CACHE_MILLIS));
        if (cache.size() > 256) {
            cache.clear();
        }
        return visible;
    }

    public void clear() {
        cache.clear();
    }

    private record Vision(boolean visible, long until) {
    }
}
