package dev.velora.util;

import dev.velora.Velora;
import dev.velora.mixin.ClientPlayerInteractionManagerAccessor;
import dev.velora.mixin.ClientPlayerEntityAccessor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Inventory helpers shared by the combat modules.
 *
 * <p>Slot switching goes through {@link #switchToSlot(int)}, which changes the
 * selected slot <b>and</b> sends the vanilla {@code UpdateSelectedSlotC2SPacket}
 * right away. Calling {@code setSelectedSlot} alone is not enough: vanilla only
 * reports the change at the start of the next tick, so any use or attack that a
 * module sends in the meantime is still addressed to the old item.</p>
 */
public final class InventoryUtil {

    /** Slots 0-8 of the hotbar. */
    public static final int HOTBAR_SIZE = 9;

    /** Hotbar plus the 27 main inventory slots. */
    public static final int MAIN_SIZE = 36;

    private InventoryUtil() {
    }

    /** @return hotbar slot (0-8) containing the item, or -1. */
    public static int findInHotbar(Item item) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    /** @return the first hotbar slot holding a stack that passes {@code filter}, or -1. */
    public static int findInHotbar(java.util.function.Predicate<ItemStack> filter) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && filter.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    public static int getSelectedSlot() {
        ClientPlayerEntity player = PlayerUtil.player();
        return player == null ? -1 : player.getInventory().getSelectedSlot();
    }

    /** @return the first main-inventory index (9-35) holding the item, or -1. */
    public static int findInMainInventory(Item item) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null) {
            return -1;
        }
        for (int slot = HOTBAR_SIZE; slot < MAIN_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Makes {@code item} the held stack. When it only sits in the main
     * inventory the stack is first moved into a hotbar slot through the player
     * screen handler, so the switch itself stays a legal vanilla action.
     *
     * @return {@code true} when the item is held afterwards.
     */
    public static boolean equipFromInventory(Item item) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null) {
            return false;
        }
        int hotbar = findInHotbar(item);
        if (hotbar != -1) {
            return switchToSlot(hotbar);
        }
        int mainSlot = findInMainInventory(item);
        if (mainSlot == -1 || Velora.mc().interactionManager == null) {
            return false;
        }
        int syncId = player.playerScreenHandler.syncId;
        int targetHotbar = player.getInventory().getSelectedSlot();
        // SWAP with button = hotbar index moves the clicked stack into that
        // hotbar slot; the held slot is used so nothing else gets displaced.
        Velora.mc().interactionManager.clickSlot(syncId,
                net.minecraft.screen.PlayerScreenHandler.INVENTORY_START + (mainSlot - HOTBAR_SIZE),
                targetHotbar, net.minecraft.screen.slot.SlotActionType.SWAP, player);
        sendSelectedSlot();
        return true;
    }

    /**
     * Selects a hotbar slot and tells the server immediately, in vanilla packet
     * order (before the next use/attack/movement packet).
     */
    public static boolean switchToSlot(int slot) {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null || slot < 0 || slot >= HOTBAR_SIZE) {
            return false;
        }
        player.getInventory().setSelectedSlot(slot);
        sendSelectedSlot();
        return true;
    }

    /**
     * Switches to the hotbar slot holding {@code item} and reports it to the
     * server. Does nothing (and returns {@code false}) when the item is missing.
     */
    public static boolean switchToItem(Item item) {
        int slot = findInHotbar(item);
        return slot != -1 && switchToSlot(slot);
    }

    /**
     * Pushes the client selected slot to the server with vanilla's own method
     * ({@code ClientPlayerInteractionManager#syncSelectedSlot}), so the packet
     * and the "last selected slot" bookkeeping stay exactly the same as when a
     * real player presses a hotbar key.
     */
    public static void sendSelectedSlot() {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null || player.networkHandler == null) {
            return;
        }
        var interactionManager = Velora.mc().interactionManager;
        if (interactionManager != null) {
            ((ClientPlayerInteractionManagerAccessor) interactionManager).velora$syncSelectedSlot();
        }
    }

    public static boolean isHolding(Item item) {
        ClientPlayerEntity player = PlayerUtil.player();
        return player != null && player.getMainHandStack().isOf(item);
    }

    public static boolean isHolding(java.util.function.Predicate<ItemStack> filter) {
        ClientPlayerEntity player = PlayerUtil.player();
        return player != null && !player.getMainHandStack().isEmpty()
                && filter.test(player.getMainHandStack());
    }

    public static boolean hasMace() {
        return findInHotbar(Items.MACE) != -1;
    }

    public static boolean isCooldownReady(Item item) {
        ClientPlayerEntity player = PlayerUtil.player();
        return player != null && !player.getItemCooldownManager().isCoolingDown(new ItemStack(item));
    }

    public static double fallDistance() {
        ClientPlayerEntity player = PlayerUtil.player();
        return player == null ? 0 : player.fallDistance;
    }

    public static boolean isFallingWithMace() {
        ClientPlayerEntity player = PlayerUtil.player();
        return player != null && !player.isOnGround() && player.fallDistance > 0f
                && isHolding(Items.MACE);
    }

    public static void attack() {
        var client = dev.velora.Velora.mc();
        if (client.interactionManager == null || client.player == null || client.crosshairTarget == null) {
            return;
        }
        if (client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY) {
            var entityHit = (net.minecraft.util.hit.EntityHitResult) client.crosshairTarget;
            client.interactionManager.attackEntity(client.player, entityHit.getEntity());
            client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
    }
}