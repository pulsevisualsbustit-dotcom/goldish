package dev.velora.util;

import net.minecraft.entity.player.PlayerEntity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class FriendManager {

    private final Set<String> friends = new LinkedHashSet<>();

    public boolean isFriend(PlayerEntity player) {
        return player != null && isFriend(player.getName().getString());
    }

    public boolean isFriend(String name) {
        return name != null && friends.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean add(String name) {
        boolean added = friends.add(name.toLowerCase(Locale.ROOT));
        if (added) {
            save();
        }
        return added;
    }

    public boolean remove(String name) {
        boolean removed = friends.remove(name.toLowerCase(Locale.ROOT));
        if (removed) {
            save();
        }
        return removed;
    }

    public boolean toggle(String name) {
        return isFriend(name) ? !remove(name) : !add(name);
    }

    public Set<String> getFriends() {
        return Collections.unmodifiableSet(friends);
    }

    public void setFriends(Set<String> names) {
        friends.clear();
        for (String name : names) {
            friends.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private void save() {
        if (dev.velora.Velora.get() != null) {
            dev.velora.Velora.get().getConfigManager().save();
        }
    }
}
