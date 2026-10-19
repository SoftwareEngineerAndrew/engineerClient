package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorGeometry
import com.engineerclient.p3sim.clear.layout.LayoutBuilder
import com.engineerclient.p3sim.clear.layout.LayoutModule
import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PracticePlan
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.Runs
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Practice against the REAL packs: every room the practice screen lists is planned and built alone, centred, with its one doorway open and
 * bedrock behind it, the spawn a block in on the floor, and every other side of the room a wall. Runs only when ADS_REAL_PACKS is set.
 */
class RealPracticeTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    private class Final(val rooms: List<RoomInstance>, edits: List<com.engineerclient.p3sim.clear.layout.Edit>) {
        private val over = HashMap<Triple<Int, Int, Int>, String>().also { m -> for (e in edits) m[Triple(e.x, e.y, e.z)] = e.state }
        fun at(x: Int, y: Int, z: Int): String? {
            over[Triple(x, y, z)]?.let { return it.takeIf { s -> s != "minecraft:air" } }
            for (r in rooms) r.stateAt(x, y, z)?.let { return it }
            return null
        }
    }

    @Test fun everyPracticeRoomBuildsAloneWithOneWayIn() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val choices = PracticePlan.choices(layouts)
            val puzzles = choices.count { it.type == RoomType.PUZZLE }
            assertTrue(puzzles >= 8, "only $puzzles puzzles to practise")
            assertTrue(choices.size >= 50, "only ${choices.size} rooms to practise")
            assertTrue(choices.none { it.type == RoomType.ENTRANCE || it.type == RoomType.BLOOD })
            val problems = ArrayList<String>()
            for (c in choices) for (seed in 1L..2L) {
                val plan = when (val r = PracticePlan.build(c.name, layouts, seed, rooms, set.runstate)) {
                    is DungeonPlan.Result.Failed -> { problems += "${c.name}: ${r.failure.message}"; continue }
                    is DungeonPlan.Result.Ok -> r.plan
                }
                assertEquals(1, plan.rooms.size); assertTrue(plan.doors.isEmpty())
                val room = plan.rooms[0]
                val p = assertNotNull(plan.practice)
                assertTrue(room.tiles.all { it.x in 0 until DungeonFrame.TILES && it.z in 0 until DungeonFrame.TILES })
                assertTrue(room.tiles.any { it.x == p.tx && it.z == p.tz }, "${c.name}: the start tile is not the room's")
                val slot = DoorGeometry.slotOf(p.tx, p.tz, p.side) ?: run { problems += "${c.name}: the way in is off the grid"; continue }
                // Centred: the room's box is in the middle two (or three) tiles.
                val minX = room.tiles.minOf { it.x }; val maxX = room.tiles.maxOf { it.x }
                assertTrue(minX + maxX in 4..6, "${c.name}: not centred ($minX..$maxX)")
                val b = when (val r = LayoutBuilder.build(plan, rooms, mapOf(slot to DoorKind.NORMAL))) {
                    is LayoutBuilder.Result.Failed -> { problems += "${c.name}: ${r.message}"; continue }
                    is LayoutBuilder.Result.Ok -> r.built
                }
                val world = Final(b.instances, b.edits + LayoutModule.practiceBackstop(slot))
                // The spawn: two free blocks on a floor.
                val s = Runs.practiceSpawn(p)
                val sx = kotlin.math.floor(s.x).toInt(); val sz = kotlin.math.floor(s.z).toInt()
                if (world.at(sx, 69, sz) != null || world.at(sx, 70, sz) != null) problems += "${c.name} (seed $seed): the spawn is blocked (${world.at(sx, 69, sz)}, ${world.at(sx, 70, sz)})"
                if (world.at(sx, 68, sz) == null) problems += "${c.name} (seed $seed): no floor under the spawn"
                // Behind: bedrock across the gap.
                for (a in DoorGeometry.OPEN_MIN..DoorGeometry.OPEN_MAX) {
                    val (gx, gz) = DoorGeometry.gapCell(slot, a)
                    for (y in 69..72) if (world.at(gx, y, gz) != "minecraft:bedrock") problems += "${c.name}: the way in is not bedrock at $gx,$y,$gz"
                }
                // Every other side of the room: no opening (the edge plane mostly solid).
                val pl = assertNotNull(room.placement); val design = assertNotNull(room.design)
                for (ds in design.slots) {
                    val wt = pl.worldTileOf(ds.tile.x, ds.tile.z)
                    val side = DoorGeometry.turn(ds.side, pl.quarterTurns)
                    if (wt.x == p.tx && wt.z == p.tz && side == p.side) continue
                    val open = DoorGeometry.openingCells(wt.x, wt.z, side, 0).count { (x, y, z) -> world.at(x, y, z) == null }
                    if (open > 2) problems += "${c.name}: side $side of tile ${wt.x},${wt.z} has $open open cells"
                }
            }
            if (problems.isNotEmpty()) fail("${problems.size} practice problems:\n" + problems.distinct().take(40).joinToString("\n"))
        } finally { set.close() }
    }
}
