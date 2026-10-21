package net.rustclear.mod.engine.boss.goldor.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The devices' pure logic: Lights' lamps, Arrow Align's boards, the target's attempts. */
class DeviceLogicTest {
    @Test
    void lightsSolvedLightsEverything() {
        LightsBoard b = new LightsBoard();
        assertFalse(b.allLit());
        b.solve();
        assertTrue(b.allLit());
        // Turning a solution lever off darkens a lamp (the completing click still counts: checked before).
        b.toggle(60, 135);
        assertFalse(b.allLit());
        assertFalse(b.lamp(60, 136));
        assertTrue(b.lamp(60, 135));
    }

    @Test
    void arrowBoardsAreWellFormed() {
        assertEquals(8, ArrowLayouts.BOARDS.length);
        for (int b = 0; b < ArrowLayouts.BOARDS.length; b++) {
            int starts = 0;
            int ends = 0;
            int arrows = 0;
            for (int y = 120; y <= 124; y++) {
                assertEquals(5, ArrowLayouts.BOARDS[b][124 - y].length());
                for (int z = 75; z <= 79; z++) {
                    char c = ArrowLayouts.cell(b, y, z);
                    starts += c == 'S' ? 1 : 0;
                    ends += c == 'E' ? 1 : 0;
                    if (ArrowLayouts.isArrow(c)) {
                        arrows++;
                        assertTrue((c - '0') % 2 == 1, "arrows point along the grid");
                    }
                }
            }
            assertTrue(starts >= 1 && ends >= 1, "board " + b);
            assertTrue(arrows >= 9 && arrows <= 15, "board " + b);
            int[] off = ArrowLayouts.unsolved(b);
            assertNotNull(off);
            assertTrue(ArrowLayouts.isArrow(ArrowLayouts.cell(b, off[0], off[1])));
        }
        // The recorded one-short frames: (120,78) on boards 6-8 (as indexed here 5-7), (121,79) on 4-5.
        assertEquals(120, ArrowLayouts.unsolved(5)[0]);
        assertEquals(78, ArrowLayouts.unsolved(5)[1]);
        assertEquals(121, ArrowLayouts.unsolved(4)[0]);
        assertEquals(79, ArrowLayouts.unsolved(4)[1]);
    }

    @Test
    void targetNeedsTheFirstEmeraldAndCountsHiddenHits() {
        TargetBoard b = new TargetBoard(new Random(1));
        b.begin(0);
        int first = b.target();
        assertEquals(TargetBoard.Strike.NONE, b.strike(first), "nothing counts before the first emerald");
        int litAt = -1;
        for (int n = 1; n <= 10 && litAt < 0; n++) {
            if (b.grid(n, true) == first) {
                litAt = n;
            }
        }
        assertTrue(litAt >= 1 && litAt <= 10, "first emerald 1-10 ticks after the start");
        assertEquals(TargetBoard.Strike.SHOWN, b.strike(first));
        assertEquals(-1, b.shown());
        int second = b.target();
        assertTrue(second >= 0 && second != first);
        assertEquals(TargetBoard.Strike.HIDDEN, b.strike(second), "a hidden target counts");
        assertEquals(2, b.count());
        // Nothing lights off the grid.
        assertEquals(-1, b.grid(litAt + 3, true));
        assertEquals(b.target(), b.grid(litAt + 10, true));
    }

    @Test
    void targetResetsOffThePlateOnAGridTick() {
        TargetBoard b = new TargetBoard(new Random(2));
        b.begin(5);
        int lit = -1;
        long n = 6;
        for (; n <= 15 && lit < 0; n++) {
            lit = b.grid(n, true);
        }
        long gridTick = n - 1;
        assertTrue(lit >= 0);
        assertEquals(-1, b.grid(gridTick + 4, false), "off the plate between grid ticks: nothing");
        assertTrue(b.running());
        assertEquals(-(lit + 2), b.grid(gridTick + 10, false));
        assertFalse(b.running());
        assertEquals(0, b.count());
    }

    @Test
    void targetCompletesOnTheNinthCountWithEveryCellOnce() {
        TargetBoard b = new TargetBoard(new Random(3));
        b.begin(0);
        long n = 1;
        while (b.grid(n, true) < 0) {
            n++;
        }
        Set<Integer> seen = new HashSet<>();
        while (!b.complete()) {
            int t = b.target();
            assertTrue(seen.add(t));
            assertTrue(b.strike(t) != TargetBoard.Strike.NONE);
        }
        assertEquals(9, seen.size());
        assertEquals(-1, b.grid(n + 10, true));
    }
}
