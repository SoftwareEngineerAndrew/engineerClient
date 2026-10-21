package net.rustclear.mod.engine.boss;

import java.util.PriorityQueue;

/**
 * Tick-scheduled tasks with phase-relative times, as Hypixel's boss scripts run: everything is "n
 * ticks after a line" (boss-p1..p5). A new phase calls [reset], which drops what the last one left
 * queued and starts its clock at 0, so a stray task can never fire into the next phase.
 * Tasks due on the same tick run in the order they were scheduled.
 */
public final class Scheduler {
    private record Task(long due, long order, int epoch, Runnable action) {}

    private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> a.due != b.due
        ? Long.compare(a.due, b.due) : Long.compare(a.order, b.order));
    private long now;
    private long order;
    private int epoch;

    /** Ticks since the last [reset]. */
    public long now() {
        return now;
    }

    /** Runs [action] [delay] ticks from now (0: this tick, after the tasks already due). */
    public void after(long delay, Runnable action) {
        queue.add(new Task(now + Math.max(0, delay), order++, epoch, action));
    }

    /** Runs [action] at phase tick [tick] (now, if that has passed). */
    public void at(long tick, Runnable action) {
        after(tick - now, action);
    }

    /** Runs [action] every [period] ticks from [first] on, while [keepGoing] says so. */
    public void every(long first, long period, java.util.function.BooleanSupplier keepGoing, Runnable action) {
        int mine = epoch;
        at(first, new Runnable() {
            @Override
            public void run() {
                if (mine != epoch || !keepGoing.getAsBoolean()) {
                    return;
                }
                action.run();
                after(period, this);
            }
        });
    }

    /** A new phase: nothing queued is left, and the clock starts again at 0. */
    public void reset() {
        queue.clear();
        epoch++;
        now = 0;
    }

    /**
     * The end of a server tick: every task due by [now] runs (tasks they add for now run too), then
     * the clock moves on. A phase started earlier in the same server tick is at 0, so at(k) runs k
     * server ticks after its start.
     */
    public void tick() {
        while (!queue.isEmpty() && queue.peek().due <= now) {
            Task task = queue.poll();
            if (task.epoch == epoch) {
                task.action.run();
            }
        }
        now++;
    }
}
