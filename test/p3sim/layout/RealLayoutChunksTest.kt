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
 * The real packs' block library against this Minecraft version, and real layouts filled into proto-chunks (split from [RealLayoutTest] so the
 * two slow halves run in parallel test JVMs). Runs only when ADS_REAL_PACKS names the packs folder; ADS_REAL_REPORT receives a summary.
 */
class RealLayoutChunksTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val report = StringBuilder()
    private fun say(s: String) { report.appendLine(s) }
    private fun flush() { System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), report.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); report.setLength(0) } }

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
