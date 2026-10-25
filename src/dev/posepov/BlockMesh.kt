package com.devgineerclient.posepov

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.engineerclient.p3sim.pack.DungeonFrame
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.Direction
import net.minecraft.client.model.geom.builders.UVPair
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pose POV's geometry, made ahead and kept, so a frame only picks what to draw:
 *  - a room design's: all its 16³ pieces in one buffer (built once from the room pack in the room's own box frame, kept on disk
 *    across runs), one draw for every room with that design, variant and turn. A piece where this run differs (a door edit, a
 *    block change) is built for the room alone and drawn in place of its range.
 *  - the boss arena's: 2x2x2 sections to a buffer (a cluster), likewise kept; a section this run changed is drawn as a world one.
 *  - the world's: everything else, from [KnownWorld.stateAt] (the cells a room draws itself left out), a section at a time,
 *    this run only.
 * Faces are textured and shaded when built (by side, and at each corner by the blocks around it), so drawing is a lookup.
 * Render thread only, except [dirty] (packets).
 */
object BlockMesh {
    class Mesh(val buffer: GpuBuffer, val indexCount: Int)
    /** Pieces in one buffer, in order: piece i is [counts] indices from vertex [starts]. */
    class Merged(val buffer: GpuBuffer, val starts: IntArray, val counts: IntArray) { val indexCount = counts.sum() }

    /** Room designs (by [KnownWorld.Room.designKey]) and arena clusters (by [cluster] key) made so far; null when empty. */
    private val rooms = HashMap<String, Merged?>()
    private val clusters = HashMap<Long, Merged?>()
    /** The pieces of one still being made (index count, vertex bytes), by its disk key: a little each frame, within budget. */
    private val making = HashMap<String, Array<Pair<Int, ByteArray?>?>>()
    private val world = HashMap<Long, Mesh?>()
    private val roomOwn = HashMap<String, Mesh?>()
    private val touchedRoom = HashSet<String>()
    private val touchedRooms = HashSet<String>()
    private val touchedArena = HashSet<Long>()
    private val touchedClusters = HashSet<Long>()
    /** World sections to rebuild, and since when: [DIRTY_DELAY_MS] later, once the packet has been applied. */
    private val dirtyAt = ConcurrentHashMap<Long, Long>()
    private val dirtyRoom = HashMap<String, Long>()
    /** The most indices one draw needs (the shared quad index buffer is asked for this many). */
    var mostIndices = 0; private set
    // The last [pump]'s near list, finished: the same again (and nothing dirty) has nothing to look at.
    private var nearDone: List<Long>? = null

    private const val DIRTY_DELAY_MS = 100L

    private val SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f) // the game's side shades: down, up, north, south, west, east
    private val DX = intArrayOf(0, 0, 0, 0, -1, 1)
    private val DY = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val DZ = intArrayOf(0, 0, -1, 1, 0, 0)

    private fun roomKey(r: KnownWorld.Room, lx: Int, ly: Int, lz: Int) = r.pieceKeys(lx, ly, lz)[0]
    private fun inArena(sx: Int, sz: Int) = sx in KnownWorld.ARENA_SX && sz in KnownWorld.ARENA_SZ && KnownWorld.bossFloor()
    /** The arena cluster a section is in: sections over 2 each way. */
    fun cluster(sectionKey: Long) = SectionPos.asLong(SectionPos.x(sectionKey) shr 1, SectionPos.y(sectionKey) shr 1, SectionPos.z(sectionKey) shr 1)

    /** A room's pieces (section coordinates in its box frame), in the order its buffer holds them. */
    private val pieceLists = HashMap<String, Array<IntArray>>()
    private fun pieces(r: KnownWorld.Room): Array<IntArray> = pieceLists.getOrPut(r.key) {
        val out = ArrayList<IntArray>()
        for (lx in 0..((r.x1 - r.x0) shr 4)) for (lz in 0..((r.z1 - r.z0) shr 4)) for (ly in (r.y0 shr 4)..(r.y1 shr 4)) out += intArrayOf(lx, ly, lz)
        out.toTypedArray()
    }

    // --- what changed ---------------------------------------------------------------------------------------------------

    /** The world section of a cell (and the next one over when the cell is on its edge) needs rebuilding. */
    fun dirty(x: Int, y: Int, z: Int) {
        val now = System.currentTimeMillis()
        val sx = x shr 4; val sy = y shr 4; val sz = z shr 4
        for (dx in edge(x)) for (dy in edge(y)) for (dz in edge(z)) dirtyAt[SectionPos.asLong(sx + dx, sy + dy, sz + dz)] = now
    }

    private val MID = intArrayOf(0); private val LOW = intArrayOf(0, -1); private val HIGH = intArrayOf(0, 1)
    private fun edge(v: Int) = when (v and 15) { 0 -> LOW; 15 -> HIGH; else -> MID }

    fun dirtyColumn(cx: Int, cz: Int, minSy: Int, maxSy: Int) {
        val now = System.currentTimeMillis()
        for (sy in minSy..maxSy) dirtyAt[SectionPos.asLong(cx, sy, cz)] = now
    }

    /** Cells that are no longer what the room pack or the arena says: their pieces become this run's own. */
    private fun drainTouched() {
        val now = System.currentTimeMillis()
        while (true) {
            val k = KnownWorld.touchedCells.poll() ?: break
            val x = BlockPos.getX(k); val y = BlockPos.getY(k); val z = BlockPos.getZ(k)
            dirty(x, y, z)
            if (inArena(x shr 4, z shr 4)) {
                val s = SectionPos.asLong(x shr 4, y shr 4, z shr 4)
                touchedArena += s; touchedClusters += cluster(s)
            }
            val r = KnownWorld.roomAt(x, z) ?: continue
            val bx = x - r.x0; val bz = z - r.z0
            for (dx in edge(bx)) for (dy in edge(y)) for (dz in edge(bz)) {
                val key = roomKey(r, (bx shr 4) + dx, (y shr 4) + dy, (bz shr 4) + dz)
                touchedRoom += key; dirtyRoom[key] = now
            }
            touchedRooms += r.key
        }
    }

    // --- building -------------------------------------------------------------------------------------------------------

    /**
     * Builds for at most [budgetNs]: rebuilds first, then the rooms, then [near] (world sections and arena clusters by the eyes,
     * nearest first), then with [everything] the rest of the dungeon and the arena.
     */
    fun pump(level: ClientLevel, budgetNs: Long, near: List<Long>, everything: Boolean) {
        pumpStart = System.nanoTime(); pumpBudget = budgetNs
        // A resource pack change moves every texture: the shared pieces built so far are for the old atlas.
        if (MeshDisk.check()) dropShared()
        drainTouched()
        val now = System.currentTimeMillis()

        for ((key, at) in dirtyRoom.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            dirtyRoom.remove(key)
            roomOwn.remove(key)?.buffer?.close()
        }
        for ((key, at) in dirtyAt.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            dirtyAt.remove(key)
            nearDone = null; allDone = false
            if (key in world) { world.remove(key)?.buffer?.close() }
        }
        if (!MeshDisk.ready) return

        for (r in KnownWorld.rooms) {
            if (r.designKey !in rooms) {
                val ps = pieces(r)
                val (done, m) = assemble("m/${r.designKey}", ps.size) { i -> val p = ps[i]; buildRoom(level, r, p[0], p[1], p[2], false) }
                if (!done) return
                rooms[r.designKey] = m
            }
            if (r.key in touchedRooms) for (p in pieces(r)) {
                val own = roomKey(r, p[0], p[1], p[2])
                if (own in touchedRoom && own !in roomOwn && dirtyRoom[own] == null) {
                    roomOwn[own] = upload(buildRoom(level, r, p[0], p[1], p[2], true))
                    if (overBudget()) return
                }
            }
            if (overBudget()) return
        }
        if (near !== nearDone) {
            for (key in near) if (!want(level, key)) return
            nearDone = near
        }
        if (!everything) return
        val minSy = level.minSectionY; val maxSy = level.maxSectionY
        val d0 = DungeonFrame.ORIGIN shr 4; val d1 = DungeonFrame.DUNGEON_MAX shr 4
        if (!allDone) {
            for (sx in d0..d1) for (sz in d0..d1) for (sy in minSy..maxSy) if (!want(level, SectionPos.asLong(sx, sy, sz))) return
            if (KnownWorld.bossFloor()) for (sx in KnownWorld.ARENA_SX) for (sz in KnownWorld.ARENA_SZ) for (sy in minSy..maxSy) {
                if (!want(level, SectionPos.asLong(sx, sy, sz))) return
            }
            allDone = true
        }
    }

    /** The whole dungeon and arena were looked over with nothing left to build (until something changes). */
    private var allDone = false

    /** Makes sure section [key] has its geometry (its arena cluster, or this run's world section); false once the budget is spent. */
    private fun want(level: ClientLevel, key: Long): Boolean {
        val sx = SectionPos.x(key); val sy = SectionPos.y(key); val sz = SectionPos.z(key)
        if (inArena(sx, sz) && key !in touchedArena) {
            val c = cluster(key)
            if (c in clusters) return true
            val cx = SectionPos.x(c); val cy = SectionPos.y(c); val cz = SectionPos.z(c)
            val (done, m) = assemble("c/$c", 8) { i ->
                val px = (cx shl 1) + (i and 1); val py = (cy shl 1) + ((i shr 1) and 1); val pz = (cz shl 1) + (i shr 2)
                if (inArena(px, pz) && py in level.minSectionY..level.maxSectionY) buildArena(level, px, py, pz) else 0 to null
            }
            if (!done) return false
            clusters[c] = m
            return !overBudget()
        }
        if (key in world || dirtyAt[key]?.let { System.currentTimeMillis() - it < DIRTY_DELAY_MS } == true) return true
        allDone = false
        world[key] = upload(buildWorld(level, sx, sy, sz))
        return !overBudget()
    }

    /**
     * One buffer of [n] pieces, from disk when made in an earlier session, otherwise [piece] by piece within the budget (kept in
     * [making] meanwhile) and then kept on disk: (done, the buffer or null when it's all empty).
     */
    private inline fun assemble(key: String, n: Int, piece: (Int) -> Pair<Int, ByteArray?>): Pair<Boolean, Merged?> {
        val disk = MeshDisk.read(key)
        val ranges = MeshDisk.read("$key#")
        if (disk != null && ranges != null && ranges.first == n) {
            val counts = IntArray(n); ranges.second?.let { b -> b.order(ByteOrder.BIG_ENDIAN); for (i in 0 until n) counts[i] = b.getInt(i * 4) }
            return true to disk.second?.let { merged(it, counts) }
        }
        val parts = making.getOrPut(key) { arrayOfNulls(n) }
        for (i in 0 until n) if (parts[i] == null) { parts[i] = piece(i); if (overBudget() && parts.any { it == null }) return false to null }
        making.remove(key)
        val counts = IntArray(n) { parts[it]!!.first }
        val total = parts.sumOf { it!!.second?.size ?: 0 }
        val rangeBytes = ByteBuffer.allocate(n * 4).order(ByteOrder.BIG_ENDIAN).also { b -> counts.forEach { b.putInt(it) } }.flip()
        if (total == 0) { MeshDisk.write(key, 0, null); MeshDisk.write("$key#", n, rangeBytes); return true to null }
        val all = ByteBuffer.allocateDirect(total)
        for (p in parts) p!!.second?.let { all.put(it) }
        all.flip()
        MeshDisk.write(key, counts.sum(), all)
        MeshDisk.write("$key#", n, rangeBytes)
        return true to merged(all, counts)
    }

    private fun merged(vertices: ByteBuffer, counts: IntArray): Merged {
        val starts = IntArray(counts.size)
        var v = 0
        for (i in counts.indices) { starts[i] = v; v += counts[i] / 6 * 4 }
        val m = Merged(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov" }, GpuBuffer.USAGE_VERTEX, vertices), starts, counts)
        if (m.indexCount > mostIndices) mostIndices = m.indexCount
        return m
    }

    private fun upload(built: Pair<Int, ByteArray?>): Mesh? {
        val bytes = built.second ?: return null
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
        if (built.first > mostIndices) mostIndices = built.first
        return Mesh(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov" }, GpuBuffer.USAGE_VERTEX, buf), built.first)
    }

    private var pumpStart = 0L
    private var pumpBudget = 0L
    private fun overBudget() = System.nanoTime() - pumpStart > pumpBudget

    private fun buildRoom(level: ClientLevel, r: KnownWorld.Room, lx: Int, ly: Int, lz: Int, own: Boolean): Pair<Int, ByteArray?> {
        val pos = BlockPos.MutableBlockPos()
        val bx0 = lx shl 4; val bz0 = lz shl 4
        val bx1 = minOf(bx0 + 15, r.x1 - r.x0); val bz1 = minOf(bz0 + 15, r.z1 - r.z0)
        val y0 = maxOf(ly shl 4, r.y0); val y1 = minOf((ly shl 4) + 15, r.y1)
        // The design's piece sees only the design (outside the box is air); the room's own sees everything this run.
        val at: (Int, Int, Int) -> BlockState =
            if (own) { x, y, z -> KnownWorld.stateAt(level, pos, r.x0 + x, y, r.z0 + z) }
            else { x, y, z -> if (x < 0 || z < 0 || x > r.x1 - r.x0 || z > r.z1 - r.z0) KnownWorld.AIR else r.designAt(x, y, z) }
        val skip: (Int, Int, Int) -> Boolean =
            if (own) { x, _, z -> KnownWorld.roomAt(r.x0 + x, r.z0 + z) !== r } else { _, _, _ -> false }
        return mesh(level, bx0..bx1, y0..y1, bz0..bz1, r.x0, r.z0, at, skip)
    }

    private fun buildArena(level: ClientLevel, sx: Int, sy: Int, sz: Int): Pair<Int, ByteArray?> {
        val a = com.engineerclient.p3sim.Arena.data
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        return mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0, { x, y, z -> a.get(x, y, z) }, { _, _, _ -> false })
    }

    private fun buildWorld(level: ClientLevel, sx: Int, sy: Int, sz: Int): Pair<Int, ByteArray?> {
        if (sy < level.minSectionY || sy > level.maxSectionY) return 0 to null
        if (!KnownWorld.mayHaveBlocks(level, sx, sy, sz)) return 0 to null
        val pos = BlockPos.MutableBlockPos()
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        return mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0,
            { x, y, z -> KnownWorld.stateAt(level, pos, x, y, z) },
            { x, _, z -> KnownWorld.coveredByRoom(x, z) })
    }

    /** The faces of the cells in the box, in [at]'s frame, whose origin is world ([ox], 0, [oz]): (index count, vertex bytes). */
    private inline fun mesh(
        level: ClientLevel, xs: IntRange, ys: IntRange, zs: IntRange, ox: Int, oz: Int,
        noinline at: (Int, Int, Int) -> BlockState, skip: (Int, Int, Int) -> Boolean,
    ): Pair<Int, ByteArray?> {
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) return 0 to null
        cellAt = at
        val format = DefaultVertexFormat.POSITION_TEX_COLOR
        val world = BlockPos.MutableBlockPos()
        ByteBufferBuilder(format.vertexSize * 4 * 1024).use { bytes ->
            val bb = BufferBuilder(bytes, PrimitiveTopology.QUADS, format)
            for (y in ys) for (z in zs) for (x in xs) {
                if (skip(x, y, z)) continue
                val state = at(x, y, z)
                if (state.isAir) continue
                world.set(x + ox, y, z + oz)
                for (f in 0..5) hidden[f] = at(x + DX[f], y + DY[f], z + DZ[f]).isSolidRender
                block(bb, level, state, world, x, y, z)
            }
            val data = bb.build() ?: return 0 to null
            data.use {
                val v = it.vertexBuffer()
                return it.drawState().indexCount() to ByteArray(v.remaining()).also { a -> v.duplicate().get(a) }
            }
        }
    }

    // --- drawing --------------------------------------------------------------------------------------------------------

    /**
     * What a feed draws: every room [room] lets through and the frustum ([inView], a world box) has, every arena cluster
     * [clusterSeen] lets through and is in view, and the world sections in view, each as (buffer, first vertex, index count, the
     * frame's x, z offset). A room this run changed in places draws its own pieces there and its design's ranges around them.
     */
    inline fun forEachDraw(
        room: (KnownWorld.Room) -> Boolean, clusterSeen: (Long) -> Boolean,
        inView: (Float, Float, Float, Float, Float, Float) -> Boolean,
        crossinline draw: (GpuBuffer, Int, Int, Int, Int) -> Unit,
    ) {
        for (r in KnownWorld.rooms) {
            if (!room(r)) continue
            if (!inView(r.x0.toFloat(), r.y0.toFloat(), r.z0.toFloat(), r.x1 + 1f, r.y1 + 1f, r.z1 + 1f)) continue
            val m = roomBuffer(r)
            if (!roomChanged(r)) { if (m != null) draw(m.buffer, 0, m.indexCount, r.x0, r.z0); continue }
            forEachRoomRange(r, m) { buf, first, count -> draw(buf, first, count, r.x0, r.z0) }
        }
        forEachCluster { key, m ->
            if (!clusterSeen(key)) return@forEachCluster
            val x = SectionPos.x(key) shl 5; val y = SectionPos.y(key) shl 5; val z = SectionPos.z(key) shl 5
            if (!inView(x.toFloat(), y.toFloat(), z.toFloat(), x + 32f, y + 32f, z + 32f)) return@forEachCluster
            if (!clusterChanged(key)) draw(m.buffer, 0, m.indexCount, 0, 0)
            else forEachClusterRange(key, m) { first, count -> draw(m.buffer, first, count, 0, 0) }
        }
        forEachWorld { k, m ->
            val x = SectionPos.x(k) shl 4; val y = SectionPos.y(k) shl 4; val z = SectionPos.z(k) shl 4
            if (inView(x.toFloat(), y.toFloat(), z.toFloat(), x + 16f, y + 16f, z + 16f)) draw(m.buffer, 0, m.indexCount, 0, 0)
        }
    }

    fun roomBuffer(r: KnownWorld.Room): Merged? = rooms[r.designKey]
    fun roomChanged(r: KnownWorld.Room) = r.key in touchedRooms
    fun clusterChanged(key: Long) = key in touchedClusters

    /** A changed room: its own pieces where built, its design's ranges (runs of them as one) everywhere else. */
    fun forEachRoomRange(r: KnownWorld.Room, m: Merged?, draw: (GpuBuffer, Int, Int) -> Unit) {
        val ps = pieces(r)
        var runStart = -1; var runCount = 0
        fun flush() { if (m != null && runCount > 0) draw(m.buffer, m.starts[runStart], runCount); runStart = -1; runCount = 0 }
        for (i in ps.indices) {
            val own = roomKey(r, ps[i][0], ps[i][1], ps[i][2])
            val mine = if (own in touchedRoom && own in roomOwn) roomOwn[own] else null
            if (own in touchedRoom && own in roomOwn) { flush(); if (mine != null) draw(mine.buffer, 0, mine.indexCount); continue }
            if (m == null) continue
            if (runStart < 0) runStart = i
            runCount += m.counts[i]
        }
        flush()
    }

    /** A changed cluster: the ranges of its sections this run hasn't touched (those are world sections now). */
    fun forEachClusterRange(key: Long, m: Merged, draw: (Int, Int) -> Unit) {
        val cx = SectionPos.x(key); val cy = SectionPos.y(key); val cz = SectionPos.z(key)
        var runStart = -1; var runCount = 0
        for (i in 0 until 8) {
            val s = SectionPos.asLong((cx shl 1) + (i and 1), (cy shl 1) + ((i shr 1) and 1), (cz shl 1) + (i shr 2))
            if (s in touchedArena) { if (runCount > 0) draw(m.starts[runStart], runCount); runStart = -1; runCount = 0; continue }
            if (runStart < 0) runStart = i
            runCount += m.counts[i]
        }
        if (runCount > 0) draw(m.starts[runStart], runCount)
    }

    inline fun forEachCluster(f: (Long, Merged) -> Unit) { if (KnownWorld.bossFloor()) for ((k, m) in clusterMap()) if (m != null) f(k, m) }
    inline fun forEachWorld(f: (Long, Mesh) -> Unit) { for ((k, m) in worldMap()) if (m != null) f(k, m) }
    fun clusterMap(): Map<Long, Merged?> = clusters
    fun worldMap(): Map<Long, Mesh?> = world

    // --- lifetime -------------------------------------------------------------------------------------------------------

    /** This run's pieces; the shared design and arena ones stay for the next run. */
    fun newRun() {
        world.values.forEach { it?.buffer?.close() }; world.clear()
        roomOwn.values.forEach { it?.buffer?.close() }; roomOwn.clear()
        touchedRoom.clear(); touchedRooms.clear(); touchedArena.clear(); touchedClusters.clear(); dirtyAt.clear(); dirtyRoom.clear()
        pieceLists.clear(); nearDone = null; allDone = false
    }

    fun clear() {
        newRun()
        dropShared()
    }

    private fun dropShared() {
        rooms.values.forEach { it?.buffer?.close() }; rooms.clear()
        clusters.values.forEach { it?.buffer?.close() }; clusters.clear()
        making.clear(); nearDone = null; allDone = false
    }

    // --- one block ------------------------------------------------------------------------------------------------------

    /** Which of the six neighbours hide that side (Direction order: down, up, north, south, west, east); set per cell by [mesh]. */
    private val hidden = BooleanArray(6)
    private val parts = ArrayList<BlockStateModelPart>()
    private val random = RandomSource.create()
    private val DIRS = Direction.entries.toTypedArray()
    /** The block lookup of the piece being meshed (its frame), for the corner shading. */
    private var cellAt: (Int, Int, Int) -> BlockState = { _, _, _ -> KnownWorld.AIR }

    /**
     * The block's own baked model at ([x], [y], [z]): every quad with its texture from the block atlas and its tint (grass,
     * leaves), full bright, shaded by the side it faces and at each corner by the blocks around it; a side's quads are left out
     * when the block next to it hides them. A liquid has no model, so it is a cube of its texture.
     */
    private fun block(bb: BufferBuilder, level: ClientLevel, state: BlockState, world: BlockPos, x: Int, y: Int, z: Int) {
        if (state.block is LiquidBlock) {
            val sprite = mc().modelManager.blockStateModelSet.getParticleMaterial(state).sprite()
            val tint = tint(level, state, world, 0)
            for (f in 0..5) if (!hidden[f]) cube(bb, f, x.toFloat(), y.toFloat(), z.toFloat(), sprite, mul(tint, SHADE[f]))
            return
        }
        if (state.renderShape == RenderShape.INVISIBLE) return
        parts.clear()
        random.setSeed(state.getSeed(world))
        mc().modelManager.blockStateModelSet.get(state).collectParts(random, parts)
        for (part in parts) {
            for (d in DIRS) if (!hidden[d.ordinal]) for (q in part.getQuads(d)) quad(bb, level, state, world, q, x, y, z)
            for (q in part.getQuads(null)) quad(bb, level, state, world, q, x, y, z)
        }
    }

    private fun quad(bb: BufferBuilder, level: ClientLevel, state: BlockState, world: BlockPos, q: BakedQuad, x: Int, y: Int, z: Int) {
        val info = q.materialInfo()
        val tint = if (info.isTinted) tint(level, state, world, info.tintIndex()) else -1
        val d = q.direction()
        val base = SHADE[d.ordinal]
        for (k in 0..3) {
            val p = q.position(k); val uv = q.packedUV(k)
            bb.addVertex(x + p.x(), y + p.y(), z + p.z()).setUv(UVPair.unpackU(uv), UVPair.unpackV(uv))
                .setColor(mul(tint, base * corner(d, x, y, z, p.x(), p.y(), p.z())))
        }
    }

    /**
     * Smooth lighting's corner shading at full brightness, the game's rule: a corner of a face is the average of the face's own
     * cell and the two cells beside the corner and the one diagonal to it, in the layer the face looks into (the block's own layer
     * for a face inside the block), where a solid block counts 0.2 and anything else 1; the diagonal counts as solid when both
     * sides are.
     */
    private fun corner(d: Direction, x: Int, y: Int, z: Int, px: Float, py: Float, pz: Float): Float {
        val nx = d.stepX; val ny = d.stepY; val nz = d.stepZ
        // The layer: next door for a face on the block's edge, the block's own for one inside it.
        val along = when (d.axis) { Direction.Axis.X -> px; Direction.Axis.Y -> py; else -> pz }
        val onEdge = if (d.axisDirection == Direction.AxisDirection.POSITIVE) along > 0.999f else along < 0.001f
        val lx = if (onEdge) x + nx else x; val ly = if (onEdge) y + ny else y; val lz = if (onEdge) z + nz else z
        // The two directions across the face, toward this corner.
        val ax: Int; val ay: Int; val az: Int; val bx: Int; val by: Int; val bz: Int
        when (d.axis) {
            Direction.Axis.Y -> { ax = if (px < 0.5f) -1 else 1; ay = 0; az = 0; bx = 0; by = 0; bz = if (pz < 0.5f) -1 else 1 }
            Direction.Axis.Z -> { ax = if (px < 0.5f) -1 else 1; ay = 0; az = 0; bx = 0; by = if (py < 0.5f) -1 else 1; bz = 0 }
            else -> { ax = 0; ay = 0; az = if (pz < 0.5f) -1 else 1; bx = 0; by = if (py < 0.5f) -1 else 1; bz = 0 }
        }
        val at = cellAt
        val face = if (at(lx, ly, lz).isSolidRender) 0.2f else 1f
        val sideA = at(lx + ax, ly + ay, lz + az).isSolidRender
        val sideB = at(lx + bx, ly + by, lz + bz).isSolidRender
        val diag = (sideA && sideB) || at(lx + ax + bx, ly + ay + by, lz + az + bz).isSolidRender
        return (face + (if (sideA) 0.2f else 1f) + (if (sideB) 0.2f else 1f) + (if (diag) 0.2f else 1f)) / 4f
    }

    private fun tint(level: ClientLevel, state: BlockState, world: BlockPos, index: Int): Int =
        mc().blockColors.getTintSource(state, index)?.colorInWorld(state, level, world) ?: -1

    private fun mul(rgb: Int, k: Float): Int {
        val r = ((rgb shr 16 and 255) * k).toInt(); val g = ((rgb shr 8 and 255) * k).toInt(); val b = ((rgb and 255) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** One side of a full cube, the whole [s] across it. */
    private fun cube(bb: BufferBuilder, f: Int, x: Float, y: Float, z: Float, s: TextureAtlasSprite, c: Int) {
        val X = x + 1; val Y = y + 1; val Z = z + 1
        val u0 = s.u0; val u1 = s.u1; val v0 = s.v0; val v1 = s.v1
        fun v(px: Float, py: Float, pz: Float, u: Float, v: Float) { bb.addVertex(px, py, pz).setUv(u, v).setColor(c) }
        when (f) {
            0 -> { v(x, y, z, u0, v0); v(X, y, z, u1, v0); v(X, y, Z, u1, v1); v(x, y, Z, u0, v1) }
            1 -> { v(x, Y, z, u0, v0); v(x, Y, Z, u0, v1); v(X, Y, Z, u1, v1); v(X, Y, z, u1, v0) }
            2 -> { v(x, y, z, u1, v1); v(x, Y, z, u1, v0); v(X, Y, z, u0, v0); v(X, y, z, u0, v1) }
            3 -> { v(x, y, Z, u0, v1); v(X, y, Z, u1, v1); v(X, Y, Z, u1, v0); v(x, Y, Z, u0, v0) }
            4 -> { v(x, y, z, u0, v1); v(x, y, Z, u1, v1); v(x, Y, Z, u1, v0); v(x, Y, z, u0, v0) }
            else -> { v(X, y, z, u1, v1); v(X, Y, z, u1, v0); v(X, Y, Z, u0, v0); v(X, y, Z, u0, v1) }
        }
    }

    private fun mc() = net.minecraft.client.Minecraft.getInstance()
}
