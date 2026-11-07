package dev.velora.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

/**
 * Base class for every value a module (or the HUD) can expose in the ClickGUI
 * and persist to the config file.
 */
public abstract class Setting<T> {

    private final String name;
    private final String description;

    protected T value;

    private BooleanSupplier visibility = () -> true;
    private Runnable changeListener = () -> {
    };

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.value = defaultValue;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T newValue) {
        if (java.util.Objects.equals(value, newValue)) {
            return;
        }
        this.value = newValue;
        onChanged();
        changeListener.run();
    }

    /** Runs extra logic after {@link #setValue(Object)} (used by number settings for clamping). */
    protected void onChanged() {
    }

    @SuppressWarnings("unchecked")
    public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
        this.visibility = condition;
        return (S) this;
    }

    public Setting<T> onUpdate(Runnable listener) {
        this.changeListener = listener;
        return this;
    }

    public boolean isVisible() {
        return visibility.getAsBoolean();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);
}
