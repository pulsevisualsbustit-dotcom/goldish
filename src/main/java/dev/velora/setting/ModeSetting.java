package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.List;

public class ModeSetting extends Setting<String> {

    private final List<String> modes;

    public ModeSetting(String name, String defaultValue, String... modes) {
        super(name, "", defaultValue);
        this.modes = Arrays.asList(modes);
        if (!this.modes.contains(value)) {
            this.value = this.modes.get(0);
        }
    }

    public boolean is(String mode) {
        return value.equalsIgnoreCase(mode);
    }

    public List<String> getModes() {
        return modes;
    }

    public void cycle() {
        int index = modes.indexOf(value);
        setValue(modes.get((index + 1) % modes.size()));
    }

    public void cycleBack() {
        int index = modes.indexOf(value);
        setValue(modes.get((index - 1 + modes.size()) % modes.size()));
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            String raw = element.getAsString();
            if (modes.contains(raw)) {
                setValue(raw);
            }
        }
    }
}
