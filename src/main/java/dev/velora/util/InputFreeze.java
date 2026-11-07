package dev.velora.util;

import net.minecraft.client.option.KeyBinding;

/**
 * Emulates an open inventory screen at the input level.
 *
 * <p>A vanilla player cannot move, sprint, jump or turn the camera while their
 * inventory is open: opening a screen releases every key binding in one tick
 * ({@link KeyBinding#unpressAll}) and the mouse is captured by the screen until
 * it closes, at which point the bindings are re-polled from the physical
 * keyboard ({@link KeyBinding#updatePressedStates}). A cheat that clicks
 * inventory slots while its input flags stay on is the textbook inventory-move
 * pattern, so AutoSwap borrows the exact vanilla choreography for its click
 * window.</p>
 *
 * <p>The state lives in a plain holder (same pattern as {@link ServerSprint}):
 * mixin classes are not real classes at runtime, so anything another part of
 * the client needs to read must not be a static of the mixin itself.</p>
 */
public final class InputFreeze {

    private static boolean frozen;

    private InputFreeze() {
    }

    public static void freeze() {
        frozen = true;
    }

    public static void unfreeze() {
        frozen = false;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    /**
     * Called from the head of {@code ClientPlayerEntity.tick()}, i.e. before
     * {@code KeyboardInput.tick()} rebuilds the movement input from the key
     * binding states. Clearing the flags there makes both the physics step and
     * the outgoing {@code PlayerInputC2SPacket} see zeroed input — the player
     * decelerates with normal friction, exactly like a real screen.
     *
     * <p>New key events that arrive while the "screen" is open are cleared the
     * same way on the next tick, which is what a real screen does by eating
     * the events.</p>
     */
    public static void onPlayerTick() {
        if (frozen) {
            KeyBinding.unpressAll();
        }
    }
}
