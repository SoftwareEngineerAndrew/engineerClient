package com.engineerclient.splits

import com.engineerclient.splits.EngineerLook.Place
import com.engineerclient.splits.EngineerLook.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineerLookTest {

    private val F7 = listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry", "§5Maxor", "§3Storm", "§6Terminals", "§7Goldor", "§cNecron", "§4Cleared", "§1Total")
    private val ALL = EngineerLook.Options(bossEntry = true, show0 = false, showTicks = true)
    private val TARGETS = listOf(14.0, 63.0, 4.2, 25.5, 45.5, 35.0, 7.2, 30.3, 4.5) // 3m 49.2s together

    /** Odin's rows: [secs] per split (0 = not reached), the one at [current] still running, ticks at 20/s. */
    private fun rows(names: List<String>, secs: List<Double>, current: Int = -1, total: Double = secs.sum()) =
        names.mapIndexed { i, n ->
            val s = if (i == names.lastIndex) total else secs.getOrElse(i) { 0.0 }
            Row(n, (s * 1000).toLong(), (s * 20).toLong(), i == current)
        }

    private fun text(lines: List<EngineerLook.Line>) = lines.map { EngineerLook.text(it).replace(Regex("§."), "") }

    @Test
    fun `nothing before the run starts, unless Show 0 splits`() {
        val before = rows(F7, List(9) { 0.0 }, total = 0.0)
        assertTrue(EngineerLook.lines(before, ALL, Place.FLOOR7, false, TARGETS).isEmpty())
        val shown = text(EngineerLook.lines(before, ALL.copy(show0 = true), Place.FLOOR7, false, TARGETS))
        // Pace is then the targets' total.
        assertEquals("Pace > 3m 49.2s (3m 49.2s)", shown[0])
        assertEquals("Open > 0.00s (0.00s)", shown[1])
        assertEquals("Enter > 0m 0.0s (0m 0.0s)", shown[4])
        assertEquals(11, shown.size)
    }

    @Test
    fun `pace mid-run - done actual, current at least its target, the rest their targets`() {
        // Open 20, Blood 60, Portal 4, Maxor 25, Storm 10 so far (target 45.5).
        val r = rows(F7, listOf(20.0, 60.0, 4.0, 25.0, 10.0), current = 4, total = 119.0)
        val lines = text(EngineerLook.lines(r, ALL, Place.FLOOR7, false, TARGETS))
        // 20 + 60 + 4 + 25 + 45.5 + 35 + 7.2 + 30.3 + 4.5 = 231.5
        assertEquals("Pace > 3m 51.5s (3m 51.5s)", lines[0])
        assertEquals(listOf("Open", "Blood", "Portal", "Enter", "Maxor", "Storm"), lines.drop(1).map { it.substringBefore(" >") })
        assertEquals("Enter > 1m 24.0s (1m 24.0s)", lines[4])
    }

    @Test
    fun `a split running past its target pushes pace back by the overrun`() {
        val r = rows(F7, listOf(20.0, 60.0, 4.0, 25.0, 50.0), current = 4, total = 159.0)
        assertEquals("Pace > 3m 56.0s (3m 56.0s)", text(EngineerLook.lines(r, ALL, Place.FLOOR7, false, TARGETS))[0])
    }

    @Test
    fun `finished - pace is the real total, the last split is Animation or Dragons`() {
        val secs = listOf(20.0, 60.0, 4.0, 25.0, 46.0, 40.0, 7.0, 30.0, 4.0)
        val f7 = text(EngineerLook.lines(rows(F7, secs), ALL, Place.FLOOR7, false, TARGETS))
        assertEquals("Pace > 3m 56.0s (3m 56.0s)", f7[0])
        assertEquals("Animation > 4.00s (4.00s)", f7.last())
        val m7 = text(EngineerLook.lines(rows(F7, secs), ALL, Place.FLOOR7, true, null))
        assertEquals("Dragons > 4.00s (4.00s)", m7.last())
        assertEquals("Terms > 40.00s (40.00s)", m7[7])
    }

    @Test
    fun `blank targets count as nothing`() {
        val r = rows(F7, listOf(20.0), current = 0, total = 20.0)
        assertEquals("Pace > 0m 20.0s (0m 20.0s)", text(EngineerLook.lines(r, ALL, Place.FLOOR7, false, List(9) { null }))[0])
    }

    @Test
    fun `Boss Entry off, tick time off`() {
        val r = rows(F7, listOf(20.0, 60.0, 4.0, 25.0), current = 3, total = 109.0)
        val lines = text(EngineerLook.lines(r, ALL.copy(bossEntry = false, showTicks = false), Place.FLOOR7, false, TARGETS))
        assertTrue(lines.none { it.startsWith("Enter") })
        assertTrue(lines.none { "(" in it })
        assertEquals("Maxor > 25.00s", lines.last())
    }

    @Test
    fun `other floors - Engineer names for the clear, Odin's for the boss, no targets`() {
        val f4 = listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry", "§4Cleared", "§1Total")
        val lines = text(EngineerLook.lines(rows(f4, listOf(30.0, 40.0, 3.0), current = 2, total = 73.0), ALL, Place.DUNGEON, false, null))
        assertEquals(listOf("Pace", "Open", "Blood", "Portal", "Enter"), lines.map { it.substringBefore(" >") })
        assertEquals("Pace > 1m 13.0s (1m 13.0s)", lines[0])
        val done = text(EngineerLook.lines(rows(f4, listOf(30.0, 40.0, 3.0, 50.0)), ALL, Place.DUNGEON, false, null))
        assertEquals("Cleared > 50.00s (50.00s)", done.last())
    }

    @Test
    fun `Kuudra keeps Odin's names, Boss Entry included as Odin does`() {
        val k = listOf("§2Supplies", "§bBuild", "§cStun", "§4Cleared", "Total")
        val lines = text(EngineerLook.lines(rows(k, listOf(30.0, 20.0, 5.0), current = 2, total = 55.0), ALL, Place.OTHER, false, null))
        assertEquals(listOf("Pace", "Supplies", "Build", "Stun", "Boss Entry"), lines.map { it.substringBefore(" >") })
    }

    @Test
    fun `current split line`() {
        val r = rows(F7, listOf(20.0, 60.0, 4.0, 12.3), current = 3, total = 96.3)
        assertEquals("Maxor > 12.30s (12.30s)", text(listOfNotNull(EngineerLook.currentLine(r, ALL, Place.FLOOR7, false))).single())
        assertNull(EngineerLook.currentLine(rows(F7, List(9) { 1.0 }), ALL, Place.FLOOR7, false))
    }

    @Test
    fun `target boxes read seconds or minutes`() {
        assertEquals(61.0, EngineerLook.parseSeconds("61"))
        assertEquals(61.5, EngineerLook.parseSeconds(" 61.5s "))
        assertEquals(61.5, EngineerLook.parseSeconds("1:01.5"))
        assertEquals(61.5, EngineerLook.parseSeconds("1m 1.5s"))
        assertEquals(60.0, EngineerLook.parseSeconds("1m"))
        assertEquals(0.0, EngineerLook.parseSeconds("0"))
        assertNull(EngineerLook.parseSeconds(""))
        assertNull(EngineerLook.parseSeconds("fast"))
        assertNull(EngineerLook.parseSeconds("1:"))
        assertNull(EngineerLook.parseSeconds("-3"))
        assertEquals("57.06", EngineerLook.formatSeconds(57.055))
        assertEquals("4", EngineerLook.formatSeconds(4.0))
    }
}
