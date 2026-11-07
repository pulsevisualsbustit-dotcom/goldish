package dev.velora.mixin;

import dev.velora.Velora;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A click inside the player's own inventory (F / swap / hotbar pick) updates the
 * server's copy of the screen handler asynchronously. Until the answer arrives
 * the client keeps predicting the items it thinks it has, so a module that
 * looks at the inventory right after a click reads stale stacks - which is
 * exactly how a "totem swap" ends up putting the very same totem back.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(method = "clickSlot", at = @At("TAIL"))
    private void velora$afterClickSlot(int syncId, int slotId, int button, SlotActionType actionType,
                                       PlayerEntity player, CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            client.onSlotClick(actionType);
        }
    }
}