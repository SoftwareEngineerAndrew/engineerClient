package com.engineerclient.betterpf

import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Captures dungeon geometry once instead of streaming it: every block of each ROOM goes to the
 * server's room library (keyed "Name|ROTATION", identical in every run). The 1-block gaps between
 * rooms aren't captured - the viewer leaves them as air - except the door boxes, which differ per run
 * (wither/blood doors, openings walled up where no room connects) and go in the run as "door"
 * volumes. The boss arena goes to the library too, one 16x16 chunk column at a time (keyed
 * "Boss|FLOOR|cx,cz"), as its chunks load. After that, the run only records changes (block updates).
 *
 * Geometry comes out as volume lines: palette + run-length encoded block indices in y, z, x order
 * (x fastest). Palette index 0 is always "" = not part of this volume (outside an L-shaped room's
 * footprint, say), so a volume never overwrites what another one placed.
 *
 * Scanning is budgeted per tick so a full room (31x31x256 ≈ 250k blocks) is spread over ~10 ticks.
 */
class GeometryCapture(private val emit: (String) -> Unit, private val libraryKeys: () -> Set<String>?) {

    private var jobs = ArrayDeque<VolumeJob>()
    private var active: VolumeJob? = null
    private val queuedRooms = HashSet<String>()
    private var ticksWaitingForLibrary = 0

    // Every place a door can be: the middle of each tile edge inside the grid. The box is generous
    // (DOOR_ALONG either side of the middle, DOOR_ACROSS blocks into each room, y DOOR_Y up
    // DOOR_H) so everything around a doorway that differs between runs - the door, the opening,
    // or the wall filling it - comes from this run. Written whole, air included.
    // Each spot: x0, z0, w, d.
    private val doorSpots = ArrayList<IntArray>().apply {
        val across = 2 * DOOR_ACROSS + 1; val along = 2 * DOOR_ALONG + 1
        for (i in 1..5) for (j in 0..5) {
            val gap = GRID_ORIGIN - 1 + 32 * i; val mid = GRID_ORIGIN + 32 * j + 15
            add(intArrayOf(gap - DOOR_ACROSS, mid - DOOR_ALONG, across, along))
            add(intArrayOf(mid - DOOR_ALONG, gap - DOOR_ACROSS, along, across))
        }
    }
    private val doorsDone = HashSet<Int>()

    private fun queueDoors(level: ClientLevel) {
        for ((i, spot) in doorSpots.withIndex()) {
            if (i in doorsDone || !loaded(level, spot[0], spot[1], spot[2], spot[3])) continue
            doorsDone += i
            jobs.addFirst(VolumeJob("door", null, spot[0], DOOR_Y, spot[1], spot[2], DOOR_H, spot[3], null))
        }
    }

    fun tick(level: ClientLevel, t: Int) {
        if (t % 10 == 0) { queueDoors(level); queueRooms(level); queueBoss(level) }
        var budget = BLOCKS_PER_TICK
        while (budget > 0) {
            val job = active ?: jobs.removeFirstOrNull() ?: return
            active = job
            budget -= job.step(level, budget)
            if (job.done) { emit(job.toLine(t)); active = null }
        }
    }

    /**
     * Queues every room Odin has fully identified (name, rotation, all tiles) that the library
     * doesn't have. Waits up to 5s for the library list; if it never arrives, captures anyway -
     * the server just ignores entries it already has.
     */
    private fun queueRooms(level: ClientLevel) {
        val have = libraryKeys()
        if (have == null && ticksWaitingForLibrary++ < 10) return
        for (room in DungeonScan.rooms) {
            val name = room.data?.name ?: continue
            val rotation = room.rotation ?: continue
            // room.shape stays OneByOne until Odin infers the layout; data.shape is the real one.
            if (room.tiles.size != (room.data?.shape?.tileAmount ?: continue)) continue
            val key = "$name|${rotation.name}"
            if (key in queuedRooms || (have != null && key in have)) continue
            val tiles = room.tiles.map { it.x to it.z }.toSet()
            val minX = tiles.minOf { it.first }; val maxX = tiles.maxOf { it.first }
            val minZ = tiles.minOf { it.second }; val maxZ = tiles.maxOf { it.second }
            val x0 = GRID_ORIGIN + 32 * minX; val z0 = GRID_ORIGIN + 32 * minZ
            val w = 32 * (maxX - minX) + 31; val d = 32 * (maxZ - minZ) + 31
            if (!loaded(level, x0, z0, w, d)) continue
            queuedRooms += key
            val (y0, h) = yRange(level)
            jobs.addLast(VolumeJob("lib", key, x0, y0, z0, w, h, d) { lx, lz -> inRoom(tiles, x0 + lx, z0 + lz) })
        }
    }

    /**
     * In the boss: queues every loaded chunk column of the arena the library doesn't have. The arena
     * is where Odin says the boss starts (x and z past a per-floor limit), and its far edge is
     * wherever the chunks stop having blocks: air-only chunks and air-only 16-high sections are
     * skipped without scanning, so the void around the arena costs nothing.
     */
    private fun queueBoss(level: ClientLevel) {
        if (!DungeonUtils.inBoss) return
        val have = libraryKeys()
        val floor = DungeonUtils.floor ?: return
        // Odin's inBoss test: x > limitX && z > limitZ.
        val (limitX, limitZ) = when (floor.floorNumber) {
            1 -> -71 to -39
            in 2..4 -> -39 to -39
            in 5..6 -> -39 to -7
            7 -> -7 to -7
            else -> return
        }
        // First chunk entirely past the limit, so no column overlaps the room grid.
        val cx0 = Math.floorDiv(limitX + 16, 16); val cz0 = Math.floorDiv(limitZ + 16, 16)
        for (cx in cx0..cx0 + BOSS_CHUNKS) for (cz in cz0..cz0 + BOSS_CHUNKS) {
            val key = "Boss|${floor.name}|$cx,$cz"
            if (key in queuedRooms || (have != null && key in have)) continue
            val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
            queuedRooms += key
            val sections = chunk.sections
            val filled = sections.indices.filter { !sections[it].hasOnlyAir() }
            if (filled.isEmpty()) continue
            val y0 = maxOf(0, level.getSectionYFromSectionIndex(filled.first()) * 16)
            val y1 = minOf(256, (level.getSectionYFromSectionIndex(filled.last()) + 1) * 16)
            if (y1 <= y0) continue
            jobs.addLast(VolumeJob("lib", key, cx * 16, y0, cz * 16, 16, y1 - y0, 16, null))
        }
    }

    /**
     * Whether a block column belongs to a room made of [tiles]: inside one of its tiles, or in the
     * 1-block gap strip between two of its tiles (the room is open there), or the centre cell of a
     * 2x2 block of its tiles.
     */
    private fun inRoom(tiles: Set<Pair<Int, Int>>, x: Int, z: Int): Boolean {
        val gx = Math.floorDiv(x - GRID_ORIGIN + 1, 32); val gz = Math.floorDiv(z - GRID_ORIGIN + 1, 32)
        val gapX = Math.floorMod(x - GRID_ORIGIN + 1, 32) == 0
        val gapZ = Math.floorMod(z - GRID_ORIGIN + 1, 32) == 0
        fun has(a: Int, b: Int) = (a to b) in tiles
        return when {
            !gapX && !gapZ -> has(gx, gz)
            gapX && !gapZ -> has(gx - 1, gz) && has(gx, gz)
            !gapX && gapZ -> has(gx, gz - 1) && has(gx, gz)
            else -> has(gx - 1, gz - 1) && has(gx, gz - 1) && has(gx - 1, gz) && has(gx, gz)
        }
    }

    private fun loaded(level: ClientLevel, x0: Int, z0: Int, w: Int, d: Int): Boolean {
        for (cx in (x0 shr 4)..((x0 + w - 1) shr 4)) for (cz in (z0 shr 4)..((z0 + d - 1) shr 4))
            if (level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) == null) return false
        return true
    }

    private fun yRange(level: ClientLevel): Pair<Int, Int> {
        val y0 = maxOf(0, level.minY)
        return y0 to (minOf(256, level.maxY + 1) - y0)
    }

    private class VolumeJob(
        val kind: String, val key: String?,
        val x0: Int, val y0: Int, val z0: Int, val w: Int, val h: Int, val d: Int,
        mask: ((Int, Int) -> Boolean)?,
    ) {
        private val columns: BooleanArray? = mask?.let { m -> BooleanArray(w * d) { i -> m(i % w, i / w) } }
        private val total = w * h * d
        private var index = 0
        private val paletteOf = HashMap<BlockState, Int>()
        private val names = arrayListOf("")
        private var rle = IntArray(1024)
        private var rleSize = 0
        private var runValue = -1
        private var runLength = 0
        val done get() = index >= total

        /** Scans up to [budget] blocks; returns how many it used. */
        fun step(level: ClientLevel, budget: Int): Int {
            val pos = BlockPos.MutableBlockPos()
            val end = minOf(total, index + budget)
            val start = index
            while (index < end) {
                val lx = index % w
                val rest = index / w
                val lz = rest % d
                val ly = rest / d
                val v = if (columns != null && !columns[lz * w + lx]) 0 else {
                    val state = level.getBlockState(pos.set(x0 + lx, y0 + ly, z0 + lz))
                    paletteOf.getOrPut(state) { names += BlockStateParser.serialize(state); names.size - 1 }
                }
                if (v == runValue) runLength++ else { flush(); runValue = v; runLength = 1 }
                index++
            }
            if (done) flush()
            return end - start
        }

        private fun flush() {
            if (runLength == 0) return
            if (rleSize + 2 > rle.size) rle = rle.copyOf(rle.size * 2)
            rle[rleSize++] = runLength; rle[rleSize++] = runValue
            runLength = 0
        }

        fun toLine(t: Int): String {
            val sb = StringBuilder(64 + rleSize * 4 + names.size * 32)
            sb.append("""{"k":"$kind","t":$t,""")
            if (key != null) sb.append("\"key\":").append(jsonString(key)).append(',')
            sb.append(""""x0":$x0,"y0":$y0,"z0":$z0,"w":$w,"h":$h,"d":$d,"pal":[""")
            names.forEachIndexed { i, n -> if (i > 0) sb.append(','); sb.append(jsonString(n)) }
            sb.append("""],"rle":[""")
            for (i in 0 until rleSize) { if (i > 0) sb.append(','); sb.append(rle[i]) }
            return sb.append("]}").toString()
        }
    }

    companion object {
        /** World x/z of tile 0's first block; tile i covers [ORIGIN + 32i, ORIGIN + 32i + 30], gaps between. */
        const val GRID_ORIGIN = -200
        const val BLOCKS_PER_TICK = 24_000
        const val DOOR_Y = 67
        const val DOOR_H = 10
        const val DOOR_ALONG = 3
        const val DOOR_ACROSS = 2
        /** How far past the boss limit to look, in chunks (F7's arena is about 9x10). */
        const val BOSS_CHUNKS = 13

        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
            return sb.append('"').toString()
        }
    }
}
