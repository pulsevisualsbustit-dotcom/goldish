package dev.velora.mixin;

import dev.velora.render.NameTags;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses vanilla name tags for entities handled by the Velora NameTags
 * module, so a player never gets two labels above the head.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
    private void velora$hideVanillaLabel(Entity entity, double squaredDistanceToCamera,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (NameTags.shouldHideVanillaLabel(entity)) {
            cir.setReturnValue(false);
        }
    }
}
