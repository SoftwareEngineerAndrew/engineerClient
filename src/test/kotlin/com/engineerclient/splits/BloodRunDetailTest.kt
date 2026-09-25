package com.engineerclient.splits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Driven by the chat of a real recorded F7 blood rush (2026-09-23_12-26-56). */
class BloodRunDetailTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)
    private fun plain(line: String) = line.replace(Regex("\u00a7."), "")

    private fun rush(): BloodRunDetail {
        val blood = BloodRunDetail()
        blood.onRoom("Hallway")
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(100))
        blood.onKeyDropped(stamp(120))
        blood.onChat("[MVP+] a has obtained Wither Key!", stamp(130))
        blood.onChat("a opened a WITHER door!", stamp(160))
        blood.onRoom("Dino")   // named once the party is actually in it
        blood.onDoorFell(stamp(170))          // the door just opened finishes coming down
        return blood
    }

    @Test
    fun `compact is one row a room, five times in a fixed order`() {
        val lines = rush().lines(BloodRunDetail.Level.COMPACT, stamp(200))
        assertEquals("Blood Rush", plain(lines[0]))
        // droped, pickup, opened, lowered, room total
        assertEquals("Hallway: 1.00s | 0.50s | 1.50s | 0.50s | 3.00s", plain(lines[1]))
        // The room being run has nothing yet: a row fills in as it is run, no dashes standing in.
        assertEquals("Dino: ", plain(lines[2]))
    }

    @Test
    fun `each column keeps its colour`() {
        val row = rush().lines(BloodRunDetail.Level.COMPACT, stamp(200))[1]
        assertTrue(row.startsWith("§5Hallway: "), row)
        for (code in listOf("§f1.00s", "§70.50s", "§c1.50s", "§40.50s", "§63.00s")) assertTrue(row.contains(code), code)
    }

    @Test
    fun `detailed is the same order, one labelled line each`() {
        val lines = rush().lines(BloodRunDetail.Level.DETAILED, stamp(200)).map { plain(it) }
        assertEquals(
            listOf("Blood Rush", "Hallway:", "droped > 1.00s", "pickup > 0.50s", "opened > 1.50s",
                   "lowered > 0.50s", "room total > 3.00s"),
            lines.take(7),
        )
    }

    @Test
    fun `the total only appears once the room is over`() {
        val blood = BloodRunDetail()
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(100))
        assertTrue(blood.lines(BloodRunDetail.Level.DETAILED, stamp(300)).none { plain(it).contains("room total") })
    }

    @Test
    fun `off shows nothing`() {
        assertEquals(emptyList(), rush().lines(BloodRunDetail.Level.OFF, stamp(200)))
    }

    @Test
    fun `the total only lands once the blood door is down`() {
        val blood = rush()
        // One room is finished, but a running average of one room says nothing.
        assertTrue(blood.lines(BloodRunDetail.Level.COMPACT, stamp(200)).none { it.startsWith("§6Total") })
        blood.onChat("[MVP+] b has obtained Wither Key!", stamp(300))
        blood.onChat("The BLOOD DOOR has been opened!", stamp(400))
        val compact = blood.lines(BloodRunDetail.Level.COMPACT, stamp(500))
        assertTrue(compact.last().startsWith("§6Total: "), compact.last())
    }

    @Test
    fun `the room that leads into fairy is pink, the rest purple`() {
        val blood = BloodRunDetail()
        blood.onRoom("Hallway")
        blood.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(100))
        blood.onChat("a opened a WITHER door!", stamp(200))
        blood.onRoom("Fairy")   // walking in marks the room just left, not this one
        blood.onChat("a opened a WITHER door!", stamp(300))
        val rows = blood.lines(BloodRunDetail.Level.COMPACT, stamp(400))
        assertTrue(rows[1].startsWith("§dHallway:"), rows[1])
        assertTrue(rows[2].startsWith("§5Fairy:"), rows[2])
    }
}
