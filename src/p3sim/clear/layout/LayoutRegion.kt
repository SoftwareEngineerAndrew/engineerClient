package com.engineerclient.p3sim.clear.layout

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.rooms.RoomBlockEntity
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import com.engineerclient.p3sim.run.WorldRegion
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunkSection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The dungeon's blocks as a [WorldRegion]: every room pasted at its tiles and rotation, then the door edits on top
 * ([RegionEdits]), with the block entities the rooms need. The chunk generator calls [fill] on its worker threads as each chunk is
 * made, straight into the sections with no block updates, so Odin sees a finished room when the chunk reaches the client.
 *
 * Built on the server (or client) thread from already-decoded volumes: [fill] only reads arrays made here, so it is safe from
 * any number of workers. It writes only non-air cells (the chunk starts as void) and never touches a column outside [minX]..[maxX]
 * by [minZ]..[maxZ].
 *
 * Which chunks it has filled is remembered, so [resync] puts back exactly the chunks that exist.
 */
class LayoutRegion(
    override val name: String,
    instances: List<RoomInstance>,
    edits: List<Edit>,
    textures: List<SkullTexture>,
) : WorldRegion {
    private class Pasted(val inst: RoomInstance, val maker: BlockEntityMaker) {
        val p = inst.placement
        val v = inst.volume
        /** The palette as Minecraft states turned to the room's rotation; null is air. */
        val states: Array<BlockState?> = Array(v.palette.size) { i ->
            val s = v.palette[i]
            if (i == 0 || s == "minecraft:air") null else BlockStates.rotated(s, p.quarterTurns).takeUnless { it.isAir }
        }
        val wantsEntity = BooleanArray(states.size) { i -> states[i]?.let { maker.wants(it) } == true }
        /** The pack's block entity records by canonical cell (builder markers are not content). */
        val records: Map<Long, RoomBlockEntity> = inst.design.blockEntities.filter { !it.marker }.associateBy { key(it.pos.x, it.pos.y, it.pos.z) }
        val x0 = p.originX; val x1 = p.originX + p.boxW - 1
        val z0 = p.originZ; val z1 = p.originZ + p.boxD - 1

        fun overlaps(cx: Int, cz: Int) = cx + 15 >= x0 && cx <= x1 && cz + 15 >= z0 && cz <= z1

        companion object { fun key(x: Int, y: Int, z: Int): Long = (x.toLong() shl 40) or (z.toLong() shl 20) or (y.toLong() and 0xFFFFF) }
    }

    private val maker = BlockEntityMaker(textures)
    private val rooms: List<Pasted> = instances.map { Pasted(it, maker) }

    /** The door and marker edits as states, per chunk and cell (later edits replace earlier ones). */
    private val overlays: Map<Long, Map<Int, BlockState>>

    override val minX: Int; override val maxX: Int; override val minZ: Int; override val maxZ: Int

    init {
        val byChunk = HashMap<Long, HashMap<Int, BlockState>>()
        var ax = Int.MAX_VALUE; var bx = Int.MIN_VALUE; var az = Int.MAX_VALUE; var bz = Int.MIN_VALUE
        for (e in edits) {
            val st = BlockStates.rotated(e.state, e.quarterTurns)
            byChunk.getOrPut(chunkKey(e.x shr 4, e.z shr 4)) { HashMap() }[cellKey(e.x and 15, e.y, e.z and 15)] = st
            ax = minOf(ax, e.x); bx = maxOf(bx, e.x); az = minOf(az, e.z); bz = maxOf(bz, e.z)
        }
        for (r in rooms) { ax = minOf(ax, r.x0); bx = maxOf(bx, r.x1); az = minOf(az, r.z0); bz = maxOf(bz, r.z1) }
        overlays = byChunk
        if (ax > bx) { ax = DungeonFrame.ORIGIN; bx = ax; az = ax; bz = ax }
        minX = ax; maxX = bx; minZ = az; maxZ = bz
    }

    /** Chunks [fill] has written, by chunk key; [resync] visits exactly these. */
    private val generated: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** What filling cost, for the spike's report: chunks, total and slowest chunk in nanoseconds, cells written. */
    class Stats(val chunks: Int, val totalNanos: Long, val maxNanos: Long, val cells: Long) {
        val totalMs: Double get() = totalNanos / 1e6
        val maxMs: Double get() = maxNanos / 1e6
        override fun toString() = "%d chunks, %.0f ms total, %.1f ms slowest, %d blocks".format(chunks, totalMs, maxMs, cells)
    }

    private val nanos = AtomicLong(); private val slowest = AtomicLong(); private val cellCount = AtomicLong()

    fun stats(): Stats = Stats(generated.size, nanos.get(), slowest.get(), cellCount.get())

    /** How many blocks the rooms (not the edits) put down in total, a size check for a region before it is used. */
    val roomCount: Int get() = rooms.size

    // ------------------------------------------------------------------ generation

    override fun fill(chunk: ChunkAccess) {
        val t0 = System.nanoTime()
        val bx = chunk.pos.minBlockX; val bz = chunk.pos.minBlockZ
        val key = chunkKey(chunk.pos.x, chunk.pos.z)
        val overlay = overlays[key]
        var cells = 0L
        for (r in rooms) if (r.overlaps(bx, bz)) cells += paste(chunk, r, bx, bz, overlay)
        if (overlay != null) cells += applyOverlay(chunk, bx, bz, overlay)
        generated.add(key)
        val dt = System.nanoTime() - t0
        nanos.addAndGet(dt); cellCount.addAndGet(cells)
        var m = slowest.get()
        while (dt > m && !slowest.compareAndSet(m, dt)) m = slowest.get()
    }

    private fun paste(chunk: ChunkAccess, r: Pasted, bx: Int, bz: Int, overlay: Map<Int, BlockState>?): Long {
        val xa = maxOf(bx, r.x0); val xb = minOf(bx + 15, r.x1)
        val za = maxOf(bz, r.z0); val zb = minOf(bz + 15, r.z1)
        val n = (xb - xa + 1) * (zb - za + 1)
        val wx = IntArray(n); val wz = IntArray(n); val cx = IntArray(n); val cz = IntArray(n)
        var i = 0
        for (z in za..zb) for (x in xa..xb) {
            val c = r.p.toCanonical(x, z) ?: continue
            wx[i] = x; wz[i] = z; cx[i] = c.first; cz[i] = c.second; i++
        }
        val v = r.v
        val cells = v.cells
        var written = 0L
        var section: LevelChunkSection? = null
        var sectionIndex = Int.MIN_VALUE
        for (y in v.y0 until v.y0 + v.h) {
            val si = chunk.getSectionIndex(y)
            if (si < 0 || si >= chunk.sections.size) continue
            if (si != sectionIndex) { section = chunk.getSection(si); sectionIndex = si }
            val sec = section ?: continue
            val layer = (y - v.y0) * v.d
            for (k in 0 until i) {
                val idx = cells[(layer + cz[k]) * v.w + cx[k]].toInt() and 0xFFFF
                if (idx == 0) continue
                val state = r.states[idx] ?: continue
                val lx = wx[k] and 15; val lz = wz[k] and 15
                if (overlay != null && overlay.containsKey(cellKey(lx, y, lz))) continue
                sec.setBlockState(lx, y and 15, lz, state, false)
                written++
                if (r.wantsEntity[idx]) {
                    val pos = BlockPos(wx[k], y, wz[k])
                    maker.create(pos, state, r.records[Pasted.key(cx[k], y, cz[k])])?.let { chunk.setBlockEntity(it) }
                }
            }
        }
        return written
    }

    private fun applyOverlay(chunk: ChunkAccess, bx: Int, bz: Int, overlay: Map<Int, BlockState>): Long {
        var written = 0L
        for ((k, state) in overlay) {
            val y = cellY(k); val lx = cellX(k); val lz = cellZ(k)
            val si = chunk.getSectionIndex(y)
            if (si < 0 || si >= chunk.sections.size) continue
            val old = chunk.getSection(si).setBlockState(lx, y and 15, lz, state, false)
            written++
            val pos = BlockPos(bx + lx, y, bz + lz)
            if (old.hasBlockEntity() && !(old.block === state.block)) chunk.removeBlockEntity(pos)
            if (maker.wants(state) && chunk.getBlockEntity(pos) == null) maker.create(pos, state, null)?.let { chunk.setBlockEntity(it) }
        }
        return written
    }

    // ------------------------------------------------------------------ resync

    override fun resync(level: ServerLevel): Int {
        var fixed = 0
        for (key in generated.toList()) {
            val cxk = (key shr 32).toInt(); val czk = key.toInt()
            fixed += try { resyncChunk(level, cxk, czk) } catch (e: Exception) {
                EngineerClient.logger.warn("[ads] layout resync of chunk $cxk,$czk failed", e); 0
            }
        }
        return fixed
    }

    /** Compares one chunk with its start state (rooms with their air included, then the door edits) and puts differences back. */
    private fun resyncChunk(level: ServerLevel, cxk: Int, czk: Int): Int {
        val chunk = level.getChunk(cxk, czk)
        val bx = cxk shl 4; val bz = czk shl 4
        val overlay = overlays[chunkKey(cxk, czk)]
        val air = Blocks.AIR.defaultBlockState()
        val seen = HashSet<Int>()
        var fixed = 0
        fun check(x: Int, y: Int, z: Int, want: BlockState, record: RoomBlockEntity?) {
            val si = chunk.getSectionIndex(y)
            if (si < 0 || si >= chunk.sections.size) return
            if (chunk.getSection(si).getBlockState(x and 15, y and 15, z and 15) == want) return
            val pos = BlockPos(x, y, z)
            level.setBlock(pos, want, Arena.FLAGS)
            if (maker.wants(want)) level.getBlockEntity(pos)?.let { maker.apply(it, record) }
            // setBlock gives a dispenser or a hopper the entity the chunk generation never made for it.
            else if (maker.forbids(want)) level.removeBlockEntity(pos)
            fixed++
        }
        for (r in rooms) {
            if (!r.overlaps(bx, bz)) continue
            val v = r.v
            for (z in maxOf(bz, r.z0)..minOf(bz + 15, r.z1)) for (x in maxOf(bx, r.x0)..minOf(bx + 15, r.x1)) {
                // Only the room's own columns: an L room's box also covers a corner that belongs to a neighbour.
                if (!r.p.ownsColumn(x, z)) continue
                val (ix, iz) = r.p.toCanonical(x, z) ?: continue
                for (y in v.y0 until v.y0 + v.h) {
                    val k = cellKey(x and 15, y, z and 15)
                    val over = overlay?.get(k)
                    if (over != null) { if (seen.add(k)) check(x, y, z, over, null); continue }
                    val idx = v.cells[((y - v.y0) * v.d + iz) * v.w + ix].toInt() and 0xFFFF
                    check(x, y, z, r.states[idx] ?: air, r.records[Pasted.key(ix, y, iz)])
                }
            }
        }
        if (overlay != null) for ((k, state) in overlay) {
            if (!seen.add(k)) continue
            check(bx + cellX(k), cellY(k), bz + cellZ(k), state, null)
        }
        return fixed
    }

    private companion object {
        fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        /** Local cell key: y (offset so it is positive) over z over x within the chunk. */
        fun cellKey(lx: Int, y: Int, lz: Int): Int = ((y + 64) shl 8) or (lz shl 4) or lx
        fun cellX(k: Int) = k and 15
        fun cellZ(k: Int) = (k shr 4) and 15
        fun cellY(k: Int) = (k shr 8) - 64
    }
}
