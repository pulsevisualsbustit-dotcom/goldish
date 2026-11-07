package dev.velora.combat;

import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BindSetting;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.util.ChatUtil;
import dev.velora.util.InputFreeze;
import dev.velora.util.Keys;
import dev.velora.util.PlayerUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;

/**
 * Totem on totem and the totem/head swap, played out as real inventory clicks.
 *
 * <p>The click window mimics a vanilla player working their inventory screen.
 * With the input freeze on, the keys release in one tick, sprint stops, the
 * camera pins and manual attacks are swallowed for the whole window — a real
 * screen eats all of that. The PICKUP clicks spread over ticks with jittered
 * gaps instead of leaving in one burst, and after the last click the key
 * bindings are re-polled from the physical keyboard, exactly like a real
 * screen closing. The server never sees a slot click while the input flags
 * are on: that combination is impossible in vanilla and is the classic
 * inventory-move flag.</p>
 *
 * <p>The clicks themselves are the swap: a raw {@code PlayerInventory} index
 * is not a slot id, every click goes through the {@code PlayerScreenHandler}
 * slot mapping instead, and a server that disagrees answers with its own
 * revision bump. Auto only refills an empty hand — a popped totem or an eaten
 * head — while the bind toggles between the two items, so auto and the bind
 * never fight over the hand.</p>
 */
public class AutoSwap extends Module {

    private static final String[] ITEMS = {"Тотем", "Шар"};

    /** PlayerScreenHandler slot that mirrors the offhand stack. */
    private static final int OFF_HAND_HANDLER_SLOT = PlayerScreenHandler.OFFHAND_ID;
    /** PlayerScreenHandler slot of hotbar cell 0. */
    private static final int HOTBAR_START = PlayerScreenHandler.HOTBAR_START;
    /** PlayerScreenHandler slot of the first main inventory cell (9). */
    private static final int INVENTORY_START = PlayerScreenHandler.INVENTORY_START;
    /** Size of the main inventory including the hotbar. */
    private static final int MAIN_SIZE = 36;
    /** PlayerInventory index of the offhand stack. */
    private static final int INVENTORY_OFF_HAND = 40;

    /** Hard cap for one click window, ticks. */
    private static final int MAX_PAUSE_TICKS = 16;
    /** Retry pause after a swap could not find its item, ms. */
    private static final int EMPTY_RETRY_MS = 120;
    /** Cooldown after any finished window, ms. */
    private static final int SWAP_COOLDOWN_MS = 150;

    private final ModeSetting item1 = register(new ModeSetting("Item 1", "Тотем", ITEMS));
    private final ModeSetting item2 = register(new ModeSetting("Item 2", "Шар", ITEMS));
    private final ModeSetting hand = register(new ModeSetting("Hand", "Off hand", "Off hand", "Main hand"));
    private final BooleanSetting auto = register(new BooleanSetting(
            "Auto", "Тотем на тотем: класть предмет 1, когда рука пуста (после срыва тотема)", true));
    private final BooleanSetting freeze = register(new BooleanSetting(
            "Freeze input", "На время кликов имитировать открытый инвентарь: стоп движения, камеры и атак, как живой игрок", true));
    private final BooleanSetting onlyEnchanted = register(new BooleanSetting(
            "Only enchanted", "Обменивать только зачарованные тотемы", false));
    private final BooleanSetting showSwap = register(new BooleanSetting(
            "Show swap", "Писать в чат предмет, вставший в руку", true));
    private final BindSetting swapBind = register(new BindSetting(
            "Swap bind", "Клавиша обмена между предметом 1 и 2"));

    /** Clicks waiting for their tick, in order. */
    private final ArrayDeque<Click> pendingClicks = new ArrayDeque<>();

    /** True while a click window is being played out. */
    private static boolean pausing;
    /** True while that window also emulates an open screen. */
    private static boolean inputFrozen;
    private boolean swapHeld;

    /** Ticks left before the next click, or before the window may close. */
    private int clickDelay;
    private int pauseTicks;
    private int pendingRevision;
    private long nextAutoAttempt;
    private Item pendingItem;

    private float pauseYaw;
    private float pausePitch;

    public AutoSwap() {
        super("AutoSwap", "Тотем на тотем и свап в шар через ванильные клики инвентаря", Category.COMBAT);
    }

    /** True while a click window is in flight: other swappers hold off. */
    public static boolean isPausing() {
        return pausing;
    }

    /** True while the window emulates an open screen: combat modules hold off. */
    public static boolean isFreezing() {
        return inputFrozen;
    }

    @Override
    public boolean ticksBeforeMovement() {
        // Runs at the head of sendMovementPackets: the clicks leave after the
        // STOP_SPRINTING packet of the tick, and the camera pin lands before
        // the rotation packet is built.
        return true;
    }

    @Override
    public void onEnable() {
        swapHeld = swapBind.isSet() && Keys.isDown(swapBind.get());
        endPause();
    }

    @Override
    public void onDisable() {
        endPause();
    }

    @Override
    public void onTick() {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player == null || mc.world == null) {
            endPause();
            return;
        }
        if (pausing) {
            tickPause(player);
            return;
        }
        if (mc.currentScreen != null) {
            // A real screen is on top, the user is clicking it themselves.
            // Refresh the bind edge so the release does not fire a swap the
            // moment the screen closes.
            swapHeld = swapBind.isSet() && Keys.isDown(swapBind.get());
            return;
        }
        handleSwapBind(player);
        if (auto.get()) {
            tickAuto(player);
        }
    }

    /**
     * Pins the camera while the window emulates a screen: a vanilla player
     * cannot look around with an inventory up, and rotation packets between
     * slot clicks are a textbook flag. The pin is repeated in tickPause, right
     * before the movement packet reads the angles.
     */
    @Override
    public void onFrame(float deltaTicks) {
        if (!inputFrozen || mc.player == null) {
            return;
        }
        mc.player.setYaw(pauseYaw);
        mc.player.setPitch(pausePitch);
    }

    private void handleSwapBind(ClientPlayerEntity player) {
        boolean down = swapBind.isSet() && Keys.isDown(swapBind.get());
        boolean pressed = down && !swapHeld;
        swapHeld = down;
        if (!pressed) {
            return;
        }
        // Toggle against what is actually in the hand instead of alternating
        // blindly: тотем -> шар and back, whatever else the hand holds.
        Item target = player.getInventory().getStack(handIdx(player)).isOf(resolve(item2.getValue()))
                ? resolve(item1.getValue())
                : resolve(item2.getValue());
        performSwap(player, target);
    }

    /** Refills an empty hand: the "тотем на тотем" path. */
    private void tickAuto(ClientPlayerEntity player) {
        long now = System.currentTimeMillis();
        if (now < nextAutoAttempt) {
            return;
        }
        if (!player.getInventory().getStack(handIdx(player)).isEmpty()) {
            // The hand is served. After a bind swap it holds предмет 2 on
            // purpose — dragging it back would make the bind useless.
            return;
        }
        if (!performSwap(player, resolve(item1.getValue()))) {
            // Nothing to place and the hand is still empty; keep looking.
            nextAutoAttempt = now + EMPTY_RETRY_MS + (long) (Math.random() * 60.0);
        }
    }

    private boolean performSwap(ClientPlayerEntity player, Item item) {
        if (!canSwap(player, item)) {
            return false;
        }
        int target = handIdx(player);
        int source = findSource(player, item, target);
        if (source == -1) {
            return false;
        }

        pendingClicks.clear();
        if (hand.is("Main hand")) {
            // A number keypress over a slot: one SWAP click moves the stack
            // into the selected hotbar cell.
            pendingClicks.add(new Click(source, player.getInventory().getSelectedSlot(), SlotActionType.SWAP));
        } else {
            // The PICKUP dance a human does: take the stack, drop it into the
            // offhand, put whatever was there back. An empty offhand needs
            // only two clicks — the cursor is clean after the second.
            boolean occupied = !player.getInventory().getStack(INVENTORY_OFF_HAND).isEmpty();
            pendingClicks.add(new Click(source, 0, SlotActionType.PICKUP));
            pendingClicks.add(new Click(OFF_HAND_HANDLER_SLOT, 0, SlotActionType.PICKUP));
            if (occupied) {
                pendingClicks.add(new Click(source, 0, SlotActionType.PICKUP));
            }
        }

        pendingItem = item;
        beginPause(player);
        return true;
    }

    private Item resolve(String name) {
        return "Тотем".equals(name) ? Items.TOTEM_OF_UNDYING : Items.PLAYER_HEAD;
    }

    /** Russian display name for the chat notification. */
    private String nameOf(Item item) {
        return item == Items.TOTEM_OF_UNDYING ? "тотем" : "шар";
    }

    private int handIdx(ClientPlayerEntity player) {
        return hand.is("Main hand")
                ? player.getInventory().getSelectedSlot()
                : INVENTORY_OFF_HAND;
    }

    private boolean canSwap(ClientPlayerEntity player, Item item) {
        return player.currentScreenHandler == player.playerScreenHandler
                && !player.isSpectator()
                && player.getInventory().getStack(handIdx(player)).getItem() != item;
    }

    private int findSource(ClientPlayerEntity player, Item item, int handIdx) {
        if (onlyEnchanted.get() && item == Items.TOTEM_OF_UNDYING) {
            // Some kits hand out enchanted totems, and the glint makes those
            // the better pick for a pop, so they are searched first.
            return findEnchantedTotem(player, handIdx);
        }
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (i == handIdx) {
                continue;
            }
            if (player.getInventory().getStack(i).isOf(item)) {
                return screenSlot(i);
            }
        }
        if (handIdx != INVENTORY_OFF_HAND && player.getInventory().getStack(INVENTORY_OFF_HAND).isOf(item)) {
            // Only reachable for the main hand mode: the offhand stack mirrors
            // into the hotbar through one SWAP click, no PICKUP dance needed.
            return OFF_HAND_HANDLER_SLOT;
        }
        return -1;
    }

    private int findEnchantedTotem(ClientPlayerEntity player, int handIdx) {
        for (int i = 0; i < MAIN_SIZE; i++) {
            if (i == handIdx || !player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                continue;
            }
            if (!player.getInventory().getStack(i).hasEnchantments()) {
                continue;
            }
            return screenSlot(i);
        }
        return -1;
    }

    /**
     * PlayerScreenHandler shows the inventory as its own set of slots: the
     * hotbar is 36-44, the main inventory 9-35 and the offhand is 45. Passing
     * the raw PlayerInventory index through instead clicks the wrong slot for
     * everything above the hotbar — which is how a totem swap ends up moving
     * the very same totem back and forth while the hand stays empty.
     */
    private static int screenSlot(int index) {
        if (index < 9) {
            return HOTBAR_START + index;
        }
        if (index < MAIN_SIZE) {
            return INVENTORY_START + (index - 9);
        }
        return OFF_HAND_HANDLER_SLOT;
    }

    /**
     * Opens the "screen": the input freezes for a tick and sprint stops before
     * the first click, the camera is pinned for the whole window. With the
     * freeze off only the sprint stop and the pacing remain.
     */
    private void beginPause(ClientPlayerEntity player) {
        pausing = true;
        pauseTicks = 0;
        pendingRevision = player.currentScreenHandler.getRevision();
        pauseYaw = player.getYaw();
        pausePitch = player.getPitch();
        // A tick or two of aiming the mouse at the first slot.
        clickDelay = 1 + (int) (Math.random() * 2.0);
        inputFrozen = freeze.get();
        if (inputFrozen) {
            InputFreeze.freeze();
        }
        if (player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    private void tickPause(ClientPlayerEntity player) {
        pauseTicks++;

        if (mc.currentScreen != null || !player.isAlive()) {
            // A real screen won or the player died. The clicks already sent
            // are a legal click history, the rest is the user's to finish.
            endPause();
            return;
        }
        if (pauseTicks > MAX_PAUSE_TICKS) {
            endPause();
            return;
        }

        // Pin the rotation before the movement packet is built.
        if (inputFrozen) {
            player.setYaw(pauseYaw);
            player.setPitch(pausePitch);
        }

        if (!pendingClicks.isEmpty()) {
            if (--clickDelay > 0) {
                return;
            }
            Click click = pendingClicks.poll();
            try {
                mc.interactionManager.clickSlot(
                        player.playerScreenHandler.syncId, click.slot(), click.button(), click.action(), player);
            } catch (RuntimeException ex) {
                endPause();
                return;
            }
            // One to three ticks of moving the mouse to the next slot; two to
            // four ticks of closing the screen after the last click.
            clickDelay = pendingClicks.isEmpty()
                    ? 2 + (int) (Math.random() * 3.0)
                    : 1 + (int) (Math.random() * 3.0);
            return;
        }

        // All clicks are out. A server that dislikes a click answers with a
        // revision bump; an accepted click needs no answer at all. Either way
        // the window closes after the close delay.
        if (--clickDelay <= 0 || player.currentScreenHandler.getRevision() != pendingRevision) {
            endPause();
            if (showSwap.get() && pendingItem != null
                    && player.getInventory().getStack(handIdx(player)).isOf(pendingItem)) {
                ChatUtil.success("Автосвап: " + nameOf(pendingItem));
            }
        }
    }

    /**
     * Closes the "screen": the key bindings are re-polled from the physical
     * keyboard, exactly what vanilla does when a real screen closes. With a
     * real screen still on top, its own close will do that instead.
     */
    private void endPause() {
        if (!pausing) {
            return;
        }
        pausing = false;
        pendingClicks.clear();
        pendingItem = null;
        nextAutoAttempt = System.currentTimeMillis() + SWAP_COOLDOWN_MS;
        if (inputFrozen) {
            inputFrozen = false;
            InputFreeze.unfreeze();
            if (mc.currentScreen == null) {
                KeyBinding.updatePressedStates();
            }
        }
    }

    @Override
    public String getSuffix() {
        return auto.get() ? "авто" : null;
    }

    /** One slot click of the sequence, in handler coordinates. */
    private record Click(int slot, int button, SlotActionType action) {
    }
}
