package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velora.util.Keys;
import dev.velora.util.PlayerUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * A fixed custom item paired with its own key bind.
 *
 * <p>FunTime items are server-side custom names, so the ClickGUI row shows the
 * real hotbar icon when the player is holding the item and a vanilla fallback
 * icon otherwise. Both the item name and the bind are persisted as one nested
 * JSON object, which keeps every item self-contained in the config.</p>
 */
public class ItemBindSetting extends Setting<ItemBindSetting.Value> {

    /** Item name + GLFW bind (mouse buttons use {@link Keys#mouse(int)}). */
    public record Value(String itemName, int bind) {
        public Value {
            itemName = itemName == null ? "" : itemName;
        }
    }

    private final Item fallback;
    private final String[] aliases;

    public ItemBindSetting(String name, String description, String itemName,
                           Item fallback, String... aliases) {
        super(name, description, new Value(itemName, Keys.NONE));
        this.fallback = fallback;
        this.aliases = aliases == null ? new String[0] : aliases;
    }

    public String getItemName() {
        return value.itemName();
    }

    public int getBind() {
        return value.bind();
    }

    public boolean isSet() {
        return value.bind() != Keys.NONE;
    }

    public void setBind(int bind) {
        setValue(new Value(value.itemName(), bind));
    }

    public void clearBind() {
        if (isSet()) {
            setValue(new Value(value.itemName(), Keys.NONE));
        }
    }

    /**
     * @return the stack currently in the hotbar that matches this item, or a
     *         vanilla fallback stack so the row always has an icon.
     */
    public ItemStack icon() {
        ClientPlayerEntity player = PlayerUtil.player();
        if (player != null) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (!stack.isEmpty() && matches(stack)) {
                    return stack;
                }
            }
        }
        return new ItemStack(fallback);
    }

    /** Whether the stack carries this item's custom (or fallback) name. */
    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String wanted = normalise(value.itemName());
        if (wanted.isEmpty()) {
            return false;
        }
        Text custom = stack.getCustomName();
        if (custom != null && normalise(custom.getString()).contains(wanted)) {
            return true;
        }
        if (normalise(stack.getName().getString()).contains(wanted)) {
            return true;
        }
        for (String alias : aliases) {
            String candidate = normalise(alias);
            if (!candidate.isEmpty() && (normalise(custom == null ? "" : custom.getString()).contains(candidate)
                    || normalise(stack.getName().getString()).contains(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Formatting.strip(value);
        return stripped == null ? "" : stripped.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("item", value.itemName());
        object.addProperty("bind", value.bind());
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String itemName = object.has("item") ? object.get("item").getAsString() : value.itemName();
            int bind = object.has("bind") ? object.get("bind").getAsInt() : Keys.NONE;
            setValue(new Value(itemName, bind));
        } else if (element.isJsonPrimitive()) {
            // Older configs may have stored just the item name.
            setValue(new Value(element.getAsString(), value.bind()));
        }
    }
}
