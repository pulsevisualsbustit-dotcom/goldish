package dev.velora.combat;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** Cancels outgoing attacks against players that are in the friend list. */
public class NoFriendDamage extends Module {

    public NoFriendDamage() {
        super("NoFriendDamage", "Cancels hits on friends", Category.COMBAT);
    }

    @Override
    public boolean onAttack(Entity target) {
        return target instanceof PlayerEntity player
                && Velora.get().getFriendManager().isFriend(player);
    }
}
