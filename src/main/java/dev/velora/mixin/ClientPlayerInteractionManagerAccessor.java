package dev.velora.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ClientPlayerInteractionManager#syncSelectedSlot()}.
 *
 * <p>In 1.21.11 this is <b>the</b> place that reports a hotbar change: it
 * compares the selected slot against {@code lastSelectedSlot} and emits one
 * {@code UpdateSelectedSlotC2SPacket}. Vanilla calls it at the start of every
 * interact/attack; a module that only calls {@code setSelectedSlot} and then
 * attacks in the same tick sends the attack while the server still believes
 * the old slot is held.</p>
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {

    @Invoker("syncSelectedSlot")
    void velora$syncSelectedSlot();
}