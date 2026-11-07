package dev.velora.combat.trigger;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.ItemStack;

/**
 * Dynamic attack cooldown for TriggerBot.
 *
 * <p>A fixed interval is wrong in both directions. With a fast weapon (axe,
 * sword) it throws away hits that were already fully charged; with a slow one
 * (mace, hoe, empty hand) it sends hits at 40% charge, which the server scales
 * down to a fraction of the damage and, on strict anticheats, counts as a
 * "too early" attack.</p>
 *
 * <p>Vanilla only divides the cooldown by {@code ATTACK_SPEED}, so the real
 * recovery time of a tick-synchronous bot is:</p>
 *
 * <pre>
 *   chargePerTick   = 20 / attackSpeed        (PlayerEntity#getAttackCooldownProgressPerTick)
 *   fullChargeTicks = ceil(chargePerTick)
 *   minimumTicks    = ceil(chargePerTick * minimumAttackCharge)
 * </pre>
 *
 * <p>On top of that the server can refuse a hit before its own invulnerability
 * frames of the target are over, and some servers add a sword/axe "attack delay"
 * on top of the vanilla cooldown. Both are approximated here from the target's
 * hurt time instead of from packet timing, so the interval adapts instead of
 * being a magic number.</p>
 */
public final class AttackCooldown {

    /** Vanilla hurt time: 10 ticks of i-frames per damage event on the server. */
    public static final int INVULNERABLE_TICKS = 10;

    private AttackCooldown() {
    }

    /**
     * Ticks of charge the vanilla client restores for the stack in hand.
     * {@code ATTACK_SPEED} is 4.0 for a sword (5 ticks), 1.6 for a mace
     * (12.5 ticks) and 1.0 for a hoe or an empty hand (20 ticks).
     */
    public static float chargePerTick(ClientPlayerEntity player) {
        double speed = player.getAttributeValue(EntityAttributes.ATTACK_SPEED);
        if (speed <= 0.0) {
            // A broken attribute would make the division infinite; vanilla would
            // treat the attack as never charged, so stay on the bare hand rate.
            return 20.0F;
        }
        return (float) (20.0 / speed);
    }

    /** Full charge in ticks, rounded up: the last partial tick does not restore anything. */
    public static int fullChargeTicks(ClientPlayerEntity player) {
        return Math.max(1, (int) Math.ceil(chargePerTick(player)));
    }

    /**
     * Charge the item demands before a hit is accepted at all
     * ({@code MINIMUM_ATTACK_CHARGE}, added in 1.21.5; 0 when the item has none).
     */
    public static float minimumCharge(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0F;
        }
        Float value = stack.getOrDefault(DataComponentTypes.MINIMUM_ATTACK_CHARGE, 0.0F);
        return value == null ? 0.0F : Math.max(0.0F, value);
    }

    /** Ticks needed before the item accepts a hit, at least one. */
    public static int minimumChargeTicks(ClientPlayerEntity player, ItemStack stack) {
        float minimum = minimumCharge(stack);
        if (minimum <= 0.0F) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(chargePerTick(player) * minimum));
    }

    /**
     * Milliseconds a full charge takes: the value a "dynamic" interval starts
     * from before the safety margin and the server side are applied.
     */
    public static int fullChargeMillis(ClientPlayerEntity player) {
        return Math.round(fullChargeTicks(player) * 50.0F);
    }

    /**
     * Cooldown of the stack in hand, taking a hard {@code ITEM_COOLDOWN} into
     * account (ender pearl, chorus fruit, ... are not attacks, but a module that
     * swaps items must not hammer a slot that is still locked).
     */
    public static boolean itemCoolingDown(ClientPlayerEntity player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemCooldownManager cooldowns = player.getItemCooldownManager();
        return cooldowns != null && cooldowns.isCoolingDown(stack);
    }

    /**
     * Interval (ms) the bot should keep between two hits, computed for the
     * current weapon, the configured floor/ceiling and the additional delay the
     * user attributes to the server.
     *
     * <ul>
     *     <li>{@code weaponFactor} scales the vanilla full charge
     *     ({@code 1.0} = exactly the tick the bar is full, {@code 1.1} = 10%
     *     slower).</li>
     *     <li>{@code serverDelayMs} is the extra sword/axe delay a strict server
     *     counts on top of vanilla.</li>
     *     <li>{@code minMs}/{@code maxMs} clamp the result, so a slow weapon can
     *     never produce a 3 s wait and a fast one never goes below the hurt
     *     time the bot already respects.</li>
     * </ul>
     */
    public static int interval(ClientPlayerEntity player, double weaponFactor,
                               int serverDelayMs, int minMs, int maxMs) {
        int station = Math.round(fullChargeMillis(player) * (float) weaponFactor);
        // The server ignores anything inside the target's i-frames, so 500 ms is
        // the natural floor for the interval.
        int floor = Math.max(minMs, INVULNERABLE_TICKS * 50);
        int value = station + Math.max(0, serverDelayMs);
        return Math.max(floor, Math.min(Math.max(floor, maxMs), value));
    }
}