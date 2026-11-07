package dev.velora.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-order animation state for the animated HUD lists.
 *
 * <p>Two properties keyed by a stable row id: the rise/fall {@code progress}
 * (0 = hidden, 1 = fully shown) and an optional horizontal {@code shift} that
 * lets rows flow from their old position to a new one without a layout jump.
 * The state lives in one shared table, so adding or removing a row never
 * disturbs the rows around it.</p>
 */
final class ItemAnimation {

    private static final Map<Integer, Float> PROGRESS = new HashMap<>();
    private static final Map<Integer, Float> SHIFT = new HashMap<>();

    private ItemAnimation() {
    }

    static float progress(int id) {
        return PROGRESS.getOrDefault(id, 0f);
    }

    static void progress(int id, float value) {
        PROGRESS.put(id, value);
    }

    /** @return {@code true} when the id already has animation state. */
    static boolean known(int id) {
        return PROGRESS.containsKey(id);
    }

    /** Extra horizontal offset in pixels (positive = still to the right of its slot). */
    static float shift(int id) {
        return SHIFT.getOrDefault(id, 0f);
    }

    static void shift(int id, float value) {
        SHIFT.put(id, value);
    }
}
