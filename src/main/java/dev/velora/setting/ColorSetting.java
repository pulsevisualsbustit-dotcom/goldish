package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** ARGB colour setting. */
public class ColorSetting extends Setting<Integer> {

    public ColorSetting(String name, int defaultArgb) {
        this(name, "", defaultArgb);
    }

    public ColorSetting(String name, String description, int defaultArgb) {
        super(name, description, defaultArgb);
    }

    public int getArgb() {
        return value;
    }

    public int getRgb() {
        return value & 0x00FFFFFF;
    }

    public ColorSetting alpha(int alpha) {
        setValue((value & 0x00FFFFFF) | ((alpha & 0xFF) << 24));
        return this;
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
