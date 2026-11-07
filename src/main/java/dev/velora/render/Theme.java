package dev.velora.render;

/**
 * A colour theme applied to the whole client (ClickGUI, HUD, TargetHUD, ...).
 *
 * @param id               stable identifier persisted in the config
 * @param name             label shown in the theme picker
 * @param accent           primary accent colour (ARGB)
 * @param accentSecondary  second gradient colour (ARGB)
 * @param background       panel background (ARGB)
 * @param text             primary text colour (ARGB)
 * @param muted            secondary text colour (ARGB)
 */
public record Theme(String id, String name, int accent, int accentSecondary,
                    int background, int text, int muted) {
}
