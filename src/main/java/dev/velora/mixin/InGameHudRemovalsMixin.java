package dev.velora.mixin;

import dev.velora.render.Removals;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removals support: every injection skips one vanilla HUD element while the
 * Removals module wants it hidden. See {@link Removals#isRemoving(String)}.
 */
@Mixin(InGameHud.class)
public class InGameHudRemovalsMixin {

    private static final String CTX = "Lnet/minecraft/client/gui/DrawContext;";
    private static final String RTC = "Lnet/minecraft/client/render/RenderTickCounter;";

    @Inject(method = "renderCrosshair(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noCrosshair(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("crosshair")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbar(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noHotbar(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("hotbar")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStatusBars(" + CTX + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noStatusBars(DrawContext context, CallbackInfo ci) {
        if (Removals.isRemoving("statusBars")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHeldItemTooltip(" + CTX + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        if (Removals.isRemoving("heldItemTooltip")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStatusEffectOverlay(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noEffects(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("effects")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderScoreboardSidebar(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noScoreboard(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("scoreboard")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderScoreboardSidebar(" + CTX + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true)
    private void velora$noScoreboardObjective(DrawContext context, ScoreboardObjective objective, CallbackInfo ci) {
        if (Removals.isRemoving("scoreboard")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBossBarHud(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noBossBar(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("bossBar")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPlayerList(" + CTX + RTC + ")V", at = @At("HEAD"), cancellable = true)
    private void velora$noPlayerList(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (Removals.isRemoving("playerList")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderVignetteOverlay(" + CTX + "Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void velora$noVignette(DrawContext context, Entity entity, CallbackInfo ci) {
        if (Removals.isRemoving("vignette")) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldShowExperienceBar()Z", at = @At("HEAD"), cancellable = true)
    private void velora$noExperienceBar(CallbackInfoReturnable<Boolean> cir) {
        if (Removals.isRemoving("experienceBar")) {
            cir.setReturnValue(false);
        }
    }
}
