package dev.velora.mixin;

import dev.velora.Velora;
import dev.velora.util.InputFreeze;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the modules that have to send their packets before the movement packet
 * of the tick. Vanilla sends attacks from {@code handleInputEvents}, i.e. before
 * {@code sendMovementPackets}; doing it at {@code END_CLIENT_TICK} puts the
 * attack after the flying packet, which GrimAC's "Post" check kicks for.
 */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void velora$beforeMovement(CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            client.onPreMovement();
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void velora$afterMovement(CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            // The movement packet of this tick is out: modules that swapped
            // the player rotation for the packet window (silent rotations)
            // put the real camera rotation back before anything else - the
            // rest of the tick or a frame - can observe it.
            client.onPostMovement();
        }
    }

    /**
     * Vanilla releases every key binding the moment a screen opens (see
     * InputFreeze). Running at the head of the player tick puts the release
     * before {@code KeyboardInput.tick()} rebuilds the movement input, so the
     * physics step and the PlayerInput packet see the same zeroed input a
     * real inventory screen produces.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void velora$beforePlayerTick(CallbackInfo ci) {
        InputFreeze.onPlayerTick();
    }
}
