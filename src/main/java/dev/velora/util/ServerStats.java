package dev.velora.util;

/**
 * Small server-health readout shared by the HUD widgets.
 *
 * <p>TPS cannot be read from the client directly: the vanilla client only knows
 * how fast it renders. The server sends a world-time packet every 20 ticks,
 * though, so measuring the interval between those packets gives the real tick
 * rate. One late packet should not make the readout jump, so the measured value
 * is smoothed; the first packet after joining is ignored because there is no
 * interval yet.</p>
 */
public final class ServerStats {

    /** No packet for this long means the world is paused (menu, disconnect). */
    private static final long STALE_NANOS = 5_000_000_000L;

    private static volatile double tps = 20.0;
    private static volatile long lastPacketNanos;
    private static volatile long lastUpdateNanos;

    private ServerStats() {
    }

    /** Called from the mixin at the head of the server time packet handler. */
    public static void onTimePacket() {
        long now = System.nanoTime();
        long previous = lastPacketNanos;
        lastPacketNanos = now;
        lastUpdateNanos = now;
        if (previous == 0L) {
            return;
        }
        double seconds = (now - previous) / 1e9;
        if (seconds < 0.001) {
            return;
        }
        // A long gap means a new session (join, respawn screen), not lag.
        if (seconds > 5.0) {
            tps = 20.0;
            return;
        }
        // 20 ticks per second: a packet every second means a healthy server.
        double measured = Math.min(20.0, 20.0 / seconds);
        tps += (measured - tps) * 0.35;
        if (Math.abs(tps - 20.0) < 0.01) {
            tps = 20.0;
        }
    }

    /** Smoothed ticks per second, 0..20. */
    public static double tps() {
        if (lastUpdateNanos == 0L || System.nanoTime() - lastUpdateNanos > STALE_NANOS) {
            return 20.0;
        }
        return Math.max(0.0, Math.min(20.0, tps));
    }

    /** Forgets the previous packet, e.g. after joining another server. */
    public static void reset() {
        tps = 20.0;
        lastPacketNanos = 0L;
        lastUpdateNanos = 0L;
    }
}
