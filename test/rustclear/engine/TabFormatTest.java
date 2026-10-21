package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The tab's number formats as Hypixel writes them (recorded rows). */
class TabFormatTest {
    @Test
    void clock() {
        assertEquals("00s", TabList.clock(0));
        assertEquals("45s", TabList.clock(45));
        assertEquals("01m 00s", TabList.clock(60));
        assertEquals("04m 14s", TabList.clock(254));
    }

    @Test
    void numbers() {
        assertEquals("0", TabList.shortNumber(0));
        assertEquals("999", TabList.shortNumber(999));
        assertEquals("2.4k", TabList.shortNumber(2_412));
        assertEquals("469.2M", TabList.shortNumber(469_234_000));
        assertEquals("469M", TabList.shortNumber(469_040_000));
        assertEquals("1.5B", TabList.shortNumber(1_530_000_000));
    }

    @Test
    void percent() {
        assertEquals("65.4", TabList.trimmed(65.38));
        assertEquals("50", TabList.trimmed(50.0));
    }
}
