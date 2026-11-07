package dev.velora.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Keyboard / mouse bind helpers shared by module toggles and in-module binds
 * such as AutoSwap's swap key.
 */
public final class Keys {

    public static final int NONE = GLFW.GLFW_KEY_UNKNOWN;
    private static final int MOUSE = 1000;

    private Keys() {
    }

    public static int mouse(int button) {
        return MOUSE + button;
    }

    public static boolean isDown(int bind) {
        if (bind == NONE) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }
        if (bind >= MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), bind - MOUSE) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(client.getWindow(), bind);
    }

    /** Parses a key name such as {@code space}, {@code rshift}, {@code f5} or {@code m1}. */
    public static int parse(String name) {
        if (name == null) {
            return NONE;
        }
        String key = name.trim();
        if (key.isEmpty() || key.equalsIgnoreCase("none") || key.equalsIgnoreCase("clear")) {
            return NONE;
        }
        if ((key.startsWith("m") || key.startsWith("M")) && key.length() == 2
                && Character.isDigit(key.charAt(1))) {
            return mouse(key.charAt(1) - '1');
        }
        for (int code = 32; code <= 348; code++) {
            if (label(code).equalsIgnoreCase(key)) {
                return code;
            }
        }
        return NONE;
    }

    public static String label(int bind) {        if (bind == NONE) {
            return "NONE";
        }
        if (bind >= MOUSE) {
            return "M" + (bind - MOUSE + 1);
        }
        String name = GLFW.glfwGetKeyName(bind, 0);
        if (name != null) {
            return name.toUpperCase();
        }
        return switch (bind) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            default -> "K" + bind;
        };
    }
}
