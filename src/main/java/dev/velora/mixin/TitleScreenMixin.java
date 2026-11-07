package dev.velora.mixin;

import dev.velora.gui.VeloraTitleScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Подменяет ванильный титульный экран на {@link VeloraTitleScreen} до того,
 * как ваниль создаст свои виджеты. {@code VeloraTitleScreen} — обычный
 * наследник {@code Screen}, а не {@code TitleScreen}, поэтому инжект никогда
 * не рекурсит: подмена происходит ровно один раз на каждый показ ванильного
 * титула. {@code setScreen} изнутри {@code init} безопасен — фреймворк уже
 * привязал экран, а отменённая инициализация ванильных кнопок просто
 * отбрасывается вместе с самим экраном.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void velora$showMainMenu(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new VeloraTitleScreen());
        ci.cancel();
    }
}
