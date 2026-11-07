package dev.velora.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.jar.JarFile;

/**
 * Агент, который загружается в уже работающую JVM Minecraft.
 *
 * <p>Задача — «подсунуть» клиенту наш мод без перезапуска игры:</p>
 * <ol>
 *   <li>добавить наш мод-JAR в системный classpath
 *       через {@link Instrumentation#appendToSystemClassLoaderSearch};</li>
 *   <li>обнулить {@code MinecraftClient.instance}, чтобы ванильный клиент
 *       пересоздал себя и подхватил мод.</li>
 * </ol>
 *
 * <p>Опции передаются строкой вида {@code modJar=C:\путь\клиент.jar}.
 * Формат намеренно простой — без JSON и внешних библиотек, потому что
 * агент живёт внутри игры и не должен тянуть лишние зависимости.</p>
 */
public final class VeloraAgent {

    private VeloraAgent() {
    }

    /** Точка входа для {@code VirtualMachine.loadAgent(...)}. */
    public static void agentmain(String args, Instrumentation inst) {
        log("agentmain " + args);

        String modJar = parseOption(args, "modJar");
        if (modJar == null || modJar.isBlank()) {
            error("no-modjar");
            return;
        }

        File jar = new File(modJar);
        if (!jar.isFile()) {
            error("jar-missing " + jar.getAbsolutePath());
            return;
        }

        try {
            inst.appendToSystemClassLoaderSearch(new JarFile(jar));
            log("cp-added");
        } catch (Throwable t) {
            error("cp-failed " + t);
            return;
        }

        reloadClient();
    }

    /** Сбрасывает {@code MinecraftClient.instance}, чтобы клиент пересоздался. */
    private static void reloadClient() {
        Class<?> mcClass;
        try {
            mcClass = Class.forName("net.minecraft.client.MinecraftClient");
        } catch (ClassNotFoundException e) {
            error("no-mcclass");
            return;
        }

        try {
            Field instance = mcClass.getDeclaredField("instance");
            instance.setAccessible(true);

            Object current = instance.get(null);
            if (current == null) {
                log("already-early");
                return;
            }

            instance.set(null, null);
            log("instance-reset");
        } catch (NoSuchFieldException e) {
            error("no-instance-field");
        } catch (Throwable t) {
            error("reset-failed " + t);
        }
    }

    /** Достаёт {@code value} из строки опций {@code key=value;key2=value2}. */
    private static String parseOption(String args, String key) {
        if (args == null || args.isBlank()) {
            return null;
        }
        for (String part : args.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = part.substring(0, eq).trim();
            if (k.equalsIgnoreCase(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static void log(String message) {
        System.out.println("agent=" + message);
        System.out.flush();
    }

    private static void error(String message) {
        System.out.println("agent-error=" + message);
        System.out.flush();
    }
}