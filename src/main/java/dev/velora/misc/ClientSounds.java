package dev.velora.misc;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * Quiet UI sound feedback.
 *
 * <p>Everything is built from soft chimes instead of the old note-block ping:
 * a short bell for "on", a warm harp note for "off" and a low airy amethyst
 * chime around the ClickGUI. Volumes are deliberately low so the cues sit
 * under the game audio instead of cutting through it.</p>
 */
public final class ClientSounds extends Module {

    /** Toggles that happen in one tick share a single, softer cue. */
    private static final long COALESCE_MS = 90L;
    private static long lastModuleSoundAt;

    private final BooleanSetting moduleSounds =
            register(new BooleanSetting("Modules", "Звук при включении модуля", true));
    private final BooleanSetting guiSounds =
            register(new BooleanSetting("GUI", "Звук открытия и закрытия меню", true));
    private final NumberSetting volume =
            register(new NumberSetting("Volume", "Громкость звуков в процентах", 45, 0, 100, 1));

    public ClientSounds() {
        super("ClientSounds", "Plays a soft sound on module and GUI state changes", Category.MISC);
        startEnabled();
    }

    /** Called from {@link Module#setEnabled(boolean)}. */
    public static void moduleToggled(boolean enabled) {
        ClientSounds sounds = instance();
        if (sounds == null || !sounds.isEnabled() || !sounds.moduleSounds.get()) {
            return;
        }
        // A bind can toggle several modules at once: play one cue for the batch
        // instead of stacking a dozen chimes on top of each other.
        long now = System.currentTimeMillis();
        if (now - lastModuleSoundAt < COALESCE_MS) {
            return;
        }
        lastModuleSoundAt = now;

        float base = sounds.baseVolume();
        if (enabled) {
            // Soft glass bell, a little above the middle of its range.
            play(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.42f, base * 0.7f);
        } else {
            // Warm harp pluck, slightly lower, so "off" is gentler than "on".
            play(SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), 0.98f, base * 0.6f);
        }
    }

    /** Called when the ClickGUI is opened or closed. */
    public static void guiToggled(boolean open) {
        ClientSounds sounds = instance();
        if (sounds == null || !sounds.isEnabled() || !sounds.guiSounds.get()) {
            return;
        }
        float base = sounds.baseVolume();
        // Amethyst is the softest block tone in the game: a quiet, airy chime.
        play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, open ? 1.28f : 0.95f, base * 0.5f);
    }

    private static ClientSounds instance() {
        Velora client = Velora.get();
        if (client == null || client.getConfigManager() == null || client.getConfigManager().isLoading()) {
            return null;
        }
        return client.getModuleManager() == null ? null
                : client.getModuleManager().get(ClientSounds.class);
    }

    private float baseVolume() {
        return volume.getInt() / 100f;
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        if (sound == null || volume <= 0.0001f) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.getSoundManager().play(PositionedSoundInstance.ui(sound, pitch, Math.min(1f, volume)));
    }
}
