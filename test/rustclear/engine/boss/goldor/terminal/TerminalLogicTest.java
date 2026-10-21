package net.rustclear.mod.engine.boss.goldor.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The terminals' boards and rules against the measured spreads (boss-p3-goldor.md §7). */
class TerminalLogicTest {
    @Test
    void orderIsAShuffleOfOneToTen() {
        Random random = new Random(1);
        for (int k = 0; k < 200; k++) {
            int[] n = TerminalLogic.orderNumbers(random).clone();
            Arrays.sort(n);
            assertArrayEquals1to10(n);
        }
    }

    private static void assertArrayEquals1to10(int[] sorted) {
        assertEquals(10, sorted.length);
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1, sorted[i]);
        }
    }

    @Test
    void panesStartWithOneToSixOn() {
        Random random = new Random(2);
        int[] seen = new int[16];
        for (int k = 0; k < 5000; k++) {
            int on = 0;
            for (boolean b : TerminalLogic.panesOn(random)) {
                on += b ? 1 : 0;
            }
            seen[on]++;
        }
        assertEquals(0, seen[0]);
        assertEquals(0, seen[7]);
        assertTrue(seen[1] > 0 && seen[6] > 0);
        assertEquals(3, median(seen));
    }

    @Test
    void rubixFewestClicksMatchesHypixel() {
        Random random = new Random(3);
        int[] seen = new int[46];
        for (int k = 0; k < 20000; k++) {
            int[] board = TerminalLogic.rubixBoard(random);
            assertFalse(TerminalLogic.rubixSolved(board));
            seen[TerminalLogic.rubixFewestClicks(board)]++;
        }
        assertEquals(8, median(seen));
        for (int c = 11; c < seen.length; c++) {
            assertEquals(0, seen[c], "no board needs more than 10");
        }
        // 10-click boards: 7 of 58 measured, ~15.5% expected from uniform draws.
        double tens = seen[10] / 20000.0;
        assertTrue(tens > 0.12 && tens < 0.19, "10-click share " + tens);
    }

    @Test
    void rubixNextClickSolvesInFewestClicks() {
        Random random = new Random(4);
        for (int k = 0; k < 500; k++) {
            int[] board = TerminalLogic.rubixBoard(random);
            int fewest = TerminalLogic.rubixFewestClicks(board);
            int clicks = 0;
            int[] next;
            while ((next = TerminalLogic.rubixNextClick(board)) != null) {
                board[next[0]] = TerminalLogic.rubixStep(board[next[0]], next[1] == 1);
                clicks++;
                assertTrue(clicks <= fewest);
            }
            assertEquals(fewest, clicks);
            assertTrue(TerminalLogic.rubixSolved(board));
        }
    }

    @Test
    void rubixStepsForwardAndBack() {
        assertEquals(1, TerminalLogic.rubixStep(0, false));
        assertEquals(4, TerminalLogic.rubixStep(0, true));
        assertEquals(0, TerminalLogic.rubixStep(4, false));
    }

    @Test
    void startsBoardsHaveTheDrawnMatches() {
        Random random = new Random(5);
        for (char letter : TerminalLogic.STARTS_LETTERS.toCharArray()) {
            for (int k = 0; k < 50; k++) {
                int matches = TerminalLogic.startsMatches(random);
                assertTrue(matches >= 4 && matches <= 11);
                int[] board = TerminalLogic.startsBoard(letter, matches, random);
                assertEquals(21, board.length);
                int got = 0;
                for (int i : board) {
                    got += TerminalLogic.STARTS_POOL[i][1].charAt(0) == letter ? 1 : 0;
                }
                assertEquals(matches, got);
            }
        }
    }

    @Test
    void startsPoolHasNoDuplicates() {
        Set<String> ids = new HashSet<>();
        for (String[] e : TerminalLogic.STARTS_POOL) {
            assertTrue(ids.add(e[0]), e[0]);
        }
        assertEquals(171, ids.size());
    }

    @Test
    void selectSplitsSixSixSixFiveFive() {
        Random random = new Random(6);
        int targetSix = 0;
        for (int k = 0; k < 3000; k++) {
            int[] board = TerminalLogic.selectBoard(random);
            int[] perFamily = new int[16];
            for (int i = 1; i < board.length; i++) {
                perFamily[board[i] / 4]++;
            }
            int[] sizes = Arrays.stream(perFamily).filter(n -> n > 0).sorted().toArray();
            assertEquals("[5, 5, 6, 6, 6]", Arrays.toString(sizes));
            assertTrue(perFamily[board[0]] >= 5);
            targetSix += perFamily[board[0]] == 6 ? 1 : 0;
        }
        // 29 of 50 measured; 3 in 5 by construction.
        assertTrue(Math.abs(targetSix / 3000.0 - 0.6) < 0.04);
    }

    @Test
    void melodyBouncesAndLocks() {
        Random random = new Random(7);
        TerminalLogic.Melody m = new TerminalLogic.Melody(random);
        assertEquals(1, m.lime);
        int[] path = new int[9];
        for (int i = 0; i < path.length; i++) {
            path[i] = m.lime;
            m.step(random);
        }
        assertEquals("[1, 2, 3, 4, 5, 4, 3, 2, 1]", Arrays.toString(path));
    }

    @Test
    void melodyLaneAdvancesAtTheNextStepWithANewTarget() {
        Random random = new Random(8);
        for (int k = 0; k < 200; k++) {
            TerminalLogic.Melody m = new TerminalLogic.Melody(random);
            int locks = 0;
            int steps = 0;
            while (!m.solved() && steps < 200) {
                if (m.lime == m.target && !m.locked) {
                    TerminalLogic.Melody.Lock r = m.lock(m.lane);
                    locks++;
                    if (locks < 3) {
                        assertEquals(TerminalLogic.Melody.Lock.ACCEPTED, r);
                        assertEquals(TerminalLogic.Melody.Lock.IGNORED, m.lock(m.lane), "a second lock in the step");
                        int lane = m.lane;
                        int target = m.target;
                        int lime = m.lime;
                        m.step(random);
                        assertEquals(lane + 1, m.lane);
                        assertNotEquals(target, m.target);
                        assertEquals(1, Math.abs(m.lime - lime), "the lime keeps moving into the new lane");
                        steps++;
                        continue;
                    }
                    assertEquals(TerminalLogic.Melody.Lock.FINISHED, r);
                }
                m.step(random);
                steps++;
            }
            assertTrue(m.solved());
        }
    }

    @Test
    void melodyWrongLockStallsTwoSteps() {
        Random random = new Random(9);
        TerminalLogic.Melody m = new TerminalLogic.Melody(random);
        m.target = 5;
        m.step(random);
        assertEquals(2, m.lime);
        assertEquals(TerminalLogic.Melody.Lock.WRONG, m.lock(0));
        assertEquals(TerminalLogic.Melody.Lock.WRONG, m.lock(0));
        m.step(random);
        m.step(random);
        assertEquals(2, m.lime, "two steps sat out, not four");
        m.step(random);
        assertEquals(3, m.lime);
    }

    private static int median(int[] histogram) {
        int total = Arrays.stream(histogram).sum();
        int run = 0;
        for (int i = 0; i < histogram.length; i++) {
            run += histogram[i];
            if (run * 2 >= total) {
                return i;
            }
        }
        return -1;
    }
}
