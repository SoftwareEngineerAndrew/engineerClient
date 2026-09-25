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
    private fun row(line: String) = plain(line).split('\t').let { it[0] + it.drop(1).joinToString(" | ") }

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
    fun `compact is door fell, last mob, key pickup delta, door opened delta, total`() {
        val lines = rush().lines(BloodRunDetail.Level.COMPACT, stamp(700)).map(::row)
        // Pipes starts when the start door starts falling (133), not on Mort's line.
        assertEquals("Pipes: 0.55s | 7.95s | 0.60s | 0.10s | 8.65s", lines[0])
        // The key was never seen on the ground here, so last mob falls back to the pickup.
        assertEquals("Deathmite: 0.65s | 5.65s | 0.00s | 0.10s | 5.75s", lines[2])
        assertTrue(lines.last().startsWith("Total: "))
    }

    @Test
    fun `the room that leads into fairy is pink`() {
        val lines = rush().lines(BloodRunDetail.Level.COMPACT, stamp(700))
        assertTrue(lines[3].startsWith("§dLocked Away: "), lines[3])
        assertTrue(lines[0].startsWith("§5Pipes: "), lines[0])
    }

    @Test
    fun `the room being run counts up live, all but its total`() {
        val lines = rush(until = 300).lines(BloodRunDetail.Level.COMPACT, stamp(300)).map(::row)
        // Key pickup is next: 8 ticks since the key appeared at 292.
        assertEquals(listOf("Pipes: 0.55s | 7.95s | 0.40s"), lines)
    }

    @Test
    fun `detailed is the same five, labelled, with averages at the end`() {
        val lines = rush().lines(BloodRunDetail.Level.DETAILED, stamp(700)).map(::plain)
        assertEquals(listOf("Pipes", "door fell > 0.55s (0.55s)", "last mob > 7.95s (7.95s)",
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
        assertTrue(lines.none { it.startsWith("Total: ") })
    }
}
