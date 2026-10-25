package com.devgineerclient.posepov

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.clear.layout.BlockStates
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import net.fabricmc.loader.api.FabricLoader
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Pose POV's geometry, made ahead and kept, so a frame only picks what to draw:
 *  - a room design's: its unturned box in 16³ pieces ([MeshBundles]: from disk, from the site, or built here once), turned
 *    as each piece is put on the GPU, and only the pieces a feed could show ([PiecePool]). A variant's bundle holds just the
 *    pieces it changes. A piece where this run differs (a door edit, a block change) is built for the room alone and drawn
 *    in its place.
 *  - the boss arena's: one bundle of its sections, likewise; a section this run changed is drawn as a world one.
 *  - the world's: everything else, from [KnownWorld.stateAt] (the cells a room draws itself left out), a section at a time,
 *    this run only.
 * A piece's faces are grouped by the way they face, and a frame leaves out the groups facing away from the eye.
 * Faces are textured and shaded when built (by side, and at each corner by the blocks around it), so drawing is a lookup.
 * Render thread only, except [dirty] (packets).
 */
object BlockMesh {
    class Mesh(val buffer: GpuBuffer, val indexCount: Int)

    private const val BUNDLE_FORMAT = 2
    const val ARENA = "arena"

    private val world = HashMap<Long, Mesh?>()
    private val roomOwn = HashMap<String, Mesh?>()
    private val touchedRoom = HashSet<String>()
    private val touchedRooms = HashSet<String>()
    private val touchedArena = HashSet<Long>()
    /** World sections to rebuild, and since when: [DIRTY_DELAY_MS] later, once the packet has been applied. */
    private val dirtyAt = ConcurrentHashMap<Long, Long>()
    private val dirtyRoom = HashMap<String, Long>()
    private var ownMost = 0
    /** Bumped whenever this run's own geometry changes (world sections, a room's own pieces). */
    var changes = 0L; private set
    /** The most indices one draw needs (the shared quad index buffer is asked for this many). */
    val mostIndices: Int get() = maxOf(ownMost, PiecePool.mostIndices)
    // The last [pump]'s near list, finished: the same again (and nothing dirty) has nothing to look at.
    private var nearDone: List<Long>? = null

    private const val DIRTY_DELAY_MS = 100L
    private const val LOAD_BUDGET_NS = 3_000_000L

    private val SHADE = floatArrayOf(0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f) // the game's side shades: down, up, north, south, west, east
    private val DX = intArrayOf(0, 0, 0, 0, -1, 1)
    private val DY = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val DZ = intArrayOf(0, 0, -1, 1, 0, 0)

    private fun inArena(sx: Int, sz: Int) = sx in KnownWorld.ARENA_SX && sz in KnownWorld.ARENA_SZ && KnownWorld.bossFloor()
    /** The arena cluster a section is in: sections over 2 each way. */
    fun cluster(sectionKey: Long) = SectionPos.asLong(SectionPos.x(sectionKey) shr 1, SectionPos.y(sectionKey) shr 1, SectionPos.z(sectionKey) shr 1)

    private val grids = HashMap<String, PieceGrid>()
    fun grid(r: KnownWorld.Room): PieceGrid = r.inst.design.let { d -> grids.getOrPut(d.id) { PieceGrid(d.w, d.d, d.y0, d.h) } }

    /** Piece ([lx], [ly], [lz]) of [r]'s unturned box as cells of its placed box, into [out]: x0 y0 z0 x1 y1 z1, inclusive. */
    fun pieceBox(r: KnownWorld.Room, g: PieceGrid, lx: Int, ly: Int, lz: Int, out: IntArray) {
        val a0 = lx shl 4; val a1 = minOf(a0 + 15, g.w - 1); val b0 = lz shl 4; val b1 = minOf(b0 + 15, g.d - 1)
        out[1] = maxOf(ly shl 4, g.y0); out[4] = minOf((ly shl 4) + 15, g.y0 + g.h - 1)
        val w = g.w; val d = g.d
        when (r.p.rot) {
            Rot.SOUTH -> { out[0] = a0; out[3] = a1; out[2] = b0; out[5] = b1 }
            Rot.NORTH -> { out[0] = w - 1 - a1; out[3] = w - 1 - a0; out[2] = d - 1 - b1; out[5] = d - 1 - b0 }
            Rot.WEST -> { out[0] = d - 1 - b1; out[3] = d - 1 - b0; out[2] = a0; out[5] = a1 }
            Rot.EAST -> { out[0] = b0; out[3] = b1; out[2] = w - 1 - a1; out[5] = w - 1 - a0 }
        }
    }

    /** The bundles a room is drawn from: its design's, and its variant's when it has one. */
    private fun bundleNames(r: KnownWorld.Room): Array<String> =
        r.variant?.let { arrayOf(r.inst.design.id, "${r.inst.design.id}@$it") } ?: arrayOf(r.inst.design.id)

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
            val (cx, cz) = r.p.toCanonical(x, z) ?: continue
            for (dx in edge(cx)) for (dy in edge(y)) for (dz in edge(cz)) {
                val key = r.pieceKey((cx shr 4) + dx, (y shr 4) + dy, (cz shr 4) + dz)
                touchedRoom += key; dirtyRoom[key] = now
            }
            touchedRooms += r.key
        }
    }

    // --- building -------------------------------------------------------------------------------------------------------

    /**
     * Puts on the GPU the pieces last frame's feeds asked for, then builds for at most [budgetNs]: rebuilds first, then bundles
     * that have to be made here, then [near] (world sections by the eyes, nearest first), then with [everything] the rest of
     * the dungeon.
     */
    fun pump(level: ClientLevel, budgetNs: Long, near: List<Long>, everything: Boolean) {
        pumpStart = System.nanoTime(); pumpBudget = budgetNs
        // A resource pack change moves every texture: what's on the GPU points at the old atlas.
        if (MeshDisk.check()) dropShared()
        KnownWorld.roomPack?.let { MeshBundles.version = "$BUNDLE_FORMAT-${it.version}" }
        PiecePool.load(minOf(budgetNs, LOAD_BUDGET_NS))
        drainTouched()
        val now = System.currentTimeMillis()

        for ((key, at) in dirtyRoom.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            dirtyRoom.remove(key)
            roomOwn.remove(key)?.buffer?.close()
            changes++
        }
        for ((key, at) in dirtyAt.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            dirtyAt.remove(key)
            nearDone = null; allDone = false
            if (key in world) { world.remove(key)?.buffer?.close() }
            changes++
        }
        if (!MeshDisk.ready) return

        for (r in KnownWorld.rooms) {
            for (name in bundleNames(r)) if (MeshBundles.get(name) == null && MeshBundles.needsBuild(name) && !make(level, name)) return
            if (r.key in touchedRooms) {
                val g = grid(r)
                for (i in 0 until g.size) {
                    val own = r.pieceKey(g.lx(i), g.ly(i), g.lz(i))
                    if (own in touchedRoom && own !in roomOwn && dirtyRoom[own] == null) {
                        roomOwn[own] = upload(buildOwn(level, r, g, g.lx(i), g.ly(i), g.lz(i)))
                        if (overBudget()) return
                    }
                }
            }
            if (overBudget()) return
        }
        if (KnownWorld.bossFloor() && MeshBundles.get(ARENA) == null && MeshBundles.needsBuild(ARENA) && !make(level, ARENA)) return
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

    /** Makes sure world section [key] has its geometry (the arena's own come from its bundle); false once the budget is spent. */
    private fun want(level: ClientLevel, key: Long): Boolean {
        val sx = SectionPos.x(key); val sy = SectionPos.y(key); val sz = SectionPos.z(key)
        if (inArena(sx, sz) && key !in touchedArena) return true
        if (key in world || dirtyAt[key]?.let { System.currentTimeMillis() - it < DIRTY_DELAY_MS } == true) return true
        allDone = false
        world[key] = upload(buildWorld(level, sx, sy, sz))
        return !overBudget()
    }

    /** A bundle being built here, a piece at a time within the budget. */
    private class Making(val places: IntArray, val sight: ((Int) -> ByteArray)? = null, val piece: (Int, MutableMap<String, Int>) -> ByteArray) {
        val parts = arrayOfNulls<ByteArray>(places.size)
        val sights = arrayOfNulls<ByteArray>(places.size)
        val names = LinkedHashMap<String, Int>()
    }
    private val making = HashMap<String, Making>()

    /** Builds bundle [name] on, within the budget: false once the budget is spent. */
    private fun make(level: ClientLevel, name: String): Boolean {
        val m = making.getOrPut(name) { spec(level, name) ?: return true }
        for (i in m.parts.indices) if (m.parts[i] == null) {
            m.parts[i] = m.piece(i, m.names)
            m.sight?.let { m.sights[i] = it(i) }
            if (overBudget()) return false
        }
        making.remove(name)
        val bytes = MeshBundles.serialize(m.names, m.places, Array(m.parts.size) { m.parts[it]!! }, m.sight?.let { Array(m.sights.size) { m.sights[it]!! } })
        MeshBundles.built(name, bytes)
        exported(name, bytes)
        return !overBudget()
    }

    /** What bundle [name] is made of: the arena, a design (`id`) or a variant (`id@variant`, only the pieces it changes). */
    private fun spec(level: ClientLevel, name: String): Making? {
        if (name == ARENA) {
            val a = Arena.data
            val xs = maxOf(KnownWorld.ARENA_SX.first, a.x0 shr 4)..minOf(KnownWorld.ARENA_SX.last, (a.x0 + a.w - 1) shr 4)
            val zs = maxOf(KnownWorld.ARENA_SZ.first, a.z0 shr 4)..minOf(KnownWorld.ARENA_SZ.last, (a.z0 + a.d - 1) shr 4)
            val ys = (a.y0 shr 4)..((a.y0 + a.h - 1) shr 4)
            val places = ArrayList<Int>()
            for (sx in xs) for (sz in zs) for (sy in ys) places += MeshBundles.place(sx, sy, sz)
            return Making(places.toIntArray()) { i, names ->
                val p = places[i]
                val x0 = MeshBundles.placeX(p) shl 4; val y0 = MeshBundles.placeY(p) shl 4; val z0 = MeshBundles.placeZ(p) shl 4
                encoded(mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0, { x, y, z -> a.get(x, y, z) }, { _, _, _ -> false }), names)
            }
        }
        val pack = KnownWorld.roomPack ?: return null
        val at = name.indexOf('@')
        val design = pack.design(if (at < 0) name else name.substring(0, at)) ?: return null
        val base = pack.volume(design) ?: return null
        val v = if (at < 0) base else design.variants.firstOrNull { it.id == name.substring(at + 1) }?.let { pack.variantVolume(design, it) } ?: return null
        val g = PieceGrid(design.w, design.d, design.y0, design.h)
        val places = (if (at < 0) IntArray(g.size) { it } else changedPieces(base, v, g)).map { MeshBundles.place(g.lx(it), g.ly(it), g.lz(it)) }.toIntArray()
        val states = Array(v.palette.size) { i -> val s = v.palette[i]; if (i == 0 || s == "minecraft:air") KnownWorld.AIR else BlockStates.rotated(s, 0) }
        val cell: (Int, Int, Int) -> BlockState = { x, y, z -> if (v.contains(x, y, z)) states[v.paletteIndexAt(x, y, z)] else KnownWorld.AIR }
        // What each piece can see, for a design's own bundle (a variant's pieces go by it: they change little, and it's widened).
        val sight: ((Int) -> ByteArray)? = if (at >= 0) null else run {
            val solid = java.util.BitSet(v.w * v.d * v.h)
            for (y in 0 until v.h) for (z in 0 until v.d) for (x in 0 until v.w) if (states[v.paletteIndexAt(x, y + v.y0, z)].isSolidRender) solid.set((y * v.d + z) * v.w + x)
            val opaque: (Int, Int, Int) -> Boolean = { x, y, z -> solid.get(((y - v.y0) * v.d + z) * v.w + x) }
            ({ i: Int -> RoomSight.from(g, i, opaque, name.hashCode() * 31L + i) })
        }
        return Making(places, sight) { i, names ->
            val p = places[i]
            val x0 = MeshBundles.placeX(p) shl 4; val z0 = MeshBundles.placeZ(p) shl 4; val ly = MeshBundles.placeY(p)
            val ys = maxOf(ly shl 4, g.y0)..minOf((ly shl 4) + 15, g.y0 + g.h - 1)
            // Placed at the dungeon's corner for the tints and the model picks that go by position.
            encoded(mesh(level, x0..minOf(x0 + 15, g.w - 1), ys, z0..minOf(z0 + 15, g.d - 1), DungeonFrame.ORIGIN, DungeonFrame.ORIGIN, cell, { _, _, _ -> false }), names)
        }
    }

    /** The pieces a variant changes: any within 2 cells of a cell it changes (a face and its corner shading look that far). */
    private fun changedPieces(base: RoomVolume, v: RoomVolume, g: PieceGrid): IntArray {
        val same = IntArray(v.palette.size) { base.palette.indexOf(v.palette[it]) }
        val hit = BooleanArray(g.size)
        for (y in v.y0 until v.y0 + v.h) for (z in 0 until v.d) for (x in 0 until v.w) {
            if (same[v.paletteIndexAt(x, y, z)] == base.paletteIndexAt(x, y, z)) continue
            for (px in ((x - 2) shr 4)..((x + 2) shr 4)) for (py in ((y - 2) shr 4)..((y + 2) shr 4)) for (pz in ((z - 2) shr 4)..((z + 2) shr 4)) {
                val i = g.index(px, py, pz)
                if (i >= 0) hit[i] = true
            }
        }
        return hit.indices.filter { hit[it] }.toIntArray()
    }

    private fun encoded(built: Pair<Int, ByteArray?>, names: MutableMap<String, Int>): ByteArray {
        val bytes = built.second ?: return ByteArray(0)
        return MeshCodec.encode(ByteBuffer.wrap(bytes), shades.copyOf(shadeCount), names) ?: ByteArray(0)
    }

    // --- the export: every bundle built here and written out, for the site ---------------------------------------------

    private var exportQueue: ArrayDeque<String>? = null
    private var exportTotal = 0
    private val exportDir get() = FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-export").resolve(MeshBundles.version)

    /** Starts building every bundle (the arena, each design, each variant) to files; the number queued, or -1 without the pack. */
    fun startExport(): Int {
        val pack = KnownWorld.roomPack ?: return -1
        MeshBundles.version = "$BUNDLE_FORMAT-${pack.version}"
        MeshBundles.offline = true
        val q = ArrayDeque<String>()
        q += ARENA
        for (d in pack.designs) { q += d.id; for (v in d.variants) q += "${d.id}@${v.id}" }
        exportQueue = q; exportTotal = q.size
        return q.size
    }

    /** The export on, for [budgetNs]: (done, of) while it runs, then null once (it's finished: the folder it wrote). */
    fun exportStep(level: ClientLevel, budgetNs: Long): Pair<Int, Int>? {
        val q = exportQueue ?: return null
        pumpStart = System.nanoTime(); pumpBudget = budgetNs
        MeshDisk.check()
        while (q.isNotEmpty() && !overBudget()) {
            val name = q.first()
            val b = MeshBundles.get(name)
            if (b != null) { exported(name, b.data); q.removeFirst(); continue }
            if (!MeshBundles.needsBuild(name)) break
            if (!make(level, name)) break
            q.removeFirst()
        }
        if (q.isEmpty()) { exportQueue = null; MeshBundles.offline = false; return exportTotal to exportTotal }
        return (exportTotal - q.size) to exportTotal
    }

    val exportFolder get() = exportDir

    private fun exported(name: String, bytes: ByteArray) {
        if (exportQueue == null) return
        runCatching { Files.createDirectories(exportDir); Files.write(exportDir.resolve(name), bytes) }
    }

    private fun upload(built: Pair<Int, ByteArray?>): Mesh? {
        changes++
        val bytes = built.second ?: return null
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
        if (built.first > ownMost) ownMost = built.first
        return Mesh(RenderSystem.getDevice().createBuffer({ "devgineerclient pose pov" }, GpuBuffer.USAGE_VERTEX, buf), built.first)
    }

    private var pumpStart = 0L
    private var pumpBudget = 0L
    private fun overBudget() = System.nanoTime() - pumpStart > pumpBudget

    /** This run's own piece of [r]: the world as it is now over that piece's cells, in the room's placed box frame. */
    private fun buildOwn(level: ClientLevel, r: KnownWorld.Room, g: PieceGrid, lx: Int, ly: Int, lz: Int): Pair<Int, ByteArray?> {
        val pos = BlockPos.MutableBlockPos()
        val b = IntArray(6)
        pieceBox(r, g, lx, ly, lz, b)
        return mesh(level, b[0]..b[3], b[1]..b[4], b[2]..b[5], r.x0, r.z0,
            { x, y, z -> KnownWorld.stateAt(level, pos, r.x0 + x, y, r.z0 + z) },
            { x, _, z -> KnownWorld.roomAt(r.x0 + x, r.z0 + z) !== r })
    }

    private fun buildWorld(level: ClientLevel, sx: Int, sy: Int, sz: Int): Pair<Int, ByteArray?> {
        if (sy < level.minSectionY || sy > level.maxSectionY) return 0 to null
        if (!KnownWorld.mayHaveBlocks(level, sx, sy, sz)) return 0 to null
        val pos = BlockPos.MutableBlockPos()
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        // Which columns a room draws, looked up once per column rather than per cell; all of them, nothing to do here.
        val covered = BooleanArray(256) { KnownWorld.coveredByRoom(x0 + (it and 15), z0 + (it shr 4)) }
        if (covered.all { it }) return 0 to null
        return mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0,
            { x, y, z -> KnownWorld.stateAt(level, pos, x, y, z) },
            { x, _, z -> covered[((z - z0) shl 4) or (x - x0)] })
    }

    /** The side each quad of the last [mesh] was shaded for (Direction order), quad by quad. */
    private var shades = ByteArray(4096)
    private var shadeCount = 0
    private fun shaded(d: Int) {
        if (shadeCount == shades.size) shades = shades.copyOf(shades.size * 2)
        shades[shadeCount++] = d.toByte()
    }

    /** The faces of the cells in the box, in [at]'s frame, whose origin is world ([ox], 0, [oz]): (index count, vertex bytes). */
    private inline fun mesh(
        level: ClientLevel, xs: IntRange, ys: IntRange, zs: IntRange, ox: Int, oz: Int,
        noinline at: (Int, Int, Int) -> BlockState, skip: (Int, Int, Int) -> Boolean,
    ): Pair<Int, ByteArray?> {
        shadeCount = 0
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
     * What a feed with its eye at ([ex], [ey], [ez]) draws, each as (buffer, first vertex, index count, the frame's x, z
     * offset): of every room [room] lets through, the pieces the frustum ([inView], a world box) has, less their faces turned
     * away from the eye; of the arena, the sections [clusterSeen] lets through, likewise; and the world sections in view.
     * Pieces not on the GPU yet are asked for (those out of view too, after, so turning doesn't show them missing).
     */
    fun forEachDraw(
        ex: Double, ey: Double, ez: Double,
        room: (KnownWorld.Room) -> Boolean, clusterSeen: (Long) -> Boolean,
        inView: (Float, Float, Float, Float, Float, Float) -> Boolean,
        draw: (GpuBuffer, Int, Int, Int, Int) -> Unit,
    ) {
        val b = IntArray(6)
        for (r in KnownWorld.rooms) {
            if (!room(r)) continue
            val roomInView = inView(r.x0.toFloat(), r.y0.toFloat(), r.z0.toFloat(), r.x1 + 1f, r.y1 + 1f, r.z1 + 1f)
            val g = grid(r)
            val names = bundleNames(r)
            val base = MeshBundles.get(names[0]); val variant = if (names.size > 1) MeshBundles.get(names[1]) else null
            if (base == null) continue
            val rx = ex - r.x0; val rz = ez - r.z0
            val changed = r.key in touchedRooms
            // The piece the eye is in, when it's in this room: only what can be seen from there (RoomSight).
            val from = if (base.hasSight) r.p.toCanonical(Math.floor(ex).toInt(), Math.floor(ez).toInt())?.let { (cx, cz) -> g.of(cx, Math.floor(ey).toInt(), cz) } ?: -1 else -1
            for (i in 0 until g.size) {
                if (from >= 0 && !base.sees(from, i)) continue
                val lx = g.lx(i); val ly = g.ly(i); val lz = g.lz(i)
                pieceBox(r, g, lx, ly, lz, b)
                val seen = roomInView && inView(r.x0 + b[0].toFloat(), b[1].toFloat(), r.z0 + b[2].toFloat(), r.x0 + b[3] + 1f, b[4] + 1f, r.z0 + b[5] + 1f)
                if (seen && changed) {
                    val own = r.pieceKey(lx, ly, lz)
                    if (own in touchedRoom && own in roomOwn) { roomOwn[own]?.let { draw(it.buffer, 0, it.indexCount, r.x0, r.z0) }; continue }
                }
                val vi = variant?.find(lx, ly, lz) ?: -1
                val bundle = if (vi >= 0) variant!! else base
                val pi = if (vi >= 0) vi else base.find(lx, ly, lz)
                if (pi < 0) continue
                val near = away(rx, ey, rz, b)
                val res = PiecePool.get(bundle, pi, r.p.rot, g.w, g.d, if (seen) near else 1e9 + near) ?: continue
                if (seen) groups(res, b, rx, ey, rz, r.x0, r.z0, draw)
            }
        }
        if (KnownWorld.bossFloor()) MeshBundles.get(ARENA)?.let { a ->
            for (i in a.places.indices) {
                val p = a.places[i]
                val sx = MeshBundles.placeX(p); val sy = MeshBundles.placeY(p); val sz = MeshBundles.placeZ(p)
                val s = SectionPos.asLong(sx, sy, sz)
                if (s in touchedArena || !clusterSeen(cluster(s))) continue
                b[0] = sx shl 4; b[1] = sy shl 4; b[2] = sz shl 4; b[3] = b[0] + 15; b[4] = b[1] + 15; b[5] = b[2] + 15
                val seen = inView(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3] + 1f, b[4] + 1f, b[5] + 1f)
                val near = away(ex, ey, ez, b)
                val res = PiecePool.get(a, i, Rot.SOUTH, 0, 0, if (seen) near else 1e9 + near) ?: continue
                if (seen) groups(res, b, ex, ey, ez, 0, 0, draw)
            }
        }
        for ((k, m) in world) {
            if (m == null) continue
            val x = SectionPos.x(k) shl 4; val y = SectionPos.y(k) shl 4; val z = SectionPos.z(k) shl 4
            if (inView(x.toFloat(), y.toFloat(), z.toFloat(), x + 16f, y + 16f, z + 16f)) draw(m.buffer, 0, m.indexCount, 0, 0)
        }
    }

    /** How far (squared) the eye is from the box [b] (cells, inclusive). */
    private fun away(ex: Double, ey: Double, ez: Double, b: IntArray): Double {
        val dx = maxOf(b[0] - ex, 0.0, ex - b[3] - 1); val dy = maxOf(b[1] - ey, 0.0, ey - b[4] - 1); val dz = maxOf(b[2] - ez, 0.0, ez - b[5] - 1)
        return dx * dx + dy * dy + dz * dz
    }

    /** A piece's groups that face the eye (in the piece's frame, its box [b]), runs of them as one draw. */
    private fun groups(res: PiecePool.Resident, b: IntArray, ex: Double, ey: Double, ez: Double, ox: Int, oz: Int, draw: (GpuBuffer, Int, Int, Int, Int) -> Unit) {
        var v = res.first; var runFirst = -1; var runQuads = 0
        for (g in 0 until MeshCodec.GROUPS) {
            val n = res.quads[g]
            if (n == 0) continue
            val facing = when (res.dirs[g]) {
                0 -> ey < b[4] + 1; 1 -> ey > b[1]
                2 -> ez < b[5] + 1; 3 -> ez > b[2]
                4 -> ex < b[3] + 1; 5 -> ex > b[0]
                else -> true
            }
            if (facing) { if (runFirst < 0) runFirst = v; runQuads += n }
            else if (runFirst >= 0) { draw(res.buffer, runFirst, runQuads * 6, ox, oz); runFirst = -1; runQuads = 0 }
            v += n * 4
        }
        if (runFirst >= 0) draw(res.buffer, runFirst, runQuads * 6, ox, oz)
    }

    // --- lifetime -------------------------------------------------------------------------------------------------------

    /** This run's pieces; the shared design and arena ones stay for the next run. */
    fun newRun() {
        changes++
        world.values.forEach { it?.buffer?.close() }; world.clear()
        roomOwn.values.forEach { it?.buffer?.close() }; roomOwn.clear()
        touchedRoom.clear(); touchedRooms.clear(); touchedArena.clear(); dirtyAt.clear(); dirtyRoom.clear()
        nearDone = null; allDone = false
    }

    fun clear() {
        newRun()
        dropShared()
    }

    private fun dropShared() {
        PiecePool.clear()
        MeshBundles.atlasChanged()
        nearDone = null; allDone = false
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
            for (f in 0..5) if (!hidden[f]) { cube(bb, f, x.toFloat(), y.toFloat(), z.toFloat(), sprite, mul(tint, SHADE[f])); shaded(f) }
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
        shaded(d.ordinal)
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
