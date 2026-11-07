package dev.velora.combat.trigger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * Finds the entity under the crosshair and checks it against the selected
 * {@link TargetType target types}. Ported from the standalone TriggerBot.
 */
public final class TargetFinder {

    private final MinecraftClient client = MinecraftClient.getInstance();

    /** @return the entity under the crosshair, or {@code null} when it is not a valid target. */
    public Entity getCrossTarget(TargetType... targetTypes) {
        if (client.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult hitResult) {
            Entity entity = hitResult.getEntity();
            if (hasAccessTarget(entity, targetTypes)) {
                return entity;
            }
        }
        return null;
    }

    public boolean hasAccessTarget(Entity entity, TargetType... targetTypes) {
        if (entity == null || entity == client.player) {
            return false;
        }
        // Friends are never targets, even if a player type is selected.
        if (dev.velora.util.PlayerUtil.isFriend(entity)) {
            return false;
        }
        for (TargetType type : targetTypes) {
            if (type.matches(entity)) {
                return true;
            }
        }
        return false;
    }
}