package dev.velora.util;

/**
 * Sprint flag as the server sees it, tracked from the START/STOP commands.
 *
 * <p>AutoSprint re-asserts the local sprint flag every tick, so the only way
 * to know what the server thinks is to watch the START_SPRINTING /
 * STOP_SPRINTING commands it actually received. Combat modules use this to
 * decide whether a hit will register as a critical.</p>
 *
 * <p>The state lives here and not in the mixin because mixin classes are not
 * real classes at runtime: their static members are never merged into the
 * target and cannot be referenced from other code.</p>
 */
public final class ServerSprint {

    private static volatile boolean sprinting;

    private ServerSprint() {
    }

    /** Called from the mixin for every ClientCommandC2SPacket sent. */
    public static void onCommand(boolean startSprinting) {
        sprinting = startSprinting;
    }

    /** Whether the server thinks we are sprinting, based on the last command. */
    public static boolean isSprinting() {
        return sprinting;
    }

    /** Forget the flag, e.g. after switching servers. */
    public static void reset() {
        sprinting = false;
    }
}
