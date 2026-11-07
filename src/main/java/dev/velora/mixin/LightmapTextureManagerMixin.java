package dev.velora.mixin;

import dev.velora.Velora;
import dev.velora.render.FullBright;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FullBright support: overrides the gamma value that the vanilla lightmap
 * update uploads to its shader.
 *
 * <p>{@code update(float)} reads three options; the gamma read is the third
 * {@code SimpleOption#getValue} call in that method (after the lightning flash
 * toggle and the darkness effect scale), hence the ordinal. The manager marks
 * itself dirty every tick, so toggling FullBright applies within one tick.</p>
 */
@Mixin(LightmapTextureManager.class)
public class LightmapTextureManagerMixin {

    @Redirect(
        method = "update(F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;",
            ordinal = 2
        )
    )
    private Object velora$fullbrightGamma(SimpleOption<?> option) {
        Double override = Velora.get() == null ? null : FullBright.gammaOverride();
        return override != null ? override : option.getValue();
    }
}
