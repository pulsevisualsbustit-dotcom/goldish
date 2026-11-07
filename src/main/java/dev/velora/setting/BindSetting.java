package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.velora.util.Keys;

/**
 * A key or mouse bind stored as a GLFW code (mouse buttons use {@link Keys#mouse(int)}).
 */
public class BindSetting extends Setting<Integer> {

    public BindSetting(String name, String description) {
        super(name, description, Keys.NONE);
    }

    public int get() {
        return value;
    }

    public boolean isSet() {
        return value != Keys.NONE;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            setValue(element.getAsInt());
        }
    }
}
