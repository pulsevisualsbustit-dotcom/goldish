package dev.velora.mixin;

import dev.velora.util.ServerSprint;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the sprint flag the server actually knows about.
 *
 * <p>AutoSprint re-asserts the local sprint flag every tick, so the only way
 * to know what the server thinks is to watch the START/STOP_SPRINTING
 * commands. This is used by combat modules that need to know if the server
 * will register a hit as a critical.</p>
 */
@Mixin(ClientCommonNetworkHandler.class)
public class ClientCommonNetworkHandlerMixin {

    @Inject(method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void velora$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientCommandC2SPacket command) {
            if (command.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING) {
                ServerSprint.onCommand(true);
            } else if (command.getMode() == ClientCommandC2SPacket.Mode.STOP_SPRINTING) {
                ServerSprint.onCommand(false);
            }
        }
    }

    /** New connection, old sprint flag is meaningless. */
    @Inject(method = "onDisconnected(Lnet/minecraft/network/DisconnectionInfo;)V", at = @At("HEAD"))
    private void velora$onDisconnected(CallbackInfo ci) {
        ServerSprint.reset();
    }
}
