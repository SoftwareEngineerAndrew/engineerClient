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

/**
 * Pose POV's geometry: flat-coloured faces (a block's map colour, shaded by face; a face only where the next block doesn't hide
 * it), in 16³ pieces of three kinds:
 *  - a room design's: built once from the room pack in the room's own box frame and kept across runs, drawn at every room that
 *    has that design, variant and turn; a piece where this run differs (a door edit, a block change) is built for the room alone.
 *  - the boss arena's: built once from the shipped arena, kept across runs; a section this run changed is built as a world one.
 *  - the world's: everything else, from [KnownWorld.stateAt] (the cells a room draws itself left out), this run only.
 * Render thread only, except [dirty] (packets).
 */
object BlockMesh {
    class Mesh(val buffer: GpuBuffer, val indexCount: Int)

    private val designs = HashMap<String, Mesh?>()
    private val arena = HashMap<Long, Mesh?>()
    private val world = HashMap<Long, Mesh?>()
    private val roomOwn = HashMap<String, Mesh?>()
    private val touchedRoom = HashSet<String>()
    private val touchedArena = HashSet<Long>()
    /** World sections to rebuild, and since when: [DIRTY_DELAY_MS] later, once the packet has been applied. */
    private val dirtyAt = ConcurrentHashMap<Long, Long>()
    private val dirtyRoom = HashMap<String, Long>()

    private const val DIRTY_DELAY_MS = 100L

    private val SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f) // the game's side shades: down, up, north, south, west, east
    private val DX = intArrayOf(0, 0, 0, 0, -1, 1)
    private val DY = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val DZ = intArrayOf(0, 0, -1, 1, 0, 0)

    private fun roomKey(r: KnownWorld.Room, lx: Int, ly: Int, lz: Int) = r.pieceKeys(lx, ly, lz)[0]
    private fun designKey(r: KnownWorld.Room, lx: Int, ly: Int, lz: Int) = r.pieceKeys(lx, ly, lz)[1]
    private fun inArena(sx: Int, sz: Int) = sx in KnownWorld.ARENA_SX && sz in KnownWorld.ARENA_SZ && KnownWorld.bossFloor()

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
            if (inArena(x shr 4, z shr 4)) touchedArena += SectionPos.asLong(x shr 4, y shr 4, z shr 4)
            val r = KnownWorld.roomAt(x, z) ?: continue
            val bx = x - r.x0; val bz = z - r.z0
            for (dx in edge(bx)) for (dy in edge(y)) for (dz in edge(bz)) {
                val key = roomKey(r, (bx shr 4) + dx, (y shr 4) + dy, (bz shr 4) + dz)
                touchedRoom += key; dirtyRoom[key] = now
            }
        }
    }

    // --- building -------------------------------------------------------------------------------------------------------

    /**
     * Builds for at most [budgetNs]: rebuilds first, then the rooms, then [near] (world sections by the eyes, nearest first), then
     * with [everything] the rest of the dungeon and the arena.
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
            if (key in world) { world.remove(key)?.buffer?.close() }
        }

        val rooms = KnownWorld.rooms
        for (r in rooms) forEachPiece(r) { lx, ly, lz ->
            val own = roomKey(r, lx, ly, lz)
            if (own in touchedRoom) {
                if (own !in roomOwn && dirtyRoom[own] == null) roomOwn[own] = buildRoom(level, r, lx, ly, lz, true)
            } else {
                val dk = designKey(r, lx, ly, lz)
                if (dk !in designs) { if (!MeshDisk.ready) return@forEachPiece true; designs[dk] = fromDisk("d/$dk") { buildRoom(level, r, lx, ly, lz, false, "d/$dk") } }
            }
            !overBudget()
        }
        if (overBudget()) return
        for (key in near) { if (!want(level, key)) return }
        if (!everything) return
        val minSy = level.minSectionY; val maxSy = level.maxSectionY
        val d0 = DungeonFrame.ORIGIN shr 4; val d1 = DungeonFrame.DUNGEON_MAX shr 4
        for (sx in d0..d1) for (sz in d0..d1) for (sy in minSy..maxSy) if (!want(level, SectionPos.asLong(sx, sy, sz))) return
        if (KnownWorld.bossFloor()) for (sx in KnownWorld.ARENA_SX) for (sz in KnownWorld.ARENA_SZ) for (sy in minSy..maxSy) {
            if (!want(level, SectionPos.asLong(sx, sy, sz))) return
        }
    }

    /** Builds the section [key] if it's missing (the shared arena piece, or this run's world one); false once the budget is spent. */
    private fun want(level: ClientLevel, key: Long): Boolean {
        val sx = SectionPos.x(key); val sy = SectionPos.y(key); val sz = SectionPos.z(key)
        if (inArena(sx, sz) && key !in touchedArena) {
            if (key !in arena) { if (!MeshDisk.ready) return true; arena[key] = fromDisk("a/$key") { buildArena(level, sx, sy, sz, "a/$key") }; return !overBudget() }
            return true
        }
        if (key in world || dirtyAt[key]?.let { System.currentTimeMillis() - it < DIRTY_DELAY_MS } == true) return true
        world[key] = buildWorld(level, sx, sy, sz)
        return !overBudget()
    }

    /** A shared piece from disk when it was built in an earlier session (null for an empty one), else [build] (which keeps it there). */
    private inline fun fromDisk(key: String, build: () -> Mesh?): Mesh? {
        val (count, bytes) = MeshDisk.read(key) ?: return build()
        return bytes?.let { Mesh(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov" }, GpuBuffer.USAGE_VERTEX, it), count) }
    }

    private var pumpStart = 0L
    private var pumpBudget = 0L
    private fun overBudget() = System.nanoTime() - pumpStart > pumpBudget

    private inline fun forEachPiece(r: KnownWorld.Room, f: (Int, Int, Int) -> Boolean) {
        for (lx in 0..((r.x1 - r.x0) shr 4)) for (lz in 0..((r.z1 - r.z0) shr 4)) for (ly in (r.y0 shr 4)..(r.y1 shr 4)) if (!f(lx, ly, lz)) return
    }

    private fun buildRoom(level: ClientLevel, r: KnownWorld.Room, lx: Int, ly: Int, lz: Int, own: Boolean, cacheKey: String? = null): Mesh? {
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
        return mesh(level, bx0..bx1, y0..y1, bz0..bz1, r.x0, r.z0, at, skip, cacheKey)
    }

    private fun buildArena(level: ClientLevel, sx: Int, sy: Int, sz: Int, cacheKey: String): Mesh? {
        val a = com.engineerclient.p3sim.Arena.data
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        return mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0, { x, y, z -> a.get(x, y, z) }, { _, _, _ -> false }, cacheKey)
    }

    private fun buildWorld(level: ClientLevel, sx: Int, sy: Int, sz: Int): Mesh? {
        if (sy < level.minSectionY || sy > level.maxSectionY) return null
        if (!KnownWorld.mayHaveBlocks(level, sx, sy, sz)) return null
        val pos = BlockPos.MutableBlockPos()
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        return mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0,
            { x, y, z -> KnownWorld.stateAt(level, pos, x, y, z) },
            { x, _, z -> KnownWorld.coveredByRoom(x, z) })
    }

    /** The faces of the cells in the box, in [at]'s frame, whose origin is world ([ox], 0, [oz]) (for tints and model variants). */
    private inline fun mesh(
        level: ClientLevel, xs: IntRange, ys: IntRange, zs: IntRange, ox: Int, oz: Int,
        noinline at: (Int, Int, Int) -> BlockState, skip: (Int, Int, Int) -> Boolean, cacheKey: String? = null,
    ): Mesh? {
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) { cacheKey?.let { MeshDisk.write(it, 0, null) }; return null }
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
            val data = bb.build() ?: run { cacheKey?.let { MeshDisk.write(it, 0, null) }; return null }
            data.use {
                cacheKey?.let { k -> MeshDisk.write(k, it.drawState().indexCount(), it.vertexBuffer()) }
                return Mesh(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov" }, GpuBuffer.USAGE_VERTEX, it.vertexBuffer()), it.drawState().indexCount())
            }
        }
    }

    // --- drawing --------------------------------------------------------------------------------------------------------

    /**
     * Every piece built, with its world box, for [draw] to cull: (mesh, box min x, y, z, its draw offset x, z). Shared pieces stand
     * in for a room's own until that is built.
     */
    inline fun forEachDrawable(draw: (Mesh, Int, Int, Int, Int, Int) -> Unit) {
        for (r in KnownWorld.rooms) for (lx in 0..((r.x1 - r.x0) shr 4)) for (lz in 0..((r.z1 - r.z0) shr 4)) for (ly in (r.y0 shr 4)..(r.y1 shr 4)) {
            val m = roomPiece(r, lx, ly, lz) ?: continue
            draw(m, r.x0 + (lx shl 4), ly shl 4, r.z0 + (lz shl 4), r.x0, r.z0)
        }
        for ((k, m) in worldPieces()) draw(m, SectionPos.x(k) shl 4, SectionPos.y(k) shl 4, SectionPos.z(k) shl 4, 0, 0)
    }

    fun roomPiece(r: KnownWorld.Room, lx: Int, ly: Int, lz: Int): Mesh? {
        val own = roomKey(r, lx, ly, lz)
        if (own in touchedRoom) roomOwn[own]?.let { return it }
        return designs[designKey(r, lx, ly, lz)]
    }

    /** The world sections and, on a boss floor, the arena's untouched ones. */
    fun worldPieces(): Sequence<Pair<Long, Mesh>> = sequence {
        for ((k, m) in world) if (m != null) yield(k to m)
        if (KnownWorld.bossFloor()) for ((k, m) in arena) if (m != null && k !in touchedArena) yield(k to m)
    }

    // --- lifetime -------------------------------------------------------------------------------------------------------

    /** This run's pieces; the shared design and arena ones stay for the next run. */
    fun newRun() {
        world.values.forEach { it?.buffer?.close() }; world.clear()
        roomOwn.values.forEach { it?.buffer?.close() }; roomOwn.clear()
        touchedRoom.clear(); touchedArena.clear(); dirtyAt.clear(); dirtyRoom.clear()
    }

    fun clear() {
        newRun()
        dropShared()
    }

    private fun dropShared() {
        designs.values.forEach { it?.buffer?.close() }; designs.clear()
        arena.values.forEach { it?.buffer?.close() }; arena.clear()
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
