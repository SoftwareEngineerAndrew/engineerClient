package net.rustclear.mod.engine.boss.goldor.device;

import java.util.Random;

/**
 * The target device's ("i4") board logic, per attempt (boss-p3-goldor.md §6.4, i4lag FINDINGS Q7):
 * nine cells; the target is drawn uniformly from the cells not yet done; the emerald shows on the
 * attempt's own 10-tick grid; a strike on the target counts whether it shows yet or not; off the
 * plate on a grid tick, progress resets. Cells are 0-8, row from the bottom (y 126/128/130) times
 * 3 plus the column (x 64/66/68).
 */
final class TargetBoard {
    static final int CELLS = 9;
    /** Emeralds light on a 10-server-tick grid (795 of 810 lights on their attempt's phase). */
    static final int GRID = 10;

    /** What a strike did. */
    enum Strike {
        /** Not the target, or no attempt is running: nothing. */
        NONE,
        /** The target, not showing yet (a hidden hit: mean 2.0 per attempt). */
        HIDDEN,
        /** The target while it showed: blue a tick later. */
        SHOWN,
    }

    private final Random random;
    private final boolean[] done = new boolean[CELLS];
    private int count;
    private int target = -1;
    private int shown = -1;
    /** The attempt's grid phase (n mod 10), or -1 with no attempt running. */
    private int phase = -1;
    private boolean shownOnce;
    private boolean complete;

    TargetBoard(Random random) {
        this.random = random;
    }

    boolean running() {
        return phase >= 0;
    }

    boolean complete() {
        return complete;
    }

    int count() {
        return count;
    }

    /** The cell that counts next (showing or not), or -1. */
    int target() {
        return target;
    }

    /** The cell showing emerald, or -1. */
    int shown() {
        return shown;
    }

    /**
     * A new attempt at tick [n] (the plate went down with none running): nothing done, a fresh
     * target, and a phase that puts the first emerald 1-10 ticks later (never in the tick itself).
     */
    void begin(long n) {
        java.util.Arrays.fill(done, false);
        count = 0;
        shown = -1;
        shownOnce = false;
        target = draw();
        phase = (int) Math.floorMod(n + 1 + random.nextInt(GRID), (long) GRID);
    }

    /**
     * Tick [n]'s grid step, before that tick's arrows (Hypixel's grid task runs before entities).
     * With the plate down it lights the target if it isn't showing: returns the cell lit. With the
     * plate up the attempt ends: returns -(cell + 2) for a lit cell that goes back to blue, -1 for
     * nothing to show.
     */
    int grid(long n, boolean plateDown) {
        if (complete || phase < 0 || Math.floorMod(n, (long) GRID) != phase) {
            return -1;
        }
        if (plateDown) {
            if (target >= 0 && shown != target) {
                shown = target;
                shownOnce = true;
                return shown;
            }
            return -1;
        }
        int was = shown;
        phase = -1;
        target = -1;
        shown = -1;
        count = 0;
        java.util.Arrays.fill(done, false);
        return was >= 0 ? -(was + 2) : -1;
    }

    /**
     * A strike on [cell]. Nothing counts before the attempt's first emerald (4 of 22 early-struck first
     * targets stayed unlit). A count draws the next target at once, so a later arrow in the same tick
     * can take it.
     */
    Strike strike(int cell) {
        if (complete || phase < 0 || !shownOnce || cell != target) {
            return Strike.NONE;
        }
        boolean wasShown = shown == cell;
        if (wasShown) {
            shown = -1;
        }
        done[cell] = true;
        count++;
        if (count >= CELLS) {
            complete = true;
            target = -1;
        } else {
            target = draw();
        }
        return wasShown ? Strike.SHOWN : Strike.HIDDEN;
    }

    private int draw() {
        int left = CELLS - count;
        int k = random.nextInt(left);
        for (int c = 0; c < CELLS; c++) {
            if (!done[c] && k-- == 0) {
                return c;
            }
        }
        return -1;
    }
}
