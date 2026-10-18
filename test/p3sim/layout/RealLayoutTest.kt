package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.DoorGeometry
import com.engineerclient.p3sim.clear.layout.DoorPlanner
import com.engineerclient.p3sim.clear.layout.LayoutBuilder
import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.DoorModel
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.run.DungeonPlan
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The layout builder against the REAL packs (outside this repo), headless: every pool layout is planned, its rooms' volumes are decoded, the
 * door edits are made, and the finished world is checked cell by cell at every doorway and every wall. Runs only when ADS_REAL_PACKS names the
 * packs folder; ADS_REAL_REPORT (a file path) receives a plain-text summary. Nothing from the data is printed but counts and design ids.
 */
class RealLayoutTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val report = StringBuilder()
    private fun say(s: String) { report.appendLine(s) }
    private fun flush() { System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), report.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); report.setLength(0) } }

    /** The state at a world cell after the rooms are pasted and the edits applied; null is air. */
    private class Final(val rooms: List<RoomInstance>, edits: List<com.engineerclient.p3sim.clear.layout.Edit>) {
        private val over = HashMap<Triple<Int, Int, Int>, String>().also { m -> for (e in edits) m[Triple(e.x, e.y, e.z)] = e.state }
        fun at(x: Int, y: Int, z: Int): String? {
            over[Triple(x, y, z)]?.let { return it.takeIf { s -> s != "minecraft:air" } }
            for (r in rooms) r.stateAt(x, y, z)?.let { return it }
            return null
        }
    }

    @Test fun everyPoolLayoutBuildsItsDoorsAndWalls() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val pool = layouts.pool()
            var built = 0; var failed = 0; var edits = 0L; var warnings = 0; var holes = 0; var blocked = 0; var wrongBody = 0
            val failReasons = HashMap<String, Int>(); val warnDesigns = HashMap<String, Int>(); val holeDesigns = HashMap<String, Int>(); val blockedDesigns = HashMap<String, Int>()
            val depths = HashMap<Int, Int>(); var carves = 0
            var maxMs = 0L; var totalMs = 0L; var cellsTotal = 0L; var cellsMax = 0L; var chunkCellsMax = 0L
            for (l in pool) {
                val plan = (DungeonPlan.build(l, 7L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val t0 = System.nanoTime()
                val b = when (val r = LayoutBuilder.build(plan, rooms)) {
                    is LayoutBuilder.Result.Failed -> { failed++; failReasons.merge(r.message.substringBefore(" cannot").take(70), 1, Int::plus); continue }
                    is LayoutBuilder.Result.Ok -> r.built
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                totalMs += ms; maxMs = maxOf(maxMs, ms)
                built++; edits += b.edits.size
                val cells = b.instances.sumOf { it.volume.nonAirCount().toLong() }
                cellsTotal += cells; cellsMax = maxOf(cellsMax, cells)
                // The busiest chunk: non-air cells of every room's volume that fall inside it (what one fill() call writes).
                val perChunk = HashMap<Long, Long>()
                for (inst in b.instances) {
                    val v = inst.volume; val pl = inst.placement
                    for (z in 0 until v.d) for (x in 0 until v.w) {
                        var n = 0
                        for (y in v.y0 until v.y0 + v.h) if (v.paletteIndexAt(x, y, z) != 0) n++
                        if (n == 0) continue
                        val w = pl.toWorld(x, v.y0, z)
                        perChunk.merge(((w.x shr 4).toLong() shl 32) or ((w.z shr 4).toLong() and 0xFFFFFFFFL), n.toLong(), Long::plus)
                    }
                }
                chunkCellsMax = maxOf(chunkCellsMax, perChunk.values.max())
                for (w in b.warnings) { warnings++; warnDesigns.merge(w.substringBefore(':'), 1, Int::plus) }
                val fin = Final(b.instances, b.edits)
                // Every wall: the first two planes of an unconnected side's opening hold no air.
                for (inst in b.instances) {
                    val p = inst.placement
                    for (slot in inst.design.slots) {
                        val wt = p.worldTileOf(slot.tile.x, slot.tile.z)
                        val side = DoorGeometry.turn(slot.side, p.quarterTurns)
                        val gap = DoorGeometry.slotOf(wt.x, wt.z, side)
                        val connected = gap != null && b.doors[gap]?.connected == true
                        if (connected) {
                            carves++
                            depths.merge(DoorPlanner.carveDepth(inst, wt.x, wt.z, side), 1, Int::plus)
                            // Passable: feet and head height are air in both planes (the lintel row and the row above the head may hold decoration), or the door's body in plane 0.
                            for (d in 0..1) for ((x, y, z) in DoorGeometry.openingCells(wt.x, wt.z, side, d)) {
                                if (y > 70) continue
                                val s = fin.at(x, y, z)
                                val body = if (d == 0) DoorModel.bodyBlock(b.doors[gap!!]!!) else null
                                if (s != body) { blocked++; blockedDesigns.merge(inst.design.id, 1, Int::plus); break }
                            }
                        } else {
                            // A wall: no air in the plane at the room's edge, so nothing walks out of the room there.
                            var hole = false
                            for ((x, y, z) in DoorGeometry.openingCells(wt.x, wt.z, side, 0)) if (fin.at(x, y, z) == null) hole = true
                            if (hole) { holes++; holeDesigns.merge(inst.design.id, 1, Int::plus) }
                        }
                    }
                }
                // Every doorway body in the gap column is the door's block.
                for ((slot, kind) in b.doors) {
                    if (!kind.connected) continue
                    val (cx, cz) = DungeonFrame.doorSpot(slot.axis, slot.tx, slot.tz)
                    val want = DoorModel.bodyBlock(kind)
                    for (y in 69..72) if (fin.at(cx, y, cz) != want) wrongBody++
                    check(DoorGeometry.gapCell(slot, 15) == (cx to cz))
                }
            }
            say("layout builds: pool=${pool.size} built=$built failed=$failed ($failReasons)")
            say("edits: total=$edits mean=${if (built > 0) edits / built else 0}; build ms: mean=${if (built > 0) totalMs / built else 0} max=$maxMs (decode of ~21 volumes, no world)")
            say("blocks per layout: mean=${if (built > 0) cellsTotal / built else 0} max=$cellsMax; busiest single chunk=$chunkCellsMax blocks (room volumes only, before door edits)")
            say("Odin core warnings=$warnings in designs: ${warnDesigns.entries.sortedByDescending { it.value }.take(15)}")
            say("carved doorway sides=$carves, carve depth histogram=$depths; doorways still blocked=$blocked ${blockedDesigns.entries.sortedByDescending { it.value }.take(10)}")
            say("wall sides with a hole=$holes ${holeDesigns.entries.sortedByDescending { it.value }.take(10)}; wrong door body cells=$wrongBody")
            assertEquals(0, failed, "layouts that did not build: $failReasons")
            assertEquals(0, blocked, "a doorway is blocked")
            assertEquals(0, holes, "a wall has a hole")
            assertEquals(0, wrongBody)
        } finally { set.close(); flush() }
    }

    /** Every pool layout names its rooms' designs the way the cores table does: a room whose core is known is built from the design that core belongs to (the Blood and Entrance second builds). */
    @Test fun designsAgreeWithTheCoresTable() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            val bad = ArrayList<String>()
            for (l in layouts.pool()) for (r in l.rooms) {
                val core = r.core ?: continue
                val want = rooms.designByCore(core) ?: continue
                // Two builds may share one core (the same centre column); then the recorded blocks decided, and the family must still match.
                if (r.design != null && r.design != want.design && rooms.design(r.design)?.family != rooms.design(want.design)?.family) bad += "${l.id} ${r.name}: ${r.design} vs ${want.design}"
                if (r.design == null && r.family in setOf("Blood", "Entrance", "Default")) bad += "${l.id} ${r.name}: core known, design not set"
            }
            say("designs vs cores table: ${bad.size} disagreements")
            assertEquals(emptyList<String>(), bad.take(10))
        } finally { set.close(); flush() }
    }

    /**
     * Odin's room scan, replayed on the finished world of every pool layout (rooms with the door edits on top): the core of each tile's centre
     * column must name the room, a 1x1 room's blue corner marker must turn up at the probe corner of the rotation the layout placed it with, and a
     * multi-tile room's tile set must give Odin's own geometry rotation. This is `WorldScan.getRoomCore` and `DungeonRoom.get1x1Rotation` /
     * `resolveGeometryRotation` of the Odin source, applied to the cells the region will write.
     */
    @Test fun odinWouldNameAndTurnEveryRoom() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            var tiles = 0; var wrongName = 0; var unknownCore = 0; var oneByOne = 0; var wrongTurn = 0; var noMarker = 0; var geomWrong = 0; var geomChecked = 0
            val badDesigns = HashMap<String, Int>()
            // Odin's probe order and corner offsets (RoomRotation: NORTH, SOUTH, WEST, EAST).
            val probes = listOf("NORTH" to (15 to 15), "SOUTH" to (-15 to -15), "WEST" to (15 to -15), "EAST" to (-15 to 15))
            for (l in layouts.pool()) {
                val plan = (DungeonPlan.build(l, 7L, rooms, set.runstate) as? DungeonPlan.Result.Ok)?.plan ?: continue
                val b = (LayoutBuilder.build(plan, rooms) as? LayoutBuilder.Result.Ok)?.built ?: continue
                val fin = Final(b.instances, b.edits)
                for (inst in b.instances) {
                    val design = inst.design; val p = inst.placement
                    for (t in p.tiles()) {
                        tiles++
                        val cx = DungeonFrame.tileCentre(t.x); val cz = DungeonFrame.tileCentre(t.z)
                        val core = com.engineerclient.p3sim.pack.rooms.OdinCore.ofStates { y -> fin.at(cx, y, cz) }
                        val entry = rooms.designByCore(core)
                        if (entry == null) { unknownCore++; badDesigns.merge(design.id, 1, Int::plus); continue }
                        if (rooms.design(entry.design)?.name != design.name) { wrongName++; badDesigns.merge(design.id, 1, Int::plus) }
                    }
                    if (design.shape == "1x1") {
                        oneByOne++
                        val t = p.tiles().first()
                        val cx = DungeonFrame.tileCentre(t.x); val cz = DungeonFrame.tileCentre(t.z)
                        var top = -1
                        for (y in 140 downTo 12) { val s = fin.at(cx, y, cz); if (s != null && s.substringBefore('[') != "minecraft:gold_block") { top = y; break } }
                        val found = probes.firstOrNull { (_, d) -> fin.at(cx + d.first, top, cz + d.second)?.substringBefore('[') == "minecraft:blue_terracotta" }?.first
                        if (found == null) { noMarker++; badDesigns.merge(design.id + " (no marker)", 1, Int::plus) }
                        else if (found != p.rot.name) { wrongTurn++; badDesigns.merge(design.id + " (turn)", 1, Int::plus) }
                    } else if (design.shape != "1x1") {
                        // Odin's resolveGeometryRotation, from the room's tiles alone.
                        val ts = p.tiles()
                        // Odin: topLeft is the corner of the bounding box (minimum x, minimum z), bottomRight the tile with the greatest x * 1000 + z.
                        val topLeft = com.engineerclient.p3sim.pack.Tile(ts.minOf { it.x }, ts.minOf { it.z }); val bottomRight = ts.maxBy { it.x * 1000 + it.z }
                        val odin = if (design.shape == "L") {
                            val other = ts.first { it != topLeft && it != bottomRight }
                            when {
                                topLeft.x == bottomRight.x -> "EAST"
                                topLeft.z == bottomRight.z -> "WEST"
                                other.x == topLeft.x -> "SOUTH"
                                else -> "NORTH"
                            }
                        } else if (topLeft.x == bottomRight.x) "WEST" else "SOUTH"
                        geomChecked++
                        if (odin != p.rot.name) { geomWrong++; badDesigns.merge(design.id + " (geometry)", 1, Int::plus) }
                    }
                }
            }
            say("Odin replay: tiles=$tiles unknownCore=$unknownCore wrongName=$wrongName; 1x1 rooms=$oneByOne noMarker=$noMarker wrongTurn=$wrongTurn; multi-tile geometry checked=$geomChecked wrong=$geomWrong")
            say("Odin replay problems by design: ${badDesigns.entries.sortedByDescending { it.value }.take(20)}")
            assertEquals(0, unknownCore + wrongName, "Odin would not name some tiles")
            assertEquals(0, geomWrong, "Odin's rotation from the tiles disagrees with the placement")
            assertEquals(0, noMarker, "a 1x1 room has no blue marker where Odin looks")
            // Rooms with two blue corners (the pack's ambiguous-marker designs) may be turned the first-found way; everything else must agree.
            assertEquals(0, wrongTurn, "Odin would turn a 1x1 room differently: $badDesigns")
        } finally { set.close(); flush() }
    }

    /**
     * With Minecraft's registries bootstrapped: every block state string of the room library parses on this version, and real layouts are filled
     * into real generation chunks, timed; Odin's room core is then read from those chunks' blocks (the WorldScan algorithm on `Block.toString`) and
     * must name every room. This is the headless stand-in for "paste a dungeon, let Odin look", minus the client.
     */
    @Test fun theLibraryParsesAndRealLayoutsFillIntoChunks() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        assumeTrue(TestChunks.boot(), "Minecraft cannot be bootstrapped here")
        val set = Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts); val rooms = assertNotNull(set.rooms)
            // 1. Every state string parses (FORMAT-rooms gate G3 on the version this mod runs on).
            val bad = HashMap<String, Int>(); var strings = 0
            fun parse(s: String) {
                strings++
                try { net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, s, false) } catch (e: Exception) { bad.merge(s, 1, Int::plus) }
            }
            for (d in rooms.designs) {
                rooms.volume(d)?.palette?.forEach(::parse)
                for (v in d.variants) rooms.variantVolume(d, v)?.palette?.forEach(::parse)
                for (sl in d.slots) sl.seal?.cells?.forEach { parse(it.state) }
            }
            say("state strings checked=$strings unparseable=${bad.size} ${bad.keys.take(5)}")
            assertEquals(0, bad.size, "states that do not parse on this Minecraft version")
            // 2. Fill real layouts.
            val pool = layouts.pool()
            var total = 0.0; var worst = 0.0; var blocks = 0L; var chunksN = 0; var entities = 0; var heads = 0; var headsTextured = 0; var tilesChecked = 0; var tilesWrong = 0
            for ((n, l) in pool.filterIndexed { i, _ -> i % 100 == 0 }.withIndex()) {
                val plan = (DungeonPlan.build(l, 7L, rooms, set.runstate) as DungeonPlan.Result.Ok).plan
                val built = (LayoutBuilder.build(plan, rooms) as LayoutBuilder.Result.Ok).built
                val region = com.engineerclient.p3sim.clear.layout.LayoutRegion("dungeon", built.instances, built.edits, rooms.textures)
                val chunks = HashMap<Pair<Int, Int>, net.minecraft.world.level.chunk.ProtoChunk>()
                for (cz in (region.minZ shr 4)..(region.maxZ shr 4)) for (cx in (region.minX shr 4)..(region.maxX shr 4)) if (region.intersects(cx shl 4, cz shl 4)) chunks[cx to cz] = TestChunks.chunk(cx, cz)
                val t0 = System.nanoTime()
                for (c in chunks.values) region.fill(c)
                val ms = (System.nanoTime() - t0) / 1e6
                total += ms; worst = maxOf(worst, ms); chunksN += chunks.size; blocks += region.stats().cells
                say("layout ${l.id}: ${chunks.size} chunks filled in %.0f ms (slowest chunk %.1f ms, %d blocks)%s".format(ms, region.stats().maxMs, region.stats().cells, if (n == 0) " [cold: includes JIT]" else ""))
                for (c in chunks.values) for (be in c.blockEntities.values) {
                    entities++
                    if (be is net.minecraft.world.level.block.entity.SkullBlockEntity) { heads++; if (be.ownerProfile != null) headsTextured++ }
                }
                // Odin's getRoomCore on the chunk the tile's centre column is in.
                for (inst in built.instances) for (t in inst.placement.tiles()) {
                    val x = DungeonFrame.tileCentre(t.x); val z = DungeonFrame.tileCentre(t.z)
                    val chunk = chunks.getValue((x shr 4) to (z shr 4))
                    val sb = StringBuilder(); var found = false; var bedrock = 0
                    for (y in 140 downTo 12) {
                        val st = chunk.getBlockState(net.minecraft.core.BlockPos(x, y, z))
                        if (!found) { if (!st.isAir && st.block !== net.minecraft.world.level.block.Blocks.GOLD_BLOCK) found = true else sb.append('0') }
                        if (found) {
                            if (st.isAir && bedrock >= 2 && y < 69) { repeat(y - 11) { sb.append('0') }; break }
                            if (st.block === net.minecraft.world.level.block.Blocks.BEDROCK) bedrock++
                            else {
                                bedrock = 0
                                val b = st.block
                                if (b === net.minecraft.world.level.block.Blocks.OAK_PLANKS || b === net.minecraft.world.level.block.Blocks.TRAPPED_CHEST || b === net.minecraft.world.level.block.Blocks.CHEST) continue
                            }
                            sb.append(st.block)
                        }
                    }
                    tilesChecked++
                    val entry = rooms.designByCore(sb.toString().hashCode())
                    if (entry == null || rooms.design(entry.design)?.name != inst.design.name) tilesWrong++
                }
            }
            say("real fill: ${chunksN} chunks, %.0f ms total over the sampled layouts, worst layout %.0f ms; %d blocks; block entities=%d, heads=%d of which textured=%d".format(total, worst, blocks, entities, heads, headsTextured))
            say("Odin core read from the filled chunks: tiles=$tilesChecked wrong=$tilesWrong")
            assertEquals(0, tilesWrong, "Odin would not name a tile of a filled chunk")
        } finally { set.close(); flush() }
    }
}
