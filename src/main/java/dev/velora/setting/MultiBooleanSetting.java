package dev.velora.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A group of named checkboxes rendered as one row of chips in the ClickGUI.
 *
 * <p>The stored value is the set of enabled option names. It is persisted as a
 * JSON array, so a config written by an older build still loads when options are
 * added later (unknown entries are dropped, missing ones start unselected).</p>
 */
public class MultiBooleanSetting extends Setting<Set<String>> {

    private final Map<String, BooleanSetting> options = new LinkedHashMap<>();

    public MultiBooleanSetting(String name, String description) {
        super(name, description, new LinkedHashSet<>());
    }

    /** Adds an option; {@code selected} decides the default state. */
    public MultiBooleanSetting add(String option, boolean selected) {
        if (options.containsKey(option)) {
            return this;
        }
        BooleanSetting child = new BooleanSetting(option, selected);
        child.onUpdate(this::rebuild);
        options.put(option, child);
        if (selected) {
            value.add(option);
        }
        return this;
    }

    /** Adds every option with the same default state. */
    public MultiBooleanSetting addAll(boolean selected, String... names) {
        for (String name : names) {
            add(name, selected);
        }
        return this;
    }

    /** Keeps the aggregate value in sync when an option is toggled directly. */
    private void rebuild() {
        Set<String> next = new LinkedHashSet<>();
        options.forEach((name, child) -> {
            if (child.get()) {
                next.add(name);
            }
        });
        if (!next.equals(value)) {
            value = next;
        }
    }

    public List<String> getOptionNames() {
        return new ArrayList<>(options.keySet());
    }

    public boolean isSelected(String option) {
        BooleanSetting child = options.get(option);
        return child != null && child.get();
    }

    public void setSelected(String option, boolean selected) {
        BooleanSetting child = options.get(option);
        if (child != null) {
            child.setValue(selected);
        }
    }

    public void toggleOption(String option) {
        BooleanSetting child = options.get(option);
        if (child != null) {
            child.setValue(!child.get());
        }
    }

    public int getSelectedCount() {
        int count = 0;
        for (BooleanSetting child : options.values()) {
            if (child.get()) {
                count++;
            }
        }
        return count;
    }

    public String getDisplayValue() {
        return getSelectedCount() + "/" + options.size();
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        options.forEach((name, child) -> {
            if (child.get()) {
                array.add(name);
            }
        });
        return array;
    }

    @Override
    protected void onChanged() {
        List<String> names = getOptionNames();
        options.forEach((name, child) -> child.setValue(value.contains(name)));
        value.retainAll(names);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        Set<String> next = new LinkedHashSet<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonPrimitive()) {
                String name = entry.getAsString();
                if (options.containsKey(name)) {
                    next.add(name);
                }
            }
        }
        options.forEach((name, child) -> child.setValue(next.contains(name)));
        value = next;
    }
}