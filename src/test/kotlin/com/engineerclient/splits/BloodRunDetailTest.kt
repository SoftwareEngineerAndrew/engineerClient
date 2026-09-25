package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Driven by the chat of a real recorded F7 blood rush (2026-09-23_12-26-56). */
class BloodRunDetailTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)
    private fun plain(line: String) = line.replace(Regex("\u00a7."), "")

    @Test
    fun `each room is a column, timed from its own start`() {
        val blood = BloodRunDetail()
        blood.onRoom("Entrance")
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(135))
        blood.onDoorFell(stamp(150))
        blood.onKeyDropped(stamp(270))
        blood.onChat("[MVP+] johnswizzlechang has obtained Wither Key!", stamp(279))
        blood.onRoom("Water Board")
        blood.onChat("johnswizzlechang opened a WITHER door!", stamp(328))

        val first = blood.columns(stamp(400))[0]
        assertEquals(
            listOf(
                "Entrance",
                "0.75s (0.75s) door fell",
                "6.75s (6.75s) last mob killed",
                "7.20s (7.20s) key picked up (johnswizzlechang)",
                "0.45s (0.45s) key delta",
                "9.65s (9.65s) door opened (johnswizzlechang)",
                "2.45s (2.45s) door delta",
                "9.65s (9.65s) total room time",
            ),
            first.map { plain(it) },
        )
    }

    @Test
    fun `the room being run counts up live`() {
        val blood = BloodRunDetail()
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(100))
        // Nothing has happened in it yet, so it is a name and a total that keeps climbing.
        assertEquals(listOf("Entrance", "5.00s (5.00s) total room time"), blood.columns(stamp(200)).single().map { plain(it) })
        assertEquals(listOf("Entrance", "10.00s (10.00s) total room time"), blood.columns(stamp(300)).single().map { plain(it) })
    }

    @Test
    fun `each kind of line gets its own colour`() {
        val blood = BloodRunDetail()
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(100))
        blood.onDoorFell(stamp(110))
        blood.onChat("[MVP+] a has obtained Wither Key!", stamp(150))
        blood.onChat("a opened a WITHER door!", stamp(200))
        val column = blood.columns(stamp(200))[0]
        assertTrue(column.all { it.startsWith("§f") || it.startsWith("§a") }, "times lead, and they are green")
        assertTrue(column.first { plain(it).contains("door fell") }.contains("§7door fell"))
        assertTrue(column.first { plain(it).contains("key picked up") }.contains("§8key picked up"))
        assertTrue(column.first { plain(it).contains("door opened") }.contains("§cdoor opened"))
        assertTrue(column.first { plain(it).contains("total room time") }.contains("§6total room time"))
    }

    @Test
    fun `the blood door ends the rush and averages every stat in gold`() {
        val blood = BloodRunDetail()
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(135))
        blood.onChat("[MVP+] a has obtained Wither Key!", stamp(279))
        blood.onChat("a opened a WITHER door!", stamp(328))
        blood.onChat("[MVP+] b has obtained Wither Key!", stamp(442))
        blood.onChat("b opened a WITHER door!", stamp(489))
        blood.onChat("[MVP+] c has obtained Blood Key!", stamp(834))
        blood.onChat("The BLOOD DOOR has been opened!", stamp(878))

        val columns = blood.columns(stamp(900))
        assertEquals(4, columns.size, "three rooms and the averages")
        val averages = columns.last()
        assertTrue(averages.all { it.startsWith("§a") && it.contains("§6average") }, "green time, gold text")
        assertEquals(
            listOf("average last mob killed", "average key picked up", "average key delta",
                   "average door opened", "average door delta", "average total room time"),
            averages.map { plain(it).substringAfter(") ") },
        )
    }
}
