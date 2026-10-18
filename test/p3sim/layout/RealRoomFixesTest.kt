package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-two room fixes against the REAL packs (ADS_REAL_PACKS; skipped otherwise): the blood room is the recorded red build, without the portal the
 * server places only after the Watcher has fallen; the five rooms where a tripwire hit happens have their hooks and strings; the trap rooms carry the marker
 * conversions Hypixel's server makes. Only counts, design ids and canonical cells are asserted.
 */
class RealRoomFixesTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private fun <T> withRooms(body: (RoomPack, com.engineerclient.p3sim.pack.PackSet) -> T): T {
        val set = Packs.load(dir!!)
        try { return body(assertNotNull(set.rooms), set) } finally { set.close() }
    }

    private fun volume(rooms: RoomPack, id: String): Pair<RoomDesign, RoomVolume> {
        val d = assertNotNull(rooms.design(id), "design $id")
        return d to assertNotNull(rooms.volume(d), "volume of $id")
    }

    @Test fun theBloodRoomIsTheRecordedRedBuildWithoutThePortal() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        withRooms { rooms, set ->
            // Two builds, one per Odin core; the stale capture that shared the red core is gone.
            assertEquals(listOf("Blood", "Blood__b3"), rooms.designsOfFamily("Blood").map { it.id }.sorted())
            assertEquals("Blood", rooms.designByCore(698844034)?.design)
            assertEquals("Blood__b3", rooms.designByCore(1898104308)?.design)
            for (id in listOf("Blood", "Blood__b3")) {
                val (_, v) = volume(rooms, id)
                // The server paints the portal only after the Watcher has fallen (the blood module opens it); a room built from the chunk data has none (12 cells in the alcove).
                assertTrue(v.palette.none { it.startsWith("minecraft:nether_portal") }, "$id has a portal")
            }
            // The red build has the four tall interior pillars (224 smooth stone cells) that the old capture lacked.
            val (_, red) = volume(rooms, "Blood")
            var smooth = 0
            val smoothIdx = red.palette.indexOf("minecraft:smooth_stone").takeIf { it >= 0 }
            if (smoothIdx != null) for (c in red.cells) if ((c.toInt() and 0xFFFF) == smoothIdx) smooth++
            assertTrue(smooth >= 200, "smooth stone cells $smooth")
            // The alcove where the portal appears is open air, and the room's door row (north side) is open as the recorded build has it.
            for (x in 14..16) for (y in 69..72) assertTrue(red.isAir(x, y, 29), "alcove $x,$y")
            // Every blood room of every pool layout whose Odin core is the red one is built from that design.
            val layouts = assertNotNull(set.layouts)
            var checked = 0
            for (l in layouts.pool()) for (r in l.rooms) if (r.family == "Blood" && r.core == 698844034) { checked++; assertEquals("Blood", r.design, "${l.id}") }
            assertTrue(checked > 100, "red blood rooms in the pool: $checked")
        }
    }

    /** Hooks pair east/west on one row and south/north on one column with a tripwire string in every cell between. */
    private fun lines(rooms: RoomPack, id: String): Int {
        val (d, v) = volume(rooms, id)
        val hooks = d.tripwires
        var lines = 0
        for (h in hooks) {
            val dx = when (h.facing) { "east" -> 1; else -> 0 }; val dz = when (h.facing) { "south" -> 1; else -> 0 }
            if (dx == 0 && dz == 0) continue
            var k = 1
            while (v.stateAt(h.pos.x + dx * k, h.pos.y, h.pos.z + dz * k).startsWith("minecraft:tripwire[")) k++
            val far = v.stateAt(h.pos.x + dx * k, h.pos.y, h.pos.z + dz * k)
            val back = if (dx == 1) "west" else "north"
            assertTrue(k > 1 && far.startsWith("minecraft:tripwire_hook") && far.contains("facing=$back"), "$id: the string from ${h.pos} ends in $far after ${k - 1} cells")
            lines++
        }
        assertEquals(hooks.size, lines * 2, "$id: every hook belongs to one line")
        return lines
    }

    @Test fun theFiveRoomsWhereATripwireHitsHaveTheirHooksAndStrings() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        withRooms { rooms, _ ->
            // Hooks and strings come from the server's block updates after the chunk data; the counts are those of the recorded sessions.
            val want = mapOf("Cathedral" to 2, "Painting" to 1, "Chambers" to 1, "Lava_Ravine" to 5, "Golden_Oasis" to 1)
            for ((id, n) in want) assertEquals(n, lines(rooms, id), id)
        }
    }

    @Test fun theTrapRoomsCarryTheMarkerConversionsOfTheServer() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        withRooms { rooms, _ ->
            // dispenser -> stone, and (New Trap) dispenser -> tripwire hook, in every real visit, a few hundred ticks before the first entry.
            val (_, nt) = volume(rooms, "New_Trap")
            assertEquals("minecraft:stone", nt.stateAt(16, 81, 16))
            assertTrue(nt.stateAt(16, 81, 17).startsWith("minecraft:tripwire_hook") && nt.stateAt(16, 81, 17).contains("facing=south"))
            val (_, ot) = volume(rooms, "Old_Trap")
            assertEquals("minecraft:stone", ot.stateAt(13, 81, 9))
        }
    }
}
