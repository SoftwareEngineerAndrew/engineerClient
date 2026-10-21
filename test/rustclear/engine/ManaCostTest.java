package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Combat.cost against every price measured on Hypixel (abilities-items.md §10.1): discounted / full (hit in the last 5 s). */
class ManaCostTest {
    @Test
    void measuredPrices() {
        // Hyperion, no Ultimate Wise: 270 / 300.
        assertEquals(270, Combat.cost(300, 1, false));
        assertEquals(300, Combat.cost(300, 1, true));
        // Bonzo's Staff, Ultimate Wise V: 41 / 45 (40.5 rounds up).
        assertEquals(41, Combat.cost(90, 0.5, false));
        assertEquals(45, Combat.cost(90, 0.5, true));
        // Jerry-chine's first shot: 14 / 15.
        assertEquals(14, Combat.cost(30, 0.5, false));
        assertEquals(15, Combat.cost(30, 0.5, true));
        // Ice Spray, Ultimate Wise V: 23 / 25. Ragnarock 450 / 500, Tactical Insertion and Spirit Sceptre 180 / 200.
        assertEquals(23, Combat.cost(50, 0.5, false));
        assertEquals(450, Combat.cost(500, 1, false));
        assertEquals(180, Combat.cost(200, 1, false));
        // AOTV with Ultimate Wise V and its unexplained 0.913: blink 18 / 21, etherwarp 73 / 81.
        assertEquals(18, Combat.cost(45 * 0.913, 0.5, false));
        assertEquals(21, Combat.cost(45 * 0.913, 0.5, true));
        assertEquals(73, Combat.cost(178 * 0.913, 0.5, false));
        assertEquals(81, Combat.cost(178 * 0.913, 0.5, true));
    }
}
