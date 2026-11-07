package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class NumberSetting extends Setting<Double> {

    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String name, double defaultValue, double min, double max, double step) {
        this(name, "", defaultValue, min, max, step);
    }

    public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
        super(name, description, clamp(defaultValue, min, max, step));
        this.min = min;
        this.max = max;
        this.step = step <= 0 ? 0.01 : step;
    }

    private static double clamp(double value, double min, double max, double step) {
        double clamped = Math.max(min, Math.min(max, value));
        double steps = Math.round((clamped - min) / step);
        return Math.max(min, Math.min(max, min + steps * step));
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public float getFloat() {
        return value.floatValue();
    }

    public int getInt() {
        return (int) Math.round(value);
    }

    public double get() {
        return value;
    }

    public void setFromSlider(double mouseX, double sliderLeft, double sliderWidth) {
        if (sliderWidth <= 0) {
            return;
        }
        double ratio = Math.max(0, Math.min(1, (mouseX - sliderLeft) / sliderWidth));
        setValue(min + (max - min) * ratio);
    }

    @Override
    protected void onChanged() {
        value = clamp(value, min, max, step);
    }

    public String getDisplayValue() {
        if (step >= 1) {
            return String.valueOf(getInt());
        }
        if (step >= 0.1) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            setValue(element.getAsDouble());
        }
    }
}
