package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.NumberSetting;

/**
 * FullBright: multiplies the lightmap brightness so caves and night look like
 * noon without any texture-pack tricks.
 *
 * <p>The gamma value the vanilla lightmap shader receives is overridden by the
 * <b>Brightness</b> setting (a {@link net.minecraft.client.option.SimpleOption}
 * is clamped to 0..1 by vanilla, so it cannot be raised from option code).
 * Because {@code LightmapTextureManager#update} re-runs every client tick, the
 * boost applies within one tick of toggling the module, and disabling restores
 * vanilla lighting with no leftovers.</p>
 */
public class FullBright extends Module {

    /** Gamma override value. 1.0 is vanilla maximum; 15.0 gives the classic fullbright look. */
    private final NumberSetting brightness = register(new NumberSetting("Brightness", 15.0, 1.0, 16.0, 0.5));

    public FullBright() {
        super("FullBright", "Maximum brightness in caves and at night", Category.RENDER);
    }

    /** @return the gamma value the lightmap should use, or {@code null} to keep vanilla. */
    public static Double gammaOverride() {
        FullBright module = Velora.get().getModuleManager().get(FullBright.class);
        if (module == null || !module.isEnabled()) {
            return null;
        }
        return module.brightness.get();
    }

    @Override
    public String getSuffix() {
        return String.format(java.util.Locale.ROOT, "%.0f%%", brightness.get() * 100);
    }
}
