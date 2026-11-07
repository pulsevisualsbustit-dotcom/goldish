package dev.velora.combat.trigger;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The kinds of entity the ported TriggerBot is allowed to hit, each with the
 * label shown in its multi-select setting.
 */
public enum TargetType {

    PLAYER("Players"),
    MOB("Mobs"),
    ANIMAL("Animals"),
    STAND("Armor stands");

    private final String targetName;

    TargetType(String targetName) {
        this.targetName = targetName;
    }

    public String getTargetName() {
        return targetName;
    }

    public boolean matches(Entity entity) {
        return switch (this) {
            case PLAYER -> entity instanceof PlayerEntity;
            case MOB -> entity instanceof MobEntity;
            case ANIMAL -> entity instanceof AnimalEntity;
            case STAND -> entity instanceof ArmorStandEntity;
        };
    }
}