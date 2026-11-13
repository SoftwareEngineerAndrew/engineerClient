package com.engineerclient.culling

import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.doors.DoorPack
import com.engineerclient.dungeonpack.pack.layouts.Layout
import com.engineerclient.dungeonpack.pack.layouts.LayoutPack
import com.engineerclient.dungeonpack.pack.rooms.RoomPack
import com.engineerclient.dungeonpack.rooms.Edit
import com.engineerclient.dungeonpack.rooms.RegionEdits
import com.engineerclient.dungeonpack.rooms.RoomInstance
import com.engineerclient.dungeonpack.rooms.Slot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Links.read] on real dungeons: every recorded layout of the bundled layout pool, built block for block from the room
 * library and the door pack the way ADS builds it, against what the layout says stands in each of its 60 slots.
 */
class LinksPackTest {
    private val dir = Path.of("src/resources/assets/engineerclient/dungeonpack/packs")
    private val rooms = RoomPack.load(dir)!!
    private val layouts = LayoutPack.load(dir, rooms)!!

    /** A layout's blocks: the pasted rooms, then the edits over them (the last one on a cell stands). */
    private class Built(val rooms: List<RoomInstance>, edits: List<Edit>) : Columns {
        /** Columns of the gaps that a room's own wall stands in (the door pack's gap walls: one entrance design's). */
        val walled: Set<Pair<Int, Int>> = buildSet {
            for (r in rooms) for (c in DoorPack.bundled.gapWalls[r.design.id].orEmpty()) r.placement.toWorld(c.x, c.y, c.z).let { add(it.x to it.z) }
        }
        private val edit = HashMap<Long, Boolean>()
        private val editYs = HashMap<Long, HashSet<Int>>()
        init {
            for (e in edits) {
                edit[cell(e.x, e.y, e.z)] = e.state != "minecraft:air"
                editYs.getOrPut(column(e.x, e.z)) { HashSet() }.add(e.y)
            }
        }
        override fun empty(x: Int, z: Int): Boolean? {
            for (r in rooms) {
                if (r.placement.toCanonical(x, z) == null) continue
                for (y in r.volume.y0 until r.volume.y0 + r.volume.h) if (edit[cell(x, y, z)] ?: r.isSolid(x, y, z)) return false
            }
            editYs[column(x, z)]?.forEach { y -> if (edit[cell(x, y, z)] == true) return false }
            return true
        }
        private fun cell(x: Int, y: Int, z: Int): Long = ((x + 512).toLong() shl 40) or ((z + 512).toLong() shl 20) or y.toLong()
        private fun column(x: Int, z: Int): Long = ((x + 512).toLong() shl 20) or (z + 512).toLong()
    }

    private fun build(layout: Layout): Built? {
        val instances = ArrayList<RoomInstance>()
        for (lr in layout.rooms) {
            val entry = lr.core?.let { rooms.designByCore(it) }
            val design = lr.design?.let { rooms.design(it) } ?: entry?.let { rooms.design(it.design) } ?: return null
            val placement = runCatching { design.place(lr.rot ?: return null, lr.tiles) }.getOrNull() ?: return null
            val volume = rooms.volumeFor(design, entry?.takeIf { it.design == design.id }?.variant) ?: return null
            instances += RoomInstance(design, volume, placement)
        }
        val doors = layout.doors.associate { Slot(it.axis, it.a.x, it.a.z) to it.kind }
        return Built(instances, RegionEdits.build(instances, doors, 0L))
    }

    /** What the layout itself says stands in a slot. */
    private fun truth(layout: Layout, axis: Char, tx: Int, tz: Int): Link {
        val (a, b) = DungeonFrame.slotTiles(axis, tx, tz)
        val ra = layout.roomAt(a.x, a.z); val rb = layout.roomAt(b.x, b.z)
        return when {
            ra != null && ra === rb -> Link.JOIN
            layout.doorAt(axis, tx, tz)?.kind?.connected == true -> Link.DOOR
            else -> Link.GAP
        }
    }

    private fun slots(): List<Triple<Char, Int, Int>> = buildList {
        for (axis in "ES") for (tz in 0 until 6) for (tx in 0 until 6) if (DungeonFrame.isSlot(axis, tx, tz)) add(Triple(axis, tx, tz))
    }

    @Test fun everySlotOfEveryRecordedLayoutReadsAsTheLayoutSays() {
        var built = 0; var read = 0; var walled = 0
        val counts = HashMap<Link, Int>()
        val wrong = ArrayList<String>()
        for (layout in layouts.layouts) {
            val world = build(layout) ?: continue
            built++
            for ((axis, tx, tz) in slots()) {
                // One entrance design walls up the whole gap beside its door: by its columns that gap is a room's, so the
                // entrance and the room in front of it read as one room (a tile more than needed is drawn there).
                val wall = Links.BESIDE.any { Links.cell(axis, tx, tz, it) in world.walled }
                if (wall) walled++
                val want = if (wall) Link.JOIN else truth(layout, axis, tx, tz)
                val got = Links.read(axis, tx, tz, world)
                // the margin the rule has: a room fills every column beside the middle, a door or a gap none
                val beside = Links.BESIDE.count { a -> Links.cell(axis, tx, tz, a).let { (x, z) -> world.empty(x, z) } == false }
                if (!wall && beside != (if (want == Link.JOIN) Links.BESIDE.size else 0)) wrong += "${layout.id} $axis $tx,$tz: $beside columns beside the middle hold blocks, in a $want"
                read++
                counts.merge(want, 1, Int::plus)
                if (got != want) {
                    val (a, b) = DungeonFrame.slotTiles(axis, tx, tz)
                    wrong += "${layout.id} $axis $tx,$tz: $got, the layout says $want (${layout.roomAt(a.x, a.z)?.let { it.design ?: it.name }} | ${layout.roomAt(b.x, b.z)?.let { it.design ?: it.name }}, door ${layout.doorAt(axis, tx, tz)?.kind})"
                }
            }
        }
        println("layouts built $built of ${layouts.layouts.size}, slots read $read: $counts ($walled of them beside an entrance's own gap wall), wrong ${wrong.size}")
        wrong.take(40).forEach(::println)
        assertTrue(built >= 500, "only $built layouts could be built")
        assertTrue(walled <= built, "$walled slots beside a gap wall in $built layouts")
        assertEquals(emptyList(), wrong.take(40))
    }

    @Test fun doorUnitsStayClearOfTheColumnsThatTellARoomFromADoor() {
        val pack = DoorPack.bundled
        for (unit in pack.styles + pack.entrance + pack.blood) {
            val along = (unit.cells + unit.keyCells).filter { it.dd == 0 }.map { it.a }
            assertTrue(along.isNotEmpty(), unit.name)
            assertTrue(Links.BESIDE.none { it in along.min()..along.max() }, "${unit.name}: ${along.min()}..${along.max()} along the side")
            assertTrue(Links.MIDDLE in along, "${unit.name}: nothing in the gap's middle column")
        }
    }

    @Test fun theRuleLeavesMostOfADungeonOut() {
        // how much of a dungeon's 169 chunk columns the rule keeps, standing on each tile of each layout
        var n = 0; var tiles = 0L; var chunks = 0L; var most = 0
        for (layout in layouts.layouts) {
            val world = build(layout) ?: continue
            val links = Array(Links.COUNT) { Links.read(it, world) }
            for (tz in 0 until 6) for (tx in 0 until 6) {
                if (layout.roomAt(tx, tz) == null) continue
                val set = TileRule.relevant(tx, tz) { links[it] }
                val mask = CullMask(set)
                n++; tiles += java.lang.Long.bitCount(set); chunks += mask.kept; most = maxOf(most, mask.kept)
            }
        }
        println("standing on each of $n tiles: ${"%.1f".format(tiles.toDouble() / n)} tiles kept on average, ${"%.1f".format(chunks.toDouble() / n)} of ${CullMask.SPAN * CullMask.SPAN} chunk columns (most $most)")
        assertTrue(n > 10_000 && chunks.toDouble() / n < 40.0, "$n tiles, ${chunks.toDouble() / n} chunk columns kept on average")
    }
}
