package dev.velora.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Free-form text value, used for identifiers that cannot be a slider or a mode
 * list (the Discord application id is the first user).
 *
 * <p>The ClickGUI edits it in place: click the row, type, press Enter. The value
 * is sanitised on every change so a malformed id can never reach the IPC pipe.</p>
 */
public class TextSetting extends Setting<String> {

    private final int maxLength;
    private final boolean digitsOnly;

    public TextSetting(String name, String description, String defaultValue,
                       int maxLength, boolean digitsOnly) {
        super(name, description, defaultValue == null ? "" : defaultValue);
        this.maxLength = Math.max(1, maxLength);
        this.digitsOnly = digitsOnly;
        sanitise();
    }

    public int getMaxLength() {
        return maxLength;
    }

    public boolean isDigitsOnly() {
        return digitsOnly;
    }

    /** Whether the current value may be accepted as-is (used by the GUI hint). */
    public boolean isComplete() {
        return !value.isEmpty() && (!digitsOnly || value.length() >= 17);
    }

    @Override
    protected void onChanged() {
        sanitise();
    }

    private void sanitise() {
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char character = value.charAt(i);
            if (digitsOnly && !Character.isDigit(character)) {
                continue;
            }
            if (character < 32) {
                continue;
            }
            builder.append(character);
        }
        value = builder.toString();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            setValue(element.getAsString());
        }
    }
}
