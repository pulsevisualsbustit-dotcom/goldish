package dev.velora.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the state {@code ClientPlayerEntity} uses to decide what goes into the
 * next movement packet.
 *
 * <p>In 1.21.11 {@code ClientPlayerEntity} keeps the rotation it last shoved
 * into a movement packet in {@code lastYawClient} / {@code lastPitchClient}
 * (the old public {@code lastYaw} / {@code lastPitch} fields are gone). Vanilla
 * compares them to the live rotation to decide whether another rotation packet
 * is needed, so a module that turns the camera must keep them up to date or the
 * server keeps the old view direction during the click.</p>
 *
 * <p>{@code lastPlayerInput} is the input of the previous packet, while
 * {@code input.playerInput} is the one about to be sent. A module that changes
 * the input for one tick has to update both: leaving {@code lastPlayerInput}
 * alone makes vanilla send the same input twice and, worse, lets a released and
 * re-pressed key collapse into one packet - the classic multi-actions kick.</p>
 */
@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {

    @Accessor("lastYawClient")
    void velora$setLastYaw(float yaw);

    @Accessor("lastPitchClient")
    void velora$setLastPitch(float pitch);

    /** The input vanilla sent with the previous movement packet. */
    @Accessor("lastPlayerInput")
    PlayerInput velora$getLastPlayerInput();

    @Accessor("lastPlayerInput")
    void velora$setLastPlayerInput(PlayerInput input);
}