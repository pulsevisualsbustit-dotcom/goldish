package dev.velora.misc;

import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.render.Hud;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ItemBindSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.InventoryUtil;
import dev.velora.util.Keys;
import dev.velora.util.PlayerUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * Uses every FunTime custom item from its own key bind.
 *
 * <p>The whole list is always visible in the ClickGUI, each row with the real
 * hotbar icon when the item is present and a vanilla fallback icon otherwise.
 * The bypass is deliberately conservative: only hotbar slots are used, one
 * action is ever pending, sprint is stopped right before the use packet and a
 * small configurable delay is inserted between the key press and the click.
 * The previous hotbar slot is restored one tick later.</p>
 */
public class FunTimeHelper extends Module {

    /** Official FunTime special items, with a fallback icon and name aliases. */
    private record ItemDef(String name, Item icon, String... aliases) {
    }

    private static final ItemDef[] DEFINITIONS = {
            new ItemDef("Трапка", Items.OAK_TRAPDOOR, "трап", "trap"),
            new ItemDef("Пласт", Items.STONE_BRICKS, "пласт", "wall"),
            new ItemDef("Огненный смерч", Items.FIRE_CHARGE,
                    "огненный шар", "смерч", "fireball"),
            new ItemDef("Дезориентация", Items.ENDER_EYE,
                    "дезориент", "disorientation"),
            new ItemDef("Явная пыль", Items.GLOWSTONE_DUST,
                    "пыль", "dust"),
            new ItemDef("Божья Аура", Items.NETHER_STAR,
                    "аура", "aura"),
            new ItemDef("Снежок заморозка", Items.SNOWBALL,
                    "снежок", "заморозка", "freeze")
    };

    private final ItemBindSetting[] items = new ItemBindSetting[DEFINITIONS.length];

    private final NumberSetting useDelay = register(new NumberSetting(
            "Use delay", "Задержка между нажатием и использованием (мс)", 50, 0, 250, 5));
    private final BooleanSetting randomDelay = register(new BooleanSetting(
            "Random delay", "Добавить случайные 0-40 мс к задержке", true));
    private final BooleanSetting stopSprint = register(new BooleanSetting(
            "Stop sprint", "Остановить бег перед использованием", true));
    private final BooleanSetting switchBack = register(new BooleanSetting(
            "Switch back", "Вернуться в предыдущий слот после использования", true));

    private final boolean[] bindHeld = new boolean[DEFINITIONS.length];
    private final Random random = new Random();

    /** Slot waiting for the use delay; -1 when idle. */
    private int pendingSlot = -1;
    private int pendingIndex = -1;
    private long useAt;

    /** One extra press is remembered and executed after the current use. */
    private int queuedIndex = -1;

    /** Slot to return to and the slot we switched to; -1 when nothing is pending. */
    private int restoreTo = -1;
    private int restoreFrom = -1;

    public FunTimeHelper() {
        super("FunTime Helper", "Использует кастомные предметы FunTime по биндам", Category.MISC);
        for (int index = 0; index < DEFINITIONS.length; index++) {
            ItemDef definition = DEFINITIONS[index];
            items[index] = register(new ItemBindSetting(
                    definition.name(), "Бинд для предмета " + definition.name(),
                    definition.name(), definition.icon(), definition.aliases()));
        }
    }

    @Override
    public boolean ticksBeforeMovement() {
        return true;
    }

    @Override
    public void onTick() {
        // Finish a pending switch-back first: one tick has passed since the use
        // packet, but if the player changed slots manually we leave them alone.
        tickRestore();

        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            resetHeld();
            cancelPending();
            return;
        }

        pollBinds();
        tickPendingUse();
    }

    private void pollBinds() {
        for (int index = 0; index < items.length; index++) {
            ItemBindSetting bind = items[index];
            if (!bind.isSet()) {
                bindHeld[index] = false;
                continue;
            }
            boolean down = Keys.isDown(bind.getBind());
            if (down && !bindHeld[index]) {
                requestUse(index);
            }
            bindHeld[index] = down;
        }
    }

    private void requestUse(int index) {
        if (pendingSlot != -1) {
            // Keep the latest extra press only; more than one would be noise.
            queuedIndex = index;
            return;
        }
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null) {
            return;
        }
        int slot = findHotbarSlot(player, index);
        if (slot == -1) {
            Hud.message("FunTime: " + items[index].getItemName() + " не найден в инвентаре");
            return;
        }
        pendingIndex = index;
        pendingSlot = slot;
        long delay = useDelay.getInt();
        if (randomDelay.get()) {
            delay += random.nextInt(41);
        }
        useAt = System.currentTimeMillis() + delay;
    }

    private void tickPendingUse() {
        if (pendingSlot == -1) {
            if (queuedIndex != -1) {
                int queued = queuedIndex;
                queuedIndex = -1;
                requestUse(queued);
            }
            return;
        }
        if (System.currentTimeMillis() < useAt) {
            return;
        }
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null || mc.interactionManager == null) {
            cancelPending();
            return;
        }
        // The hotbar may have changed during the delay; never use a different item.
        ItemStack stack = player.getInventory().getStack(pendingSlot);
        if (stack.isEmpty() || !items[pendingIndex].matches(stack)) {
            int replacement = findHotbarSlot(player, pendingIndex);
            if (replacement == -1) {
                cancelPending();
                return;
            }
            pendingSlot = replacement;
        }

        int previous = player.getInventory().getSelectedSlot();
        if (stopSprint.get() && player.isSprinting()) {
            // Let vanilla send the command packet itself. Sending STOP_SPRINTING
            // by hand fights the client's own sprint tracking: vanilla keeps
            // re-adding the sprint flag and the server sees a sprint start/stop
            // storm, which is exactly what the anticheat punishes.
            player.setSprinting(false);
        }
        InventoryUtil.switchToSlot(pendingSlot);
        // interactItem syncs the selected slot and sends the use packet itself.
        mc.interactionManager.interactItem(player, Hand.MAIN_HAND);

        if (switchBack.get() && previous != pendingSlot
                && player.getInventory().getSelectedSlot() == pendingSlot) {
            restoreTo = previous;
            restoreFrom = pendingSlot;
        }

        pendingSlot = -1;
        pendingIndex = -1;
        if (queuedIndex != -1) {
            int queued = queuedIndex;
            queuedIndex = -1;
            requestUse(queued);
        }
    }

    private void tickRestore() {
        if (restoreTo == -1) {
            return;
        }
        int to = restoreTo;
        int from = restoreFrom;
        restoreTo = -1;
        restoreFrom = -1;
        if (InventoryUtil.getSelectedSlot() != from) {
            return;
        }
        InventoryUtil.switchToSlot(to);
    }

    private int findHotbarSlot(ClientPlayerEntity player, int index) {
        for (int slot = 0; slot < InventoryUtil.HOTBAR_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && items[index].matches(stack)) {
                return slot;
            }
        }
        // Not in the hotbar: we do NOT pull items from the main inventory here.
        // clickSlot from a tick handler is a classic inventory-action flag.
        // The player must keep the item in the hotbar, or use AutoSwap first.
        return -1;
    }

    private void cancelPending() {
        pendingSlot = -1;
        pendingIndex = -1;
        queuedIndex = -1;
    }

    private void resetHeld() {
        for (int index = 0; index < items.length; index++) {
            bindHeld[index] = false;
        }
    }

    @Override
    public void onDisable() {
        resetHeld();
        cancelPending();
        // Never leave the player stuck on the custom-item slot after disabling.
        tickRestore();
    }
}
