package dev.velora.mixin;

import dev.velora.Velora;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame hook used by the frame-rate independent aim smoothing. It runs before
 * the camera is updated, so a rotation applied here is visible on the same frame.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"))
    private void velora$onFrame(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            client.onFrame(tickCounter.getDynamicDeltaTicks());
        }
    }
}
