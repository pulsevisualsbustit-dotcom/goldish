package dev.velora.mixin;

import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Screen#addDrawableChild} so the client can place its own
 * buttons on vanilla screens (title screen, multiplayer list).
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Invoker("addDrawableChild")
    <T extends Element & Drawable & Selectable> T velora$addDrawableChild(T drawable);
}
