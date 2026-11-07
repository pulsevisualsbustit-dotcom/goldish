package dev.velora.mixin;

import dev.velora.Velora;
import dev.velora.util.InputFreeze;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "getWindowTitle", at = @At("RETURN"), cancellable = true)
    private void velora$windowTitle(CallbackInfoReturnable<String> cir) {
        String title = cir.getReturnValue();
        String suffix = " | " + Velora.NAME + " " + Velora.VERSION;
        if (title != null && !title.contains(Velora.NAME)) {
            cir.setReturnValue(title + suffix);
        }
    }

    /**
     * A vanilla player cannot attack while their inventory is open — the
     * screen eats the mouse. The same window swallows manual attacks here so
     * the packet stream never mixes attacks with inventory clicks.
     */
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void velora$noAttackWhileFrozen(CallbackInfoReturnable<Boolean> cir) {
        if (InputFreeze.isFrozen()) {
            cir.setReturnValue(false);
        }
    }

    /** Same story for right clicks: using items needs a closed screen. */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void velora$noItemUseWhileFrozen(CallbackInfo ci) {
        if (InputFreeze.isFrozen()) {
            ci.cancel();
        }
    }
}
