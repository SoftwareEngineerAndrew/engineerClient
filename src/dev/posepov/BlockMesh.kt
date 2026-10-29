package com.devgineerclient.posepov

import com.engineerclient.p3sim.Arena
import com.engineerclient.dungeonpack.rooms.BlockStates
import com.engineerclient.dungeonpack.pack.DungeonFrame
import com.engineerclient.dungeonpack.pack.Rot
import com.engineerclient.dungeonpack.pack.rooms.RoomVolume
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
import net.minecraft.world.level.chunk.PalettedContainer
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
 * Building runs on [builder]'s threads from a copy of the chunk sections it needs ([Snapshot]: the game thread only copies
 * those, a few arrays, and uploads); a chunk that arrives again is checked there too. Render thread only, except [dirty]
 * (packets) and the building.
 */
object BlockMesh {
    /**
     * This run's own geometry ([CompactMesh]'s form) in the shared pools ([PiecePool.putOwn]), drawn with its frame's corner at
     * world ([ox], 0, [oz]); its quads per group.
     */
    class Mesh(val res: PiecePool.Resident, val ox: Int, val oz: Int) {
        val buffer get() = res.buffer
        val first get() = res.first
        val counts get() = res.counts
    }

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

    private val grids = ConcurrentHashMap<String, PieceGrid>()
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
    fun bundleNames(r: KnownWorld.Room): Array<String> =
        r.variant?.let { arrayOf(r.inst.design.id, "${r.inst.design.id}@$it") } ?: arrayOf(r.inst.design.id)

    // --- what changed ---------------------------------------------------------------------------------------------------

    /** The world section of a cell (and the next one over when the cell is on its edge) needs rebuilding. */
    fun dirty(x: Int, y: Int, z: Int) {
        val now = System.currentTimeMillis()
        val sx = x shr 4; val sy = y shr 4; val sz = z shr 4
        for (dx in edge(x)) for (dy in edge(y)) for (dz in edge(z)) SectionPos.asLong(sx + dx, sy + dy, sz + dz).let { dirtyAt[it] = now; chunkOnly.remove(it) }
    }

    private val MID = intArrayOf(0); private val LOW = intArrayOf(0, -1); private val HIGH = intArrayOf(0, 1)
    private fun edge(v: Int) = when (v and 15) { 0 -> LOW; 15 -> HIGH; else -> MID }

    /**
     * A column's world sections need rebuilding: a room placed over it ([check] false), or its chunk arriving ([check] true).
     * A chunk arriving is only looked at: its sections are rebuilt only where the blocks differ from what they were built
     * from (moving about the dungeon reloads the same chunks over and over).
     */
    fun dirtyColumn(cx: Int, cz: Int, minSy: Int, maxSy: Int, check: Boolean = false) {
        val now = System.currentTimeMillis()
        for (sy in minSy..maxSy) {
            val k = SectionPos.asLong(cx, sy, cz)
            if (check) { if (!dirtyAt.containsKey(k)) chunkOnly += k } else chunkOnly.remove(k)
            dirtyAt[k] = now
        }
    }

    /** Dirty world sections only because their chunk arrived (see [dirtyColumn]). */
    private val chunkOnly = ConcurrentHashMap.newKeySet<Long>()
    /** What the level held in each world section when it was last built ([levelHash]). */
    private val builtFrom = HashMap<Long, Int>()

    /** A section's own blocks as one number (1 when it's all air or not loaded: no copy). Any thread. */
    private fun sectionHash(states: PalettedContainer<BlockState>?): Int {
        if (states == null) return 1
        var h = 17
        for (y in 0..15) for (z in 0..15) for (x in 0..15) h = h * 31 + System.identityHashCode(states.get(x, y, z))
        return h
    }

    /** Cells that are no longer what the room pack or the arena says: their pieces become this run's own. */
    private fun drainTouched() {
        val now = System.currentTimeMillis()
        while (true) {
            val k = KnownWorld.touchedCells.poll() ?: break
            val x = BlockPos.getX(k); val y = BlockPos.getY(k); val z = BlockPos.getZ(k)
            dirty(x, y, z)
            if (inArena(x shr 4, z shr 4)) touchedArena += SectionPos.asLong(x shr 4, y shr 4, z shr 4)
            // Its room's pieces around it, and a room's across the gap (its outer wall, left out of its pieces, can show
            // through a hole here: RoomCut). Next to a sealed pocket, that room's pockets are drawn from now on.
            var seen: KnownWorld.Room? = null
            for (dx in NEAR_ROOMS) for (dz in NEAR_ROOMS) {
                val r = KnownWorld.roomAt(x + dx, z + dz) ?: continue
                if (r === seen || (dx != 0 || dz != 0) && r === KnownWorld.roomAt(x, z)) continue
                seen = r
                touchPieces(r, x + dx, y, z + dz, now)
                if (r.cutPockets && (dx == 0 && dz == 0) && SIDES.any { (ax, ay, az) -> r.inPocket(x + ax, y + ay, z + az) }) { r.cutPockets = false; changes++ }
            }
        }
    }
    private val NEAR_ROOMS = intArrayOf(0, -2, 2)

    /** A room's pieces are read differently now ([KnownWorld.Room.cutPockets]): what's drawn is worked out again. */
    fun roomChanged() { changes++ }
    private val SIDES = listOf(Triple(0, 0, 0), Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))

    /** [r]'s pieces around world cell ([x], [y], [z]) become this run's own (built from the world as it is now). */
    private fun touchPieces(r: KnownWorld.Room, x: Int, y: Int, z: Int, now: Long) {
        val (cx, cz) = r.p.toCanonical(x, z) ?: return
        for (dx in edge(cx)) for (dy in edge(y)) for (dz in edge(cz)) {
            val key = r.pieceKey((cx shr 4) + dx, (y shr 4) + dy, (cz shr 4) + dz)
            touchedRoom += key; dirtyRoom[key] = now
        }
        touchedRooms += r.key
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
        // (Everything built here goes up in the sprite table's numbers.)
        if (!CompactMesh.SpriteTable.ready) return
        drainBuilt()
        drainTouched()
        val now = System.currentTimeMillis()

        for ((key, at) in dirtyRoom.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            dirtyRoom.remove(key)
            // What's built stays drawn until its rebuild comes back (no gap while it's on a builder).
            if (key in roomOwn) staleOwn += key
            ownPending.remove(key)
            changes++
        }
        sectionCopies.clear()
        for ((key, at) in dirtyAt.entries.toList()) {
            if (now - at < DIRTY_DELAY_MS) continue
            // Its chunk came again: a builder checks it against what it was built from, and builds it again only if it differs
            // (what's built stays drawn meanwhile).
            if (chunkOnly.contains(key) && key in world && key !in worldPending && key !in stale) {
                if (building >= IN_FLIGHT) break
                dirtyAt.remove(key); chunkOnly.remove(key)
                PovPerf.time(PovPerf.CHUNK_CHECK) { buildWorld(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), key, builtFrom[key]) }
                continue
            }
            dirtyAt.remove(key); chunkOnly.remove(key)
            nearDone = null; allDone = false
            if (key in world) stale += key
            worldPending.remove(key)
            changes++
        }
        if (!MeshDisk.ready) return

        for (r in KnownWorld.rooms) {
            for (name in bundleNames(r)) if (MeshBundles.get(name) == null && MeshBundles.needsBuild(name)) makeLater(level, name)
            if (r.key in touchedRooms) {
                val g = grid(r)
                for (i in 0 until g.size) {
                    val own = r.pieceKey(g.lx(i), g.ly(i), g.lz(i))
                    if (own in touchedRoom && (own !in roomOwn || own in staleOwn) && own !in ownPending && dirtyRoom[own] == null) {
                        if (building >= IN_FLIGHT) return
                        PovPerf.time(PovPerf.MAKE_OWN) { buildOwn(level, r, g, g.lx(i), g.ly(i), g.lz(i), own) }
                        if (overBudget()) return
                    }
                }
            }
            if (overBudget()) return
        }
        if (KnownWorld.bossFloor() && MeshBundles.get(ARENA) == null && MeshBundles.needsBuild(ARENA)) makeLater(level, ARENA)
        if (near !== nearDone) {
            for (key in near) if (!want(level, key)) return
            nearDone = near
        }
        if (!everything) return
        prebuildRooms()
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

    private var prebuiltAt = 0L
    /**
     * Every placed room's pieces asked onto the GPU (after anything a view wants, nearest you first), and those already there
     * kept from going idle: the whole dungeon is up before anyone opens the menu. Once a second, while the pools are under 70%
     * (past that, only what views ask for).
     */
    private fun prebuildRooms() {
        val now = System.currentTimeMillis()
        if (now - prebuiltAt < 1000 || PiecePool.usedBytes > PiecePool.CAP_BYTES * 7 / 10) return
        prebuiltAt = now
        val me = mc().player ?: return
        for (r in KnownWorld.rooms) {
            val names = bundleNames(r)
            val base = MeshBundles.get(names[0]) ?: continue
            val variant = if (names.size > 1) MeshBundles.get(names[1]) else null
            val g = grid(r)
            val dx = me.x - (r.x0 + r.x1) / 2.0; val dz = me.z - (r.z0 + r.z1) / 2.0
            val away = dx * dx + dz * dz
            for (i in 0 until g.size) {
                val vi = variant?.find(g.lx(i), g.ly(i), g.lz(i)) ?: -1
                val bundle = if (vi >= 0) variant!! else base
                val pi = if (vi >= 0) vi else base.find(g.lx(i), g.ly(i), g.lz(i))
                if (pi >= 0) PiecePool.get(bundle, pi, r.p.rot, g.w, g.d, r, 1e10 + away)
            }
        }
    }

    /** Makes sure world section [key] has its geometry (the arena's own come from its bundle); false once the budget is spent. */
    private fun want(level: ClientLevel, key: Long): Boolean {
        val sx = SectionPos.x(key); val sy = SectionPos.y(key); val sz = SectionPos.z(key)
        if (inArena(sx, sz) && key !in touchedArena) return true
        if ((key in world && key !in stale) || dirtyAt[key]?.let { System.currentTimeMillis() - it < DIRTY_DELAY_MS } == true) return true
        allDone = false
        if (key in worldPending) return true
        if (building >= IN_FLIGHT) return false
        PovPerf.time(PovPerf.MAKE_WORLD) { buildWorld(level, sx, sy, sz, key) }
        return !overBudget()
    }

    // --- building, on the builders --------------------------------------------------------------------------------------

    /** Builds at most this many at once (each holds a copy of its cells); the rest wait for the next frames. */
    private const val IN_FLIGHT = 32
    private val builder = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "pose pov build").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val meshers = ThreadLocal.withInitial { Mesher() }
    private class Built(val world: Long, val own: String?, val bundle: String?, val token: Int, val packed: CompactMesh.Packed?, val bytes: ByteArray?, val ox: Int, val oz: Int, val hash: Int = 0, val unchanged: Boolean = false)
    private val built = java.util.concurrent.ConcurrentLinkedQueue<Built>()
    private var building = 0
    private var tokens = 0
    /** What's being built for each world section and changed room piece: a result whose token isn't here any more is stale. */
    private val worldPending = HashMap<Long, Int>()
    private val ownPending = HashMap<String, Int>()
    private val bundlesPending = HashSet<String>()
    /** Built, changed since, rebuild not back yet: still drawn. */
    private val stale = HashSet<Long>()
    private val staleOwn = HashSet<String>()

    /**
     * The level's chunk sections around a build, copied on the game thread (null: all air, not loaded or out of the world); a
     * build reads its cells through [KnownWorld.known] and these on a builder.
     */
    private class Snapshot(val sx0: Int, val sy0: Int, val sz0: Int, val nx: Int, val ny: Int, val nz: Int, val sections: Array<PalettedContainer<BlockState>?>) {
        fun section(sx: Int, sy: Int, sz: Int): PalettedContainer<BlockState>? {
            val i = sx - sx0; val j = sy - sy0; val k = sz - sz0
            if (i !in 0 until nx || j !in 0 until ny || k !in 0 until nz) return null
            return sections[(j * nz + k) * nx + i]
        }
        fun get(x: Int, y: Int, z: Int): BlockState =
            KnownWorld.known(x, y, z) ?: section(x shr 4, y shr 4, z shr 4)?.get(x and 15, y and 15, z and 15) ?: KnownWorld.AIR
    }

    /** This pump's section copies, shared by its builds. */
    private val sectionCopies = HashMap<Long, PalettedContainer<BlockState>?>()

    /** The sections under world cells [xs] x [ys] x [zs], copied (game thread). */
    private fun snapshot(level: ClientLevel, xs: IntRange, ys: IntRange, zs: IntRange): Snapshot {
        val sx0 = xs.first shr 4; val sy0 = ys.first shr 4; val sz0 = zs.first shr 4
        val nx = (xs.last shr 4) - sx0 + 1; val ny = (ys.last shr 4) - sy0 + 1; val nz = (zs.last shr 4) - sz0 + 1
        val out = arrayOfNulls<PalettedContainer<BlockState>>(nx * ny * nz)
        for (j in 0 until ny) for (k in 0 until nz) for (i in 0 until nx) {
            val sx = sx0 + i; val sy = sy0 + j; val sz = sz0 + k
            out[(j * nz + k) * nx + i] = sectionCopies.getOrPut(SectionPos.asLong(sx, sy, sz)) {
                if (sy < level.minSectionY || sy > level.maxSectionY || !level.hasChunk(sx, sz)) null
                else level.getChunk(sx, sz).sections[level.getSectionIndexFromSectionY(sy)].takeIf { !it.hasOnlyAir() }?.states?.copy()
            }
        }
        return Snapshot(sx0, sy0, sz0, nx, ny, nz, out)
    }

    /** The cells a build reads: a box of the world, filled on the builder. */
    private class Cells(val x0: Int, val y0: Int, val z0: Int, val w: Int, val h: Int, val d: Int) {
        val states = arrayOfNulls<BlockState>(w * h * d)
        fun get(x: Int, y: Int, z: Int): BlockState {
            val i = x - x0; val j = y - y0; val k = z - z0
            if (i < 0 || j < 0 || k < 0 || i >= w || j >= h || k >= d) return KnownWorld.AIR
            return states[(j * d + k) * w + i] ?: KnownWorld.AIR
        }
    }

    /** The box [xs]×[ys]×[zs] and the two cells around it (the corner shading looks that far), through [at]. */
    private inline fun cells(xs: IntRange, ys: IntRange, zs: IntRange, at: (Int, Int, Int) -> BlockState): Cells {
        val c = Cells(xs.first - 2, ys.first - 2, zs.first - 2, xs.last - xs.first + 5, ys.last - ys.first + 5, zs.last - zs.first + 5)
        var n = 0
        for (y in c.y0 until c.y0 + c.h) for (z in c.z0 until c.z0 + c.d) for (x in c.x0 until c.x0 + c.w) c.states[n++] = at(x, y, z)
        return c
    }

    /** Built geometry in [CompactMesh]'s form, its corners less ([px], [pz]) (any thread). */
    private fun pack(built: Pair<Int, ByteArray?>, px: Int, pz: Int): CompactMesh.Packed? {
        val bytes = built.second ?: return null
        val q = packQuads.get(); q.clear()
        if (!CompactMesh.fromVertices(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()), q)) return null
        return CompactMesh.pack(q, px.toFloat(), pz.toFloat()).takeIf { it.quads > 0 }
    }
    private val packQuads = ThreadLocal.withInitial { CompactMesh.Quads(1024) }

    /** Finished builds onto the GPU (stale ones dropped). */
    private fun drainBuilt() {
        while (true) {
            val b = built.poll() ?: break
            building--
            if (b.bundle != null) {
                bundlesPending.remove(b.bundle)
                if (b.bytes != null) { MeshBundles.built(b.bundle, b.bytes); changes++ } else bundlesFailed += b.bundle
                continue
            }
            val own = b.own
            if (own != null) {
                if (ownPending[own] != b.token) continue
                ownPending.remove(own); staleOwn.remove(own)
                roomOwn.remove(own)?.let { PiecePool.release(it.res) }
                roomOwn[own] = put(b)
            } else {
                if (worldPending[b.world] != b.token) continue
                worldPending.remove(b.world); stale.remove(b.world)
                builtFrom[b.world] = b.hash
                // Checked and the same as it was built from: what's built stands.
                if (b.unchanged) continue
                world.remove(b.world)?.let { PiecePool.release(it.res) }
                world[b.world] = put(b)
                worldKeys = null
            }
            changes++
        }
    }

    private fun put(b: Built): Mesh? {
        val p = b.packed ?: return null
        if (p.quads * 6 > ownMost) ownMost = p.quads * 6
        val t0 = System.nanoTime()
        val res = PiecePool.putOwn(p)
        PovPerf.add(PovPerf.UPLOAD, System.nanoTime() - t0)
        return res?.let { Mesh(it, b.ox, b.oz) }
    }

    /** [job] on a builder; [failed] stands in for it if it throws (an empty result: nothing is asked again forever). */
    private fun submit(failed: Built, job: () -> Built) {
        building++
        builder.execute {
            val t0 = System.nanoTime()
            val b = runCatching(job).onFailure { com.devgineerclient.DevgineerClient.logger.warn("[pose pov] a build failed", it) }.getOrNull()
            PovPerf.add(PovPerf.BUILD_WORKER, System.nanoTime() - t0)
            built += b ?: failed
        }
    }
    private val bundlesFailed = HashSet<String>()

    /** Bundle [name], built whole on a builder and kept (rooms the site doesn't have, or offline). */
    private fun makeLater(level: ClientLevel, name: String) {
        if (name in bundlesPending || name in bundlesFailed || building >= IN_FLIGHT) return
        bundlesPending += name
        submit(Built(0, null, name, 0, null, null, 0, 0)) {
            val m = spec(level, name, meshers.get())
            val bytes = m?.let { mk ->
                for (i in mk.parts.indices) { mk.parts[i] = mk.piece(i, mk.names); mk.sight?.let { mk.sights[i] = it(i) } }
                MeshBundles.serialize(mk.names, mk.places, Array(mk.parts.size) { mk.parts[it]!! }, mk.sight?.let { Array(mk.sights.size) { mk.sights[it]!! } })
            }
            Built(0, null, name, 0, null, bytes, 0, 0)
        }
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
        val m = making.getOrPut(name) { spec(level, name, gameMesher) ?: return true }
        for (i in m.parts.indices) if (m.parts[i] == null) {
            PovPerf.time(PovPerf.MAKE) {
                m.parts[i] = m.piece(i, m.names)
                m.sight?.let { m.sights[i] = it(i) }
            }
            if (overBudget()) return false
        }
        making.remove(name)
        val bytes = MeshBundles.serialize(m.names, m.places, Array(m.parts.size) { m.parts[it]!! }, m.sight?.let { Array(m.sights.size) { m.sights[it]!! } })
        MeshBundles.built(name, bytes)
        exported(name, bytes)
        return !overBudget()
    }

    /** What bundle [name] is made of: the arena, a design (`id`) or a variant (`id@variant`, only the pieces it changes). */
    private fun spec(level: ClientLevel, name: String, mesher: Mesher): Making? {
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
                mesher.encoded(mesher.mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0, { x, y, z -> a.get(x, y, z) }, { _, _, _ -> false }), names)
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
            mesher.encoded(mesher.mesh(level, x0..minOf(x0 + 15, g.w - 1), ys, z0..minOf(z0 + 15, g.d - 1), DungeonFrame.ORIGIN, DungeonFrame.ORIGIN, cell, { _, _, _ -> false }), names)
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


    // --- the export: every bundle built here and written out, for the site ---------------------------------------------

    private var exportQueue: ArrayDeque<String>? = null
    private var exportTotal = 0
    private val exportDir get() = FabricLoader.getInstance().gameDir.resolve("devgineerclient").resolve("posepov-export").resolve(MeshBundles.version)

    /**
     * Every bundle onto disk in the background ([MeshBundles.prefetch]), once: the arena first on a floor that has it, then
     * the designs, smallest first (the common one-tile rooms), then the variants. Any thread; needs the room pack (render thread: the disk is opened here).
     */
    fun prefetchAll(floor7: Boolean) {
        val pack = KnownWorld.roomPack ?: return
        if (!MeshDisk.ready) { MeshDisk.check(); return }
        MeshBundles.version = "$BUNDLE_FORMAT-${pack.version}"
        val names = ArrayList<String>()
        if (floor7) names += ARENA
        val designs = pack.designs.sortedBy { it.w.toLong() * it.d * it.h }
        for (d in designs) names += d.id
        for (d in designs) for (v in d.variants) names += "${d.id}@${v.id}"
        if (!floor7) names += ARENA
        MeshBundles.prefetch(names)
        prefetchStarted = true
    }
    /** [prefetchAll] has handed its list over (for this launch). */
    var prefetchStarted = false; private set

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

    private var pumpStart = 0L
    private var pumpBudget = 0L
    private fun overBudget() = System.nanoTime() - pumpStart > pumpBudget

    /**
     * This run's own piece [own] of [r]: the world as it is now over that piece's cells, in the room's placed box frame, copied
     * here and built on a builder.
     */
    private fun buildOwn(level: ClientLevel, r: KnownWorld.Room, g: PieceGrid, lx: Int, ly: Int, lz: Int, own: String) {
        val b = IntArray(6)
        pieceBox(r, g, lx, ly, lz, b)
        val xs = b[0]..b[3]; val ys = b[1]..b[4]; val zs = b[2]..b[5]
        val x0 = r.x0; val z0 = r.z0
        val snap = snapshot(level, x0 + xs.first - 2..x0 + xs.last + 2, ys.first - 2..ys.last + 2, z0 + zs.first - 2..z0 + zs.last + 2)
        val token = ++tokens
        ownPending[own] = token
        // Drawn in the world's frame, like the room's own pieces.
        submit(Built(0, own, null, token, null, null, 0, 0)) {
            val cells = cells(xs, ys, zs) { x, y, z -> snap.get(x0 + x, y, z0 + z) }
            val w = xs.last - xs.first + 1
            val others = BooleanArray(w * (zs.last - zs.first + 1)) { KnownWorld.roomAt(x0 + xs.first + it % w, z0 + zs.first + it / w) !== r }
            val m = meshers.get()
            val built = m.mesh(level, xs, ys, zs, x0, z0, cells::get) { x, _, z -> others[(z - zs.first) * w + (x - xs.first)] }
            Built(0, own, null, token, pack(built, -x0, -z0), null, 0, 0)
        }
    }

    /**
     * World section [key] built on a builder from a copy of the sections around it. Drawn in the shared frame at the world's
     * corner when its corners fit the compact form's range from there (the dungeon and the arena do), else in its own. With
     * [expect] (its chunk came again: the blocks it was built from), the builder first checks the section against that, and
     * only builds it if it differs.
     */
    private fun buildWorld(level: ClientLevel, sx: Int, sy: Int, sz: Int, key: Long, expect: Int? = null) {
        val x0 = sx shl 4; val y0 = sy shl 4; val z0 = sz shl 4
        // Nothing in it to draw (the sky over the dungeon, unloaded): no build.
        if (sy < level.minSectionY || sy > level.maxSectionY || !KnownWorld.mayHaveBlocks(level, sx, sy, sz)) {
            stale.remove(key); worldPending.remove(key)
            val had = world.containsKey(key)
            builtFrom[key] = 1
            if (expect == 1 && had) return
            world.remove(key)?.let { PiecePool.release(it.res) }
            world[key] = null; worldKeys = null; changes++
            return
        }
        val snap = snapshot(level, x0 - 2..x0 + 17, y0 - 2..y0 + 17, z0 - 2..z0 + 17)
        val shared = x0 in -256..736 && z0 in -256..736
        val ox = if (shared) 0 else x0; val oz = if (shared) 0 else z0
        val token = ++tokens
        worldPending[key] = token
        submit(Built(key, null, null, token, null, null, ox, oz)) {
            val hash = sectionHash(snap.section(sx, sy, sz))
            if (expect != null && hash == expect) return@submit Built(key, null, null, token, null, null, ox, oz, hash, unchanged = true)
            // Which columns a room draws, looked up once per column; all of them: nothing to build.
            val covered = BooleanArray(256) { KnownWorld.coveredByRoom(x0 + (it and 15), z0 + (it shr 4)) }
            if (covered.all { it }) return@submit Built(key, null, null, token, null, null, ox, oz, hash)
            val cells = cells(x0..x0 + 15, y0..y0 + 15, z0..z0 + 15) { x, y, z -> snap.get(x, y, z) }
            val m = meshers.get()
            val built = m.mesh(level, x0..x0 + 15, y0..y0 + 15, z0..z0 + 15, 0, 0, cells::get) { x, _, z -> covered[((z - z0) shl 4) or (x - x0)] }
            Built(key, null, null, token, pack(built, ox, oz), null, ox, oz, hash)
        }
    }

    private val gameMesher = Mesher()

    /** One thread's meshing: what the faces were shaded for, the model parts, the cell lookup ([meshers], [gameMesher]). */
    private class Mesher {
    /** The side each quad of the last [mesh] was shaded for (Direction order), quad by quad. */
    private var shades = ByteArray(4096)
    private var shadeCount = 0
    private fun shaded(d: Int) {
        if (shadeCount == shades.size) shades = shades.copyOf(shades.size * 2)
        shades[shadeCount++] = d.toByte()
    }

    fun encoded(built: Pair<Int, ByteArray?>, names: MutableMap<String, Int>): ByteArray {
        val bytes = built.second ?: return ByteArray(0)
        return MeshCodec.encode(ByteBuffer.wrap(bytes), shades.copyOf(shadeCount), names) ?: ByteArray(0)
    }

    /** The faces of the cells in the box, in [at]'s frame, whose origin is world ([ox], 0, [oz]): (index count, vertex bytes). */
    fun mesh(
        level: ClientLevel, xs: IntRange, ys: IntRange, zs: IntRange, ox: Int, oz: Int,
        at: (Int, Int, Int) -> BlockState, skip: (Int, Int, Int) -> Boolean,
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

    // --- one block ------------------------------------------------------------------------------------------------------

    /** Which of the six neighbours hide that side (Direction order: down, up, north, south, west, east); set per cell by [mesh]. */
    private val hidden = BooleanArray(6)
    private val parts = ArrayList<BlockStateModelPart>()
    private val random = RandomSource.create()
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
    }

    // --- drawing (what PovCull picked, looked up) -----------------------------------------------------------------------

    /** This run's own piece of [r] at ([lx], [ly], [lz]) when it has one built: drawn in place of the design's. */
    fun own(r: KnownWorld.Room, lx: Int, ly: Int, lz: Int): Mesh? {
        if (r.key !in touchedRooms) return null
        val k = r.pieceKey(lx, ly, lz)
        return if (k in touchedRoom) roomOwn[k] else null
    }

    /** Whether this run changed arena section [s] (it's drawn as a world section then). */
    fun arenaTouched(s: Long) = s in touchedArena

    fun worldMesh(s: Long): Mesh? = world[s]

    @Volatile private var worldKeys: LongArray? = null
    /** The world sections with something to draw, as of now (made again when one comes or goes). */
    fun worldSections(): LongArray = worldKeys ?: world.entries.filter { it.value != null }.map { it.key }.toLongArray().also { worldKeys = it }

    // --- lifetime -------------------------------------------------------------------------------------------------------

    /** This run's pieces; the shared design and arena ones stay for the next run. */
    fun newRun() {
        changes++
        world.values.forEach { it?.let { m -> PiecePool.release(m.res) } }; world.clear(); worldKeys = null
        roomOwn.values.forEach { it?.let { m -> PiecePool.release(m.res) } }; roomOwn.clear()
        touchedRoom.clear(); touchedRooms.clear(); touchedArena.clear(); dirtyAt.clear(); dirtyRoom.clear()
        worldPending.clear(); ownPending.clear(); chunkOnly.clear(); builtFrom.clear(); stale.clear(); staleOwn.clear()
        nearDone = null; allDone = false
    }

    fun clear() {
        newRun()
        dropShared()
    }

    private fun dropShared() {
        // This run's own geometry lives in the pools too: built again with the new textures.
        world.clear(); worldKeys = null; roomOwn.clear(); worldPending.clear(); ownPending.clear(); builtFrom.clear(); stale.clear(); staleOwn.clear(); changes++
        PiecePool.clear()
        CompactMesh.SpriteTable.clear()
        nearDone = null; allDone = false
    }

    private val DIRS = Direction.entries.toTypedArray()

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
