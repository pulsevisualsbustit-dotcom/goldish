package dev.velora.util;

import dev.velora.Velora;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatUtil {

    private ChatUtil() {
    }

    public static void info(String message) {
        send(Text.literal(message).formatted(Formatting.GRAY));
    }

    public static void success(String message) {
        send(Text.literal(message).formatted(Formatting.WHITE));
    }

    public static void error(String message) {
        send(Text.literal(message).formatted(Formatting.RED));
    }

    public static void send(Text text) {
        if (Velora.mc().player == null) {
            return;
        }
        MutableText prefix = Text.literal(Velora.NAME + " ").formatted(Formatting.GOLD)
                .append(Text.literal("» ").formatted(Formatting.DARK_GRAY));
        Velora.mc().player.sendMessage(prefix.append(text), false);
    }
}
