package com.engineerclient.betterpf

import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Captures dungeon geometry once instead of streaming it: every block of each ROOM goes to the
 * server's room library (keyed "Name|ROTATION", identical in every run), and only the 1-block GAPS
 * between rooms (doors, walls between rooms - which differ per run) are captured per run. After
 * that, the run only records changes (block updates).
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
    private val gapSegments = ArrayList<Seg>()
    private var ticksWaitingForLibrary = 0

    private class Seg(val x0: Int, val z0: Int, val w: Int, val d: Int, var done: Boolean = false)

    fun start() {
        // x-gap lines (x = const) and z-gap lines (z = const) around every tile of the 6x6 grid.
        for (i in 0..6) for (j in 0..5) {
            gapSegments += Seg(GRID_ORIGIN - 1 + 32 * i, GRID_ORIGIN - 1 + 32 * j, 1, 32)
            gapSegments += Seg(GRID_ORIGIN + 32 * j, GRID_ORIGIN - 1 + 32 * i, 31, 1)
        }
    }

    fun tick(level: ClientLevel, t: Int) {
        if (t % 10 == 0) queueRooms(level)
        var budget = BLOCKS_PER_TICK
        while (budget > 0) {
            val job = active ?: nextJob(level) ?: return
            active = job
            budget -= job.step(level, budget)
            if (job.done) { emit(job.toLine(t)); active = null }
        }
    }

    private fun nextJob(level: ClientLevel): VolumeJob? {
        jobs.removeFirstOrNull()?.let { return it }
        val seg = gapSegments.firstOrNull { !it.done && loaded(level, it.x0, it.z0, it.w, it.d) } ?: return null
        seg.done = true
        val (y0, h) = yRange(level)
        return VolumeJob("vol", null, seg.x0, y0, seg.z0, seg.w, h, seg.d, null)
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
            if (room.tiles.size != room.shape.tileAmount) continue
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
