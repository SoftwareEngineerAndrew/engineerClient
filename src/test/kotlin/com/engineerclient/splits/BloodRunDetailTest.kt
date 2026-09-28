package com.engineerclient.splits

import com.engineerclient.splits.BloodRunDetail.MapRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Driven by the recorded F7 run 2026-09-23_21-01-37: its chat, and the doors and keys the world
 * showed, each on the tick it happened. That run went Pipes, Duncan, Deathmite, Locked Away, through
 * fairy, Pirate, Arrow Trap.
 */
class BloodRunDetailTest {

    private fun stamp(t: Int) = Stamp(t * 50L, t)
    private fun plain(line: String) = line.replace(Regex("§."), "")

    /** A compact row read back the way it looks on screen. */
    private fun row(line: String) = plain(line).replace('\t', ' ').replace(Regex(" +"), " ").trim()

    private val entrance = MapRoom("e", "Entrance", fairy = false, entrance = true)
    private val fairy = MapRoom("f", "Fairy", fairy = true, entrance = false)
    private fun room(name: String) = MapRoom(name, name, fairy = false, entrance = false)

    private fun rush(until: Int = Int.MAX_VALUE): BloodRunDetail {
        val b = BloodRunDetail()
        val events = listOf<Pair<Int, (Int) -> Unit>>(
            128 to { t -> b.onChat("[NPC] Mort: Here, I found this map when I first entered the dungeon.", stamp(t)) },
            131 to { t -> b.onDoorStart(stamp(t), room("Locked Away"), fairy) },   // fairy's own door
            133 to { t -> b.onDoorStart(stamp(t), entrance, room("Pipes")) },
            142 to { t -> b.onDoorDown(stamp(t), room("Locked Away"), fairy) },
            144 to { t -> b.onDoorDown(stamp(t), entrance, room("Pipes")) },
            292 to { t -> b.onKeySpawned(stamp(t)) },
            304 to { t -> b.onChat("[MVP+] johnswizzlechang has obtained Wither Key!", stamp(t)) },
            306 to { t -> b.onChat("TheBadOne opened a WITHER door!", stamp(t)) },
            319 to { t -> b.onDoorDown(stamp(t), room("Pipes"), room("Duncan")) },
            334 to { t -> b.onKeySpawned(stamp(t)) },
            340 to { t -> b.onChat("[MVP+] Teletappi has obtained Wither Key!", stamp(t)) },
            348 to { t -> b.onChat("TheBadOne opened a WITHER door!", stamp(t)) },
            361 to { t -> b.onDoorDown(stamp(t), room("Duncan"), room("Deathmite")) },
            461 to { t -> b.onChat("[MVP+] johnswizzlechang has obtained Wither Key!", stamp(t)) },
            463 to { t -> b.onChat("TheBadOne opened a WITHER door!", stamp(t)) },
            477 to { t -> b.onDoorDown(stamp(t), room("Deathmite"), room("Locked Away")) },
            518 to { t -> b.onKeySpawned(stamp(t)) },
            528 to { t -> b.onChat("[MVP+] Teletappi has obtained Wither Key!", stamp(t)) },
            529 to { t -> b.onChat("TheBadOne opened a WITHER door!", stamp(t)) },
            543 to { t -> b.onDoorDown(stamp(t), fairy, room("Pirate")) },
            622 to { t -> b.onKeySpawned(stamp(t)) },
            640 to { t -> b.onChat("[MVP+] RockyField21 has obtained Wither Key!", stamp(t)) },
            641 to { t -> b.onChat("TheBadOne opened a WITHER door!", stamp(t)) },
            655 to { t -> b.onDoorDown(stamp(t), room("Arrow Trap"), room("Pirate")) },
            673 to { t -> b.onKeySpawned(stamp(t)) },
            680 to { t -> b.onChat("The BLOOD DOOR has been opened!", stamp(t)) },
        )
        for ((t, f) in events) if (t <= until) f(t)
        return b
    }

    @Test
    fun `rooms are named by the door that fell, never Entrance`() {
        assertEquals(listOf("Pipes", "Duncan", "Deathmite", "Locked Away", "Pirate", "Arrow Trap"), rush().rooms())
    }

    @Test
    fun `compact is total, name, door down to key picked up, and the door only when slow`() {
        val raw = rush().lines(BloodRunDetail.Level.COMPACT, stamp(700))
        val lines = raw.map(::row)
        // Pipes starts when the start door starts falling (133), not on Mort's line; the key counts
        // from the door being down (144) to the pickup (304). Its door took 0.10s: not shown.
        assertEquals("8.00s Pipes: 8.65s", lines[0])
        // A pickup of 0.60s (key seen at 292): dark red.
        assertTrue(raw[0].split('\t')[1].startsWith("§4"), raw[0])
        // Duncan: a 0.30s pickup is light red, and its door took 0.40s, so it shows, light grey.
        assertEquals("0.40s | 1.05s Duncan: 2.10s", lines[1])
        assertTrue(raw[1].split('\t')[1].startsWith("§c") && raw[1].split('\t')[0].startsWith("§7"), raw[1])
        // The key never seen on the ground: from the door down to the pickup all the same.
        assertEquals("5.00s Deathmite: 5.75s", lines[2])
        assertTrue(lines.last().contains(" Avg: "), lines.last())
    }

    @Test
    fun `the room that leads into fairy is pink`() {
        val lines = rush().lines(BloodRunDetail.Level.COMPACT, stamp(700))
        assertTrue(lines[3].split('\t')[2].startsWith("§dLocked Away: "), lines[3])
        assertTrue(lines[0].split('\t')[2].startsWith("§5Pipes: "), lines[0])
    }

    @Test
    fun `the room being run counts up live, all but its total`() {
        val lines = rush(until = 300).lines(BloodRunDetail.Level.COMPACT, stamp(300)).map(::row)
        // Waiting on the pickup: 156 ticks since the door was down, the key on the ground 8 of them.
        assertEquals(listOf("7.80s Pipes:"), lines)
    }

    @Test
    fun `the key only counts once the door is down`() {
        val falling = rush(until = 140).lines(BloodRunDetail.Level.COMPACT, stamp(140)).map(::row)
        assertEquals(listOf("...:"), falling) // named once the door is down
        val down = rush(until = 150).lines(BloodRunDetail.Level.COMPACT, stamp(150)).map(::row)
        assertEquals(listOf("0.30s Pipes:"), down)
    }

    @Test
    fun `detailed is the same five, labelled, with averages at the end`() {
        val lines = rush().lines(BloodRunDetail.Level.DETAILED, stamp(700)).map(::plain)
        assertEquals(listOf("Pipes", "door fell > 0.55s (0.55s)", "last mob > 7.40s (7.40s)",
            "pickup > 0.60s (0.60s)", "opened > 0.10s (0.10s)", "room total > 8.65s (8.65s)", ""), lines.take(7))
        assertTrue(lines.any { it.startsWith("room total avg > ") })
        assertTrue(lines.none { it.contains("total room") || it.contains(":") })
    }

    @Test
    fun `extreme has all seven lines with who did it`() {
        val lines = rush().lines(BloodRunDetail.Level.EXTREME, stamp(700)).map(::plain)
        assertEquals("key picked up > 8.55s (8.55s) johnswizzlechang", lines[3])
        assertEquals("door opened > 8.65s (8.65s) TheBadOne", lines[5])
        assertTrue(lines.any { it.startsWith("average total room time > ") })
    }

    @Test
    fun `the total row can be turned off`() {
        val lines = rush().lines(BloodRunDetail.Level.COMPACT, stamp(700), totalRow = false).map(::row)
        assertEquals(6, lines.size)
        assertTrue(lines.none { it.contains("Avg: ") })
    }
}
