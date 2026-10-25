package com.devgineerclient.posepov

import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.pack.DungeonFrame
import net.minecraft.core.SectionPos
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * What a feed's eye could possibly see, worked out ahead so a frame only looks it up ([BlockMesh.forEachDraw] draws the rest).
 * Always on the safe side: anything it can't be sure of is drawn.
 *  - Clear: rooms are walled boxes joined by doors, so from inside one only rooms a few doorways away can show: the rooms
 *    within [ROOM_STEPS] steps over the 6x6 grid (a step being into another room; tiles not placed yet count as rooms, so a gap
 *    in what's known never hides what's past it). Worked out once per room per layout.
 *  - Boss: which arena clusters can be seen from anywhere in the section the eye is in, by rays through the shipped arena with
 *    every cell a recorded run ever saw change (gates, crushers, devices: boss-dynamic.bin.gz) counted as open, then widened by
 *    a cluster each way for what the rays missed. Worked out off the game thread the first time an eye is in a section, and
 *    kept on disk: after that, never again.
 * Anywhere else (an eye above the rooms, in a hallway, a section not worked out yet) everything in view is drawn.
 */
object PovVisibility {
    private const val ROOM_STEPS = 3
    private const val FAR = 156.0
    private const val SOURCES = 32
    private const val RAYS = 96
    // The arena's clusters (2x2x2 sections): x 0..4, z 0..4, y -2..9 (y -64..319).
    private const val CX = 5
    private const val CZ = 5
    private const val CY0 = -2
    private const val CY = 12
    private const val CLUSTERS = CX * CZ * CY
    // The arena's cells, for the rays: x 0..159, z 0..159, y -64..319.
    private const val AX = 160
    private const val AZ = 160
    private const val AY0 = -64
    private const val AY = 384

    // --- clear ------------------------------------------------------------------------------------------------------------

    private var layout: List<KnownWorld.Room>? = null
    private val seenFrom = HashMap<String, Set<KnownWorld.Room>>()

    /** The rooms an eye in a room can see, or null for everything (not in a placed room, or above it). */
    fun rooms(eye: Vec3): Set<KnownWorld.Room>? {
        val r = KnownWorld.roomAt(Math.floor(eye.x).toInt(), Math.floor(eye.z).toInt()) ?: return null
        if (eye.y < r.y0 - 1 || eye.y > r.y1 + 1) return null
        val rooms = KnownWorld.rooms
        if (rooms !== layout) { layout = rooms; seenFrom.clear() }
        return seenFrom.getOrPut(r.key) { reach(r) }
    }

    private fun tileRoom(tx: Int, tz: Int): KnownWorld.Room? =
        KnownWorld.roomAt(DungeonFrame.ORIGIN + tx * DungeonFrame.PITCH + 15, DungeonFrame.ORIGIN + tz * DungeonFrame.PITCH + 15)

    /** Tiles by steps from [from] (0-1: within a room free, into another room or an unplaced tile one), the rooms reached. */
    private fun reach(from: KnownWorld.Room): Set<KnownWorld.Room> {
        val dist = IntArray(36) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (t in 0 until 36) if (tileRoom(t % 6, t / 6) === from) { dist[t] = 0; queue.addLast(t) }
        val out = HashSet<KnownWorld.Room>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            val tr = tileRoom(t % 6, t / 6)
            for ((dx, dz) in STEPS) {
                val nx = t % 6 + dx; val nz = t / 6 + dz
                if (nx !in 0..5 || nz !in 0..5) continue
                val n = nz * 6 + nx
                val nr = tileRoom(nx, nz)
                val d = dist[t] + if (nr != null && nr === tr) 0 else 1
                if (d > ROOM_STEPS || d >= dist[n]) continue
                dist[n] = d
                if (d == dist[t]) queue.addFirst(n) else queue.addLast(n)
                nr?.let { out += it }
            }
        }
        return out
    }

    private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    // --- boss -------------------------------------------------------------------------------------------------------------

    private val known = ConcurrentHashMap<Long, BitSet>()
    private val asked = ConcurrentHashMap.newKeySet<Long>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pose pov visibility").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var solid: BitSet? = null

    /** Whether the arena cluster [key] ([BlockMesh.cluster]) can show from where [eye] is, or null for everything (not known). */
    fun clusters(eye: Vec3): BitSet? {
        if (!KnownWorld.bossFloor() || eye.x < 0 || eye.z < 0 || eye.x >= AX || eye.z >= AZ) return null
        val s = SectionPos.asLong(Math.floor(eye.x).toInt() shr 4, Math.floor(eye.y).toInt() shr 4, Math.floor(eye.z).toInt() shr 4)
        known[s]?.let { return it }
        if (asked.add(s)) {
            val disk = if (MeshDisk.ready) MeshDisk.read("v/$s") else null
            if (disk != null) disk.second?.let { known[s] = BitSet.valueOf(it) } ?: known.put(s, BitSet())
            else worker.execute { runCatching { work(s) }.onFailure { asked.remove(s) } }
        }
        return known[s]
    }

    fun index(key: Long): Int {
        val x = SectionPos.x(key); val y = SectionPos.y(key) - CY0; val z = SectionPos.z(key)
        return if (x !in 0 until CX || z !in 0 until CZ || y !in 0 until CY) -1 else (y * CZ + z) * CX + x
    }

    private fun work(s: Long) {
        val grid = solid ?: buildSolid().also { solid = it }
        val sx = SectionPos.x(s) shl 4; val sy = SectionPos.y(s) shl 4; val sz = SectionPos.z(s) shl 4
        val rnd = Random(s)
        // Where an eye in the section could be: open cells in it (all of it if none are: then nothing is ruled out).
        val from = ArrayList<IntArray>()
        for (i in 0 until 400) {
            if (from.size >= SOURCES) break
            val x = sx + rnd.nextInt(16); val y = sy + rnd.nextInt(16); val z = sz + rnd.nextInt(16)
            if (!opaque(grid, x, y, z)) from += intArrayOf(x, y, z)
        }
        val seen = BitSet(CLUSTERS)
        if (from.isEmpty()) { seen.set(0, CLUSTERS) }
        else for (i in 0 until CLUSTERS) {
            val cx = i % CX; val cz = (i / CX) % CZ; val cy = i / (CX * CZ) + CY0
            val x0 = cx shl 5; val y0 = cy shl 5; val z0 = cz shl 5
            // Too far to be drawn at all, or the cluster the eye is in (and its neighbours: widened below anyway).
            val nx = (sx + 8).coerceIn(x0, x0 + 31) - (sx + 8); val ny = (sy + 8).coerceIn(y0, y0 + 31) - (sy + 8); val nz = (sz + 8).coerceIn(z0, z0 + 31) - (sz + 8)
            if (Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()) > FAR) continue
            if (sx shr 5 == cx && sy shr 5 == cy && sz shr 5 == cz) { seen.set(i); continue }
            rays@ for (t in 0 until RAYS) {
                val tx = x0 + rnd.nextInt(32); val ty = y0 + rnd.nextInt(32); val tz = z0 + rnd.nextInt(32)
                val f = from[rnd.nextInt(from.size)]
                if (clear(grid, f[0], f[1], f[2], tx, ty, tz)) { seen.set(i); break@rays }
            }
        }
        // Widened by a cluster each way: a sliver of a view the rays missed is never a hole.
        val wide = seen.clone() as BitSet
        var i = seen.nextSetBit(0)
        while (i >= 0) {
            val cx = i % CX; val cz = (i / CX) % CZ; val cy = i / (CX * CZ)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val x = cx + dx; val y = cy + dy; val z = cz + dz
                if (x in 0 until CX && z in 0 until CZ && y in 0 until CY) wide.set((y * CZ + z) * CX + x)
            }
            i = seen.nextSetBit(i + 1)
        }
        known[s] = wide
        MeshDisk.write("v/$s", CLUSTERS, ByteBuffer.wrap(wide.toByteArray()))
    }

    private fun opaque(g: BitSet, x: Int, y: Int, z: Int): Boolean {
        if (x !in 0 until AX || z !in 0 until AZ || y - AY0 !in 0 until AY) return false
        return g[((y - AY0) * AZ + z) * AX + x]
    }

    /** Whether nothing solid stands between the two cells' middles (stepping cell to cell along the line). */
    private fun clear(g: BitSet, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int): Boolean {
        val dx = (x1 - x0).toDouble(); val dy = (y1 - y0).toDouble(); val dz = (z1 - z0).toDouble()
        var x = x0; var y = y0; var z = z0
        val stepX = if (dx > 0) 1 else -1; val stepY = if (dy > 0) 1 else -1; val stepZ = if (dz > 0) 1 else -1
        val tdx = if (dx != 0.0) Math.abs(1 / dx) else Double.MAX_VALUE
        val tdy = if (dy != 0.0) Math.abs(1 / dy) else Double.MAX_VALUE
        val tdz = if (dz != 0.0) Math.abs(1 / dz) else Double.MAX_VALUE
        var tx = tdx / 2; var ty = tdy / 2; var tz = tdz / 2
        while (x != x1 || y != y1 || z != z1) {
            if (tx <= ty && tx <= tz) { x += stepX; tx += tdx } else if (ty <= tz) { y += stepY; ty += tdy } else { z += stepZ; tz += tdz }
            if (x == x1 && y == y1 && z == z1) return true
            if (opaque(g, x, y, z)) return false
        }
        return true
    }

    /** The arena's solid cells, less every cell known to change. */
    private fun buildSolid(): BitSet {
        val a = Arena.data
        val g = BitSet(AX * AZ * AY)
        for (y in 0 until AY) for (z in 0 until AZ) for (x in 0 until AX) if (a.get(x, y + AY0, z).isSolidRender) g.set((y * AZ + z) * AX + x)
        PovVisibility::class.java.getResourceAsStream("/posepov/boss-dynamic.bin.gz")?.use { raw ->
            val b = ByteBuffer.wrap(GZIPInputStream(raw).readAllBytes())
            repeat(b.int) {
                val x = b.get().toInt() and 255; val y = b.get().toInt() and 255; val z = b.get().toInt() and 255
                if (x < AX && z < AZ && y - AY0 in 0 until AY) g.clear(((y - AY0) * AZ + z) * AX + x)
            }
        }
        return g
    }

    /** A new run's rooms: their views are worked out again (the arena's stay). */
    fun newRun() { layout = null; seenFrom.clear() }
}
