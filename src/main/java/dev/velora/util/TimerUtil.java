package dev.velora.util;

public final class TimerUtil {

    private long lastMs;

    public TimerUtil() {
        this.lastMs = System.currentTimeMillis();
    }

    public void reset() {
        lastMs = System.currentTimeMillis();
    }

    public long elapsed() {
        return System.currentTimeMillis() - lastMs;
    }

    public boolean hasPassed(long ms) {
        return elapsed() >= ms;
    }

    public boolean hasPassed(double ms) {
        return elapsed() >= ms;
    }

    public boolean tryReset(long ms) {
        if (hasPassed(ms)) {
            reset();
            return true;
        }
        return false;
    }
}
