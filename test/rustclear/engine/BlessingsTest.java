package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Blessing lines against the recorded "Granted you" text (colour codes stripped). */
class BlessingsTest {
    private static String plain(String legacy) {
        return legacy.replaceAll("§.", "");
    }

    @Test
    void grantedLines() {
        assertEquals("     Granted you +5.8 & +1.03x ❁ Strength.", plain(Blessings.granted(Pickup.Kind.BLESSING_POWER, 1)[0]));
        assertEquals("     Also granted you +11.6 & +1.06x ☠ Crit Damage.", plain(Blessings.granted(Pickup.Kind.BLESSING_POWER, 2)[1]));
        assertEquals("     Granted you +29 & +1.15x ❈ Defense and +36 ❁ Damage.", plain(Blessings.granted(Pickup.Kind.BLESSING_STONE, 5)[0]));
        assertEquals("     Granted you +5.8 & +1.03x ✎ Intelligence and +4.8 ✦ Speed.", plain(Blessings.granted(Pickup.Kind.BLESSING_WISDOM, 1)[0]));
        assertEquals("     Granted you +1.09x HP and +1.09x ❤ Health Regen.", plain(Blessings.granted(Pickup.Kind.BLESSING_LIFE, 2)[0]));
        assertEquals("     Granted you +1.22x HP and +1.22x ❤ Health Regen.", plain(Blessings.granted(Pickup.Kind.BLESSING_LIFE, 5)[0]));
    }

    @Test
    void lyingTime() {
        assertEquals("07s", Blessings.time(140));
        assertEquals("01m 22s", Blessings.time(82 * 20 + 5));
    }
}
