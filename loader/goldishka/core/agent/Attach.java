package dev.velora.agent;

import com.sun.tools.attach.VirtualMachine;

/**
 * Отдельный процесс, который подключается к живой JVM Minecraft
 * через Java Attach API и загружает в неё {@link VeloraAgent}.
 *
 * <p>Запускается той же java.exe, что и игра — иначе Attach API
 * не сможет найти таргет.</p>
 *
 * <p>Аргументы: {@code <pid> <agentJar> <options>}</p>
 *
 * <p>Вывод намеренно лаконичный и латинский: консоль Windows часто живёт
 * в старой кодовой странице, и кириллица в логе превращается в мусор.
 * Русские подписи рисует сам лаунчер.</p>
 */
public final class Attach {

    private Attach() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: Attach <pid> <agentJar> <options>");
            System.exit(2);
        }

        String pid = args[0];
        String agentJar = args[1];
        String options = args[2];

        VirtualMachine vm = null;
        try {
            System.out.println("stage=attach pid=" + pid);
            vm = VirtualMachine.attach(pid);

            System.out.println("stage=load");
            vm.loadAgent(agentJar, options);

            System.out.println("stage=ok");
        } catch (Throwable t) {
            System.out.println("stage=failed");
            System.out.println("reason=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (Throwable ignored) {
                    // Процесс всё равно завершается.
                }
            }
        }
    }
}