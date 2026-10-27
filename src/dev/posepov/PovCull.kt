package com.devgineerclient.posepov

import com.engineerclient.ads.pack.DungeonFrame
import com.engineerclient.ads.pack.Rot
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Vector4f
import java.util.BitSet

/**
 * What one view draws, worked out from a snapshot so it can run off the game thread ([PosePov] runs it on a worker a frame
 * ahead, and keeps each answer while the view stays close to where it was worked out for):
 *  - which rooms show at all: from the eye's room through each opening between rooms (a door, over the walls, a wall
 *    something broke), each opening narrowing the view for what's past it ([Portals]);
 *  - of those rooms, the arena and the world sections, the pieces in the view and within the view distance, less what the
 *    eye's piece can't see (RoomSight); per piece, the groups that face the eye, and whether it's far enough for the big faces only;
 *  - nearest first, so the GPU can skip what's hidden behind what it drew already.
 * Every test leaves [MARGIN] blocks and [ANGLE_MARGIN]° of room, so the answer still holds for an eye up to that far off.
 */
object PovCull {
    const val MARGIN = 1.0
    const val ANGLE_MARGIN = 5f
    /** Pieces out of view but this close (squared) are put on the GPU too, so turning doesn't find them missing. */
    private const val AROUND2 = 48.0 * 48.0
    private const val DOOR_Y0 = DungeonFrame.DOOR_Y_MIN.toDouble()
    private const val DOOR_Y1 = DungeonFrame.DOOR_Y_MAX + 1.0
    /** Openings crossed at most (rooms this many apart in a straight line are past any view distance). */
    private const val MAX_STEPS = 6

    const val ROOM = 0
    const val ARENA = 1
    const val WORLD = 2

    /**
     * A piece to draw (or, in [Result.around], only to have ready): a room's piece ([lx] [ly] [lz] of [bundle], [piece] in it, -1
     * when it has none: then only this run's own geometry can stand there), an arena section ([section], [piece] in the arena's
     * bundle) or a world section; how far, which sides of it face the eye, whether it's past the detail distance.
     */
    class Ref(
        val kind: Int, val room: KnownWorld.Room?, val bundle: MeshBundles.Bundle?, val piece: Int, val rot: Rot, val w: Int, val d: Int,
        val lx: Int, val ly: Int, val lz: Int, val section: Long, val dist2: Double, val faces: Int, val far: Boolean,
    )

    class Result(val refs: List<Ref>, val around: List<Ref>, val rooms: Set<KnownWorld.Room>?, val eyePiece: Long)

    /** What a view needs from the game thread, taken there. */
    class Input(
        val ex: Double, val ey: Double, val ez: Double,
        /** The view's projection times view, widened by [ANGLE_MARGIN] (world coordinates). */
        val viewProj: Matrix4f,
        val viewDist: Double, val lodDist: Double,
        val rooms: List<KnownWorld.Room>, val portals: Portals?, val clusters: BitSet?, val arena: MeshBundles.Bundle?, val world: LongArray,
        /** Every way at once (no view: [viewProj] unused), the openings not narrowing anything: what could show from there at all. */
        val wide: Boolean = false,
    )

    /** Which room and piece the eye is in, as a number (an answer is for one); 0 outside the rooms. */
    fun eyePiece(ex: Double, ey: Double, ez: Double): Long {
        val x = Math.floor(ex).toInt(); val z = Math.floor(ez).toInt()
        val r = KnownWorld.roomAt(x, z) ?: return 0L
        val i = r.p.toCanonical(x, z)?.let { (cx, cz) -> BlockMesh.grid(r).of(cx, Math.floor(ey).toInt(), cz) } ?: -1
        return (System.identityHashCode(r).toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
    }

    fun compute(input: Input): Result {
        val f = FrustumIntersection(input.viewProj)
        val m = MARGIN.toFloat()
        fun inView(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) = input.wide || f.testAab(x0 - m, y0 - m, z0 - m, x1 + m, y1 + m, z1 + m)
        val ex = input.ex; val ey = input.ey; val ez = input.ez
        val far2 = (input.viewDist + MARGIN) * (input.viewDist + MARGIN)
        val lod2 = input.lodDist * input.lodDist
        val refs = ArrayList<Ref>(); val around = ArrayList<Ref>()
        val roomsSeen = input.portals?.visible(ex, ey, ez, if (input.wide) null else input.viewProj)
        val b = IntArray(6)
        for (r in input.rooms) {
            if (roomsSeen != null && r !in roomsSeen) continue
            val names = BlockMesh.bundleNames(r)
            val base = MeshBundles.get(names[0]) ?: continue
            val variant = if (names.size > 1) MeshBundles.get(names[1]) else null
            val roomInView = inView(r.x0.toFloat(), r.y0.toFloat(), r.z0.toFloat(), r.x1 + 1f, r.y1 + 1f, r.z1 + 1f)
            val g = BlockMesh.grid(r)
            val rx = ex - r.x0; val rz = ez - r.z0
            // The eye's piece, when it's in this room: only what can be seen from there.
            val from = if (base.hasSight) r.p.toCanonical(Math.floor(ex).toInt(), Math.floor(ez).toInt())?.let { (cx, cz) -> g.of(cx, Math.floor(ey).toInt(), cz) } ?: -1 else -1
            for (i in 0 until g.size) {
                if (from >= 0 && !base.sees(from, i)) continue
                val lx = g.lx(i); val ly = g.ly(i); val lz = g.lz(i)
                BlockMesh.pieceBox(r, g, lx, ly, lz, b)
                val dist2 = away(rx, ey, rz, b)
                if (dist2 > far2) continue
                val seen = roomInView && inView(r.x0 + b[0].toFloat(), b[1].toFloat(), r.z0 + b[2].toFloat(), r.x0 + b[3] + 1f, b[4] + 1f, r.z0 + b[5] + 1f)
                if (!seen && dist2 > AROUND2) continue
                val vi = variant?.find(lx, ly, lz) ?: -1
                val bundle = if (vi >= 0) variant!! else base
                val pi = if (vi >= 0) vi else base.find(lx, ly, lz)
                if (pi < 0 && !seen) continue
                val ref = Ref(ROOM, r, bundle, pi, r.p.rot, g.w, g.d, lx, ly, lz, 0L, dist2, faces(b, rx, ey, rz), dist2 > lod2)
                if (seen) refs += ref else around += ref
            }
        }
        val arena = input.arena
        if (arena != null) for (i in arena.places.indices) {
            val p = arena.places[i]
            val sx = MeshBundles.placeX(p); val sy = MeshBundles.placeY(p); val sz = MeshBundles.placeZ(p)
            val s = SectionPos.asLong(sx, sy, sz)
            val cl = input.clusters
            if (cl != null) { val ci = PovVisibility.index(BlockMesh.cluster(s)); if (ci >= 0 && !cl[ci]) continue }
            section(b, sx, sy, sz)
            val dist2 = away(ex, ey, ez, b)
            if (dist2 > far2) continue
            val seen = inView(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3] + 1f, b[4] + 1f, b[5] + 1f)
            if (!seen && dist2 > AROUND2) continue
            val ref = Ref(ARENA, null, arena, i, Rot.SOUTH, 0, 0, 0, 0, 0, s, dist2, faces(b, ex, ey, ez), dist2 > lod2)
            if (seen) refs += ref else around += ref
        }
        for (s in input.world) {
            section(b, SectionPos.x(s), SectionPos.y(s), SectionPos.z(s))
            val dist2 = away(ex, ey, ez, b)
            if (dist2 > far2 || !inView(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3] + 1f, b[4] + 1f, b[5] + 1f)) continue
            refs += Ref(WORLD, null, null, -1, Rot.SOUTH, 0, 0, 0, 0, 0, s, dist2, faces(b, ex, ey, ez), dist2 > lod2)
        }
        refs.sortBy { it.dist2 }
        around.sortBy { it.dist2 }
        return Result(refs, around, roomsSeen, eyePiece(ex, ey, ez))
    }

    private fun section(b: IntArray, sx: Int, sy: Int, sz: Int) {
        b[0] = sx shl 4; b[1] = sy shl 4; b[2] = sz shl 4; b[3] = b[0] + 15; b[4] = b[1] + 15; b[5] = b[2] + 15
    }

    /** How far (squared) the eye is from the box [b] (cells, inclusive). */
    fun away(ex: Double, ey: Double, ez: Double, b: IntArray): Double {
        val dx = maxOf(b[0] - ex, 0.0, ex - b[3] - 1); val dy = maxOf(b[1] - ey, 0.0, ey - b[4] - 1); val dz = maxOf(b[2] - ez, 0.0, ez - b[5] - 1)
        return dx * dx + dy * dy + dz * dz
    }

    /** Which sides of faces in box [b] (Direction order, a bit each) can face an eye within [MARGIN] of (ex, ey, ez). */
    fun faces(b: IntArray, ex: Double, ey: Double, ez: Double): Int {
        var m = 0
        if (ey < b[4] + 1 + MARGIN) m = m or 1
        if (ey > b[1] - MARGIN) m = m or 2
        if (ez < b[5] + 1 + MARGIN) m = m or 4
        if (ez > b[2] - MARGIN) m = m or 8
        if (ex < b[3] + 1 + MARGIN) m = m or 16
        if (ex > b[0] - MARGIN) m = m or 32
        return m
    }

    /** Whether group [g] of a piece is drawn, for its [faces] and whether it's [far]. */
    fun drawn(g: Int, faces: Int, far: Boolean): Boolean {
        if (far && g >= CompactMesh.FAR_GROUPS) return false
        val d = CompactMesh.groupDir(g)
        return d < 0 || (faces shr d) and 1 != 0
    }

    // --- portals ------------------------------------------------------------------------------------------------------------

    // Tile steps: east, west, south, north (+x, -x, +z, -z).
    private val DX = intArrayOf(1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 1, -1)
    private val OPPOSITE = intArrayOf(1, 0, 3, 2)

    /** The edge between tile (tx, tz) and its neighbour in [dir]: one number for both sides (0..59). */
    fun edge(tx: Int, tz: Int, dir: Int): Int = when (dir) {
        0 -> tz * 5 + tx
        1 -> tz * 5 + tx - 1
        2 -> 30 + tx * 5 + tz
        else -> 30 + tx * 5 + tz - 1
    }

    /**
     * The dungeon's rooms as tiles joined by openings, for [visible]: the room on each tile, how high each room's wall stands
     * along each side of each of its tiles (its lowest column: anything over that can be seen over), and the edges a block
     * change broke through (open all the way). Every edge between two rooms has a door's opening in its middle, known door or
     * not. Made on the game thread, read from any.
     */
    class Portals private constructor(
        private val tiles: Array<KnownWorld.Room?>,
        private val tops: IntArray,
        private val broken: BooleanArray,
    ) {
        /**
         * The rooms an eye can see, or null for all of them (not inside a placed room). From the eye's room, through each
         * opening in view, each opening narrowing the view (a rect on screen) for what's past it. A tile with no room yet passes
         * everything on (the world's blocks are drawn there, whatever they are).
         */
        fun visible(ex: Double, ey: Double, ez: Double, vp: Matrix4f?): Set<KnownWorld.Room>? {
            val start = KnownWorld.roomAt(Math.floor(ex).toInt(), Math.floor(ez).toInt()) ?: return null
            if (ey < start.y0 - 1 || ey > start.y1 + 1 || tiles.none { it === start }) return null
            val seen = HashSet<KnownWorld.Room>().apply { add(start) }
            // Per tile, the screen rect it was reached with (x0 y0 x1 y1 in -1..1), and through how many openings.
            val reached = arrayOfNulls<FloatArray>(36)
            val steps = IntArray(36)
            val queue = ArrayDeque<Int>()
            for (t in 0 until 36) if (tiles[t] === start) { reached[t] = floatArrayOf(-1f, -1f, 1f, 1f); queue += t }
            val v = Vector4f()
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                val rect = reached[t]!!
                val tx = t % 6; val tz = t / 6
                val here = tiles[t]
                for (dir in 0 until 4) {
                    val nx = tx + DX[dir]; val nz = tz + DZ[dir]
                    if (nx !in 0..5 || nz !in 0..5) continue
                    val n = nz * 6 + nx
                    val there = tiles[n]
                    val same = here != null && here === there
                    val next: FloatArray = if (same) rect else {
                        if (steps[t] >= MAX_STEPS) continue
                        opening(tx, tz, nx, nz, dir, here, there, rect, vp, v) ?: continue
                    }
                    val prev = reached[n]
                    if (prev != null && prev[0] <= next[0] && prev[1] <= next[1] && prev[2] >= next[2] && prev[3] >= next[3]) continue
                    reached[n] = if (prev == null) next else floatArrayOf(minOf(prev[0], next[0]), minOf(prev[1], next[1]), maxOf(prev[2], next[2]), maxOf(prev[3], next[3]))
                    steps[n] = if (same) steps[t] else steps[t] + 1
                    there?.let { seen += it }
                    queue += n
                }
            }
            return seen
        }

        /** What of [rect] the openings between tile (tx, tz) and (nx, nz) let through, as a rect on screen; null for nothing. */
        private fun opening(tx: Int, tz: Int, nx: Int, nz: Int, dir: Int, here: KnownWorld.Room?, there: KnownWorld.Room?, rect: FloatArray, vp: Matrix4f?, v: Vector4f): FloatArray? {
            val m = MARGIN
            // Across the edge: the gap column between the tiles and the wall each side; along it, the tile's 31 blocks.
            val acrossX = dir < 2
            val gap = (DungeonFrame.ORIGIN + DungeonFrame.PITCH * (if (acrossX) minOf(tx, nx) else minOf(tz, nz)) + 31).toDouble()
            val a0 = DungeonFrame.tileMin(if (acrossX) tz else tx).toDouble()
            var out: FloatArray? = null
            fun through(y0: Double, y1: Double, s0: Double, s1: Double) {
                val r = (if (acrossX) project(vp, gap - 1 - m, y0, s0, gap + 2 + m, y1, s1, rect, v) else project(vp, s0, y0, gap - 1 - m, s1, y1, gap + 2 + m, rect, v)) ?: return
                out = out?.let { floatArrayOf(minOf(it[0], r[0]), minOf(it[1], r[1]), maxOf(it[2], r[2]), maxOf(it[3], r[3])) } ?: r
            }
            if (here == null || there == null || broken[edge(tx, tz, dir)]) through(-64.0, 320.0, a0 - m, a0 + 31 + m)
            else {
                val c = a0 + 15
                through(DOOR_Y0 - m, DOOR_Y1 + m, c - 1 - m, c + 2 + m)
                val top = minOf(tops[(tz * 6 + tx) * 4 + dir], tops[(nz * 6 + nx) * 4 + OPPOSITE[dir]])
                through(top + 1 - m, 320.0, a0 - m, a0 + 31 + m)
            }
            return out
        }

        companion object {
            /** Each placed room's wall heights per tile side, worked out the first time an edge to another room needs one. */
            private val roomTops = java.util.WeakHashMap<KnownWorld.Room, IntArray>()

            /** The portals as the rooms and this run's changes stand now ([old]'s walls kept when the rooms are the same). Game thread. */
            fun build(old: Portals?, changed: Collection<Long>): Portals {
                val tiles = Array(36) { KnownWorld.tile(it) }
                val tops = if (old != null && old.tiles.contentEquals(tiles)) old.tops else IntArray(36 * 4) { i ->
                    val t = i / 4; val dir = i % 4
                    val r = tiles[t] ?: return@IntArray -64
                    val nx = t % 6 + DX[dir]; val nz = t / 6 + DZ[dir]
                    // Only along an edge to another placed room (one to nothing, or inside the room, is open anyway).
                    val there = if (nx in 0..5 && nz in 0..5) tiles[nz * 6 + nx] else null
                    if (there == null || there === r) return@IntArray -64
                    val known = roomTops.getOrPut(r) { IntArray(36 * 4) { Int.MIN_VALUE } }
                    if (known[i] == Int.MIN_VALUE) known[i] = wallTop(r, t % 6, t / 6, dir)
                    known[i]
                }
                val broken = BooleanArray(60)
                for (k in changed) {
                    val x = BlockPos.getX(k) - DungeonFrame.ORIGIN; val y = BlockPos.getY(k); val z = BlockPos.getZ(k) - DungeonFrame.ORIGIN
                    mark(broken, x, z, y, 0)
                    mark(broken, z, x, y, 1)
                }
                return Portals(tiles, tops, broken)
            }

            /**
             * A change at ([across], [along]) from the grid's corner ([axis] 0: across is x, the edges between tiles east and
             * west; 1: across is z) within 2 blocks of an edge's gap column, and not in its door, breaks that edge open.
             */
            fun mark(broken: BooleanArray, across: Int, along: Int, y: Int, axis: Int) {
                val t = Math.floorDiv(across - 15, DungeonFrame.PITCH)
                if (t !in 0..4 || across - (t * DungeonFrame.PITCH + 31) !in -2..2) return
                val ta = Math.floorDiv(along, DungeonFrame.PITCH)
                if (ta !in 0..5) return
                if (y in DungeonFrame.DOOR_Y_MIN - 1..DungeonFrame.DOOR_Y_MAX + 2 && Math.abs(along - ta * DungeonFrame.PITCH - 15) <= 2) return
                broken[if (axis == 0) ta * 5 + t else 30 + ta * 5 + t] = true
            }

            /** How high [r]'s wall stands along side [dir] of its tile (tx, tz): the lowest of its columns' highest opaque block. */
            private fun wallTop(r: KnownWorld.Room, tx: Int, tz: Int, dir: Int): Int {
                val x0 = DungeonFrame.tileMin(tx); val z0 = DungeonFrame.tileMin(tz)
                var low = Int.MAX_VALUE
                for (k in 0 until DungeonFrame.TILE_SIZE) {
                    val x = when (dir) { 0 -> x0 + 30; 1 -> x0; else -> x0 + k }
                    val z = when (dir) { 2 -> z0 + 30; 3 -> z0; else -> z0 + k }
                    var top = r.y0 - 1
                    for (y in r.y1 downTo r.y0) if (r.designAt(x - r.x0, y, z - r.z0).isSolidRender) { top = y; break }
                    low = minOf(low, top)
                    if (low < r.y0) break
                }
                return low
            }
        }
    }

    /** The box's corners on screen as a rect, cut to [clip]; null when nothing of it is left. A corner behind the eye: all of [clip]. */
    private fun project(vp: Matrix4f?, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, clip: FloatArray, v: Vector4f): FloatArray? {
        if (vp == null) return clip
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until 8) {
            v.set((if (k and 1 == 0) x0 else x1).toFloat(), (if (k and 2 == 0) y0 else y1).toFloat(), (if (k and 4 == 0) z0 else z1).toFloat(), 1f).mul(vp)
            if (v.w <= 0.05f) return clip
            val sx = v.x / v.w; val sy = v.y / v.w
            minX = minOf(minX, sx); maxX = maxOf(maxX, sx); minY = minOf(minY, sy); maxY = maxOf(maxY, sy)
        }
        val r = floatArrayOf(maxOf(minX, clip[0]), maxOf(minY, clip[1]), minOf(maxX, clip[2]), minOf(maxY, clip[3]))
        return if (r[0] < r[2] && r[1] < r[3]) r else null
    }
}
