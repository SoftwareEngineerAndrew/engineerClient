package net.rustclear.mod.engine.boss;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * The boss chat queue (blood-watcher.md §7, the boss-p1..p5 scripts): a FIFO where a line can
 * print only once the previous line's minimum gap has passed, free = last.t + S(last). The gap
 * belongs to the line before: 62 after a Watcher spawn, ledge or "handle" line, 42 after a
 * reaction or attack line, and the fixed script spacings of each boss (Maxor 42, Storm 62, ...).
 * The Watcher and every boss share one queue per run, as one NPC voice would.
 */
public final class BossChat {
    /** A queued line: its text (§ codes) and the ticks the next line must wait after it prints. */
    public record Line(String legacy, int gapAfter, Runnable onSent) {}

    /** Gaps measured in blood-watcher.md §7 and the boss files. */
    public static final int GAP_SPAWN = 62;
    public static final int GAP_REACT = 42;

    private final ArrayDeque<Line> queue = new ArrayDeque<>();
    private long free = Long.MIN_VALUE;
    /** The tick of the last [tick] call. */
    private long last;
    /** The tick the last line printed. */
    private long sent = Long.MIN_VALUE;

    /** "§4[BOSS] Maxor§c: §cWELL! WELL!..." as Hypixel colours a boss line (menus-ui.md, FLOW-26). */
    public static String boss(String name, String text) {
        return "§4[BOSS] " + name + "§c: §c" + text;
    }

    /** Queues [legacy]; the next line waits [gapAfter] ticks after it prints. */
    public void say(String legacy, int gapAfter) {
        queue.add(new Line(legacy, gapAfter, null));
    }

    /** Queues [legacy], then runs [onSent] on the tick it prints (scripts anchored on a line). */
    public void say(String legacy, int gapAfter, Runnable onSent) {
        queue.add(new Line(legacy, gapAfter, onSent));
    }

    /** Lines waiting. */
    public int pending() {
        return queue.size();
    }

    /** Drops every waiting line ("requests are dropped at proven"). The current gap still holds. */
    public void clear() {
        queue.clear();
    }

    /** Forgets the gap too: the next line may print at once (a new phase's first line). */
    public void reset() {
        queue.clear();
        free = Long.MIN_VALUE;
        sent = Long.MIN_VALUE;
    }

    /** Lets the next line print at once, though the last one's gap hasn't passed ("proven" past rule P's 20 ticks). */
    public void release() {
        free = Long.MIN_VALUE;
    }

    /**
     * How many ticks from this one the next line must still wait, when asked before this tick's
     * [tick] (the run ticks the camp and the bosses first): 0 when it may print now.
     */
    public long waitTicks() {
        return free == Long.MIN_VALUE ? 0 : Math.max(0, free - (last + 1));
    }

    /** The first tick the next line may print. */
    public long free() {
        return free;
    }

    /** The tick the last line printed (Long.MIN_VALUE before any). */
    public long sent() {
        return sent;
    }

    /** At tick [now]: prints the head line through [send] if its turn has come (one line a tick at most). */
    public void tick(long now, Consumer<String> send) {
        last = now;
        if (queue.isEmpty() || now < free) {
            return;
        }
        Line line = queue.poll();
        send.accept(line.legacy);
        sent = now;
        free = now + line.gapAfter;
        if (line.onSent != null) {
            line.onSent.run();
        }
    }
}
