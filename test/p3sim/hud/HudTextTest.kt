package com.engineerclient.p3sim.clear.hud

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudTextTest {
    @Test fun aBotShowsItsOwnMaximumHealthOnTheSidebar() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val ctx = HudFixtures.ctx(st)
        val human = st.players[HudFixtures.me]!!; val bot = st.players[HudFixtures.a]!!
        human.hpPercent = 50.0; bot.hpPercent = 50.0
        assertEquals(Math.round(ctx.stats.maxHp * 0.5).toInt(), ctx.hpOf(bot), "without a figure of its own a bot reads as the viewer")
        bot.extras["maxHp"] = 30_000
        assertEquals(15_000, ctx.hpOf(bot))
        assertEquals(Math.round(ctx.stats.maxHp * 0.5).toInt(), ctx.hpOf(human), "a human keeps the viewer's maximum")
    }

    @Test fun numbersAreFormattedAsHypixelPrintsThem() {
        assertEquals("13,879", HudFormat.commas(13879))
        assertEquals("999", HudFormat.commas(999))
        assertEquals("1,000,000", HudFormat.commas(1_000_000))
        assertEquals("2,178", HudFormat.commas(2177.6))
        assertEquals("3k", HudFormat.abbreviate(3400))
        assertEquals("526M", HudFormat.abbreviate(526_900_000))
        assertEquals("42", HudFormat.abbreviate(42))
    }

    @Test fun elapsedTimeUsesTheTwoRecordedShapes() {
        assertEquals("00s", HudFormat.elapsed(0))
        assertEquals("13s", HudFormat.elapsed(13 * 20))
        assertEquals("01m 17s", HudFormat.elapsed(77 * 20))
        assertEquals("04m 10s", HudFormat.elapsed(250 * 20))
        assertEquals("1h 02m 03s", HudFormat.elapsed((3600 + 123) * 20))
        assertEquals("00s", HudFormat.elapsed(-5))
        // Odin's time regex accepts every shape.
        val rx = Regex("^ Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)$")
        for (t in listOf(0, 260, 1540, 73_000)) assertTrue(rx.matches(" Time: ${HudFormat.elapsed(t)}"), HudFormat.elapsed(t))
    }

    @Test fun countdownRoundsUp() {
        assertEquals("0:05", HudFormat.countdown(81))
        assertEquals("0:04", HudFormat.countdown(80))
        assertEquals("0:01", HudFormat.countdown(20))
        assertEquals("0:01", HudFormat.countdown(1))
        assertEquals("0:00", HudFormat.countdown(0))
        assertEquals("1:05", HudFormat.countdown(65 * 20))
    }

    @Test fun romanNumeralsRoundTripThroughOdinsReader() {
        // Odin's romanToInt, reproduced: subtract a smaller numeral that precedes a larger one.
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)
        fun read(s: String): Int { var r = 0; for (i in 0 until s.length - 1) { val c = map[s[i]]!!; val n = map[s[i + 1]]!!; r += if (c < n) -c else c }; return r + map[s.last()]!! }
        for (n in 1..99) assertEquals(n, read(HudFormat.roman(n)), HudFormat.roman(n))
        assertEquals("L", HudFormat.roman(50))
        assertEquals("XLVIII", HudFormat.roman(48))
    }

    @Test fun lastColorsFollowsMinecraft() {
        assertEquals("§a", HudFormat.lastColors("Time Elapsed: §a"))
        assertEquals("§c", HudFormat.lastColors("x §a y §c"))
        assertEquals("", HudFormat.lastColors("plain"))
        assertEquals("§a§l", HudFormat.lastColors("§a§lbold"))
        assertEquals("", HudFormat.lastColors("§aa§rb"))
        assertEquals("§b", HudFormat.lastColors("§l§bb"))
    }

    @Test fun lineSplitMatchesRecordedHypixelSalt() {
        // Prefix 16 characters, suffix opens with the colour in force at the cut (the doubled code in recordings).
        assertEquals("Time Elapsed: §a" to "§a13s", HudFormat.splitLine("Time Elapsed: §a13s"))
        assertEquals("§e[H] §aQuillfox" to "§a9 §a13,879§c❤", HudFormat.splitLine("§e[H] §aQuillfox9 §a13,879§c❤"))
        assertEquals("§e[M] §bRook5 §c" to "§cDEAD", HudFormat.splitLine("§e[M] §bRook5 §cDEAD"))
        assertEquals("§ewww.hypixel.ne" to "§et", HudFormat.splitLine("§ewww.hypixel.net"))
        assertEquals(" §7 §cThe Catac" to "§combs §7(F7)", HudFormat.splitLine(" §7 §cThe Catacombs §7(F7)"))
        assertEquals("short" to "", HudFormat.splitLine("short"))
    }

    @Test fun splitNeverCutsInsideAColourCode() {
        val (p, s) = HudFormat.splitLine("Cleared: §c31% §8(53)")
        assertTrue(!p.endsWith("§"), p)
        assertEquals("Cleared: 31% (53)", HudFormat.strip(p + s))
    }

    @Test fun joinedStrippedLinesEqualTheOriginalForLinesUpTo32Chars() {
        for (text in listOf("Keys: §c■ §c✗ §8§8■ §a0x", "Time Elapsed: §a04m 10s", "§e[B] §aMarigold §a20,251§c❤", "Starting in: §a0§a:04", "               ")) {
            val (p, s) = HudFormat.splitLine(text)
            assertEquals(HudFormat.strip(text), HudFormat.strip(p + s), text)
            assertTrue(p.length <= 16 && s.length <= 16)
        }
    }

    @Test fun aLongNameWithFiveDigitHealthKeepsItsHeart() {
        val text = "§e[B] §a" + "Abcdefghijkl" + " §a13,879§c❤"
        assertEquals(32, text.length)
        val (p, s) = HudFormat.splitLine(text)
        assertTrue(p.length <= 16 && s.length <= 16 && !s.endsWith("§"), "$p|$s")
        assertEquals(HudFormat.strip(text), HudFormat.strip(p + s))
    }

    @Test fun classNamesAndLetters() {
        assertEquals("Berserk", HudFormat.className("BERSERKER"))
        assertEquals('B', HudFormat.classLetter("berserk"))
        assertEquals('H', HudFormat.classLetter("Healer"))
        assertEquals('T', HudFormat.classLetter("tank"))
        assertEquals('A', HudFormat.classLetter("Archer"))
        assertEquals("12345678901", HudFormat.sidebarName("12345678901"))
        assertEquals("ABCDEFGHIJKL", HudFormat.sidebarName("ABCDEFGHIJKLMN"))
    }
}
