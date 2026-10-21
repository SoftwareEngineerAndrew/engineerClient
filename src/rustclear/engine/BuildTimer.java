package net.rustclear.mod.engine;

import java.util.Locale;
import java.util.StringJoiner;

/** Times the phases of building a dungeon, for the "Built Rustclear dungeon" log line. */
final class BuildTimer {
    private final long started = System.nanoTime();
    private long last = started;
    private final StringJoiner laps = new StringJoiner(", ");

    /** Ends the phase running since the last lap (or since this timer was made) as [name]. */
    void lap(String name) {
        long now = System.nanoTime();
        laps.add(name + " " + ms(now - last));
        last = now;
    }

    long totalMs() {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public String toString() {
        return laps.toString();
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f ms", nanos / 1e6);
    }
}
