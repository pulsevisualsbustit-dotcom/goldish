package dev.velora.mixin;

import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/**
 * Lets the AltManager swap the live session without restarting the game.
 *
 * <p>The vanilla {@code session} field is final, so it needs {@link Mutable}; the
 * cached game profile future is replaced with an empty one, which makes
 * {@code MinecraftClient#getGameProfile} fall back to the new session instead of
 * returning the account the game was launched with.</p>
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

    @Mutable
    @Accessor("session")
    void velora$setSession(Session session);

    @Mutable
    @Accessor("gameProfileFuture")
    void velora$setGameProfileFuture(CompletableFuture<ProfileResult> future);

    /**
     * Runs vanilla's right-click path for the currently held item, so custom
     * server items behave exactly as they do when the player uses them.
     */
    @Invoker("doItemUse")
    void velora$doItemUse();
}
