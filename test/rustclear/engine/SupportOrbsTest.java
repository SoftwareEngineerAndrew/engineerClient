package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SupportOrbs against the recorded orbs (abilities-items.md "Support Orbs"). */
class SupportOrbsTest {
    @Test
    void labelCountsDownEachSecond() {
        // "DAMAGE 60s" on spawn, one second less every 20 ticks, "1s" in the last second.
        assertEquals("§c§lDAMAGE §e60s", SupportOrbs.labelText(SupportOrbs.Type.DAMAGE, 0));
        assertEquals("§a§lDEFENSE §e59s", SupportOrbs.labelText(SupportOrbs.Type.DEFENSE, 20));
        assertEquals("§c§lABILITY DAMAGE §e1s", SupportOrbs.labelText(SupportOrbs.Type.ABILITY_DAMAGE, 1180));
    }

    @Test
    void bobsOverEightyTicks() {
        // Median offsets from the spawn block: -1 at the start, 0 at 20, +1 at 40, -1 again at 80.
        assertEquals(-1, SupportOrbs.bob(0), 1e-9);
        assertEquals(0, SupportOrbs.bob(20), 1e-9);
        assertEquals(1, SupportOrbs.bob(40), 1e-9);
        assertEquals(-0.707, SupportOrbs.bob(10), 0.01);
    }
}
