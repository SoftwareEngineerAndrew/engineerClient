package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Driven by the chat of a real recorded F7 blood rush (2026-09-23_12-26-56). */
class BloodRunDetailTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)

    @Test
    fun `each room gets its own block, timed from its own start`() {
        val detail = SplitDetail()
        val blood = BloodRunDetail(detail)
        blood.onRoom("Entrance")
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(135))
        blood.onDoorFell(stamp(150))
        blood.onKeyDropped(stamp(270))
        blood.onChat("[MVP+] johnswizzlechang has obtained Wither Key!", stamp(279))
        blood.onRoom("Water Board")
        blood.onChat("johnswizzlechang opened a WITHER door!", stamp(328))

        val lines = detail.lines(SplitTracker.BLOOD).map { it.label }
        assertEquals("§fEntrance", lines[0])
        assertTrue(lines[1].contains("door fell"), lines[1])
        assertTrue(lines[2].contains("last mob killed"))
        assertTrue(lines[3].contains("key picked up") && lines[3].contains("§7(johnswizzlechang)"))
        assertTrue(lines[4].contains("key delta"))
        assertTrue(lines[5].contains("door opened"))
        assertTrue(lines[6].contains("door delta"))
        assertTrue(lines[7].contains("total room time"))
        assertEquals(" ", lines[8], "a blank line between rooms")

        // 328 - 135 = 193 ticks = 9.65s, measured from this room's start, not the run's.
        assertTrue(lines[7].startsWith("§f9.65s"), lines[7])
        // The key landed at 270 and was picked up at 279: 9 ticks.
        assertTrue(lines[4].startsWith("§f0.45s"), lines[4])
    }

    @Test
    fun `the blood door ends the rush and averages every stat in gold`() {
        val detail = SplitDetail()
        val blood = BloodRunDetail(detail)
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(135))
        blood.onChat("[MVP+] a has obtained Wither Key!", stamp(279))
        blood.onChat("a opened a WITHER door!", stamp(328))
        blood.onChat("[MVP+] b has obtained Wither Key!", stamp(442))
        blood.onChat("b opened a WITHER door!", stamp(489))
        blood.onChat("[MVP+] c has obtained Blood Key!", stamp(834))
        blood.onChat("The BLOOD DOOR has been opened!", stamp(878))

        val lines = detail.lines(SplitTracker.BLOOD).map { it.label }
        val averages = lines.filter { it.startsWith("§6") }
        assertEquals(
            listOf(
                "average last mob killed", "average key picked up", "average key delta",
                "average door opened", "average door delta", "average total room time",
            ),
            averages.map { it.substringAfter(")§f ") },
        )
        // Three rooms ran: 135-328, 328-489, 489-878.
        assertEquals(3, lines.count { !it.startsWith("§6") && it.contains("total room time") })
    }
}
