package dev.velora.mixin;

import dev.velora.Velora;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns the open chat screen into the HUD editor: elements can be dragged with
 * the mouse while typing still works normally.
 */
@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
    private void velora$renderHudEditor(DrawContext context, int mouseX, int mouseY, float deltaTicks,
                                         CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            client.renderHudEditor(context, mouseX, mouseY);
        }
    }

    @Inject(method = "mouseClicked(Lnet/minecraft/client/gui/Click;Z)Z", at = @At("HEAD"), cancellable = true)
    private void velora$hudClicked(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        Velora client = Velora.get();
        if (client != null && client.hudMouseClicked(click.x(), click.y(), click.button())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Added override (the method itself is inherited from {@code ParentElement});
     * it reproduces the vanilla drag delegation after giving the HUD editor a chance.
     */
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        Velora client = Velora.get();
        if (client != null && client.hudMouseDragged(click.x(), click.y())) {
            return true;
        }
        Screen screen = (Screen) (Object) this;
        return screen.getFocused() != null && screen.isDragging() && click.button() == 0
                && screen.getFocused().mouseDragged(click, offsetX, offsetY);
    }

    /** Added override with the same behaviour as the vanilla {@code ParentElement} default. */
    public boolean mouseReleased(Click click) {
        Velora client = Velora.get();
        if (client != null && client.hudMouseReleased()) {
            return true;
        }
        Screen screen = (Screen) (Object) this;
        if (click.button() == 0 && screen.isDragging()) {
            screen.setDragging(false);
            if (screen.getFocused() != null) {
                return screen.getFocused().mouseReleased(click);
            }
        }
        return false;
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void velora$onRemoved(CallbackInfo ci) {
        Velora client = Velora.get();
        if (client != null) {
            client.cancelHudDrag();
        }
    }
}
